package org.dddml.uniauth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.entity.EmailVerificationCode;
import org.dddml.uniauth.entity.EmailVerificationCode.VerificationPurpose;
import org.dddml.uniauth.repository.EmailVerificationCodeRepository;
import org.dddml.uniauth.service.email.EmailSendResult;
import org.dddml.uniauth.service.email.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationCodeService {

    private final EmailVerificationCodeRepository verificationCodeRepository;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    @Value("${app.email.verification.code-length:6}")
    private int codeLength;

    @Value("${app.email.verification.expiry-minutes:10}")
    private int expiryMinutes;

    @Value("${app.email.verification.max-send-per-day:10}")
    private int maxSendPerDay;

    @Value("${app.email.verification.resend-cooldown-seconds:60}")
    private int resendCooldownSeconds;

    private static final String EMAIL_VERIFY_TEMPLATE = "email/email-verify";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Transactional
    public void sendVerificationCode(String email, VerificationPurpose purpose, Map<String, Object> metadata) {
        log.info("Sending verification code for email: {}, purpose: {}", email, purpose);

        if (emailService.isAvailable()) {
            String result = emailService.sendTemplateEmail(
                email,
                "Verify your email",
                EMAIL_VERIFY_TEMPLATE,
                Map.of("code", generateVerificationCode()),
                "VERIFICATION"
            ).name();

            if (result.equals("FAILED") || result.equals("RATE_LIMITED")) {
                log.warn("Email service returned: {}", result);
            }
        } else {
            log.warn("Email service is unavailable, verification code will still be created");
        }

        EmailVerificationCode code = EmailVerificationCode.builder()
            .id(UUID.randomUUID().toString())
            .email(email)
            .verificationCode(generateVerificationCode())
            .purpose(purpose)
            .metadata(serializeMetadata(metadata))
            .expiresAt(Instant.now().plus(expiryMinutes, ChronoUnit.MINUTES))
            .isUsed(false)
            .retryCount(0)
            .build();

        verificationCodeRepository.save(code);
        log.info("Verification code created for email: {}", email);
    }

    @Transactional
    public void sendVerificationCode(String email, VerificationPurpose purpose) {
        sendVerificationCode(email, purpose, null);
    }

    public CodeCheckResult checkVerificationCode(String email, String code, VerificationPurpose purpose) {
        log.info("Checking verification code for email: {}, purpose: {}", email, purpose);

        EmailVerificationCode codeRecord = verificationCodeRepository
            .findFirstByEmailAndPurposeAndIsUsedFalseOrderByCreatedAtDesc(email, purpose)
            .orElse(null);

        if (codeRecord == null) {
            return CodeCheckResult.notFound();
        }

        if (codeRecord.isExpired()) {
            return CodeCheckResult.expired();
        }

        if (!codeRecord.getVerificationCode().equals(code)) {
            int remainingAttempts = 5 - codeRecord.getRetryCount();
            return CodeCheckResult.invalid(remainingAttempts);
        }

        return CodeCheckResult.valid();
    }

    public VerificationResult verifyCode(String email, String code, VerificationPurpose purpose) {
        log.info("Verifying code for email: {}, purpose: {}", email, purpose);

        EmailVerificationCode codeRecord = verificationCodeRepository
            .findFirstByEmailAndPurposeAndIsUsedFalseOrderByCreatedAtDesc(email, purpose)
            .orElse(null);

        if (codeRecord == null) {
            return VerificationResult.notFound();
        }

        if (codeRecord.isExpired()) {
            verificationCodeRepository.delete(codeRecord);
            return VerificationResult.expired();
        }

        if (!codeRecord.getVerificationCode().equals(code)) {
            codeRecord.incrementRetryCount();
            verificationCodeRepository.save(codeRecord);

            if (codeRecord.getRetryCount() >= 5) {
                verificationCodeRepository.delete(codeRecord);
                return VerificationResult.maxRetriesExceeded();
            }

            int remainingAttempts = 5 - codeRecord.getRetryCount();
            return VerificationResult.invalid(remainingAttempts);
        }

        codeRecord.setIsUsed(true);
        verificationCodeRepository.save(codeRecord);

        String metadata = codeRecord.getMetadata();
        Map<String, Object> metadataMap = deserializeMetadata(metadata);

        return VerificationResult.success(metadataMap);
    }

    public boolean canSend(String email) {
        long todayCount = verificationCodeRepository.countByEmailAndCreatedAtAfter(
            email,
            Instant.now().truncatedTo(ChronoUnit.DAYS)
        );
        return todayCount < maxSendPerDay;
    }

    public long getResendCooldown(String email) {
        List<EmailVerificationCode> codes = verificationCodeRepository.findByEmail(email);
        if (codes.isEmpty()) {
            return 0;
        }

        return codes.stream()
            .filter(c -> Boolean.FALSE.equals(c.getIsUsed()))
            .findFirst()
            .map(c -> {
                Instant lastSend = c.getCreatedAt();
                Instant cooldownEnd = lastSend.plus(resendCooldownSeconds, ChronoUnit.SECONDS);
                long remaining = Instant.now().until(cooldownEnd, ChronoUnit.SECONDS);
                return Math.max(0, remaining);
            })
            .orElse(0L);
    }

    @Transactional
    public int cleanupExpiredCodes() {
        int deleted = verificationCodeRepository.deleteExpiredCodes(Instant.now());
        log.info("Cleaned up {} expired verification codes", deleted);
        return deleted;
    }

    public boolean hasPendingVerification(String email, VerificationPurpose purpose) {
        return verificationCodeRepository.existsByEmailAndPurposeAndIsUsedFalse(email, purpose);
    }

    @Transactional
    public void markAsUsed(String email, VerificationPurpose purpose) {
        verificationCodeRepository.findFirstByEmailAndPurposeAndIsUsedFalseOrderByCreatedAtDesc(email, purpose)
            .ifPresent(code -> {
                code.setIsUsed(true);
                verificationCodeRepository.save(code);
            });
    }

    private String generateVerificationCode() {
        StringBuilder code = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) {
            code.append(SECURE_RANDOM.nextInt(10));
        }
        return code.toString();
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize metadata", e);
            return null;
        }
    }

    private Map<String, Object> deserializeMetadata(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(metadata, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize metadata", e);
            return new HashMap<>();
        }
    }

    public static class VerificationResult {
        private final boolean success;
        private final String error;
        private final int remainingAttempts;
        private final Map<String, Object> metadata;

        private VerificationResult(boolean success, String error, int remainingAttempts, Map<String, Object> metadata) {
            this.success = success;
            this.error = error;
            this.remainingAttempts = remainingAttempts;
            this.metadata = metadata;
        }

        public static VerificationResult success(Map<String, Object> metadata) {
            return new VerificationResult(true, null, 0, metadata);
        }

        public static VerificationResult notFound() {
            return new VerificationResult(false, "Verification code not found", 0, null);
        }

        public static VerificationResult expired() {
            return new VerificationResult(false, "Verification code expired", 0, null);
        }

        public static VerificationResult maxRetriesExceeded() {
            return new VerificationResult(false, "Maximum retry attempts exceeded", 0, null);
        }

        public static VerificationResult invalid(int remainingAttempts) {
            return new VerificationResult(false, "Invalid verification code", remainingAttempts, null);
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

        public Map<String, Object> getMetadata() {
            return metadata;
        }
    }

    public static class CodeCheckResult {
        private final boolean valid;
        private final String status;
        private final String message;
        private final int remainingAttempts;

        private CodeCheckResult(boolean valid, String status, String message, int remainingAttempts) {
            this.valid = valid;
            this.status = status;
            this.message = message;
            this.remainingAttempts = remainingAttempts;
        }

        public static CodeCheckResult valid() {
            return new CodeCheckResult(true, "VALID", "Verification code is valid", 0);
        }

        public static CodeCheckResult notFound() {
            return new CodeCheckResult(false, "NOT_FOUND", "No pending verification code found", 0);
        }

        public static CodeCheckResult expired() {
            return new CodeCheckResult(false, "EXPIRED", "Verification code has expired", 0);
        }

        public static CodeCheckResult invalid(int remainingAttempts) {
            return new CodeCheckResult(false, "INVALID", "Invalid verification code", remainingAttempts);
        }

        public boolean isValid() {
            return valid;
        }

        public String getStatus() {
            return status;
        }

        public String getMessage() {
            return message;
        }

        public int getRemainingAttempts() {
            return remainingAttempts;
        }
    }
}
