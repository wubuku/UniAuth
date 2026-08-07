package org.dddml.email.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dddml.email.entity.EmailQueue;
import org.dddml.email.entity.EmailLog;
import org.dddml.email.repository.EmailLogRepository;
import org.dddml.email.service.EmailQueueService;
import org.dddml.email.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

class EmailControllerTest {

    private MockMvc mockMvc;

    private ObjectMapper objectMapper = new ObjectMapper();

    private EmailService emailService;

    private EmailQueueService emailQueueService;

    private EmailLogRepository emailLogRepository;

    private EmailController emailController;

    private EmailQueue testQueue;
    private EmailLog testLog;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        emailQueueService = mock(EmailQueueService.class);
        emailLogRepository = mock(EmailLogRepository.class);

        emailController = new EmailController();
        setField(emailController, "emailService", emailService);
        setField(emailController, "emailQueueService", emailQueueService);
        setField(emailController, "emailLogRepository", emailLogRepository);

        mockMvc = MockMvcBuilders.standaloneSetup(emailController).build();

        testQueue = EmailQueue.builder()
                .id(1L)
                .recipient("test@example.com")
                .subject("Test Subject")
                .htmlContent("<p>Test Content</p>")
                .emailType("TEST")
                .priority(5)
                .retryCount(0)
                .maxRetries(3)
                .build();

        testLog = EmailLog.builder()
                .id(1L)
                .queueId(1L)
                .recipient("test@example.com")
                .subject("Test Subject")
                .status("SUCCESS")
                .emailType("TEST")
                .mailProvider("Gmail")
                .sendMethod("EVENT")
                .durationMs(100L)
                .sentTime(LocalDateTime.now())
                .build();
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testSendSimpleEmail_Success() throws Exception {
        when(emailService.sendSimpleHtmlEmail(anyString(), anyString(), anyString()))
                .thenReturn(testQueue);

        String requestBody = """
            {
                "to": "user@example.com",
                "subject": "Test Email",
                "htmlContent": "<h1>Hello</h1>"
            }
            """;

        mockMvc.perform(post("/api/email/simple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.queueId").value(1))
                .andExpect(jsonPath("$.message").value("Email enqueued, will be sent immediately"));

        verify(emailService).sendSimpleHtmlEmail("user@example.com", "Test Email", "<h1>Hello</h1>");
    }

    @Test
    void testSendSimpleEmail_Failure() throws Exception {
        when(emailService.sendSimpleHtmlEmail(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("SMTP connection failed"));

        String requestBody = """
            {
                "to": "user@example.com",
                "subject": "Test Email",
                "htmlContent": "<h1>Hello</h1>"
            }
            """;

        mockMvc.perform(post("/api/email/simple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("SMTP connection failed"));
    }

    @Test
    void testValidateEmail_Valid() throws Exception {
        when(emailService.isValidEmail("test@example.com")).thenReturn(true);

        String requestBody = """
            {"email": "test@example.com"}
            """;

        mockMvc.perform(post("/api/email/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void testValidateEmail_Invalid() throws Exception {
        when(emailService.isValidEmail("invalid-email")).thenReturn(false);

        String requestBody = """
            {"email": "invalid-email"}
            """;

        mockMvc.perform(post("/api/email/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("invalid-email"))
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void testGetQueueStats() throws Exception {
        EmailQueueService.QueueStats stats = new EmailQueueService.QueueStats(5, 2, 100, 3);

        when(emailQueueService.getStats()).thenReturn(stats);
        when(emailLogRepository.countBySendMethodSince(any())).thenReturn(
                List.of(new Object[]{"EVENT", 95L}, new Object[]{"SCHEDULED", 5L})
        );

        mockMvc.perform(get("/api/email/queue/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(5))
                .andExpect(jsonPath("$.processing").value(2))
                .andExpect(jsonPath("$.completed").value(100))
                .andExpect(jsonPath("$.failed").value(3))
                .andExpect(jsonPath("$.eventDrivenCount").value(95))
                .andExpect(jsonPath("$.scheduledCount").value(5));
    }

    @Test
    void testGetQueueDetail_Found() throws Exception {
        when(emailQueueService.findById(1L)).thenReturn(Optional.of(testQueue));

        mockMvc.perform(get("/api/email/queue/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.recipient").value("test@example.com"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.emailType").value("TEST"));
    }

    @Test
    void testGetQueueDetail_NotFound() throws Exception {
        when(emailQueueService.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/email/queue/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testGetEmailLogs() throws Exception {
        when(emailLogRepository.findAll()).thenReturn(List.of(testLog));

        mockMvc.perform(get("/api/email/logs")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs", hasSize(1)))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.logs[0].recipient").value("test@example.com"))
                .andExpect(jsonPath("$.logs[0].status").value("SUCCESS"));
    }

    @Test
    void testGetEmailLogs_FilterByStatus() throws Exception {
        when(emailLogRepository.findByStatus("SUCCESS")).thenReturn(List.of(testLog));

        mockMvc.perform(get("/api/email/logs")
                        .param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs", hasSize(1)))
                .andExpect(jsonPath("$.logs[0].status").value("SUCCESS"));
    }

    @Test
    void testGetAvailableTemplates() throws Exception {
        mockMvc.perform(get("/api/email/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0]").value("email/welcome"))
                .andExpect(jsonPath("$[1]").value("email/password-reset"))
                .andExpect(jsonPath("$[2]").value("email/email-verify"));
    }

    @Test
    void testHealthCheck() throws Exception {
        mockMvc.perform(get("/api/email/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("email-service"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
