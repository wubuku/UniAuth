package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.entity.EmailVerificationCode.VerificationPurpose;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ForgotPasswordService {

    private final EmailVerificationCodeService verificationCodeService;
    private final UserLoginMethodRepository loginMethodRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public boolean sendPasswordResetCode(String email) {
        log.info("Password reset code requested");

        boolean emailExists = loginMethodRepository.findByLocalUsername(email).isPresent();

        if (!emailExists) {
            log.info("Password reset request did not match a local account");
            return false;
        }

        verificationCodeService.sendVerificationCode(email, VerificationPurpose.PASSWORD_RESET, null);
        log.info("Password reset code created");
        return true;
    }

    @Transactional
    public void resetPassword(String email, String verificationCode, String newPassword) {
        log.info("Password reset verification started");

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
        log.info("Password reset completed");
    }

    public long getResendCooldown(String email) {
        return verificationCodeService.getResendCooldown(email);
    }

    public boolean canSend(String email) {
        return verificationCodeService.canSend(email);
    }
}
