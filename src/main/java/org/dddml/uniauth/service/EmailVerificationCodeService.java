package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.config.EmailDeliveryProperties;
import org.dddml.uniauth.config.EmailVerificationProperties;
import org.dddml.uniauth.entity.EmailDeliveryOutbox;
import org.dddml.uniauth.entity.EmailVerificationCode;
import org.dddml.uniauth.entity.EmailVerificationCode.DeliveryStatus;
import org.dddml.uniauth.entity.EmailVerificationCode.UsageStatus;
import org.dddml.uniauth.entity.EmailVerificationCode.VerificationPurpose;
import org.dddml.uniauth.repository.EmailDeliveryOutboxRepository;
import org.dddml.uniauth.repository.EmailVerificationCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationCodeService {

    private final EmailVerificationCodeRepository verificationCodeRepository;
    private final EmailDeliveryOutboxRepository outboxRepository;
    private final EmailVerificationProperties properties;
    private final EmailDeliveryProperties deliveryProperties;
    private final CanonicalEmailService canonicalEmailService;
    private final EmailVerificationCodeProtector codeProtector;
    private final SecurityEventService securityEventService;

    @Transactional
    public ChallengeDispatch sendVerificationCode(
            String submittedEmail,
            VerificationPurpose purpose) {
        String email = canonicalEmailService.canonicalize(submittedEmail);
        Instant now = Instant.now();
        String handle = UUID.randomUUID().toString();
        String keyId = codeProtector.currentKeyId();
        String verificationCode = codeProtector.deriveCode(handle, keyId);

        verificationCodeRepository.invalidateActive(email, purpose);
        Instant totalExpiresAt = now.plus(
                properties.getExpiryMinutes(),
                ChronoUnit.MINUTES
        );
        Instant deliveryDeadline = now.plus(
                deliveryProperties.getDeliveryDeadlineSeconds(),
                ChronoUnit.SECONDS
        );
        if (deliveryDeadline.isAfter(totalExpiresAt)) {
            deliveryDeadline = totalExpiresAt;
        }
        String idempotencyKey = "email-challenge:" + handle;
        EmailVerificationCode challenge = EmailVerificationCode.builder()
                .id(handle)
                .email(email)
                .codeDigest(codeProtector.digest(
                        handle,
                        verificationCode,
                        keyId
                ))
                .codeKeyId(keyId)
                .purpose(purpose)
                .deliveryStatus(DeliveryStatus.PENDING_DELIVERY)
                .usageStatus(UsageStatus.UNUSED)
                .idempotencyKey(idempotencyKey)
                .expiresAt(totalExpiresAt)
                .deliveryDeadline(deliveryDeadline)
                .retryCount(0)
                .build();
        verificationCodeRepository.saveAndFlush(challenge);
        outboxRepository.saveAndFlush(EmailDeliveryOutbox.builder()
                .id(UUID.randomUUID().toString())
                .challengeId(handle)
                .idempotencyKey(idempotencyKey)
                .status(EmailDeliveryOutbox.Status.PENDING)
                .attemptCount(0)
                .nextAttemptAt(now)
                .build());
        securityEventService.append(
                "EMAIL_CHALLENGE_QUEUED",
                handle,
                SecurityEventService.Outcome.SUCCESS,
                purpose.name()
        );
        log.info("Email verification challenge queued for purpose {}", purpose);
        return new ChallengeDispatch(
                handle,
                properties.getExpiryMinutes() * 60,
                properties.getResendCooldownSeconds()
        );
    }

    @Deprecated
    @Transactional
    public ChallengeDispatch sendVerificationCode(
            String email,
            VerificationPurpose purpose,
            Map<String, Object> ignoredMetadata) {
        return sendVerificationCode(email, purpose);
    }

    @Transactional
    public VerificationResult verifyCode(
            String challengeHandle,
            String submittedEmail,
            String suppliedCode,
            VerificationPurpose purpose) {
        String email;
        try {
            email = canonicalEmailService.canonicalize(submittedEmail);
        } catch (IllegalArgumentException exception) {
            return VerificationResult.invalid(0);
        }

        EmailVerificationCode challenge = verificationCodeRepository
                .findById(challengeHandle)
                .orElse(null);
        if (challenge == null
                || !email.equals(challenge.getEmail())
                || purpose != challenge.getPurpose()
                || !challenge.isActive()) {
            return VerificationResult.notFound();
        }

        Instant now = Instant.now();
        if (challenge.isExpired(now)) {
            challenge.setUsageStatus(UsageStatus.EXPIRED);
            verificationCodeRepository.save(challenge);
            securityEventService.append(
                    "EMAIL_CHALLENGE_REJECTED",
                    challenge.getId(),
                    SecurityEventService.Outcome.DENIED,
                    "EXPIRED"
            );
            return VerificationResult.expired();
        }

        if (codeProtector.matches(
                challenge.getId(),
                suppliedCode,
                challenge.getCodeKeyId(),
                challenge.getCodeDigest()
        )) {
            if (verificationCodeRepository.consumeIfUsable(
                    challenge.getId()
            ) == 1) {
                securityEventService.append(
                        "EMAIL_CHALLENGE_CONSUMED",
                        challenge.getId(),
                        SecurityEventService.Outcome.SUCCESS,
                        purpose.name()
                );
                return VerificationResult.success();
            }
            return VerificationResult.notFound();
        }
        return recordInvalidAttempt(challenge, now);
    }

    @Deprecated
    @Transactional
    public VerificationResult verifyCode(
            String email,
            String code,
            VerificationPurpose purpose) {
        List<EmailVerificationCode> active =
                verificationCodeRepository.findActive(
                        canonicalEmailService.canonicalize(email),
                        purpose
                );
        if (active.isEmpty()) {
            return VerificationResult.notFound();
        }
        return verifyCode(active.get(0).getId(), email, code, purpose);
    }

    private VerificationResult recordInvalidAttempt(
            EmailVerificationCode initial,
            Instant now) {
        EmailVerificationCode current = initial;
        int maxRetryAttempts = properties.getMaxRetryAttempts();

        while (true) {
            int currentRetryCount = current.getRetryCount();
            if (currentRetryCount >= maxRetryAttempts) {
                return VerificationResult.maxRetriesExceeded();
            }
            int updated = verificationCodeRepository.incrementRetryCountIfCurrent(
                    current.getId(),
                    currentRetryCount,
                    maxRetryAttempts
            );
            if (updated == 1) {
                int newRetryCount = currentRetryCount + 1;
                securityEventService.append(
                        "EMAIL_CHALLENGE_REJECTED",
                        current.getId(),
                        SecurityEventService.Outcome.DENIED,
                        newRetryCount >= maxRetryAttempts
                                ? "MAX_ATTEMPTS"
                                : "INVALID_CODE"
                );
                return newRetryCount >= maxRetryAttempts
                        ? VerificationResult.maxRetriesExceeded()
                        : VerificationResult.invalid(
                                maxRetryAttempts - newRetryCount
                        );
            }

            current = verificationCodeRepository.findById(initial.getId())
                    .orElse(null);
            if (current == null || current.getUsageStatus() != UsageStatus.UNUSED) {
                return VerificationResult.notFound();
            }
            if (current.isExpired(now)) {
                return VerificationResult.expired();
            }
        }
    }

    public boolean canSend(String submittedEmail) {
        String email = canonicalEmailService.canonicalize(submittedEmail);
        long todayCount = verificationCodeRepository.countByEmailAndCreatedAtAfter(
                email,
                Instant.now().truncatedTo(ChronoUnit.DAYS)
        );
        return todayCount < properties.getMaxSendPerDay();
    }

    public long getResendCooldown(
            String submittedEmail,
            VerificationPurpose purpose) {
        String email = canonicalEmailService.canonicalize(submittedEmail);
        return verificationCodeRepository
                .findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, purpose)
                .map(challenge -> {
                    Instant cooldownEnd = challenge.getCreatedAt().plus(
                            properties.getResendCooldownSeconds(),
                            ChronoUnit.SECONDS
                    );
                    long remainingMillis = Duration.between(
                            Instant.now(),
                            cooldownEnd
                    ).toMillis();
                    return Math.max(0, (remainingMillis + 999) / 1000);
                })
                .orElse(0L);
    }

    public int getExpirySeconds() {
        return Math.multiplyExact(properties.getExpiryMinutes(), 60);
    }

    public int getResendCooldownSeconds() {
        return properties.getResendCooldownSeconds();
    }

    @Transactional
    public int cleanupExpiredCodes() {
        return verificationCodeRepository.expireChallenges(Instant.now());
    }

    public record ChallengeDispatch(
            String challengeHandle,
            int expiresIn,
            int resendAfter) {
    }

    public static class VerificationResult {
        private final boolean success;
        private final String error;
        private final int remainingAttempts;

        private VerificationResult(
                boolean success,
                String error,
                int remainingAttempts) {
            this.success = success;
            this.error = error;
            this.remainingAttempts = remainingAttempts;
        }

        public static VerificationResult success() {
            return new VerificationResult(true, null, 0);
        }

        public static VerificationResult notFound() {
            return new VerificationResult(
                    false,
                    "Invalid or expired verification challenge",
                    0
            );
        }

        public static VerificationResult expired() {
            return new VerificationResult(
                    false,
                    "Invalid or expired verification challenge",
                    0
            );
        }

        public static VerificationResult maxRetriesExceeded() {
            return new VerificationResult(
                    false,
                    "Invalid or expired verification challenge",
                    0
            );
        }

        public static VerificationResult invalid(int remainingAttempts) {
            return new VerificationResult(
                    false,
                    "Invalid or expired verification challenge",
                    remainingAttempts
            );
        }

        public boolean isSuccess() {
            return success;
        }

        public String getError() {
            return error;
        }

        public int getRemainingAttempts() {
            return remainingAttempts;
        }

        @Deprecated
        public Map<String, Object> getMetadata() {
            return Map.of();
        }
    }
}
