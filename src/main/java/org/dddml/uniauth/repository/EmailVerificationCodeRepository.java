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
public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, String> {

    Optional<EmailVerificationCode> findByEmailAndPurposeAndIsUsedFalse(
        String email,
        VerificationPurpose purpose
    );

    Optional<EmailVerificationCode> findFirstByEmailAndPurposeAndIsUsedFalseOrderByCreatedAtDesc(
        String email,
        VerificationPurpose purpose
    );

    Optional<EmailVerificationCode> findFirstByEmailAndPurposeOrderByCreatedAtDesc(
        String email,
        VerificationPurpose purpose
    );

    List<EmailVerificationCode> findByEmail(String email);

    List<EmailVerificationCode> findByExpiresAtBeforeAndIsUsedFalse(Instant now);

    @Modifying
    @Query("DELETE FROM EmailVerificationCode e WHERE e.expiresAt < :now")
    int deleteExpiredCodes(@Param("now") Instant now);

    boolean existsByEmailAndPurposeAndIsUsedFalse(String email, VerificationPurpose purpose);

    long countByEmailAndCreatedAtAfter(String email, Instant since);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE EmailVerificationCode e
        SET e.isUsed = true,
            e.updatedAt = CURRENT_TIMESTAMP
        WHERE e.id = :id
          AND e.isUsed = false
          AND e.expiresAt > CURRENT_TIMESTAMP
          AND e.verificationCode = :code
        """)
    int consumeIfUsable(
        @Param("id") String id,
        @Param("code") String code
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE EmailVerificationCode e
        SET e.retryCount = e.retryCount + 1,
            e.updatedAt = CURRENT_TIMESTAMP
        WHERE e.id = :id
          AND e.isUsed = false
          AND e.expiresAt > CURRENT_TIMESTAMP
          AND e.retryCount = :expectedRetryCount
        """)
    int incrementRetryCountIfCurrent(
        @Param("id") String id,
        @Param("expectedRetryCount") int expectedRetryCount
    );
}
