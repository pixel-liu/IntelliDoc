package com.yizhaoqi.intellidoc.config;

import com.yizhaoqi.intellidoc.model.FileProcessingTask;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import static org.junit.jupiter.api.Assertions.*;

class KafkaReliabilityConfigTest {
    @Test
    void factoriesUseCommittedRecordsAndRecordAcksAndCreateReplicaProtectedTopics() {
        new ApplicationContextRunner().withUserConfiguration(KafkaConfig.class).withPropertyValues(
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                "spring.kafka.topic.file-processing=processing",
                "spring.kafka.topic.dlt=processing-dlt",
                "spring.kafka.topic.partitions=3",
                "spring.kafka.topic.replication-factor=3",
                "spring.kafka.topic.min-insync-replicas=2",
                "spring.kafka.consumer.group-id=processing-group",
                "spring.kafka.consumer.dlt-group-id=dlt-group",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "spring.kafka.consumer.properties.spring.json.trusted.packages=com.yizhaoqi.intellidoc.model",
                "file.processing.transaction-id-prefix=instance-a-",
                "file.processing.kafka.transactions-enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var config = context.getBean(KafkaConfig.class);
                    var consumer = context.getBean(ConsumerFactory.class).getConfigurationProperties();
                    assertEquals("read_committed", consumer.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG));
                    assertEquals(false, consumer.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
                    assertEquals(1, consumer.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
                    assertEquals(ErrorHandlingDeserializer.class,
                            consumer.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
                    assertEquals(JsonDeserializer.class,
                            consumer.get(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS));
                    assertEquals(FileProcessingTask.class, consumer.get(JsonDeserializer.VALUE_DEFAULT_TYPE));
                    assertEquals(false, consumer.get(JsonDeserializer.USE_TYPE_INFO_HEADERS));
                    assertEquals("2", config.fileProcessingNewTopic().configs().get("min.insync.replicas"));
                    assertEquals(3, config.fileProcessingNewTopic().replicationFactor());
                    assertEquals(3, config.fileProcessingDltNewTopic().replicationFactor());
                    assertEquals(ContainerProperties.AckMode.RECORD,
                            config.dltKafkaListenerContainerFactory(config.consumerFactory()).getContainerProperties().getAckMode());
                });
    }
}
