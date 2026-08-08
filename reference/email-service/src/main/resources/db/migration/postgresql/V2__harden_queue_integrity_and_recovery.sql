ALTER TABLE email_queue
    ADD CONSTRAINT chk_email_queue_retry_bounds
        CHECK (retry_count <= max_retries);

ALTER TABLE email_logs
    ADD CONSTRAINT fk_email_logs_queue
        FOREIGN KEY (queue_id)
        REFERENCES email_queue (id)
        ON DELETE SET NULL;

CREATE INDEX idx_email_queue_recovery
    ON email_queue (status, next_retry_time, updated_time, priority, created_time);

CREATE INDEX idx_email_logs_status_sent_time
    ON email_logs (status, sent_time DESC);
