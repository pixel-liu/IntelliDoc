package com.yizhaoqi.intellidoc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.intellidoc.exception.CustomException;
import com.yizhaoqi.intellidoc.model.FileProcessingEvent;
import com.yizhaoqi.intellidoc.model.FileProcessingTask;
import com.yizhaoqi.intellidoc.model.FileUpload;
import com.yizhaoqi.intellidoc.repository.FileProcessingEventRepository;
import com.yizhaoqi.intellidoc.repository.FileUploadRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
public class FileProcessingOutboxService {
    private final FileUploadRepository files;
    private final FileProcessingEventRepository events;
    private final ObjectMapper mapper;

    @PersistenceContext
    private EntityManager entityManager;

    public FileProcessingOutboxService(FileUploadRepository files, FileProcessingEventRepository events,
                                       ObjectMapper mapper) {
        this.files = files;
        this.events = events;
        this.mapper = mapper;
    }

    /** Commit the completion marker and its dispatch intent together, before deleting chunks. */
    @Transactional
    public void completeUpload(String md5, String userId) {
        FileUpload file = lockFile(md5, userId);
        if (file.getStatus() != FileUpload.STATUS_MERGING && file.getStatus() != FileUpload.STATUS_COMPLETED) {
            throw new IllegalStateException("文件未处于合并状态");
        }
        file.setStatus(FileUpload.STATUS_COMPLETED);
        file.setMergedAt(LocalDateTime.now());
        ensureUploadEvent(file);
    }

    /** Repair old completed uploads lacking a dispatch record on an idempotent merge request. */
    @Transactional
    public void ensureUploadTask(String md5, String userId) {
        FileUpload file = lockFile(md5, userId);
        if (file.getStatus() == FileUpload.STATUS_COMPLETED) {
            ensureUploadEvent(file);
        }
    }

    private void ensureUploadEvent(FileUpload file) {
        if (file.getProcessingTaskId() != null
                || FileUpload.VECTORIZATION_STATUS_COMPLETED.equals(file.getVectorizationStatus())) {
            return;
        }
        String key = "upload:" + file.getId();
        FileProcessingEvent event = events.findByEventKey(key)
                .orElseGet(() -> createEvent(file, key, FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS, file.getUserId()));
        file.setProcessingTaskId(event.getId());
        files.save(file);
    }

    @Transactional
    public void saveEstimate(String md5, String userId, long tokens, int chunks) {
        FileUpload file = lockFile(md5, userId);
        file.setEstimatedEmbeddingTokens(tokens);
        file.setEstimatedChunkCount(chunks);
    }

    @Transactional
    public FileUpload enqueueRetry(String md5, String ownerId, String requesterId) {
        FileUpload file = lockFile(md5, ownerId);
        if (file.getStatus() != FileUpload.STATUS_COMPLETED) {
            throw new CustomException("文件尚未完成合并", HttpStatus.CONFLICT);
        }
        if (file.getProcessingTaskId() != null) {
            FileProcessingEvent current = events.findByIdForUpdate(file.getProcessingTaskId()).orElseThrow();
            if (FileProcessingEvent.PENDING.equals(current.getProcessingStatus())) {
                return file;
            }
        }
        createEvent(file, "retry:" + UUID.randomUUID(), FileProcessingTask.TASK_TYPE_REINDEX, requesterId);
        return file;
    }

    /** A replay is a new task; delayed DLT records for the original cannot fail the replay. */
    @Transactional
    public String replay(String taskId, String requesterId) {
        String payload = events.findPayloadById(taskId)
                .orElseThrow(() -> new CustomException("任务不存在", HttpStatus.NOT_FOUND));
        FileProcessingTask original = readTask(payload);
        FileUpload file = lockFile(original.getFileMd5(), original.getUserId());
        FileProcessingEvent dead = events.findByIdForUpdate(taskId).orElseThrow();
        String key = "replay:" + taskId;
        var existing = events.findByEventKey(key);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        if (!FileProcessingEvent.DEAD.equals(dead.getProcessingStatus())) {
            throw new CustomException("只能重放死信任务", HttpStatus.CONFLICT);
        }
        if (!Objects.equals(file.getProcessingTaskId(), taskId)) {
            throw new CustomException("文档已有更新的处理任务，请检查最新任务状态", HttpStatus.CONFLICT);
        }
        return createEvent(file, key, FileProcessingTask.TASK_TYPE_REINDEX, requesterId).getId();
    }

