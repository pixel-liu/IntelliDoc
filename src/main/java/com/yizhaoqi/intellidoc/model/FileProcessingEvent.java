package com.yizhaoqi.intellidoc.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** Durable outbox and processing receipt. Kafka delivery is at least once. */
@Entity
@Getter
@Setter
@Table(name = "file_processing_event", indexes = {
        @Index(name = "idx_processing_event_delivery", columnList = "delivery_status,next_attempt_at"),
        @Index(name = "idx_processing_event_state", columnList = "processing_status,created_at")
}, uniqueConstraints = @UniqueConstraint(name = "uk_processing_event_key", columnNames = "event_key"))
public class FileProcessingEvent {
    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String COMPLETED = "COMPLETED";
    public static final String DEAD = "DEAD";
    public static final String SUPERSEDED = "SUPERSEDED";

    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "event_key", nullable = false, length = 100)
    private String eventKey;
    @Column(name = "file_upload_id", nullable = false)
    private Long fileUploadId;
    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String payload;
    @Column(name = "delivery_status", nullable = false, length = 16)
    private String deliveryStatus = PENDING;
    @Column(name = "processing_status", nullable = false, length = 16)
    private String processingStatus = PENDING;
    private int publishAttempts;
    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    private LocalDateTime sentAt;
    private LocalDateTime completedAt;
    @Column(length = 4000)
    private String lastError;
}
