package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.dddml.uniauth.entity.EmailDeliveryOutbox;
import org.dddml.uniauth.entity.EmailVerificationCode;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.EmailDeliveryOutboxRepository;
import org.dddml.uniauth.repository.EmailVerificationCodeRepository;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.service.EmailDeliveryOutboxProcessor;
import org.dddml.uniauth.service.EmailVerificationCodeProtector;
import org.dddml.uniauth.service.EmailVerificationCodeService;
import org.dddml.uniauth.service.UserService;
import org.dddml.uniauth.service.email.EmailDeliveryClientException;
import org.dddml.uniauth.service.email.EmailDeliveryReceipt;
import org.dddml.uniauth.service.email.EmailService;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "app.email.verification.max-retry-attempts=3",
    "app.email.verification.expiry-minutes=2",
    "app.email.verification.resend-cooldown-seconds=7",
    "app.email.verification.hmac-key=test-only-email-authentication-key",
    "app.email.verification.hmac-key-id=test-email-key-1",
    "app.email.delivery.max-attempts=1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailAuthenticationIntegrationTest extends PostgreSqlIntegrationTest {

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final String REGISTRATION_EMAIL_TYPE = "VERIFICATION";
    private static final String PASSWORD_RESET_EMAIL_TYPE = "PASSWORD_RESET";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmailVerificationCodeRepository verificationCodeRepository;

    @Autowired
    private EmailDeliveryOutboxRepository outboxRepository;

    @Autowired
    private EmailDeliveryOutboxProcessor outboxProcessor;

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
    private EmailVerificationCodeProtector codeProtector;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private EmailService emailService;

    private final Map<String, String> deliveredCodes = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void configureEmailBoundary() {
        reset(emailService);
        deliveredCodes.clear();
        doReturn(true).when(emailService).isAvailable();
        doReturn(Optional.empty()).when(emailService)
                .findDeliveryByIdempotencyKey(anyString());
        doAnswer(invocation -> {
            String email = invocation.getArgument(0);
            Map<String, Object> variables = invocation.getArgument(3);
            String emailType = invocation.getArgument(4);
            deliveredCodes.put(
                    deliveryKey(email, emailType),
                    String.valueOf(variables.get("verificationCode"))
            );
            return new EmailDeliveryReceipt(
                    UUID.randomUUID().toString(),
                    EmailDeliveryReceipt.DeliveryState.PENDING
            );
        }).when(emailService).enqueueTemplateEmail(
                anyString(),
                anyString(),
                anyString(),
                any(),
                anyString(),
                anyString()
        );
    }

    @Test
    void emailRegistrationAndPasswordResetUseOpaqueChallengesEndToEnd()
            throws Exception {
        String email = uniqueEmail("email-flow");
        String initialPassword = "initial-password";
        String newPassword = "updated-password";

        DeliveredChallenge registration = sendRegistrationChallenge(email);
        EmailVerificationCode registrationRow =
                verificationCodeRepository.findById(registration.handle())
                        .orElseThrow();
        assertThat(registrationRow.getCodeDigest())
                .isNotBlank()
                .doesNotContain(registration.code());

        MvcResult verifyResult = completeRegistration(
                registration,
                email,
                email,
                initialPassword,
                "Email Flow"
        );
        assertTokenCookies(verifyResult);

        assertThat(verificationCodeRepository.findById(registration.handle()))
                .get()
                .extracting(EmailVerificationCode::getUsageStatus)
                .isEqualTo(EmailVerificationCode.UsageStatus.USED);
        assertThat(loginMethodRepository.findByLocalUsername(email))
                .get()
                .satisfies(method -> assertThat(passwordEncoder.matches(
                        initialPassword,
                        method.getLocalPasswordHash()
                )).isTrue());

        login(email, initialPassword).andExpect(status().isOk());

        clearInvocations(emailService);
        DeliveredChallenge reset = requestPasswordReset(email);
        mockMvc.perform(post("/api/auth/verify-reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "challengeHandle", reset.handle(),
                                "verificationCode", reset.code(),
                                "newPassword", newPassword
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        login(email, initialPassword).andExpect(status().isUnauthorized());
        login(email, newPassword).andExpect(status().isOk());
    }

    @Test
    void invalidCodeConsumesTheRetryBudgetAndInvalidatesTheChallenge()
            throws Exception {
        String email = uniqueEmail("retry-budget");
        DeliveredChallenge challenge = sendRegistrationChallenge(email);
        String invalidCode = differentCode(challenge.code());

        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            completeRegistrationRequest(
                    challenge.handle(),
                    invalidCode,
                    email,
                    email,
                    "integration-password",
                    "Retry Budget"
            ).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail")
                            .value("Invalid or expired verification challenge"));

            EmailVerificationCode persisted =
                    verificationCodeRepository.findById(challenge.handle())
                            .orElseThrow();
            assertThat(persisted.getRetryCount()).isEqualTo(attempt);
        }

        assertThat(verificationCodeRepository.findById(challenge.handle()))
                .get()
                .extracting(EmailVerificationCode::getUsageStatus)
                .isEqualTo(EmailVerificationCode.UsageStatus.INVALIDATED);
    }

    @Test
    void expiredChallengeIsRejectedAndMarkedExpired() throws Exception {
        String email = uniqueEmail("expired");
        DeliveredChallenge challenge = sendRegistrationChallenge(email);
        EmailVerificationCode row = verificationCodeRepository
                .findById(challenge.handle())
                .orElseThrow();
        row.setExpiresAt(Instant.now().minusSeconds(1));
        verificationCodeRepository.saveAndFlush(row);

        completeRegistrationRequest(
                challenge.handle(),
                challenge.code(),
                email,
                email,
                "integration-password",
                "Expired Challenge"
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("Invalid or expired verification challenge"));

        assertThat(verificationCodeRepository.findById(challenge.handle()))
                .get()
                .extracting(EmailVerificationCode::getUsageStatus)
                .isEqualTo(EmailVerificationCode.UsageStatus.EXPIRED);
    }

    @Test
    void resendCooldownPreventsASecondActiveChallenge() throws Exception {
        String email = uniqueEmail("cooldown");
        sendRegistrationChallenge(email);

        mockMvc.perform(post("/api/auth/send-verification-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "purpose", "REGISTRATION"
                        ))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("COOLDOWN"));

        assertThat(verificationCodeRepository.findByEmail(email)).hasSize(1);
    }

    @Test
    void publicStatusAndReadOnlyCodeOraclesStayClosed() throws Exception {
        String email = uniqueEmail("oracle");
        DeliveredChallenge challenge = sendRegistrationChallenge(email);

        mockMvc.perform(get("/api/auth/email/status/{email}", email))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/check-verification-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "challengeHandle", challenge.handle(),
                                "verificationCode", differentCode(challenge.code()),
                                "purpose", "REGISTRATION"
                        ))))
                .andExpect(status().isForbidden());

        assertThat(verificationCodeRepository.findById(challenge.handle()))
                .get()
                .extracting(EmailVerificationCode::getRetryCount)
                .isEqualTo(0);
    }

    @Test
    void emailBoundaryExceptionDoesNotPersistAUsableChallenge() throws Exception {
        String email = uniqueEmail("delivery-exception");
        doThrow(new IllegalStateException("simulated email boundary failure"))
                .when(emailService)
                .enqueueTemplateEmail(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString(),
                        anyString()
                );

        MvcResult result = mockMvc.perform(post("/api/auth/send-verification-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "purpose", "REGISTRATION"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        String handle = responseJson(result).path("challengeHandle").asText();
        processChallenge(handle);
        assertFailedChallenge(handle);
    }

    @Test
    void passwordResetDeliveryFailureDoesNotPersistAChallenge() throws Exception {
        String email = uniqueEmail("password-reset-delivery");
        createLocalUser(email, email, "integration-password");
        doThrow(new EmailDeliveryClientException("DELIVERY_FAILED", false))
                .when(emailService).enqueueTemplateEmail(
                anyString(),
                anyString(),
                anyString(),
                any(),
                anyString(),
                anyString()
        );

        MvcResult result = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andReturn();
        String handle = responseJson(result).path("challengeHandle").asText();
        processChallenge(handle);
        assertFailedChallenge(handle);
    }

    @Test
    void registerWithCodeRollsBackChallengeWhenIdentityAlreadyExists()
            throws Exception {
        String email = uniqueEmail("registration-rollback");
        userService.getOrCreateOAuthUser(
                "GITHUB",
                "registration-rollback-" + UUID.randomUUID(),
                email,
                "Existing OAuth User",
                null
        );
        DeliveredChallenge challenge = sendRegistrationChallenge(email);

        completeRegistrationRequest(
                challenge.handle(),
                challenge.code(),
                email,
                email,
                "integration-password",
                "Conflicting Registration"
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("Registration could not be completed"));

        assertThat(verificationCodeRepository.findById(challenge.handle()))
                .get()
                .satisfies(persisted -> {
                    assertThat(persisted.getUsageStatus())
                            .isEqualTo(EmailVerificationCode.UsageStatus.UNUSED);
                    assertThat(persisted.getRetryCount()).isZero();
                });
        assertThat(loginMethodRepository.findByLocalUsername(email)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("rejectedDeliveryResults")
    void rejectedEmailDeliveryDoesNotPersistAUsableChallenge(
            String errorCode,
            boolean retryable) throws Exception {
        String email = uniqueEmail("delivery-" + errorCode.toLowerCase());
        doThrow(new EmailDeliveryClientException(errorCode, retryable))
                .when(emailService).enqueueTemplateEmail(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString(),
                        anyString()
                );

        MvcResult result = mockMvc.perform(post("/api/auth/send-verification-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "purpose", "REGISTRATION"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        String handle = responseJson(result).path("challengeHandle").asText();
        processChallenge(handle);
        assertFailedChallenge(handle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOGIN", "PASSWORD_RESET", "UNKNOWN"})
    void registrationSendEndpointRejectsUnsupportedPurpose(String purpose)
            throws Exception {
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

        verify(emailService, never()).enqueueTemplateEmail(
                anyString(),
                anyString(),
                anyString(),
                any(),
                anyString(),
                anyString()
        );
        assertThat(verificationCodeRepository.findByEmail(email)).isEmpty();
    }

    @Test
    void concurrentVerificationConsumesTheChallengeExactlyOnce() throws Exception {
        String email = uniqueEmail("concurrent-success");
        DeliveredChallenge challenge = sendRegistrationChallenge(email);
        String payload = registrationPayload(
                challenge.handle(),
                challenge.code(),
                email,
                email,
                "integration-password",
                "Concurrent Success"
        );

        List<Integer> statuses = runConcurrently(
                () -> verifyEmailStatus(payload),
                () -> verifyEmailStatus(payload)
        );

        assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        assertThat(userRepository.findByEmail(email)).isPresent();
        assertThat(loginMethodRepository.findByLocalUsername(email)).isPresent();
        assertThat(verificationCodeRepository.findById(challenge.handle()))
                .get()
                .extracting(EmailVerificationCode::getUsageStatus)
                .isEqualTo(EmailVerificationCode.UsageStatus.USED);
    }

    @Test
    void verifyEmailDoesNotConsumeAChallengeCreatedAfterAtomicVerification()
            throws Exception {
        String email = uniqueEmail("verify-email-replacement");
        DeliveredChallenge original = sendRegistrationChallenge(email);
        AtomicReference<String> replacementId =
                insertReplacementAfterSuccessfulVerification(email, original);

        completeRegistration(
                original,
                email,
                email,
                "integration-password",
                "Replacement Test"
        );

        assertConsumedOriginalAndActiveReplacement(
                original.handle(),
                replacementId
        );
    }

    @Test
    void registerEndpointDoesNotConsumeAReplacementChallenge() throws Exception {
        String email = uniqueEmail("register-replacement");
        DeliveredChallenge original = sendRegistrationChallenge(email);
        AtomicReference<String> replacementId =
                insertReplacementAfterSuccessfulVerification(email, original);

        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationPayload(
                                original.handle(),
                                original.code(),
                                email,
                                email,
                                "integration-password",
                                "Register Replacement"
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andReturn();
        assertTokenCookies(registerResult);

        assertConsumedOriginalAndActiveReplacement(
                original.handle(),
                replacementId
        );
    }

    @Test
    void concurrentInvalidAttemptsDoNotLoseRetryCount() throws Exception {
        String email = uniqueEmail("concurrent-invalid");
        DeliveredChallenge challenge = sendRegistrationChallenge(email);
        String payload = registrationPayload(
                challenge.handle(),
                differentCode(challenge.code()),
                email,
                email,
                "integration-password",
                "Concurrent Invalid"
        );

        List<Integer> statuses = runConcurrently(
                () -> verifyEmailStatus(payload),
                () -> verifyEmailStatus(payload)
        );

        assertThat(statuses).containsOnly(400);
        assertThat(verificationCodeRepository.findById(challenge.handle()))
                .get()
                .extracting(EmailVerificationCode::getRetryCount)
                .isEqualTo(2);
    }

    private DeliveredChallenge sendRegistrationChallenge(String email)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/send-verification-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "purpose", "REGISTRATION"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.challengeHandle").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(120))
                .andExpect(jsonPath("$.resendAfter").value(7))
                .andReturn();
        JsonNode body = responseJson(result);
        processChallenge(body.path("challengeHandle").asText());
        return new DeliveredChallenge(
                body.path("challengeHandle").asText(),
                deliveredCode(email, REGISTRATION_EMAIL_TYPE)
        );
    }

    private DeliveredChallenge requestPasswordReset(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.challengeHandle").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(120))
                .andExpect(jsonPath("$.resendAfter").value(7))
                .andReturn();
        String handle = responseJson(result).path("challengeHandle").asText();
        processChallenge(handle);
        return new DeliveredChallenge(
                handle,
                deliveredCode(email, PASSWORD_RESET_EMAIL_TYPE)
        );
    }

    private MvcResult completeRegistration(
            DeliveredChallenge challenge,
            String email,
            String username,
            String password,
            String displayName) throws Exception {
        return completeRegistrationRequest(
                challenge.handle(),
                challenge.code(),
                email,
                username,
                password,
                displayName
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.user.id").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value(username))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions
            completeRegistrationRequest(
                    String handle,
                    String code,
                    String email,
                    String username,
                    String password,
                    String displayName) throws Exception {
        return mockMvc.perform(post("/api/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationPayload(
                        handle,
                        code,
                        email,
                        username,
                        password,
                        displayName
                )));
    }

    private String registrationPayload(
            String handle,
            String code,
            String email,
            String username,
            String password,
            String displayName) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "challengeHandle", handle,
                "verificationCode", code,
                "email", email,
                "username", username,
                "password", password,
                "displayName", displayName
        ));
    }

    private org.springframework.test.web.servlet.ResultActions login(
            String username,
            String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "username", username,
                        "password", password
                ))));
    }

    private String deliveredCode(String email, String emailType) {
        return java.util.Objects.requireNonNull(
                deliveredCodes.get(deliveryKey(email, emailType))
        );
    }

    private void processChallenge(String challengeId) {
        EmailDeliveryOutbox outbox = outboxRepository
                .findByChallengeId(challengeId)
                .orElseThrow();
        assertThat(outboxProcessor.processOne(outbox.getId())).isTrue();
    }

    private void assertFailedChallenge(String challengeId) {
        assertThat(verificationCodeRepository.findById(challengeId))
                .get()
                .satisfies(challenge -> {
                    assertThat(challenge.getDeliveryStatus())
                            .isEqualTo(EmailVerificationCode.DeliveryStatus.FAILED);
                    assertThat(challenge.getUsageStatus())
                            .isEqualTo(EmailVerificationCode.UsageStatus.INVALIDATED);
                });
        assertThat(outboxRepository.findByChallengeId(challengeId))
                .get()
                .extracting(EmailDeliveryOutbox::getStatus)
                .isEqualTo(EmailDeliveryOutbox.Status.FAILED);
    }

    private String deliveryKey(String email, String emailType) {
        return email + "\n" + emailType;
    }

    private String differentCode(String code) {
        return "000000".equals(code) ? "111111" : "000000";
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.invalid";
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
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
            DeliveredChallenge original) {
        AtomicReference<String> replacementId = new AtomicReference<>();
        doAnswer(invocation -> {
            EmailVerificationCodeService.VerificationResult result =
                    (EmailVerificationCodeService.VerificationResult)
                            invocation.callRealMethod();
            if (result.isSuccess()) {
                String handle = UUID.randomUUID().toString();
                String replacementCode = differentCode(original.code());
                String keyId = codeProtector.currentKeyId();
                EmailVerificationCode replacement =
                        EmailVerificationCode.builder()
                                .id(handle)
                                .email(email)
                                .codeDigest(codeProtector.digest(
                                        handle,
                                        replacementCode,
                                        keyId
                                ))
                                .codeKeyId(keyId)
                                .purpose(EmailVerificationCode.VerificationPurpose
                                        .REGISTRATION)
                                .deliveryStatus(EmailVerificationCode.DeliveryStatus
                                        .ACTIVE)
                                .usageStatus(EmailVerificationCode.UsageStatus.UNUSED)
                                .idempotencyKey("test-replacement:" + handle)
                                .expiresAt(Instant.now().plusSeconds(120))
                                .deliveryDeadline(Instant.now().plusSeconds(120))
                                .acceptedAt(Instant.now())
                                .activatedAt(Instant.now())
                                .retryCount(0)
                                .build();
                verificationCodeRepository.saveAndFlush(replacement);
                replacementId.set(replacement.getId());
            }
            return result;
        }).when(verificationCodeService).verifyCode(
                original.handle(),
                email,
                original.code(),
                EmailVerificationCode.VerificationPurpose.REGISTRATION
        );
        return replacementId;
    }

    private void assertConsumedOriginalAndActiveReplacement(
            String originalId,
            AtomicReference<String> replacementId) {
        assertThat(replacementId.get()).isNotNull();
        assertThat(verificationCodeRepository.findById(originalId))
                .get()
                .extracting(EmailVerificationCode::getUsageStatus)
                .isEqualTo(EmailVerificationCode.UsageStatus.USED);
        assertThat(verificationCodeRepository.findById(replacementId.get()))
                .get()
                .satisfies(replacement -> {
                    assertThat(replacement.getUsageStatus())
                            .isEqualTo(EmailVerificationCode.UsageStatus.UNUSED);
                    assertThat(replacement.getDeliveryStatus())
                            .isEqualTo(EmailVerificationCode.DeliveryStatus.ACTIVE);
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

    private UserEntity createLocalUser(
            String username,
            String email,
            String password) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setEmail(email);
        user.setEmailIdentityType(UserEntity.EmailIdentityType.VERIFIED_CONTACT);
        user.setDisplayName("Email Authentication User");
        user.setEmailVerified(true);
        user.setEnabled(true);
        user.setAuthorities(new HashSet<>(Set.of("ROLE_USER")));

        UserLoginMethod method = UserLoginMethod.builder()
                .id(UUID.randomUUID().toString())
                .user(user)
                .authProvider(UserLoginMethod.AuthProvider.LOCAL)
                .localUsername(username)
                .localPasswordHash(passwordEncoder.encode(password))
                .isPrimary(true)
                .isVerified(true)
                .build();
        user.addLoginMethod(method);
        return userRepository.saveAndFlush(user);
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
                        throw new IllegalStateException(
                                "Concurrent test start timed out"
                        );
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

    private static Stream<org.junit.jupiter.params.provider.Arguments>
            rejectedDeliveryResults() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "DELIVERY_FAILED",
                        false
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "RATE_LIMITED",
                        true
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "INVALID_EMAIL",
                        false
                )
        );
    }

    private record DeliveredChallenge(String handle, String code) {
    }
}
