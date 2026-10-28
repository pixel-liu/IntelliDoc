package com.yizhaoqi.intellidoc.consumer;

import com.yizhaoqi.intellidoc.model.FileProcessingTask;
import com.yizhaoqi.intellidoc.service.FileProcessingOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;

/**
 * DLT (Dead Letter Topic) 消费者。
 * 监听 file-processing-dlt 主题，记录所有进入死信队列的消息及其失败原因。
 * <p>
 * 使用专用工厂，数据库不可用时持续重试，避免提交尚未持久化的死信。
 */
@Service
@Slf4j
public class FileProcessingDltConsumer {

    private static final int MAX_RECORDED_ERROR_LENGTH = 3_500;
    private static final int MAX_LOGGED_ERROR_LENGTH = 2_000;

    private final FileProcessingOutboxService outbox;

    public FileProcessingDltConsumer(FileProcessingOutboxService outbox) {
        this.outbox = outbox;
    }

    /**
     * 消费死信队列消息，记录完整的失败信息以便排查。
     * 使用 ConsumerRecord 接收原始消息以提取 Kafka 头信息。
     */
    @KafkaListener(
            topics = "#{kafkaConfig.getFileProcessingDltTopic()}",
            groupId = "#{kafkaConfig.getFileProcessingDltGroupId()}",
            containerFactory = "dltKafkaListenerContainerFactory"
    )
    public void processDltMessage(ConsumerRecord<String, FileProcessingTask> record) {
        FileProcessingTask task = record.value();

        // 提取 DLT 头信息
        String originalTopic = getHeaderAsString(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        String originalPartition = getNumericHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        String originalOffset = getNumericHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET);
        String exceptionFqcn = getHeaderAsString(record, KafkaHeaders.DLT_EXCEPTION_FQCN);
        String exceptionMessage = getHeaderAsString(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        String exceptionStacktrace = getHeaderAsString(record, KafkaHeaders.DLT_EXCEPTION_STACKTRACE);
        String originalTimestamp = getNumericHeader(record, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP);

        if (task == null) {
            // ErrorHandlingDeserializer exposes unreadable records as null. Returning
            // commits this DLT offset instead of retrying and logging the same poison
            // message forever.
            log.warn("跳过无法反序列化的 DLT 消息: topic={}, partition={}, offset={}, exceptionType={}, exceptionMessage={}",
                    record.topic(), record.partition(), record.offset(), exceptionFqcn, limit(exceptionMessage, MAX_LOGGED_ERROR_LENGTH));
            return;
        }

        String error = exceptionFqcn + ": " + exceptionMessage + "\n" + exceptionStacktrace;
        log.warn("记录 DLT 消息: source={}-{}@{}, dlt={}-{}@{}, taskId={}, fileMd5={}, exception={}",
                originalTopic, originalPartition, originalOffset, record.topic(), record.partition(), record.offset(),
                task.getTaskId(), task.getFileMd5(), limit(error, MAX_LOGGED_ERROR_LENGTH));
        outbox.recordDeadLetter(task, limit(error, MAX_RECORDED_ERROR_LENGTH));
    }

    /**
     * 从 ConsumerRecord 头中提取字符串值。
     */
    private String getHeaderAsString(ConsumerRecord<?, ?> record, String headerKey) {
        Header header = record.headers().lastHeader(headerKey);
        if (header != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return "N/A";
    }

    private String getNumericHeader(ConsumerRecord<?, ?> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header == null || header.value() == null) {
            return "N/A";
        }
        ByteBuffer value = ByteBuffer.wrap(header.value());
        if (value.remaining() == Integer.BYTES) {
            return Integer.toString(value.getInt());
        }
        return value.remaining() == Long.BYTES ? Long.toString(value.getLong()) : "N/A";
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…[truncated]";
    }
}
