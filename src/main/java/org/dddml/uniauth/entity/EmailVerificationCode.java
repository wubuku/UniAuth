package org.dddml.uniauth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_verification_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailVerificationCode {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "code_digest", length = 128)
    private String codeDigest;

    @Column(name = "code_key_id", length = 64)
    private String codeKeyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 50)
    private VerificationPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 32)
    private DeliveryStatus deliveryStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_status", nullable = false, length = 32)
    private UsageStatus usageStatus;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "provider_delivery_id", length = 128)
    private String providerDeliveryId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "delivery_deadline")
    private Instant deliveryDeadline;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "failure_reason", length = 64)
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isActive() {
        return deliveryStatus == DeliveryStatus.ACTIVE
                && usageStatus == UsageStatus.UNUSED;
    }

    public enum VerificationPurpose {
        REGISTRATION,
        PASSWORD_RESET
    }

    public enum DeliveryStatus {
        PENDING_DELIVERY,
        ACCEPTED,
        ACTIVE,
        FAILED
    }

    public enum UsageStatus {
        UNUSED,
        USED,
        INVALIDATED,
        EXPIRED
    }
}
