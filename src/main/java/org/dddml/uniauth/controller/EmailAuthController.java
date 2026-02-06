package org.dddml.uniauth.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.config.EmailRegistrationProperties;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.entity.UserLoginMethod.AuthProvider;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.service.EmailVerificationCodeService;
import org.dddml.uniauth.service.JwtTokenService;
import org.dddml.uniauth.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class EmailAuthController {

    private final EmailVerificationCodeService verificationCodeService;
    private final UserRepository userRepository;
    private final UserLoginMethodRepository loginMethodRepository;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final EmailRegistrationProperties emailRegistrationProperties;

    @GetMapping("/email/status/{email}")
    public ResponseEntity<Map<String, Object>> getEmailStatus(@PathVariable String email) {
        boolean hasPendingRegistration = verificationCodeService.hasPendingVerification(
            email,
            org.dddml.uniauth.entity.EmailVerificationCode.VerificationPurpose.REGISTRATION
        );

        return ResponseEntity.ok(Map.of(
            "email", email,
            "hasPendingVerification", hasPendingRegistration
        ));
    }

    @PostMapping("/send-verification-code")
    @Transactional
    public ResponseEntity<Map<String, Object>> sendVerificationCode(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String purposeStr = request.getOrDefault("purpose", "REGISTRATION");
        var purpose = org.dddml.uniauth.entity.EmailVerificationCode.VerificationPurpose.valueOf(purposeStr);

        if (!verificationCodeService.canSend(email)) {
            return ResponseEntity.status(429).body(Map.of(
                "success", false,
                "error", "RATE_LIMITED",
                "message", "Too many requests, please try again later",
                "retryAfter", 86400
            ));
        }

        long cooldown = verificationCodeService.getResendCooldown(email);
        if (cooldown > 0) {
            return ResponseEntity.status(429).body(Map.of(
                "success", false,
                "error", "COOLDOWN",
                "message", "Please wait before requesting a new code",
                "retryAfter", cooldown
            ));
        }

        Map<String, Object> metadata = new HashMap<>();
        if (purpose == org.dddml.uniauth.entity.EmailVerificationCode.VerificationPurpose.REGISTRATION) {
            if (request.containsKey("password")) {
                metadata.put("password", passwordEncoder.encode(request.get("password")));
            }
            if (request.containsKey("displayName")) {
                metadata.put("displayName", request.get("displayName"));
            }
        }

        verificationCodeService.sendVerificationCode(email, purpose, metadata);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Verification code sent successfully",
            "expiresIn", 600,
            "resendAfter", 60
        ));
    }

    @PostMapping("/verify-email")
    @Transactional
    public ResponseEntity<?> verifyEmail(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String code = request.get("verificationCode");

        if (email == null || code == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "INVALID_REQUEST",
                "message", "Email and verification code are required"
            ));
        }

        var result = verificationCodeService.verifyCode(
            email,
            code,
            org.dddml.uniauth.entity.EmailVerificationCode.VerificationPurpose.REGISTRATION
        );

        if (!result.isSuccess()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "INVALID_CODE",
                "message", result.getError(),
                "remainingAttempts", result.getRemainingAttempts()
            ));
        }

        Optional<UserEntity> existingUser = userRepository.findByEmail(email);

        if (existingUser.isPresent()) {
            UserEntity user = existingUser.get();
            bindEmailLoginMethod(user, email);
            verificationCodeService.markAsUsed(email, org.dddml.uniauth.entity.EmailVerificationCode.VerificationPurpose.REGISTRATION);
            return createLoginResponse(user);
        } else {
            UserEntity user = createUserWithEmailLogin(email, result.getMetadata());
            verificationCodeService.markAsUsed(email, org.dddml.uniauth.entity.EmailVerificationCode.VerificationPurpose.REGISTRATION);
            return createLoginResponse(user);
        }
    }

    private void bindEmailLoginMethod(UserEntity user, String email) {
        boolean alreadyBound = user.getLoginMethods().stream()
            .anyMatch(lm -> lm.getAuthProvider() == AuthProvider.LOCAL
                         && email.equalsIgnoreCase(lm.getLocalUsername()));

        if (alreadyBound) {
            log.info("User {} already has email login method bound: {}", user.getId(), email);
            return;
        }

        if (loginMethodRepository.existsByLocalUsername(email)) {
            log.warn("Email {} already registered as username by another user", email);
            return;
        }

        UserLoginMethod emailLoginMethod = UserLoginMethod.builder()
            .id(UUID.randomUUID().toString())
            .user(user)
            .authProvider(AuthProvider.LOCAL)
            .localUsername(email)
            .localPasswordHash(null)
            .isPrimary(false)
            .isVerified(true)
            .build();

        user.addLoginMethod(emailLoginMethod);
        userRepository.save(user);
        log.info("Bound email login method to existing user: userId={}, email={}", user.getId(), email);
    }

    private UserEntity createUserWithEmailLogin(String email, Map<String, Object> metadata) {
        String displayName = (String) metadata.getOrDefault("displayName", extractDisplayNameFromEmail(email));
        String passwordHash = (String) metadata.get("password");

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(email);
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setAuthorities(Set.of("ROLE_USER"));

        UserLoginMethod loginMethod = UserLoginMethod.builder()
            .id(UUID.randomUUID().toString())
            .user(user)
            .authProvider(AuthProvider.LOCAL)
            .localUsername(email)
            .localPasswordHash(passwordHash)
            .isPrimary(true)
            .isVerified(true)
            .build();

        user.addLoginMethod(loginMethod);
        userRepository.save(user);
        log.info("Created new user with email login: userId={}, email={}", user.getId(), email);
        return user;
    }

    private String extractDisplayNameFromEmail(String email) {
        return email.split("@")[0];
    }

    private ResponseEntity<Map<String, Object>> createLoginResponse(UserEntity user) {
        String accessToken = jwtTokenService.generateAccessToken(
            user.getUsername(),
            user.getEmail(),
            user.getId(),
            user.getAuthorities()
        );

        String refreshToken = jwtTokenService.generateRefreshToken(
            user.getUsername(),
            user.getId()
        );

        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("username", user.getUsername());
        userInfo.put("email", user.getEmail());
        userInfo.put("displayName", user.getDisplayName());

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Email verified successfully",
            "user", userInfo,
            "accessToken", accessToken,
            "refreshToken", refreshToken,
            "accessTokenExpiresIn", 3600,
            "refreshTokenExpiresIn", 604800,
            "tokenType", "Bearer"
        ));
    }
}
