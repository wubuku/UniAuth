package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.dddml.uniauth.entity.EmailVerificationCode;
import org.dddml.uniauth.repository.EmailVerificationCodeRepository;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.service.EmailVerificationCodeService;
import org.dddml.uniauth.service.UserService;
import org.dddml.uniauth.service.email.EmailSendResult;
import org.dddml.uniauth.service.email.EmailService;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "app.email.verification.max-retry-attempts=3",
    "app.email.verification.expiry-minutes=2",
    "app.email.verification.resend-cooldown-seconds=7"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailAuthenticationIntegrationTest extends PostgreSqlIntegrationTest {

    private static final int MAX_RETRY_ATTEMPTS = 3;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmailVerificationCodeRepository verificationCodeRepository;

    @Autowired
    private UserLoginMethodRepository loginMethodRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    @SpyBean
    private EmailVerificationCodeService verificationCodeService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private EmailService emailService;

    @BeforeEach
    void configureEmailBoundary() {
        reset(emailService);
        when(emailService.isAvailable()).thenReturn(true);
        when(emailService.sendTemplateEmail(
                anyString(),
                anyString(),
                anyString(),
                any(),
                anyString()
        )).thenReturn(EmailSendResult.QUEUED);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void emailRegistrationAndPasswordResetUseThePersistedCodeEndToEnd()
            throws Exception {
        String email = "email-flow@example.invalid";
        String initialPassword = "initial-password";
        String newPassword = "updated-password";

        mockMvc.perform(post("/api/auth/send-verification-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "purpose", "REGISTRATION",
                                "password", initialPassword,
                                "displayName", "Email Flow"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.expiresIn").value(120))
                .andExpect(jsonPath("$.resendAfter").value(7));

        EmailVerificationCode registrationCode = verificationCodeRepository
                .findFirstByEmailAndPurposeAndIsUsedFalseOrderByCreatedAtDesc(
                        email,
                        EmailVerificationCode.VerificationPurpose.REGISTRATION
                )
                .orElseThrow();

        ArgumentCaptor<Map<String, Object>> registrationVariables =
                ArgumentCaptor.forClass((Class) Map.class);
        verify(emailService).sendTemplateEmail(
                eq(email),
                eq("Verify your email"),
                eq("email/email-verify"),
                registrationVariables.capture(),
                eq("VERIFICATION")
        );
        assertThat(registrationVariables.getValue().get("code"))
                .isEqualTo(registrationCode.getVerificationCode());

        mockMvc.perform(post("/api/auth/check-verification-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "verificationCode", registrationCode.getVerificationCode(),
                                "purpose", "REGISTRATION"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.status").value("VALID"));

        MvcResult verifyResult = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "verificationCode", registrationCode.getVerificationCode()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.user.id").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value(email))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();
        assertTokenCookies(verifyResult);

        assertThat(verificationCodeRepository.findById(registrationCode.getId()))
                .get()
                .extracting(EmailVerificationCode::getIsUsed)
                .isEqualTo(true);
        assertThat(loginMethodRepository.findByLocalUsername(email))
                .get()
                .satisfies(method -> assertThat(passwordEncoder.matches(
                        initialPassword,
                        method.getLocalPasswordHash()
                )).isTrue());

        mockMvc.perform(post("/api/auth/login")
                        .param("username", email)
                        .param("password", initialPassword))
                .andExpect(status().isOk());

        clearInvocations(emailService);
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.expiresIn").value(120))
                .andExpect(jsonPath("$.resendAfter").value(7));

        EmailVerificationCode resetCode = verificationCodeRepository
                .findFirstByEmailAndPurposeAndIsUsedFalseOrderByCreatedAtDesc(
                        email,
                        EmailVerificationCode.VerificationPurpose.PASSWORD_RESET
                )
                .orElseThrow();

        ArgumentCaptor<Map<String, Object>> resetVariables =
                ArgumentCaptor.forClass((Class) Map.class);
        verify(emailService).sendTemplateEmail(
                eq(email),
                eq("重置您的密码"),
                eq("email/password-reset"),
                resetVariables.capture(),
                eq("PASSWORD_RESET")
        );
        assertThat(resetVariables.getValue().get("verificationCode"))
                .isEqualTo(resetCode.getVerificationCode());

        mockMvc.perform(post("/api/auth/verify-reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "verificationCode", resetCode.getVerificationCode(),
                                "newPassword", newPassword
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/auth/login")
                        .param("username", email)
                        .param("password", initialPassword))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login")
                        .param("username", email)
                        .param("password", newPassword))
                .andExpect(status().isOk());
    }

    @Test
    void invalidCodeConsumesTheRetryBudgetAndDeletesTheChallenge() throws Exception {
        String email = uniqueEmail("retry-budget");
        sendRegistrationCode(email);

        EmailVerificationCode code = latestCode(
                email,
                EmailVerificationCode.VerificationPurpose.REGISTRATION
        );
        String invalidCode = "000000".equals(code.getVerificationCode())
                ? "111111"
                : "000000";

        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            int expectedRemaining = Math.max(0, MAX_RETRY_ATTEMPTS - attempt);
            mockMvc.perform(post("/api/auth/verify-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "email", email,
                                    "verificationCode", invalidCode
                            ))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.remainingAttempts").value(expectedRemaining));
        }

        assertThat(verificationCodeRepository.findById(code.getId())).isEmpty();
    }

    @Test
    void expiredChallengeIsRejectedAndRemoved() throws Exception {
        String email = uniqueEmail("expired");
        sendRegistrationCode(email);
        EmailVerificationCode code = latestCode(
                email,
                EmailVerificationCode.VerificationPurpose.REGISTRATION
        );
        code.setExpiresAt(Instant.now().minusSeconds(1));
        verificationCodeRepository.saveAndFlush(code);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "verificationCode", code.getVerificationCode()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Verification code expired"));

        assertThat(verificationCodeRepository.findById(code.getId())).isEmpty();
    }

    @Test
    void resendCooldownPreventsASecondPendingChallenge() throws Exception {
        String email = uniqueEmail("cooldown");
        sendRegistrationCode(email);

        mockMvc.perform(post("/api/auth/send-verification-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "purpose", "REGISTRATION",
                                "password", "integration-password"
                        ))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("COOLDOWN"));

        assertThat(verificationCodeRepository.findByEmail(email)).hasSize(1);
    }

    @Test
    void emailStatusAndReadOnlyCodeCheckTrackThePersistedChallenge() throws Exception {
        String email = uniqueEmail("status");
        sendRegistrationCode(email);
        EmailVerificationCode code = latestCode(
                email,
                EmailVerificationCode.VerificationPurpose.REGISTRATION
        );
        String invalidCode = "000000".equals(code.getVerificationCode())
                ? "111111"
                : "000000";

        mockMvc.perform(get("/api/auth/email/status/{email}", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.hasPendingVerification").value(true));

        mockMvc.perform(post("/api/auth/check-verification-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "verificationCode", invalidCode,
                                "purpose", "REGISTRATION"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.remainingAttempts").value(MAX_RETRY_ATTEMPTS));

        assertThat(verificationCodeRepository.findById(code.getId()))
                .get()
                .extracting(EmailVerificationCode::getRetryCount)
                .isEqualTo(0);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "verificationCode", code.getVerificationCode()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/auth/email/status/{email}", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPendingVerification").value(false));
    }

    @Test
    void emailBoundaryExceptionDoesNotPersistAUsableChallenge() throws Exception {
        String email = uniqueEmail("delivery-exception");
        when(emailService.sendTemplateEmail(
                anyString(),
                anyString(),
                anyString(),
                any(),
                anyString()
        )).thenThrow(new IllegalStateException("simulated email boundary failure"));

        mockMvc.perform(post("/api/auth/send-verification-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "purpose", "REGISTRATION",
                                "password", "integration-password"
                        ))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("EMAIL_SERVICE_UNAVAILABLE"));

        assertThat(verificationCodeRepository.findByEmail(email)).isEmpty();
    }

    @Test
    void passwordResetDeliveryFailureDoesNotPersistAChallenge() throws Exception {
        String email = uniqueEmail("password-reset-delivery");
        sendRegistrationCode(email);
        EmailVerificationCode registrationCode = latestCode(
            email,
            EmailVerificationCode.VerificationPurpose.REGISTRATION
        );
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "verificationCode", registrationCode.getVerificationCode()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        when(emailService.sendTemplateEmail(
                anyString(),
                anyString(),
                anyString(),
                any(),
                anyString()
        )).thenReturn(EmailSendResult.FAILED);

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("发送失败，请稍后重试"));

        assertThat(verificationCodeRepository
                .findFirstByEmailAndPurposeAndIsUsedFalseOrderByCreatedAtDesc(
                    email,
                    EmailVerificationCode.VerificationPurpose.PASSWORD_RESET
                ))
            .isEmpty();
    }

    @Test
    void legacyRegisterWithCodeRollsBackChallengeWhenUserCreationFails() throws Exception {
        String email = uniqueEmail("registration-rollback");
        userService.getOrCreateOAuthUser(
            "GITHUB",
            "registration-rollback-" + UUID.randomUUID(),
            email,
            "Existing OAuth User",
            null
        );
        sendRegistrationCode(email);
        EmailVerificationCode code = latestCode(
            email,
            EmailVerificationCode.VerificationPurpose.REGISTRATION
        );

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", email,
                                "email", email,
                                "password", "integration-password",
                                "displayName", "Conflicting Registration",
                                "verificationCode", code.getVerificationCode()
                        ))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));

        assertThat(verificationCodeRepository.findById(code.getId()))
            .get()
            .satisfies(persisted -> {
                assertThat(persisted.getIsUsed()).isFalse();
                assertThat(persisted.getRetryCount()).isZero();
            });
        assertThat(loginMethodRepository.findByLocalUsername(email)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("rejectedDeliveryResults")
    void rejectedEmailDeliveryDoesNotPersistAUsableChallenge(
            EmailSendResult result,
            int expectedStatus) throws Exception {
        String email = uniqueEmail("delivery-" + result.name().toLowerCase());
        when(emailService.sendTemplateEmail(
                anyString(),
                anyString(),
                anyString(),
                any(),
                anyString()
        )).thenReturn(result);

        mockMvc.perform(post("/api/auth/send-verification-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "purpose", "REGISTRATION",
                                "password", "integration-password"
                        ))))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.success").value(false));

        assertThat(verificationCodeRepository.findByEmail(email)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOGIN", "PASSWORD_RESET", "UNKNOWN"})
    void registrationSendEndpointRejectsUnsupportedPurpose(String purpose) throws Exception {
        String email = uniqueEmail("unsupported-purpose");

        mockMvc.perform(post("/api/auth/send-verification-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "purpose", purpose
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_PURPOSE"));

        verify(emailService, never()).sendTemplateEmail(
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyString()
        );
        assertThat(verificationCodeRepository.findByEmail(email)).isEmpty();
    }

    @Test
    void concurrentVerificationConsumesTheChallengeExactlyOnce() throws Exception {
        String email = uniqueEmail("concurrent-success");
        sendRegistrationCode(email);
        EmailVerificationCode code = latestCode(
            email,
            EmailVerificationCode.VerificationPurpose.REGISTRATION
        );
        String payload = objectMapper.writeValueAsString(Map.of(
            "email", email,
            "verificationCode", code.getVerificationCode()
        ));

        List<Integer> statuses = runConcurrently(
            () -> verifyEmailStatus(payload),
            () -> verifyEmailStatus(payload)
        );

        assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        assertThat(userRepository.findByEmail(email)).isPresent();
        assertThat(loginMethodRepository.findByLocalUsername(email)).isPresent();
        assertThat(verificationCodeRepository.findById(code.getId()))
            .get()
            .extracting(EmailVerificationCode::getIsUsed)
            .isEqualTo(true);
    }

    @Test
    void verifyEmailDoesNotConsumeAChallengeCreatedAfterAtomicVerification() throws Exception {
        String email = uniqueEmail("verify-email-replacement");
        sendRegistrationCode(email);
        EmailVerificationCode original = latestCode(
            email,
            EmailVerificationCode.VerificationPurpose.REGISTRATION
        );
        AtomicReference<String> replacementId = insertReplacementAfterSuccessfulVerification(
            email,
            original
        );

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "verificationCode", original.getVerificationCode()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertConsumedOriginalAndPendingReplacement(original.getId(), replacementId);
    }

    @Test
    void legacyRegisterDoesNotConsumeAChallengeCreatedAfterAtomicVerification() throws Exception {
        String email = uniqueEmail("legacy-register-replacement");
        sendRegistrationCode(email);
        EmailVerificationCode original = latestCode(
            email,
            EmailVerificationCode.VerificationPurpose.REGISTRATION
        );
        AtomicReference<String> replacementId = insertReplacementAfterSuccessfulVerification(
            email,
            original
        );

        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", email,
                                "email", email,
                                "password", "integration-password",
                                "displayName", "Legacy Registration",
                                "verificationCode", original.getVerificationCode()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andReturn();
        assertTokenCookies(registerResult);

        assertConsumedOriginalAndPendingReplacement(original.getId(), replacementId);
    }

    @Test
    void concurrentInvalidAttemptsDoNotLoseRetryCount() throws Exception {
        String email = uniqueEmail("concurrent-invalid");
        sendRegistrationCode(email);
        EmailVerificationCode code = latestCode(
            email,
            EmailVerificationCode.VerificationPurpose.REGISTRATION
        );
        String invalidCode = "000000".equals(code.getVerificationCode())
            ? "111111"
            : "000000";
        String payload = objectMapper.writeValueAsString(Map.of(
            "email", email,
            "verificationCode", invalidCode
        ));

        List<Integer> remainingAttempts = runConcurrently(
            () -> verifyEmailRemainingAttempts(payload),
            () -> verifyEmailRemainingAttempts(payload)
        );

        assertThat(remainingAttempts).containsExactlyInAnyOrder(1, 2);
        assertThat(verificationCodeRepository.findById(code.getId()))
            .get()
            .extracting(EmailVerificationCode::getRetryCount)
            .isEqualTo(2);
    }

    private void sendRegistrationCode(String email) throws Exception {
        mockMvc.perform(post("/api/auth/send-verification-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "purpose", "REGISTRATION",
                                "password", "integration-password",
                                "displayName", "Email Boundary User"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private EmailVerificationCode latestCode(
            String email,
            EmailVerificationCode.VerificationPurpose purpose) {
        return verificationCodeRepository
                .findFirstByEmailAndPurposeAndIsUsedFalseOrderByCreatedAtDesc(email, purpose)
                .orElseThrow();
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.invalid";
    }

    private void assertTokenCookies(MvcResult result) {
        assertCookie(result, "accessToken", 3600);
        assertCookie(result, "refreshToken", 604800);
    }

    private void assertCookie(MvcResult result, String name, int maxAge) {
        Cookie cookie = result.getResponse().getCookie(name);
        assertThat(cookie).as(name).isNotNull();
        assertThat(cookie.isHttpOnly()).as(name + " HttpOnly").isTrue();
        assertThat(cookie.getSecure()).as(name + " Secure").isFalse();
        assertThat(cookie.getPath()).as(name + " Path").isEqualTo("/");
        assertThat(cookie.getMaxAge()).as(name + " Max-Age").isEqualTo(maxAge);
        assertThat(cookie.getAttribute("SameSite"))
                .as(name + " SameSite")
                .isEqualTo("Lax");
    }

    private AtomicReference<String> insertReplacementAfterSuccessfulVerification(
            String email,
            EmailVerificationCode original) {
        AtomicReference<String> replacementId = new AtomicReference<>();
        doAnswer(invocation -> {
            EmailVerificationCodeService.VerificationResult result =
                (EmailVerificationCodeService.VerificationResult) invocation.callRealMethod();
            if (result.isSuccess()) {
                String replacementCode = "999999".equals(original.getVerificationCode())
                    ? "888888"
                    : "999999";
                EmailVerificationCode replacement = EmailVerificationCode.builder()
                    .id(UUID.randomUUID().toString())
                    .email(email)
                    .verificationCode(replacementCode)
                    .purpose(EmailVerificationCode.VerificationPurpose.REGISTRATION)
                    .expiresAt(Instant.now().plusSeconds(120))
                    .retryCount(0)
                    .isUsed(false)
                    .build();
                verificationCodeRepository.saveAndFlush(replacement);
                replacementId.set(replacement.getId());
            }
            return result;
        }).when(verificationCodeService).verifyCode(
            email,
            original.getVerificationCode(),
            EmailVerificationCode.VerificationPurpose.REGISTRATION
        );
        return replacementId;
    }

    private void assertConsumedOriginalAndPendingReplacement(
            String originalId,
            AtomicReference<String> replacementId) {
        assertThat(replacementId.get()).isNotNull();
        assertThat(verificationCodeRepository.findById(originalId))
            .get()
            .extracting(EmailVerificationCode::getIsUsed)
            .isEqualTo(true);
        assertThat(verificationCodeRepository.findById(replacementId.get()))
            .get()
            .satisfies(replacement -> {
                assertThat(replacement.getIsUsed()).isFalse();
                assertThat(replacement.getRetryCount()).isZero();
            });
    }

    private int verifyEmailStatus(String payload) throws Exception {
        return mockMvc.perform(post("/api/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andReturn()
            .getResponse()
            .getStatus();
    }

    private int verifyEmailRemainingAttempts(String payload) throws Exception {
        var response = mockMvc.perform(post("/api/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andReturn()
            .getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        return objectMapper.readTree(response.getContentAsString())
            .path("remainingAttempts")
            .asInt();
    }

    @SafeVarargs
    private <T> List<T> runConcurrently(Callable<T>... tasks) throws Exception {
        var executor = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch ready = new CountDownLatch(tasks.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test start timed out");
                    }
                    return task.call();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> rejectedDeliveryResults() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(EmailSendResult.FAILED, 503),
            org.junit.jupiter.params.provider.Arguments.of(EmailSendResult.RATE_LIMITED, 429),
            org.junit.jupiter.params.provider.Arguments.of(EmailSendResult.INVALID_EMAIL, 400)
        );
    }
}
