package org.dddml.uniauth.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.dto.LoginRequest;
import org.dddml.uniauth.dto.RegisterRequest;
import org.dddml.uniauth.dto.UserDto;
import org.dddml.uniauth.dto.VerifyEmailRequest;
import org.dddml.uniauth.service.AuthenticationLogoutService;
import org.dddml.uniauth.service.CredentialAuthenticationService;
import org.dddml.uniauth.service.RegistrationService;
import org.dddml.uniauth.service.TokenIssuanceFacade;
import org.dddml.uniauth.service.TokenRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "用户认证相关API")
@Slf4j
public class AuthController {

    private final RegistrationService registrationService;
    private final CredentialAuthenticationService credentialAuthenticationService;
    private final TokenIssuanceFacade tokenIssuanceFacade;
    private final AuthenticationLogoutService authenticationLogoutService;
    private final org.dddml.uniauth.service.AuthRateLimiter authRateLimiter;

    @PostMapping(
        value = "/register",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        authRateLimiter.requireAllowed(
                org.dddml.uniauth.service.AuthRateLimiter.Policy.REGISTRATION,
                httpRequest.getRemoteAddr(),
                request.getEmail()
        );
        if (request.getChallengeHandle() != null
                || request.getVerificationCode() != null) {
            if (request.getChallengeHandle() == null
                    || request.getVerificationCode() == null) {
                throw new IllegalArgumentException(
                        "Challenge handle and verification code are required"
                );
            }
            VerifyEmailRequest verification = new VerifyEmailRequest();
            verification.setChallengeHandle(request.getChallengeHandle());
            verification.setUsername(request.getUsername());
            verification.setEmail(request.getEmail());
            verification.setPassword(request.getPassword());
            verification.setDisplayName(request.getDisplayName());
            verification.setVerificationCode(request.getVerificationCode());
            UserDto user = registrationService.complete(verification);
            Map<String, Object> body = new LinkedHashMap<>(
                    tokenIssuanceFacade.issue(
                            user,
                            httpRequest,
                            response,
                            "Registration completed successfully",
                            java.time.Instant.now()
                    )
            );
            body.put("success", true);
            return ResponseEntity.ok(body);
        }

        RegistrationService.RegistrationPreview preview =
                registrationService.preview(
                        request.getUsername(),
                        request.getEmail(),
                        request.getPassword(),
                        request.getDisplayName()
                );
        return ResponseEntity.ok(Map.of(
                "requireEmailVerification", true,
                "username", preview.username(),
                "email", preview.email(),
                "message", "Complete email verification to finish registration"
        ));
    }

    @PostMapping(
        value = "/login",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        try {
            authRateLimiter.requireAllowed(
                    org.dddml.uniauth.service.AuthRateLimiter.Policy.LOGIN,
                    httpRequest.getRemoteAddr(),
                    request.getUsername()
            );
            UserDto user = credentialAuthenticationService.authenticate(request);
            return ResponseEntity.ok(tokenIssuanceFacade.issue(
                    user,
                    httpRequest,
                    response,
                    "Login successful",
                    java.time.Instant.now()
            ));
        } catch (TokenRejectedException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "error", "ACTIVE_SESSION_CONFLICT",
                            "message", "Log out the current browser session first"
                    ));
        } catch (BadCredentialsException | IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid credentials"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthenticationLogoutService.LogoutResult result =
                authenticationLogoutService.logout(request, response);
        if (!result.revocationComplete()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "REVOCATION_INCOMPLETE",
                    "message", "Logged out locally; token revocation is incomplete"
            ));
        }
        log.info("Authentication logout completed");
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }
}
