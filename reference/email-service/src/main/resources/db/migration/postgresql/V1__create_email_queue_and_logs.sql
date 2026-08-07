CREATE TABLE email_queue (
    id BIGSERIAL PRIMARY KEY,
    recipient VARCHAR(255) NOT NULL,
    subject VARCHAR(500) NOT NULL,
    html_content TEXT NOT NULL,
    email_type VARCHAR(50),
    status VARCHAR(20) NOT NULL,
    priority INTEGER NOT NULL,
    retry_count INTEGER NOT NULL,
    max_retries INTEGER NOT NULL,
    next_retry_time TIMESTAMP,
    error_message TEXT,
    created_time TIMESTAMP NOT NULL,
    updated_time TIMESTAMP NOT NULL,
    processed_time TIMESTAMP,
    metadata TEXT,
    CONSTRAINT chk_email_queue_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_email_queue_priority
        CHECK (priority >= 0),
    CONSTRAINT chk_email_queue_retry_count
        CHECK (retry_count >= 0),
    CONSTRAINT chk_email_queue_max_retries
        CHECK (max_retries >= 0)
);

CREATE INDEX idx_email_queue_status
    ON email_queue (status);

CREATE INDEX idx_email_queue_priority_created
    ON email_queue (priority, created_time);

CREATE INDEX idx_email_queue_next_retry
    ON email_queue (next_retry_time);

CREATE INDEX idx_email_queue_status_updated
    ON email_queue (status, updated_time);

CREATE TABLE email_logs (
    id BIGSERIAL PRIMARY KEY,
    queue_id BIGINT,
    recipient VARCHAR(255) NOT NULL,
    subject VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    sent_time TIMESTAMP NOT NULL,
    retry_count INTEGER NOT NULL,
    email_content TEXT,
    email_type VARCHAR(50),
    mail_provider VARCHAR(100),
    duration_ms BIGINT,
    send_method VARCHAR(20),
    CONSTRAINT chk_email_logs_status
        CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT chk_email_logs_retry_count
        CHECK (retry_count >= 0)
);

CREATE INDEX idx_email_logs_status
    ON email_logs (status);

CREATE INDEX idx_email_logs_recipient
    ON email_logs (recipient);

CREATE INDEX idx_email_logs_sent_time
    ON email_logs (sent_time);

CREATE INDEX idx_email_logs_queue_id
    ON email_logs (queue_id);
