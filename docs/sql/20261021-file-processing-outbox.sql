-- MySQL 8.0. Apply before deploying when Hibernate schema updates are disabled.
-- The column addition is idempotent, including databases already updated by JPA.
SET @outbox_column_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'file_upload'
       AND column_name = 'processing_task_id') = 0,
    'ALTER TABLE file_upload ADD COLUMN processing_task_id VARCHAR(36) NULL',
    'SELECT 1');
PREPARE outbox_column_stmt FROM @outbox_column_ddl;
EXECUTE outbox_column_stmt;
DEALLOCATE PREPARE outbox_column_stmt;

CREATE TABLE IF NOT EXISTS file_processing_event (
    id VARCHAR(36) NOT NULL,
    event_key VARCHAR(100) NOT NULL,
    file_upload_id BIGINT NOT NULL,
    payload LONGTEXT NOT NULL,
    delivery_status VARCHAR(16) NOT NULL,
    processing_status VARCHAR(16) NOT NULL,
    publish_attempts INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    last_error VARCHAR(4000) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_processing_event_key (event_key),
    KEY idx_processing_event_delivery (delivery_status, next_attempt_at),
    KEY idx_processing_event_state (processing_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
