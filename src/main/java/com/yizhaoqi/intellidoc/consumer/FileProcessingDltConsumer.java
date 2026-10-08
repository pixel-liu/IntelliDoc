package com.yizhaoqi.intellidoc.consumer;

import com.yizhaoqi.intellidoc.model.FileProcessingTask;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * DLT (Dead Letter Topic) 消费者。
 * 监听 file-processing-dlt 主题，记录所有进入死信队列的消息及其失败原因。
 * <p>
 * 该消费者使用 {@code dltKafkaListenerContainerFactory}，不带重试和死信逻辑，
 * 防止消费失败后再次进入 DLT 形成无限循环。
 */
@Service
@Slf4j
public class FileProcessingDltConsumer {

    @Autowired
    private com.yizhaoqi.intellidoc.config.KafkaConfig kafkaConfig;

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
        String originalPartition = getHeaderAsString(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        String originalOffset = getHeaderAsString(record, KafkaHeaders.DLT_ORIGINAL_OFFSET);
        String exceptionFqcn = getHeaderAsString(record, KafkaHeaders.DLT_EXCEPTION_FQCN);
        String exceptionMessage = getHeaderAsString(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        String exceptionStacktrace = getHeaderAsString(record, KafkaHeaders.DLT_EXCEPTION_STACKTRACE);
        String originalTimestamp = getHeaderAsString(record, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP);

        log.warn("========== DLT 死信消息 ==========");
        log.warn("原始主题: {}, 分区: {}, 偏移量: {}", originalTopic, originalPartition, originalOffset);
        log.warn("原始时间戳: {}", originalTimestamp);
        log.warn("DLT 主题: {}, 分区: {}, 偏移量: {}", record.topic(), record.partition(), record.offset());
        log.warn("异常类型: {}", exceptionFqcn);
        log.warn("异常信息: {}", exceptionMessage);
        log.warn("任务详情: fileMd5={}, fileName={}, filePath={}, taskType={}, userId={}, orgTag={}, isPublic={}, requesterId={}",
                task != null ? task.getFileMd5() : "null",
                task != null ? task.getFileName() : "null",
                task != null ? task.getFilePath() : "null",
                task != null ? task.getTaskType() : "null",
                task != null ? task.getUserId() : "null",
                task != null ? task.getOrgTag() : "null",
                task != null ? task.isPublic() : false,
                task != null ? task.getRequesterId() : "null");
        log.warn("异常堆栈:\n{}", exceptionStacktrace);
        log.warn("==================================");

        // 此处可扩展：
        // - 写入数据库死信表，方便运维人员通过界面查看和重试
        // - 发送告警通知（钉钉/企微/邮件）
        // - 写入专门的死信日志文件
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
}