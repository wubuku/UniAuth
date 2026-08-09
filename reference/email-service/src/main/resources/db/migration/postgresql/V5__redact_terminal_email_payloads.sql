UPDATE email_logs
SET email_content = NULL
WHERE email_content IS NOT NULL;

UPDATE email_queue
SET html_content = '<redacted/>',
    metadata = NULL
WHERE status IN ('COMPLETED', 'FAILED')
  AND (
      html_content <> '<redacted/>'
      OR metadata IS NOT NULL
  );

ALTER TABLE email_logs
    ADD CONSTRAINT chk_email_logs_content_redacted
        CHECK (email_content IS NULL);

ALTER TABLE email_queue
    ADD CONSTRAINT chk_email_queue_terminal_payload_redacted
        CHECK (
            status NOT IN ('COMPLETED', 'FAILED')
            OR (
                html_content = '<redacted/>'
                AND metadata IS NULL
            )
        );
