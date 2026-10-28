package com.yizhaoqi.intellidoc.service;

import com.yizhaoqi.intellidoc.config.KafkaConfig;
import com.yizhaoqi.intellidoc.model.FileProcessingEvent;
import com.yizhaoqi.intellidoc.repository.FileProcessingEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@ConditionalOnProperty(name = "file.processing.outbox.enabled", matchIfMissing = true)
public class FileProcessingOutboxPublisher {
    private final FileProcessingEventRepository events;
    private final FileProcessingOutboxService outbox;
    private final KafkaTemplate<String, Object> kafka;
    private final KafkaConfig config;
    private final TransactionTemplate transactions;

    /**
     * A successful Kafka send is not proof that a consumer will still see the record. This is
     * deliberately longer than an ordinary broker/network blip so active document processing is
     * not needlessly duplicated.
     */
    @Value("${file.processing.outbox.sent-recovery-delay-ms:60000}")
    private long sentRecoveryDelayMs;

    public FileProcessingOutboxPublisher(FileProcessingEventRepository events, FileProcessingOutboxService outbox,
                                         KafkaTemplate<String, Object> kafka, KafkaConfig config,
                                         @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.events = events;
        this.outbox = outbox;
        this.kafka = kafka;
        this.config = config;
        this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${file.processing.outbox.poll-interval-ms:3000}")
    public void publishReady() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sentBefore = now.minusNanos(TimeUnit.MILLISECONDS.toNanos(sentRecoveryDelayMs));
        for (String id : events.findDispatchableIds(now, sentBefore, PageRequest.of(0, 10))) {
            try {
                publishOne(id);
            } catch (Exception e) {
                // A database commit failure leaves PENDING intact; the next scan safely resends.
                log.error("Outbox dispatch transaction failed: taskId={}", id, e);
            }
        }
    }

    public void publishOne(String id) {
        transactions.executeWithoutResult(status -> {
            // Keep this lock until the DB receipt commits. Concurrent publishers recheck state.
            FileProcessingEvent event = events.findByIdForUpdate(id).orElse(null);
            if (event == null || !FileProcessingEvent.PENDING.equals(event.getProcessingStatus())) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            boolean pendingDelivery = FileProcessingEvent.PENDING.equals(event.getDeliveryStatus())
                    && !event.getNextAttemptAt().isAfter(now);
            boolean staleSentDelivery = FileProcessingEvent.SENT.equals(event.getDeliveryStatus())
                    && event.getSentAt() != null
                    && !event.getSentAt().isAfter(now.minusNanos(TimeUnit.MILLISECONDS.toNanos(sentRecoveryDelayMs)));
            if (!pendingDelivery && !staleSentDelivery) {
                return;
            }
            event.setPublishAttempts(event.getPublishAttempts() + 1);
            try {
                var task = outbox.readTask(event);
                if (config.isTransactionsEnabled()) {
                    kafka.executeInTransaction(kt -> sendAndAwait(kt, task));
                } else {
                    sendAndAwait(kafka, task);
                }
                event.setDeliveryStatus(FileProcessingEvent.SENT);
                event.setSentAt(LocalDateTime.now());
                event.setLastError(null);
            } catch (Exception e) {
                // A redelivery may fail after a previous successful send. Return it to the
                // normal backoff path instead of retrying it on every scheduler tick.
                event.setDeliveryStatus(FileProcessingEvent.PENDING);
                long delaySeconds = Math.min(300, 3L << Math.min(event.getPublishAttempts() - 1, 7));
                event.setNextAttemptAt(LocalDateTime.now().plusSeconds(delaySeconds));
                event.setLastError(FileProcessingOutboxService.truncate(e.toString(), 4000));
                log.warn("Outbox delivery will retry: taskId={}, attempts={}, nextAttempt={}",
                        id, event.getPublishAttempts(), event.getNextAttemptAt(), e);
            }
        });
    }

    private boolean sendAndAwait(KafkaOperations<String, Object> operations, com.yizhaoqi.intellidoc.model.FileProcessingTask task) {
        try {
            operations.send(config.getFileProcessingTopic(), task.getUserId() + ":" + task.getFileMd5(), task)
                    .get(35, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Kafka 投递失败", e);
        }
    }
}
