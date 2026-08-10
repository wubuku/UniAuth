package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dddml.uniauth.entity.EmailDeliveryOutbox;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.EmailDeliveryOutboxRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.service.EmailDeliveryOutboxProcessor;
import org.dddml.uniauth.service.email.EmailDeliveryReceipt;
import org.dddml.uniauth.service.email.EmailService;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "app.email.verification.hmac-key=test-only-f1-verification-key-32-bytes",
    "app.email.verification.hmac-key-id=test-key-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class F1IdentityBoundaryIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmailDeliveryOutboxRepository outboxRepository;

    @Autowired
    private EmailDeliveryOutboxProcessor outboxProcessor;

    @MockBean
    private EmailService emailService;

    @BeforeEach
    void configureEmailService() {
        reset(emailService);
        when(emailService.isAvailable()).thenReturn(true);
        when(emailService.findDeliveryByIdempotencyKey(anyString()))
                .thenReturn(Optional.empty());
        when(emailService.enqueueTemplateEmail(
                anyString(),
                anyString(),
                anyString(),
                any(),
                anyString(),
                anyString()
        )).thenReturn(new EmailDeliveryReceipt(
                "f1-delivery-1",
                EmailDeliveryReceipt.DeliveryState.PENDING
        ));
    }

    @Test
    void loginAcceptsOnlyCanonicalJsonCredentials() throws Exception {
        String password = "integration-password";
        createLocalUser("f1-json-user", "f1-json-user@example.invalid", password);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "f1-json-user",
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));

        mockMvc.perform(post("/api/auth/login")
                        .param("username", "f1-json-user")
                        .param("password", password))
                .andExpect(status().isUnsupportedMediaType());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "f1-json-user")
                        .param("password", password))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void registrationChallengeIsCanonicalOpaqueAndContainsNoReusableCredential()
            throws Exception {
        String submittedEmail = "  F1.Challenge@Example.Invalid  ";
        ArgumentCaptor<Map<String, Object>> variables =
                ArgumentCaptor.forClass((Class) Map.class);

        MvcResult sendResult = mockMvc.perform(post("/api/auth/send-verification-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", submittedEmail,
                                "purpose", "REGISTRATION",
                                "password", "integration-password",
                                "displayName", "F1 Challenge"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.challengeHandle").isNotEmpty())
                .andReturn();

        JsonNode response = objectMapper.readTree(
                sendResult.getResponse().getContentAsByteArray()
        );
        String handle = response.path("challengeHandle").asText();
        EmailDeliveryOutbox outbox = outboxRepository
                .findByChallengeId(handle)
                .orElseThrow();
        assertThat(outboxProcessor.processOne(outbox.getId())).isTrue();

        verify(emailService).enqueueTemplateEmail(
                org.mockito.ArgumentMatchers.eq("f1.challenge@example.invalid"),
                anyString(),
                anyString(),
                variables.capture(),
                anyString(),
                anyString()
        );
        String deliveredCode = variables.getValue().get("verificationCode").toString();

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT email,
                       code_digest,
                       code_key_id
                FROM email_verification_codes
                WHERE id = ?
                """, handle);
        assertThat(row.get("email")).isEqualTo("f1.challenge@example.invalid");
        assertThat(row.get("code_digest")).isNotNull();
        assertThat(row.get("code_digest").toString()).doesNotContain(deliveredCode);
        assertThat(row.get("code_key_id")).isEqualTo("test-key-1");

        Integer removedCredentialColumnCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'email_verification_codes'
                  AND column_name IN ('verification_code', 'metadata')
                """, Integer.class);
        assertThat(removedCredentialColumnCount).isZero();
    }

    @Test
    void publicEmailAndReadOnlyCodeOraclesAreClosed() throws Exception {
        mockMvc.perform(get(
                        "/api/auth/email/status/{email}",
                        "f1-oracle@example.invalid"
                ))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/auth/check-verification-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "f1-oracle@example.invalid",
                                  "verificationCode": "000000",
                                  "purpose": "REGISTRATION"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void forgotPasswordDoesNotRevealWhetherTheLocalAccountExists() throws Exception {
        String existingEmail = "f1-reset-existing@example.invalid";
        createLocalUser(existingEmail, existingEmail, "integration-password");

        MvcResult existingResult = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", existingEmail)
                        )))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult missingResult = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email",
                                "f1-reset-missing@example.invalid"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode existingBody = objectMapper.readTree(
                existingResult.getResponse().getContentAsByteArray()
        );
        JsonNode missingBody = objectMapper.readTree(
                missingResult.getResponse().getContentAsByteArray()
        );
        assertThat(missingBody.path("success"))
                .isEqualTo(existingBody.path("success"));
        assertThat(missingBody.path("message"))
                .isEqualTo(existingBody.path("message"));
        assertThat(missingBody.path("expiresIn"))
                .isEqualTo(existingBody.path("expiresIn"));
        assertThat(missingBody.path("resendAfter"))
                .isEqualTo(existingBody.path("resendAfter"));
        assertThat(existingBody.path("challengeHandle").asText())
                .matches("[0-9a-f-]{36}");
        assertThat(missingBody.path("challengeHandle").asText())
                .matches("[0-9a-f-]{36}")
                .isNotEqualTo(existingBody.path("challengeHandle").asText());
    }

    private void createLocalUser(String username, String email, String password) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setEmail(email);
        user.setDisplayName("F1 Integration User");
        user.setEmailVerified(true);
        user.setEnabled(true);
        user.setAuthorities(Set.of("ROLE_USER"));

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
        userRepository.saveAndFlush(user);
    }
}
