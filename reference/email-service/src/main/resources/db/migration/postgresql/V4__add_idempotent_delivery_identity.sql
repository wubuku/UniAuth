ALTER TABLE email_queue
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN request_fingerprint VARCHAR(64),
    ADD CONSTRAINT chk_email_queue_idempotency_shape
        CHECK (
            (idempotency_key IS NULL AND request_fingerprint IS NULL)
            OR (
                idempotency_key IS NOT NULL
                AND request_fingerprint IS NOT NULL
            )
        );

CREATE UNIQUE INDEX uk_email_queue_idempotency_key
    ON email_queue (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
