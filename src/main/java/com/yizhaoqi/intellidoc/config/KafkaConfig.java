package com.yizhaoqi.intellidoc.config;

import com.yizhaoqi.intellidoc.model.FileProcessingTask;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.topic.file-processing}")
    private String fileProcessingTopic;

    @Value("${spring.kafka.topic.dlt}")
    private String fileProcessingDltTopic;

    @Value("${spring.kafka.topic.partitions:1}")
    private int topicPartitions;

    @Value("${spring.kafka.topic.replication-factor:1}")
    private short topicReplicationFactor;

    @Value("${spring.kafka.consumer.group-id}")
    private String fileProcessingGroupId;

    @Value("${spring.kafka.consumer.dlt-group-id}")
    private String fileProcessingDltGroupId;

    @Value("${spring.kafka.consumer.auto-offset-reset}")
    private String autoOffsetReset;

    @Value("${spring.kafka.consumer.properties.spring.json.trusted.packages}")
    private String trustedPackages;

    @Value("${file.processing.transaction-id-prefix:file-upload-${random.uuid}-}")
    private String transactionIdPrefix;

    /** Outbox delivery is safe without broker transactions; keep them optional for local brokers. */
    @Value("${file.processing.kafka.transactions-enabled:true}")
    private boolean transactionsEnabled = true;

    @Value("${spring.kafka.consumer.max-poll-interval-ms:1800000}")
    private int maxPollIntervalMs;

    @Value("${spring.kafka.topic.min-insync-replicas:1}")
    private String minInsyncReplicas;


    public String getFileProcessingTopic() {
        return fileProcessingTopic;
    }

    public String getFileProcessingDltTopic() {
        return fileProcessingDltTopic;
    }

    public String getFileProcessingGroupId() {
        return fileProcessingGroupId;
    }

    public String getFileProcessingDltGroupId() {
        return fileProcessingDltGroupId;
    }

    public boolean isTransactionsEnabled() {
        return transactionsEnabled;
    }

    @Bean
    public NewTopic fileProcessingNewTopic() {
        return TopicBuilder.name(fileProcessingTopic)
                .partitions(topicPartitions)
                .replicas(topicReplicationFactor)
                .config("min.insync.replicas", minInsyncReplicas)
                .build();
    }

    @Bean
    public NewTopic fileProcessingDltNewTopic() {
        return TopicBuilder.name(fileProcessingDltTopic)
                .partitions(topicPartitions)
                .replicas(topicReplicationFactor)
                .config("min.insync.replicas", minInsyncReplicas)
                .build();
    }

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> config = new HashMap<>();
//        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // 可靠投递配置
        config.put(ProducerConfig.ACKS_CONFIG, "all"); // 等待全部 ISR 确认
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // 幂等生产者
        config.put(ProducerConfig.RETRIES_CONFIG, 3); // 自动重试 3 次
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        config.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, 60000);

        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(config);
        if (transactionsEnabled) {
            factory.setTransactionIdPrefix(transactionIdPrefix);
        }
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
//        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // 禁用自动提交偏移量
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, fileProcessingGroupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Do not trust a producer's Java class header. A renamed package in an old
        // DLT record must deserialize into the stable task schema, not retry forever.
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, FileProcessingTask.class);
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, trustedPackages);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMs);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    // 带自动重试和死信队列的监听器工厂
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate) {
        // 当重试失败后，消息发送至 file-processing-dlt 主题，分区与原消息保持一致
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(fileProcessingDltTopic, record.partition()));
        recoverer.setFailIfSendResultIsError(true);

        // 固定退避策略：每 3 秒重试一次，最多重试 4 次（加首次共 5 次）
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(3000L, 4));

        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    /**
     * DLT 消费者专用工厂。数据库不可用时持续重试，不再次投递到 DLT。
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> dltKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // A DB outage must not discard the DLT receipt or commit its offset.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
