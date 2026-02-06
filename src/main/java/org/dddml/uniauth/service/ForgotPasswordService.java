package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.entity.EmailVerificationCode.VerificationPurpose;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.service.email.EmailService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ForgotPasswordService {

    private final EmailVerificationCodeService verificationCodeService;
    private final UserLoginMethodRepository loginMethodRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    private static final String PASSWORD_RESET_TEMPLATE = "email/password-reset";

    @Transactional
    public boolean sendPasswordResetCode(String email) {
        log.info("Sending password reset code for email: {}", email);

        boolean emailExists = loginMethodRepository.findByLocalUsername(email).isPresent();

        if (!emailExists) {
            log.info("Email not found in local login methods: {}", email);
            return false;
        }

        if (emailService.isAvailable()) {
            String result = emailService.sendTemplateEmail(
                email,
                "重置您的密码",
                PASSWORD_RESET_TEMPLATE,
                Map.of(
                    "username", email,
                    "verificationCode", "123456",
                    "expiryMinutes", 10
                ),
                "PASSWORD_RESET"
            ).name();

            if (result.equals("FAILED") || result.equals("RATE_LIMITED")) {
                log.warn("Email service returned: {}", result);
            }
        } else {
            log.warn("Email service is unavailable, verification code will still be created");
        }

        verificationCodeService.sendVerificationCode(email, VerificationPurpose.PASSWORD_RESET, null);
        log.info("Password reset code created for email: {}", email);
        return true;
    }

    @Transactional
    public void resetPassword(String email, String verificationCode, String newPassword) {
        log.info("Resetting password for email: {}", email);

        UserLoginMethod loginMethod = loginMethodRepository.findByLocalUsername(email)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        var result = verificationCodeService.verifyCode(email, verificationCode, VerificationPurpose.PASSWORD_RESET);

        if (!result.isSuccess()) {
            if (result.getError().contains("not found")) {
                throw new IllegalArgumentException("验证码不存在或已过期，请重新获取");
            } else if (result.getError().contains("expired")) {
                throw new IllegalArgumentException("验证码已过期，请重新获取");
            } else if (result.getError().contains("Maximum retry")) {
                throw new IllegalArgumentException("验证失败次数过多，请重新获取验证码");
            } else {
                int remaining = result.getRemainingAttempts();
                throw new IllegalArgumentException("验证码错误，剩余" + remaining + "次尝试");
            }
        }

        loginMethod.setLocalPasswordHash(passwordEncoder.encode(newPassword));
        loginMethodRepository.save(loginMethod);
        log.info("Password reset successfully for email: {}", email);
    }

    public long getResendCooldown(String email) {
        return verificationCodeService.getResendCooldown(email);
    }

    public boolean canSend(String email) {
        return verificationCodeService.canSend(email);
    }
}
