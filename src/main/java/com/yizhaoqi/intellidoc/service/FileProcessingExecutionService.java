package com.yizhaoqi.intellidoc.service;

import com.yizhaoqi.intellidoc.model.FileProcessingEvent;
import com.yizhaoqi.intellidoc.model.FileProcessingTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class FileProcessingExecutionService {
    private final FileProcessingOutboxService outbox;
    private final DocumentService documents;

    public FileProcessingExecutionService(FileProcessingOutboxService outbox, DocumentService documents) {
        this.outbox = outbox;
        this.documents = documents;
    }

    /** The per-file DB lock serializes duplicates, retries and synchronous reindex requests. */
    @Transactional
    public void process(FileProcessingTask incoming) {
        var locked = outbox.lockTask(incoming);
        if (locked == null || !FileProcessingEvent.PENDING.equals(locked.event().getProcessingStatus())) {
            return;
        }
        FileProcessingTask task = locked.task();
        String requester = task.getRequesterId() == null ? task.getUserId() : task.getRequesterId();
        // Rebuild clears partial DB/ES results and reads the immutable MinIO object directly.
        documents.reindexDocument(task.getFileMd5(), task.getUserId(), requester);
        locked.event().setProcessingStatus(FileProcessingEvent.COMPLETED);
        locked.event().setCompletedAt(LocalDateTime.now());
        locked.event().setLastError(null);
    }
}
