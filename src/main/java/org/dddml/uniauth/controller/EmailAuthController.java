package org.dddml.uniauth.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.dto.SendVerificationCodeRequest;
import org.dddml.uniauth.dto.UserDto;
import org.dddml.uniauth.dto.VerifyEmailRequest;
import org.dddml.uniauth.entity.EmailVerificationCode.VerificationPurpose;
import org.dddml.uniauth.service.EmailVerificationCodeService;
import org.dddml.uniauth.service.AuthRateLimiter;
import org.dddml.uniauth.service.RegistrationService;
import org.dddml.uniauth.service.TokenIssuanceFacade;
import org.dddml.uniauth.service.VerificationCodeDeliveryException;
import org.dddml.uniauth.service.email.EmailSendResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class EmailAuthController {

    private final EmailVerificationCodeService verificationCodeService;
    private final RegistrationService registrationService;
    private final TokenIssuanceFacade tokenIssuanceFacade;
    private final AuthRateLimiter authRateLimiter;

    @PostMapping(
        value = "/send-verification-code",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Map<String, Object>> sendVerificationCode(
            @Valid @RequestBody SendVerificationCodeRequest request,
            HttpServletRequest httpRequest) {
        authRateLimiter.requireAllowed(
                AuthRateLimiter.Policy.CHALLENGE_SEND,
                httpRequest.getRemoteAddr(),
                request.getEmail()
        );
        if (!VerificationPurpose.REGISTRATION.name().equals(request.getPurpose())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "UNSUPPORTED_PURPOSE",
                    "message", "This endpoint only supports registration verification"
            ));
        }

        String email = request.getEmail();
        if (!verificationCodeService.canSend(email)) {
            return ResponseEntity.status(429).body(Map.of(
                    "success", false,
                    "error", "RATE_LIMITED",
                    "message", "Too many requests, please try again later",
                    "retryAfter", 86400
            ));
        }
        long cooldown = verificationCodeService.getResendCooldown(
                email,
                VerificationPurpose.REGISTRATION
        );
        if (cooldown > 0) {
            return ResponseEntity.status(429).body(Map.of(
                    "success", false,
                    "error", "COOLDOWN",
                    "message", "Please wait before requesting a new code",
                    "retryAfter", cooldown
            ));
        }

        try {
            EmailVerificationCodeService.ChallengeDispatch dispatch =
                    verificationCodeService.sendVerificationCode(
                            email,
                            VerificationPurpose.REGISTRATION
                    );
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Verification code sent successfully");
            response.put("challengeHandle", dispatch.challengeHandle());
            response.put("expiresIn", dispatch.expiresIn());
            response.put("resendAfter", dispatch.resendAfter());
            return ResponseEntity.ok(response);
        } catch (VerificationCodeDeliveryException exception) {
            return deliveryFailureResponse(exception.getResult());
        }
    }

    @PostMapping(
        value = "/verify-email",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        authRateLimiter.requireAllowed(
                AuthRateLimiter.Policy.CHALLENGE_VERIFY,
                httpRequest.getRemoteAddr(),
                request.getEmail()
        );
        UserDto user = registrationService.complete(request);
        Map<String, Object> body = new LinkedHashMap<>(
                tokenIssuanceFacade.issue(
                        user,
                        httpRequest,
                        response,
                        "Email verified successfully",
                        java.time.Instant.now()
                )
        );
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> deliveryFailureResponse(
            EmailSendResult result) {
        if (result == EmailSendResult.INVALID_EMAIL) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "INVALID_EMAIL",
                    "message", "The email address is invalid"
            ));
        }
        if (result == EmailSendResult.RATE_LIMITED) {
            return ResponseEntity.status(429).body(Map.of(
                    "success", false,
                    "error", "EMAIL_SERVICE_RATE_LIMITED",
                    "message", "Email delivery is temporarily rate limited",
                    "retryAfter",
                    verificationCodeService.getResendCooldownSeconds()
            ));
        }
        return ResponseEntity.status(503).body(Map.of(
                "success", false,
                "error", "EMAIL_SERVICE_UNAVAILABLE",
                "message", "Email delivery is temporarily unavailable"
        ));
    }
}
