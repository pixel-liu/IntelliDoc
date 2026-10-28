package com.yizhaoqi.intellidoc.consumer;

import com.yizhaoqi.intellidoc.model.FileProcessingTask;
import com.yizhaoqi.intellidoc.service.FileProcessingExecutionService;
import com.yizhaoqi.intellidoc.service.FileProcessingOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class FileProcessingConsumer {
    private final FileProcessingExecutionService execution;
    private final FileProcessingOutboxService outbox;

    public FileProcessingConsumer(FileProcessingExecutionService execution, FileProcessingOutboxService outbox) {
        this.execution = execution;
        this.outbox = outbox;
    }

    @KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        try {
            execution.process(task);
        } catch (Exception e) {
            // The processing transaction has rolled back before recording the error.
            try {
                outbox.recordFailure(task, e);
            } catch (Exception persistenceError) {
                e.addSuppressed(persistenceError);
            }
            log.error("文件处理失败: taskId={}, fileMd5={}", task.getTaskId(), task.getFileMd5(), e);
            throw new IllegalStateException("文件处理失败", e);
        }
    }
}
