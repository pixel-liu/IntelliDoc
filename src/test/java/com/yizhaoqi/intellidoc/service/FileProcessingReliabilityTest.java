package com.yizhaoqi.intellidoc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.intellidoc.config.KafkaConfig;
import com.yizhaoqi.intellidoc.model.*;
import com.yizhaoqi.intellidoc.repository.*;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import com.yizhaoqi.intellidoc.consumer.FileProcessingConsumer;
import com.yizhaoqi.intellidoc.consumer.FileProcessingDltConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/** Real JPA transactions/locks, with only external storage, search and AI mocked. */
@DataJpaTest(properties = {"spring.jpa.show-sql=false", "file.processing.outbox.enabled=false"}, showSql = false)
@ActiveProfiles("test")
@Import({FileProcessingOutboxService.class, FileProcessingExecutionService.class, DocumentService.class,
        FileProcessingReliabilityTest.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FileProcessingReliabilityTest {
    @DynamicPropertySource
    static void optionalMysql(DynamicPropertyRegistry registry) {
        String url = System.getProperty("reliability.jdbc.url");
        if (url != null) {
            registry.add("spring.test.database.replace", () -> "NONE");
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> "root");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        }
    }

    @TestConfiguration
    static class JsonConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @Autowired FileUploadRepository files;
    @Autowired FileProcessingEventRepository events;
    @Autowired DocumentVectorRepository chunks;
    @Autowired FileProcessingOutboxService outbox;
    @Autowired FileProcessingExecutionService execution;
    @Autowired PlatformTransactionManager manager;
    @MockBean UploadService storage;
    @MockBean ParseService parser;
    @MockBean VectorizationService embedding;
    @MockBean ElasticsearchService search;
    @MockBean MinioClient minio;
    @MockBean OrgTagCacheService orgs;
    @MockBean StringRedisTemplate redis;

    private TransactionTemplate transactions;

    @BeforeEach
    void setup() throws Exception {
        transactions = new TransactionTemplate(manager);
        chunks.deleteAll();
        events.deleteAll();
        files.deleteAll();
        when(storage.getMergedFileStream(anyString())).thenAnswer(i -> mock(GetObjectResponse.class));
        when(embedding.vectorizeWithUsage(anyString(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(new VectorizationService.VectorizationUsageResult(42, 1, "test-model"));
        doAnswer(inv -> {
            DocumentVector chunk = new DocumentVector();
            chunk.setFileMd5(inv.getArgument(0));
            chunk.setUserId(inv.getArgument(2));
            chunk.setOrgTag(inv.getArgument(3));
            chunk.setChunkId(0);
            chunk.setTextContent("test");
            chunks.saveAndFlush(chunk);
            return null;
        }).when(parser).parseAndSave(anyString(), any(), anyString(), anyString(), anyBoolean());
    }

    private FileUpload merging(String owner) {
        FileUpload file = new FileUpload();
        file.setFileMd5("0123456789abcdef0123456789abcdef");
        file.setFileName("document.pdf");
        file.setUserId(owner);
        file.setOrgTag("ORG_" + owner);
        file.setStatus(FileUpload.STATUS_MERGING);
        return files.saveAndFlush(file);
    }

    private FileProcessingTask queued(FileUpload file) {
        outbox.completeUpload(file.getFileMd5(), file.getUserId());
        return outbox.readTask(events.findById(files.findById(file.getId()).orElseThrow().getProcessingTaskId()).orElseThrow());
    }

    @Test
    void completionAndOutboxCommitTogetherAndRepeatedMergeDoesNotCreateAnotherTask() {
        FileUpload file = merging("1");
        FileProcessingTask task = queued(file);
        outbox.ensureUploadTask(file.getFileMd5(), "1");
        outbox.completeUpload(file.getFileMd5(), "1");
        assertEquals(1, events.count());
        assertEquals(FileUpload.STATUS_COMPLETED, files.findById(file.getId()).orElseThrow().getStatus());
        assertEquals(FileProcessingEvent.PENDING, events.findById(task.getTaskId()).orElseThrow().getDeliveryStatus());
        assertNull(task.getFilePath(), "The durable task must not contain an expiring signed URL");
        assertEquals("ORG_1", task.getOrgTag());
    }

    @Test
    void databaseRollbackRollsBackBothCompletionAndOutbox() {
        FileUpload file = merging("1");
        transactions.executeWithoutResult(status -> {
            outbox.completeUpload(file.getFileMd5(), "1");
            assertEquals(1, events.count());
            status.setRollbackOnly();
        });
        assertEquals(0, events.count());
        assertEquals(FileUpload.STATUS_MERGING, files.findById(file.getId()).orElseThrow().getStatus());
        assertNull(files.findById(file.getId()).orElseThrow().getProcessingTaskId());
    }

    @Test
    void historicalCompletedUploadWithoutReceiptCanBeRepaired() {
        FileUpload file = merging("1");
        file.setStatus(FileUpload.STATUS_COMPLETED);
        file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_PROCESSING);
        files.saveAndFlush(file);
        outbox.ensureUploadTask(file.getFileMd5(), "1");
        assertEquals(1, events.count());
        assertNotNull(files.findById(file.getId()).orElseThrow().getProcessingTaskId());
    }

    @Test
    void successfulTaskIsSkippedOnRedeliveryAndLateEstimatePreservesCompletion() throws Exception {
        FileUpload file = merging("1");
        FileProcessingTask task = queued(file);
        task.setFilePath("https://expired.invalid/?signature=old");
        task.setOrgTag("FORGED_ORG");
        execution.process(task);
        execution.process(task);
        outbox.saveEstimate(file.getFileMd5(), "1", 100L, 2);
        FileUpload result = files.findById(file.getId()).orElseThrow();
        assertEquals(FileUpload.VECTORIZATION_STATUS_COMPLETED, result.getVectorizationStatus());
        assertEquals(42L, result.getActualEmbeddingTokens());
        assertEquals(1, chunks.count());
        assertEquals(FileProcessingEvent.COMPLETED, events.findById(task.getTaskId()).orElseThrow().getProcessingStatus());
        verify(parser, times(1)).parseAndSave(eq(file.getFileMd5()), any(), eq("1"), eq("ORG_1"), eq(false));
        verify(storage, times(1)).getMergedFileStream(file.getFileMd5());
    }

    @Test
    void failedProcessingRollsBackPartialChunksAndRetriesCleanly() {
        FileUpload file = merging("1");
        FileProcessingTask task = queued(file);
        when(embedding.vectorizeWithUsage(anyString(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenThrow(new IllegalStateException("embedding unavailable"))
                .thenReturn(new VectorizationService.VectorizationUsageResult(42, 1, "model"));
        assertThrows(RuntimeException.class, () -> execution.process(task));
        assertEquals(0, chunks.count(), "Partial parsing must roll back");
        assertEquals(FileProcessingEvent.PENDING, events.findById(task.getTaskId()).orElseThrow().getProcessingStatus());
        execution.process(task);
        assertEquals(1, chunks.count());
        assertEquals(FileProcessingEvent.COMPLETED, events.findById(task.getTaskId()).orElseThrow().getProcessingStatus());
    }

    @Test
    void duplicateConcurrentConsumersRunEmbeddingOnlyOnce() throws Exception {
        FileUpload file = merging("1");
        FileProcessingTask task = queued(file);
        CountDownLatch enteredEmbedding = new CountDownLatch(1);
        CountDownLatch releaseEmbedding = new CountDownLatch(1);
        when(embedding.vectorizeWithUsage(anyString(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenAnswer(inv -> {
                    enteredEmbedding.countDown();
                    assertTrue(releaseEmbedding.await(10, TimeUnit.SECONDS));
                    return new VectorizationService.VectorizationUsageResult(42, 1, "model");
                });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> execution.process(task));
            assertTrue(enteredEmbedding.await(10, TimeUnit.SECONDS));
            Future<?> duplicate = pool.submit(() -> execution.process(task));
            releaseEmbedding.countDown();
            first.get(15, TimeUnit.SECONDS);
            duplicate.get(15, TimeUnit.SECONDS);
            verify(embedding, times(1)).vectorizeWithUsage(anyString(), anyString(), anyString(), anyBoolean(), anyString());
            assertEquals(1, chunks.count());
        } finally {
            releaseEmbedding.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void deadLetterReplayIsDurableAndDelayedOriginalCannotFailTheNewTask() {
        FileUpload file = merging("1");
        FileProcessingTask task = queued(file);
        outbox.recordDeadLetter(task, "PDF processing failed");
        assertEquals(FileProcessingEvent.DEAD, events.findById(task.getTaskId()).orElseThrow().getProcessingStatus());
        String replayId = outbox.replay(task.getTaskId(), "admin");
        assertNotEquals(task.getTaskId(), replayId);
        assertEquals(replayId, outbox.replay(task.getTaskId(), "admin"));
        outbox.recordDeadLetter(task, "delayed original DLT");
        assertEquals(FileUpload.VECTORIZATION_STATUS_PENDING, files.findById(file.getId()).orElseThrow().getVectorizationStatus());
        execution.process(task);
        verifyNoInteractions(embedding);
        execution.process(outbox.readTask(events.findById(replayId).orElseThrow()));
        assertEquals(FileProcessingEvent.COMPLETED, events.findById(replayId).orElseThrow().getProcessingStatus());
    }

    @Test
    void unreadableDltPayloadIsAcknowledgedWithoutAnInfiniteRetry() {
        var listener = new FileProcessingDltConsumer(outbox);
        ConsumerRecord<String, FileProcessingTask> unreadable = new ConsumerRecord<>("file-processing-dlt", 0, 7L, "key", null);

        assertDoesNotThrow(() -> listener.processDltMessage(unreadable));
        assertEquals(0, events.count());
    }

    @Test
    void sameMd5FromAnotherTenantIsProcessedIndependentlyAndDeletedUploadIsNotResurrected() {
        FileUpload first = merging("1");
        FileProcessingTask obsolete = queued(first);
        FileUpload second = merging("2");
        FileProcessingTask own = queued(second);
        files.deleteById(first.getId());
        execution.process(obsolete);
        execution.process(own);
        assertEquals(1, chunks.count());
        assertEquals("2", chunks.findAll().get(0).getUserId());
        assertEquals(FileProcessingEvent.SUPERSEDED, events.findById(obsolete.getTaskId()).orElseThrow().getProcessingStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void kafkaFailureSurvivesPublisherRestartAndCommitGapResendIsIdempotent() {
        FileUpload file = merging("1");
        FileProcessingTask task = queued(file);
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        KafkaConfig config = mock(KafkaConfig.class);
        when(config.getFileProcessingTopic()).thenReturn("processing");
        when(config.isTransactionsEnabled()).thenReturn(true);
        when(kafka.executeInTransaction(any())).thenThrow(new IllegalStateException("broker unavailable"));
        var publisher = new FileProcessingOutboxPublisher(events, outbox, kafka, config, manager);
        publisher.publishOne(task.getTaskId());
        FileProcessingEvent failed = events.findById(task.getTaskId()).orElseThrow();
        assertEquals(FileProcessingEvent.PENDING, failed.getDeliveryStatus());
        assertEquals(1, failed.getPublishAttempts());
        assertTrue(failed.getNextAttemptAt().isAfter(failed.getCreatedAt()));
        assertNotNull(failed.getLastError());

        failed.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        events.saveAndFlush(failed);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        doAnswer(inv -> {
            KafkaOperations.OperationsCallback<String, Object, Boolean> callback = inv.getArgument(0);
            return callback.doInOperations(kafka);
        }).when(kafka).executeInTransaction(any());
        var restartedPublisher = new FileProcessingOutboxPublisher(events, outbox, kafka, config, manager);
        transactions.executeWithoutResult(status -> {
            restartedPublisher.publishOne(task.getTaskId());
            // Simulate DB failure after Kafka committed: receipt must remain pending.
            status.setRollbackOnly();
        });
        assertEquals(FileProcessingEvent.PENDING, events.findById(task.getTaskId()).orElseThrow().getDeliveryStatus());
        restartedPublisher.publishOne(task.getTaskId());
        assertEquals(FileProcessingEvent.SENT, events.findById(task.getTaskId()).orElseThrow().getDeliveryStatus());
        verify(kafka, times(2)).send(eq("processing"), eq("1:" + file.getFileMd5()), any());
        execution.process(task);
        execution.process(task);
        verify(embedding, times(1)).vectorizeWithUsage(anyString(), anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleSentReceiptIsRedeliveredWithoutResettingConsumerOffsets() {
        FileUpload file = merging("1");
        FileProcessingTask task = queued(file);
        FileProcessingEvent event = events.findById(task.getTaskId()).orElseThrow();
        event.setDeliveryStatus(FileProcessingEvent.SENT);
        event.setSentAt(LocalDateTime.now().minusMinutes(2));
        events.saveAndFlush(event);

        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        KafkaConfig config = mock(KafkaConfig.class);
        when(config.getFileProcessingTopic()).thenReturn("processing");
        when(config.isTransactionsEnabled()).thenReturn(true);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        doAnswer(inv -> {
            KafkaOperations.OperationsCallback<String, Object, Boolean> callback = inv.getArgument(0);
            return callback.doInOperations(kafka);
        }).when(kafka).executeInTransaction(any());
        var publisher = new FileProcessingOutboxPublisher(events, outbox, kafka, config, manager);
        ReflectionTestUtils.setField(publisher, "sentRecoveryDelayMs", 1_000L);

        publisher.publishReady();

        FileProcessingEvent redelivered = events.findById(task.getTaskId()).orElseThrow();
        assertEquals(FileProcessingEvent.SENT, redelivered.getDeliveryStatus());
        assertEquals(1, redelivered.getPublishAttempts());
        verify(kafka).send(eq("processing"), eq("1:" + file.getFileMd5()), any());
        execution.process(task);
        execution.process(task);
        verify(embedding, times(1)).vectorizeWithUsage(anyString(), anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    @EnabledIfSystemProperty(named = "reliability.kafka.bootstrap", matches = ".+")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void realKafkaCommitAbortDuplicateDeadLetterAndReplay() throws Exception {
        KafkaConfig config = new KafkaConfig();
        String name = "reliability-" + UUID.randomUUID();
        ReflectionTestUtils.setField(config, "bootstrapServers", System.getProperty("reliability.kafka.bootstrap"));
        ReflectionTestUtils.setField(config, "fileProcessingTopic", name);
        ReflectionTestUtils.setField(config, "fileProcessingDltTopic", name + "-dlt");
        ReflectionTestUtils.setField(config, "topicPartitions", 1);
        ReflectionTestUtils.setField(config, "topicReplicationFactor", (short) 1);
        ReflectionTestUtils.setField(config, "minInsyncReplicas", "1");
        ReflectionTestUtils.setField(config, "fileProcessingGroupId", name + "-group");
        ReflectionTestUtils.setField(config, "autoOffsetReset", "earliest");
        ReflectionTestUtils.setField(config, "trustedPackages", "com.yizhaoqi.intellidoc.model");
        ReflectionTestUtils.setField(config, "transactionIdPrefix", name + "-tx-");
        ReflectionTestUtils.setField(config, "maxPollIntervalMs", 1800000);
        var producerFactory = (DefaultKafkaProducerFactory<String, Object>) config.producerFactory();
        var kafka = new KafkaTemplate<String, Object>(producerFactory);
        var consumers = config.consumerFactory();
        var main = config.kafkaListenerContainerFactory(consumers, kafka).createContainer(name);
        var dlt = config.dltKafkaListenerContainerFactory(consumers).createContainer(name + "-dlt");
        main.getContainerProperties().setGroupId(name + "-group");
        dlt.getContainerProperties().setGroupId(name + "-dlt-group");
        var listener = new FileProcessingConsumer(execution, outbox);
        var deadListener = new FileProcessingDltConsumer(outbox);
        main.getContainerProperties().setMessageListener((MessageListener<String, Object>) record ->
                listener.processTask((FileProcessingTask) record.value()));
        dlt.getContainerProperties().setMessageListener((MessageListener<String, Object>) record ->
                deadListener.processDltMessage((org.apache.kafka.clients.consumer.ConsumerRecord) record));
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", System.getProperty("reliability.kafka.bootstrap")))) {
            admin.createTopics(List.of(config.fileProcessingNewTopic(), config.fileProcessingDltNewTopic()))
                    .all().get(20, TimeUnit.SECONDS);
            main.start();
            dlt.start();
            var publisher = new FileProcessingOutboxPublisher(events, outbox, kafka, config, manager);
            FileProcessingTask first = queued(merging("1"));
            publisher.publishOne(first.getTaskId());
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals(FileProcessingEvent.COMPLETED,
                    events.findById(first.getTaskId()).orElseThrow().getProcessingStatus()));

            // A second upload is written in an aborted Kafka transaction.
            FileProcessingTask aborted = queued(merging("2"));
            assertThrows(IllegalStateException.class, () -> kafka.executeInTransaction(kt -> {
                kt.send(name, "2", aborted).join();
                throw new IllegalStateException("deliberate transaction rollback");
            }));
            // A committed duplicate after the abort is a barrier: after its offset
            // commits, the consumer has traversed the aborted records as well.
            var duplicate = kafka.executeInTransaction(kt -> kt.send(name, "1", first).join());
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var offsets = admin.listConsumerGroupOffsets(name + "-group").partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS);
                var offset = offsets.get(new TopicPartition(name, 0));
                assertNotNull(offset);
                assertTrue(offset.offset() > duplicate.getRecordMetadata().offset());
            });
            assertEquals(FileProcessingEvent.PENDING, events.findById(aborted.getTaskId()).orElseThrow().getProcessingStatus());
            verify(embedding, times(1)).vectorizeWithUsage(anyString(), eq("1"), anyString(), anyBoolean(), anyString());
            verify(embedding, never()).vectorizeWithUsage(anyString(), eq("2"), anyString(), anyBoolean(), anyString());

            FileProcessingTask failing = queued(merging("3"));
            when(embedding.vectorizeWithUsage(anyString(), eq("3"), anyString(), anyBoolean(), anyString()))
                    .thenThrow(new IllegalStateException("deliberate model outage"));
            publisher.publishOne(failing.getTaskId());
            await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> assertEquals(FileProcessingEvent.DEAD,
                    events.findById(failing.getTaskId()).orElseThrow().getProcessingStatus()));
            verify(embedding, times(5)).vectorizeWithUsage(anyString(), eq("3"), anyString(), anyBoolean(), anyString());

            doReturn(new VectorizationService.VectorizationUsageResult(42, 1, "model")).when(embedding)
                    .vectorizeWithUsage(anyString(), eq("3"), anyString(), anyBoolean(), anyString());
            String replay = outbox.replay(failing.getTaskId(), "admin");
            publisher.publishOne(replay);
            var replayReceipt = events.findById(replay).orElseThrow();
            assertEquals(FileProcessingEvent.SENT, replayReceipt.getDeliveryStatus(),
                    "attempts=" + replayReceipt.getPublishAttempts() + ", next=" + replayReceipt.getNextAttemptAt()
                            + ", now=" + LocalDateTime.now() + ", processing=" + replayReceipt.getProcessingStatus()
                            + ", error=" + replayReceipt.getLastError());
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals(FileProcessingEvent.COMPLETED,
                    events.findById(replay).orElseThrow().getProcessingStatus()));
            assertEquals(2, chunks.count());
        } finally {
            main.stop();
            dlt.stop();
            kafka.destroy();
            producerFactory.destroy();
        }
    }
}
