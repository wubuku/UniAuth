package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.entity.EmailVerificationCode.VerificationPurpose;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ForgotPasswordService {

    private final EmailVerificationCodeService verificationCodeService;
    private final UserLoginMethodRepository loginMethodRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyService passwordPolicyService;
    private final CanonicalEmailService canonicalEmailService;
    private final EmailVerificationCodeProtector codeProtector;
    private final SecurityEventService securityEventService;
    private final TokenSessionTransactionService tokenSessionTransactionService;

    public PasswordResetDispatch requestPasswordReset(String submittedEmail) {
        String email = canonicalEmailService.canonicalize(submittedEmail);
        UserLoginMethod method = loginMethodRepository
                .findByLocalUsername(email)
                .orElse(null);
        if (method == null
                || method.getLocalPasswordHash() == null
                || !method.getUser().isEnabled()) {
            String decoyHandle = UUID.randomUUID().toString();
            codeProtector.deriveCode(decoyHandle, codeProtector.currentKeyId());
            return new PasswordResetDispatch(
                    decoyHandle,
                    verificationCodeService.getExpirySeconds(),
                    verificationCodeService.getResendCooldownSeconds()
            );
        }

        EmailVerificationCodeService.ChallengeDispatch dispatch =
                verificationCodeService.sendVerificationCode(
                        email,
                        VerificationPurpose.PASSWORD_RESET
                );
        return new PasswordResetDispatch(
                dispatch.challengeHandle(),
                dispatch.expiresIn(),
                dispatch.resendAfter()
        );
    }

    @Transactional(
        noRollbackFor = VerificationChallengeRejectedException.class
    )
    public void resetPassword(
            String challengeHandle,
            String submittedEmail,
            String verificationCode,
            String newPassword) {
        String email = canonicalEmailService.canonicalize(submittedEmail);
        passwordPolicyService.validateNewPassword(newPassword);

        UserLoginMethod method = loginMethodRepository
                .findByLocalUsername(email)
                .orElse(null);
        if (method == null
                || method.getLocalPasswordHash() == null
                || !method.getUser().isEnabled()) {
            throw new IllegalArgumentException(
                    "Invalid or expired verification challenge"
            );
        }

        EmailVerificationCodeService.VerificationResult result =
                verificationCodeService.verifyCode(
                        challengeHandle,
                        email,
                        verificationCode,
                        VerificationPurpose.PASSWORD_RESET
                );
        if (!result.isSuccess()) {
            throw new VerificationChallengeRejectedException();
        }
        method.setLocalPasswordHash(passwordEncoder.encode(newPassword));
        loginMethodRepository.save(method);
        tokenSessionTransactionService.incrementSecurityVersionAndRevoke(
                method.getUser().getId(),
                "PASSWORD_RESET"
        );
        securityEventService.append(
                "PASSWORD_RESET_COMPLETED",
                method.getUser().getId(),
                SecurityEventService.Outcome.SUCCESS,
                null
        );
        log.info("Password reset completed");
    }

    public boolean canSend(String submittedEmail) {
        return verificationCodeService.canSend(
                canonicalEmailService.canonicalize(submittedEmail)
        );
    }

    public long getResendCooldown(String submittedEmail) {
        return verificationCodeService.getResendCooldown(
                canonicalEmailService.canonicalize(submittedEmail),
                VerificationPurpose.PASSWORD_RESET
        );
    }

    public record PasswordResetDispatch(
            String challengeHandle,
            int expiresIn,
            int resendAfter) {
    }
}
