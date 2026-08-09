package org.dddml.email.service;

import org.dddml.email.entity.EmailQueue;
import org.dddml.email.event.EmailQueuedEvent;
import org.dddml.email.repository.EmailQueueRepository;
import org.dddml.email.config.MailProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class EmailQueueService {

    private static final Logger log = LoggerFactory.getLogger(EmailQueueService.class);

    private final EmailQueueRepository emailQueueRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MailProperties mailProperties;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public EmailQueue enqueue(String recipient, String subject, String htmlContent,
                             String emailType, Integer priority) {
        EmailQueue emailQueue = EmailQueue.builder()
                .recipient(recipient)
                .subject(subject)
                .htmlContent(htmlContent)
                .emailType(emailType != null ? emailType : "GENERAL")
                .status("PENDING")
                .priority(priority != null ? priority : 5)
                .retryCount(0)
                .maxRetries(mailProperties.getRetry().getMaxAttempts())
                .build();

        EmailQueue saved = emailQueueRepository.save(emailQueue);
        log.info("Email enqueued [ID={}]", saved.getId());

        try {
            EmailQueuedEvent event = new EmailQueuedEvent(this, saved.getId());
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            log.error("Event publish failed [ID={}], scheduled task will handle", saved.getId());
        }

        return saved;
    }

    public EmailQueue enqueue(String recipient, String subject, String htmlContent, String emailType) {
        return enqueue(recipient, subject, htmlContent, emailType, 5);
    }

    @Transactional
    public EmailQueue enqueueIdempotent(
            String recipient,
            String subject,
            String htmlContent,
            String emailType,
            String idempotencyKey) {
        String normalizedType = emailType != null ? emailType : "GENERAL";
        String fingerprint = requestFingerprint(
                recipient,
                subject,
                htmlContent,
                normalizedType
        );
        LocalDateTime now = LocalDateTime.now();
        Long queueId = jdbcTemplate.queryForObject(
            """
            INSERT INTO email_queue (
                recipient,
                subject,
                html_content,
                email_type,
                status,
                priority,
                retry_count,
                max_retries,
                created_time,
                updated_time,
                idempotency_key,
                request_fingerprint
            )
            VALUES (?, ?, ?, ?, 'PENDING', 5, 0, ?, ?, ?, ?, ?)
            ON CONFLICT (idempotency_key)
                WHERE idempotency_key IS NOT NULL
            DO UPDATE SET idempotency_key = EXCLUDED.idempotency_key
            RETURNING id
            """,
            Long.class,
            recipient,
            subject,
            htmlContent,
            normalizedType,
            mailProperties.getRetry().getMaxAttempts(),
            now,
            now,
            idempotencyKey,
            fingerprint
        );
        EmailQueue queue = emailQueueRepository.findById(queueId).orElseThrow();
        if (!fingerprint.equals(queue.getRequestFingerprint())) {
            throw new IdempotencyConflictException();
        }
        eventPublisher.publishEvent(new EmailQueuedEvent(this, queue.getId()));
        return queue;
    }

    public QueueStats getStats() {
        return new QueueStats(
            emailQueueRepository.countByStatus("PENDING"),
            emailQueueRepository.countByStatus("PROCESSING"),
            emailQueueRepository.countByStatus("COMPLETED"),
            emailQueueRepository.countByStatus("FAILED")
        );
    }

    public java.util.Optional<EmailQueue> findById(Long id) {
        return emailQueueRepository.findById(id);
    }

    public java.util.Optional<EmailQueue> findByIdempotencyKey(
            String idempotencyKey) {
        return emailQueueRepository.findByIdempotencyKey(idempotencyKey);
    }

    private String requestFingerprint(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(bytes.length)
                        .array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 is not available",
                exception
            );
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class QueueStats {
        private long pending;
        private long processing;
        private long completed;
        private long failed;
    }
}
