UPDATE email_queue
SET processed_time = CASE
        WHEN status IN ('COMPLETED', 'FAILED')
            THEN COALESCE(processed_time, updated_time, created_time, CURRENT_TIMESTAMP)
        ELSE NULL
    END,
    next_retry_time = CASE
        WHEN status = 'PENDING' THEN next_retry_time
        ELSE NULL
    END,
    error_message = CASE
        WHEN status = 'FAILED' THEN error_message
        ELSE NULL
    END;

ALTER TABLE email_queue
    ADD CONSTRAINT chk_email_queue_lifecycle_state
        CHECK (
            (
                status = 'PENDING'
                AND processed_time IS NULL
                AND error_message IS NULL
            )
            OR (
                status = 'PROCESSING'
                AND processed_time IS NULL
                AND next_retry_time IS NULL
                AND error_message IS NULL
            )
            OR (
                status = 'COMPLETED'
                AND processed_time IS NOT NULL
                AND next_retry_time IS NULL
                AND error_message IS NULL
            )
            OR (
                status = 'FAILED'
                AND processed_time IS NOT NULL
                AND next_retry_time IS NULL
            )
        );
