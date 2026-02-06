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

    List<EmailVerificationCode> findByEmail(String email);

    List<EmailVerificationCode> findByExpiresAtBeforeAndIsUsedFalse(Instant now);

    @Modifying
    @Query("DELETE FROM EmailVerificationCode e WHERE e.expiresAt < :now")
    int deleteExpiredCodes(@Param("now") Instant now);

    boolean existsByEmailAndPurposeAndIsUsedFalse(String email, VerificationPurpose purpose);

    long countByEmailAndCreatedAtAfter(String email, Instant since);
}
