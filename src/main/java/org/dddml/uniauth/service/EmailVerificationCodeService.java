package org.dddml.uniauth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.config.EmailVerificationProperties;
import org.dddml.uniauth.entity.EmailVerificationCode;
import org.dddml.uniauth.entity.EmailVerificationCode.VerificationPurpose;
import org.dddml.uniauth.repository.EmailVerificationCodeRepository;
import org.dddml.uniauth.service.email.EmailSendResult;
import org.dddml.uniauth.service.email.EmailService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationCodeService {

    private final EmailVerificationCodeRepository verificationCodeRepository;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;
    private final EmailVerificationProperties properties;

    private static final String EMAIL_VERIFY_TEMPLATE = "email/email-verify";
    private static final String PASSWORD_RESET_TEMPLATE = "email/password-reset";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Transactional
    public void sendVerificationCode(String email, VerificationPurpose purpose, Map<String, Object> metadata) {
        log.info("Sending verification code for purpose {}", purpose);

        String verificationCode = generateVerificationCode();
        Map<String, Object> templateVariables = new HashMap<>();
        templateVariables.put("code", verificationCode);
        templateVariables.put("verificationCode", verificationCode);
        templateVariables.put("username", email);
        templateVariables.put("expiryMinutes", properties.getExpiryMinutes());

        String template = purpose == VerificationPurpose.PASSWORD_RESET
                ? PASSWORD_RESET_TEMPLATE
                : EMAIL_VERIFY_TEMPLATE;
        String subject = purpose == VerificationPurpose.PASSWORD_RESET
                ? "重置您的密码"
                : "Verify your email";
        String emailType = purpose == VerificationPurpose.PASSWORD_RESET
                ? "PASSWORD_RESET"
                : "VERIFICATION";

        EmailSendResult result;
        try {
            result = emailService.sendTemplateEmail(
                email,
                subject,
                template,
                templateVariables,
                emailType
            );
        } catch (RuntimeException exception) {
            log.warn(
                "Email service boundary failed with {}",
                exception.getClass().getSimpleName()
            );
            throw new VerificationCodeDeliveryException(
                EmailSendResult.FAILED,
                exception
            );
        }
        if (result == null) {
            result = EmailSendResult.FAILED;
        }
        if (result != EmailSendResult.SUCCESS && result != EmailSendResult.QUEUED) {
            log.warn("Email service did not accept the verification request");
            throw new VerificationCodeDeliveryException(result);
        }

        EmailVerificationCode code = EmailVerificationCode.builder()
            .id(UUID.randomUUID().toString())
            .email(email)
            .verificationCode(verificationCode)
            .purpose(purpose)
            .metadata(serializeMetadata(metadata))
            .expiresAt(Instant.now().plus(properties.getExpiryMinutes(), ChronoUnit.MINUTES))
            .isUsed(false)
            .retryCount(0)
            .build();

        verificationCodeRepository.save(code);
        log.info("Verification code created for purpose {}", purpose);
    }

    @Transactional
    public void sendVerificationCode(String email, VerificationPurpose purpose) {
        sendVerificationCode(email, purpose, null);
    }

    public CodeCheckResult checkVerificationCode(String email, String code, VerificationPurpose purpose) {
        log.debug("Checking verification code for purpose {}", purpose);

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
            int remainingAttempts = Math.max(
                0,
                properties.getMaxRetryAttempts() - codeRecord.getRetryCount()
            );
            return CodeCheckResult.invalid(remainingAttempts);
        }

        return CodeCheckResult.valid();
    }

    @Transactional
    public VerificationResult verifyCode(String email, String code, VerificationPurpose purpose) {
        log.debug("Verifying code for purpose {}", purpose);

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

        Instant now = Instant.now();
        if (codeRecord.getVerificationCode().equals(code)) {
            Map<String, Object> metadataMap = deserializeMetadata(codeRecord.getMetadata());
            int consumed = verificationCodeRepository.consumeIfUsable(
                codeRecord.getId(),
                code
            );
            return consumed == 1
                ? VerificationResult.success(metadataMap)
                : VerificationResult.notFound();
        }

        return recordInvalidAttempt(codeRecord, now);
    }

    private VerificationResult recordInvalidAttempt(
            EmailVerificationCode initialRecord,
            Instant now) {
        EmailVerificationCode current = initialRecord;
        int maxRetryAttempts = properties.getMaxRetryAttempts();

        while (true) {
            int currentRetryCount = current.getRetryCount();
            if (currentRetryCount >= maxRetryAttempts) {
                verificationCodeRepository.deleteById(current.getId());
                return VerificationResult.maxRetriesExceeded();
            }

            int updated = verificationCodeRepository.incrementRetryCountIfCurrent(
                current.getId(),
                currentRetryCount
            );
            if (updated == 1) {
                int newRetryCount = currentRetryCount + 1;
                if (newRetryCount >= maxRetryAttempts) {
                    verificationCodeRepository.deleteById(current.getId());
                    return VerificationResult.maxRetriesExceeded();
                }
                return VerificationResult.invalid(maxRetryAttempts - newRetryCount);
            }

            current = verificationCodeRepository.findById(initialRecord.getId()).orElse(null);
            if (current == null || Boolean.TRUE.equals(current.getIsUsed())) {
                return VerificationResult.notFound();
            }
            if (!current.getExpiresAt().isAfter(now)) {
                verificationCodeRepository.delete(current);
                return VerificationResult.expired();
            }
        }
    }

    public boolean canSend(String email) {
        long todayCount = verificationCodeRepository.countByEmailAndCreatedAtAfter(
            email,
            Instant.now().truncatedTo(ChronoUnit.DAYS)
        );
        return todayCount < properties.getMaxSendPerDay();
    }

    public long getResendCooldown(String email, VerificationPurpose purpose) {
        return verificationCodeRepository
            .findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, purpose)
            .map(c -> {
                Instant lastSend = c.getCreatedAt();
                Instant cooldownEnd = lastSend.plus(
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
        int deleted = verificationCodeRepository.deleteExpiredCodes(Instant.now());
        log.info("Cleaned up {} expired verification codes", deleted);
        return deleted;
    }

    public boolean hasPendingVerification(String email, VerificationPurpose purpose) {
        return verificationCodeRepository.existsByEmailAndPurposeAndIsUsedFalse(email, purpose);
    }

    private String generateVerificationCode() {
        int codeLength = properties.getCodeLength();
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
            log.error("Failed to serialize verification metadata");
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
            log.error("Failed to deserialize verification metadata");
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
