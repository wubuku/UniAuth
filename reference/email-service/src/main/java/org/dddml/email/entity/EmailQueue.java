package org.dddml.email.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "email_queue", indexes = {
    @Index(name = "idx_email_queue_status", columnList = "status"),
    @Index(name = "idx_email_queue_priority_created", columnList = "priority,created_time"),
    @Index(name = "idx_email_queue_next_retry", columnList = "next_retry_time"),
    @Index(name = "idx_email_queue_status_updated", columnList = "status,updated_time")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailQueue {

    public static final String REDACTED_HTML_CONTENT = "<redacted/>";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String recipient;

    @Column(nullable = false, length = 500)
    private String subject;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String htmlContent;

    @Column(length = 50)
    @Builder.Default
    private String emailType = "GENERAL";

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 5;

    @Column(nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer maxRetries = 3;

    private LocalDateTime nextRetryTime;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdTime;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedTime;

    private LocalDateTime processedTime;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    public void incrementRetry(int delayMinutes) {
        this.retryCount++;
        this.nextRetryTime = LocalDateTime.now().plusMinutes(delayMinutes);
        this.status = "PENDING";
        this.errorMessage = null;
        this.processedTime = null;
    }

    public void markAsFailed(String error) {
        this.status = "FAILED";
        this.errorMessage = error;
        this.nextRetryTime = null;
        this.processedTime = LocalDateTime.now();
        redactTerminalPayload();
    }

    public void markAsCompleted() {
        this.status = "COMPLETED";
        this.errorMessage = null;
        this.nextRetryTime = null;
        this.processedTime = LocalDateTime.now();
        redactTerminalPayload();
    }

    public void markAsProcessing() {
        this.status = "PROCESSING";
        this.errorMessage = null;
        this.nextRetryTime = null;
        this.processedTime = null;
    }

    private void redactTerminalPayload() {
        this.htmlContent = REDACTED_HTML_CONTENT;
        this.metadata = null;
    }
}