    private FileProcessingEvent createEvent(FileUpload file, String key, String type, String requesterId) {
        FileProcessingEvent event = new FileProcessingEvent();
        event.setId(UUID.randomUUID().toString());
        event.setEventKey(key);
        event.setFileUploadId(file.getId());
        // Storage is resolved by the consumer from MD5, never from an expiring signed URL.
        FileProcessingTask task = new FileProcessingTask(file.getFileMd5(), null, file.getFileName(),
                file.getUserId(), file.getOrgTag(), file.isPublic(), type, requesterId);
        task.setTaskId(event.getId());
        try {
            event.setPayload(mapper.writeValueAsString(task));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化文件处理任务", e);
        }
        events.save(event);
        file.setProcessingTaskId(event.getId());
        file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_PENDING);
        file.setVectorizationErrorMessage(null);
        file.setActualEmbeddingTokens(null);
        file.setActualChunkCount(null);
        files.save(file);
        return event;
    }

    public FileProcessingTask readTask(FileProcessingEvent event) {
        return readTask(event.getPayload());
    }

    private FileProcessingTask readTask(String payload) {
        try {
            return mapper.readValue(payload, FileProcessingTask.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取文件处理任务", e);
        }
    }

    /** Lock file first, then event, in every processing/dead-letter/replay path. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedTask lockTask(FileProcessingTask incoming) {
        // For new records, trust the durable payload rather than mutable Kafka fields.
        FileProcessingTask task = incoming.getTaskId() == null ? incoming : readTask(events.findPayloadById(incoming.getTaskId())
                .orElseThrow(() -> new IllegalStateException("文件处理任务不存在: " + incoming.getTaskId())));
        var fileOpt = files.findForProcessing(task.getFileMd5(), task.getUserId());
        if (fileOpt.isEmpty()) {
            // A deleted document must not be resurrected by delayed messages.
            if (incoming.getTaskId() != null) {
                events.findByIdForUpdate(incoming.getTaskId()).ifPresent(event ->
                        event.setProcessingStatus(FileProcessingEvent.SUPERSEDED));
            }
            return null;
        }
        FileUpload file = fileOpt.get();
        // OSIV may already contain an older copy loaded by the controller. Refresh
        // after acquiring the row lock, before deciding which task is current.
        entityManager.refresh(file);
        if (incoming.getTaskId() == null) {
            // Legacy records have no stable ID. Bind them to the initial upload receipt.
            // If a newer retry exists, the legacy message is obsolete.
            if (file.getProcessingTaskId() != null) {
                FileProcessingEvent current = events.findByIdForUpdate(file.getProcessingTaskId()).orElseThrow();
                if (!current.getEventKey().equals("upload:" + file.getId())) {
                    return null;
                }
            } else if (FileUpload.VECTORIZATION_STATUS_COMPLETED.equals(file.getVectorizationStatus())) {
                return null;
            }
            ensureUploadEvent(file);
            task = readTask(events.findById(file.getProcessingTaskId()).orElseThrow());
        }
        FileProcessingEvent event = events.findByIdForUpdate(task.getTaskId()).orElseThrow();
        if (!Objects.equals(file.getId(), event.getFileUploadId())
                || !Objects.equals(file.getProcessingTaskId(), event.getId())) {
            event.setProcessingStatus(FileProcessingEvent.SUPERSEDED);
            return null;
        }
        return new LockedTask(file, event, task);
    }

    @Transactional
    public void recordFailure(FileProcessingTask task, Throwable error) {
        updateFailure(task, error.toString(), false);
    }

    @Transactional
    public void recordDeadLetter(FileProcessingTask task, String error) {
        updateFailure(task, error, true);
    }

    private void updateFailure(FileProcessingTask incoming, String error, boolean dead) {
        LockedTask locked = lockTask(incoming);
        if (locked == null || !FileProcessingEvent.PENDING.equals(locked.event().getProcessingStatus())) {
            return;
        }
        String message = truncate(error, 1000);
        locked.event().setLastError(truncate(error, 4000));
        if (dead) {
            locked.event().setProcessingStatus(FileProcessingEvent.DEAD);
        }
        locked.file().setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_FAILED);
        locked.file().setVectorizationErrorMessage(message);
    }

    private FileUpload lockFile(String md5, String userId) {
        FileUpload file = files.findForProcessing(md5, userId)
                .orElseThrow(() -> new CustomException("文件不存在", HttpStatus.NOT_FOUND));
        entityManager.refresh(file);
        return file;
    }

    public static String truncate(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(value.length(), max));
    }

    public record LockedTask(FileUpload file, FileProcessingEvent event, FileProcessingTask task) {}
}
