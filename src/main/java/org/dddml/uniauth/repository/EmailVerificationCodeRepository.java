package org.dddml.uniauth.repository;

import org.dddml.uniauth.entity.EmailVerificationCode;
import org.dddml.uniauth.entity.EmailVerificationCode.VerificationPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmailVerificationCodeRepository
        extends JpaRepository<EmailVerificationCode, String> {

    @Query("""
        SELECT challenge
        FROM EmailVerificationCode challenge
        WHERE challenge.email = :email
          AND challenge.purpose = :purpose
          AND challenge.usageStatus = org.dddml.uniauth.entity.EmailVerificationCode.UsageStatus.UNUSED
          AND challenge.deliveryStatus IN (
              org.dddml.uniauth.entity.EmailVerificationCode.DeliveryStatus.PENDING_DELIVERY,
              org.dddml.uniauth.entity.EmailVerificationCode.DeliveryStatus.ACCEPTED,
              org.dddml.uniauth.entity.EmailVerificationCode.DeliveryStatus.ACTIVE
          )
        ORDER BY challenge.createdAt DESC
        """)
    List<EmailVerificationCode> findActive(
            @Param("email") String email,
            @Param("purpose") VerificationPurpose purpose
    );

    Optional<EmailVerificationCode> findFirstByEmailAndPurposeOrderByCreatedAtDesc(
            String email,
            VerificationPurpose purpose
    );

    List<EmailVerificationCode> findByEmail(String email);

    long countByEmailAndCreatedAtAfter(String email, Instant since);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE EmailVerificationCode challenge
        SET challenge.usageStatus =
                org.dddml.uniauth.entity.EmailVerificationCode.UsageStatus.INVALIDATED,
            challenge.updatedAt = CURRENT_TIMESTAMP
        WHERE challenge.email = :email
          AND challenge.purpose = :purpose
          AND challenge.usageStatus =
                org.dddml.uniauth.entity.EmailVerificationCode.UsageStatus.UNUSED
        """)
    int invalidateActive(
            @Param("email") String email,
            @Param("purpose") VerificationPurpose purpose
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE EmailVerificationCode challenge
        SET challenge.usageStatus =
                org.dddml.uniauth.entity.EmailVerificationCode.UsageStatus.USED,
            challenge.updatedAt = CURRENT_TIMESTAMP
        WHERE challenge.id = :id
          AND challenge.usageStatus =
                org.dddml.uniauth.entity.EmailVerificationCode.UsageStatus.UNUSED
          AND challenge.deliveryStatus =
                org.dddml.uniauth.entity.EmailVerificationCode.DeliveryStatus.ACTIVE
          AND challenge.expiresAt > CURRENT_TIMESTAMP
        """)
    int consumeIfUsable(@Param("id") String id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE email_verification_codes
            SET retry_count = retry_count + 1,
                usage_status = CASE
                    WHEN retry_count + 1 >= :maxRetryAttempts
                        THEN 'INVALIDATED'
                    ELSE usage_status
                END,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
              AND usage_status = 'UNUSED'
              AND delivery_status = 'ACTIVE'
              AND expires_at > CURRENT_TIMESTAMP
              AND retry_count = :expectedRetryCount
            """,
        nativeQuery = true
    )
    int incrementRetryCountIfCurrent(
            @Param("id") String id,
            @Param("expectedRetryCount") int expectedRetryCount,
            @Param("maxRetryAttempts") int maxRetryAttempts
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE EmailVerificationCode challenge
        SET challenge.usageStatus =
                org.dddml.uniauth.entity.EmailVerificationCode.UsageStatus.EXPIRED,
            challenge.updatedAt = CURRENT_TIMESTAMP
        WHERE challenge.usageStatus =
                org.dddml.uniauth.entity.EmailVerificationCode.UsageStatus.UNUSED
          AND challenge.expiresAt <= :now
        """)
    int expireChallenges(@Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE email_verification_codes
            SET delivery_status = 'ACTIVE',
                provider_delivery_id = :providerDeliveryId,
                accepted_at = COALESCE(accepted_at, :now),
                activated_at = COALESCE(activated_at, :now),
                expires_at = LEAST(expires_at, :activeExpiresAt),
                updated_at = :now
            WHERE id = :id
              AND usage_status = 'UNUSED'
              AND delivery_status IN ('PENDING_DELIVERY', 'ACCEPTED')
              AND delivery_deadline > :now
              AND expires_at > :now
            """,
        nativeQuery = true
    )
    int activateAcceptedDelivery(
            @Param("id") String id,
            @Param("providerDeliveryId") String providerDeliveryId,
            @Param("now") Instant now,
            @Param("activeExpiresAt") Instant activeExpiresAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE email_verification_codes
            SET delivery_status = 'FAILED',
                usage_status = CASE
                    WHEN usage_status = 'UNUSED' THEN 'INVALIDATED'
                    ELSE usage_status
                END,
                failed_at = COALESCE(failed_at, :now),
                failure_reason = :reason,
                updated_at = :now
            WHERE id = :id
              AND delivery_status IN (
                    'PENDING_DELIVERY',
                    'ACCEPTED'
              )
            """,
        nativeQuery = true
    )
    int failDelivery(
            @Param("id") String id,
            @Param("reason") String reason,
            @Param("now") Instant now
    );
}
