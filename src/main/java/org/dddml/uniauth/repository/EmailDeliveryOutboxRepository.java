package org.dddml.uniauth.repository;

import org.dddml.uniauth.entity.EmailDeliveryOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmailDeliveryOutboxRepository
        extends JpaRepository<EmailDeliveryOutbox, String> {

    Optional<EmailDeliveryOutbox> findByChallengeId(String challengeId);

    @Query(
        value = """
            SELECT id
            FROM email_delivery_outbox
            WHERE (
                    status = 'PENDING'
                    AND next_attempt_at <= :now
                )
               OR (
                    status = 'PROCESSING'
                    AND processing_started_at < :stuckBefore
                )
            ORDER BY next_attempt_at, created_at
            LIMIT :batchSize
            """,
        nativeQuery = true
    )
    List<String> findClaimCandidates(
            @Param("now") Instant now,
            @Param("stuckBefore") Instant stuckBefore,
            @Param("batchSize") int batchSize
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE email_delivery_outbox
            SET status = 'PROCESSING',
                attempt_count = attempt_count + 1,
                processing_started_at = :now,
                updated_at = :now,
                last_error_code = NULL
            WHERE id = :id
              AND (
                    (
                        status = 'PENDING'
                        AND next_attempt_at <= :now
                    )
                    OR (
                        status = 'PROCESSING'
                        AND processing_started_at < :stuckBefore
                    )
              )
            """,
        nativeQuery = true
    )
    int claim(
            @Param("id") String id,
            @Param("now") Instant now,
            @Param("stuckBefore") Instant stuckBefore
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE email_delivery_outbox
            SET status = 'ACCEPTED',
                provider_delivery_id = :providerDeliveryId,
                processing_started_at = NULL,
                updated_at = :now,
                last_error_code = NULL
            WHERE id = :id
              AND status = 'PROCESSING'
            """,
        nativeQuery = true
    )
    int markAccepted(
            @Param("id") String id,
            @Param("providerDeliveryId") String providerDeliveryId,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE email_delivery_outbox
            SET status = 'PENDING',
                next_attempt_at = :nextAttemptAt,
                processing_started_at = NULL,
                updated_at = :now,
                last_error_code = :errorCode
            WHERE id = :id
              AND status = 'PROCESSING'
            """,
        nativeQuery = true
    )
    int markPending(
            @Param("id") String id,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("errorCode") String errorCode,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            UPDATE email_delivery_outbox
            SET status = 'FAILED',
                processing_started_at = NULL,
                updated_at = :now,
                last_error_code = :errorCode
            WHERE id = :id
              AND status IN ('PENDING', 'PROCESSING')
            """,
        nativeQuery = true
    )
    int markFailed(
            @Param("id") String id,
            @Param("errorCode") String errorCode,
            @Param("now") Instant now
    );
}
