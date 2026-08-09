package org.dddml.uniauth.controller;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.dto.ForgotPasswordRequest;
import org.dddml.uniauth.dto.ResetPasswordRequest;
import org.dddml.uniauth.service.ForgotPasswordService;
import org.dddml.uniauth.service.AuthRateLimiter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class ForgotPasswordController {

    private final ForgotPasswordService forgotPasswordService;
    private final AuthRateLimiter authRateLimiter;

    @PostMapping(
        value = "/forgot-password",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {
        authRateLimiter.requireAllowed(
                AuthRateLimiter.Policy.PASSWORD_RESET_SEND,
                httpRequest.getRemoteAddr(),
                request.getEmail()
        );
        ForgotPasswordService.PasswordResetDispatch dispatch =
                forgotPasswordService.requestPasswordReset(request.getEmail());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put(
                "message",
                "If the account can be recovered, a verification code was sent"
        );
        body.put("challengeHandle", dispatch.challengeHandle());
        body.put("resendAfter", dispatch.resendAfter());
        body.put("expiresIn", dispatch.expiresIn());
        return ResponseEntity.ok(body);
    }

    @PostMapping(
        value = "/verify-reset-code",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> verifyResetCode(
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest httpRequest) {
        authRateLimiter.requireAllowed(
                AuthRateLimiter.Policy.PASSWORD_RESET_VERIFY,
                httpRequest.getRemoteAddr(),
                request.getEmail()
        );
        try {
            forgotPasswordService.resetPassword(
                    request.getChallengeHandle(),
                    request.getEmail(),
                    request.getVerificationCode(),
                    request.getNewPassword()
            );
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Password reset completed"
            ));
        } catch (IllegalArgumentException exception) {
            log.warn("Password reset challenge was rejected");
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Invalid or expired verification challenge"
            ));
        }
    }
}
