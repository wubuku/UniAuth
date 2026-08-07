package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dddml.uniauth.entity.EmailVerificationCode;
import org.dddml.uniauth.repository.EmailVerificationCodeRepository;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.service.email.EmailSendResult;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailAuthenticationIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmailVerificationCodeRepository verificationCodeRepository;

    @Autowired
    private UserLoginMethodRepository loginMethodRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private EmailService emailService;

    @BeforeEach
    void configureEmailBoundary() {
        when(emailService.isAvailable()).thenReturn(true);
        when(emailService.sendTemplateEmail(
                anyString(),
                anyString(),
                anyString(),
                any(),
                anyString()
        )).thenReturn(EmailSendResult.SUCCESS);
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
                .andExpect(jsonPath("$.success").value(true));

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

        mockMvc.perform(post("/api/auth/verify-email")
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
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

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
                .andExpect(jsonPath("$.success").value(true));

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
}
