package org.dddml.email.database;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.dddml.email.config.EmailSecurityProperties;
import org.dddml.email.entity.EmailLog;
import org.dddml.email.entity.EmailQueue;
import org.dddml.email.event.EmailEventListener;
import org.dddml.email.event.EmailQueuedEvent;
import org.dddml.email.repository.EmailLogRepository;
import org.dddml.email.repository.EmailQueueRepository;
import org.dddml.email.service.EmailDeliveryService;
import org.dddml.email.service.EmailQueueClaimService;
import org.dddml.email.service.EmailRateLimiter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/postgresql",
        "spring.flyway.table=email_service_flyway_schema_history",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.mail.username=",
        "spring.mail.password=",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "spring.mail.properties.mail.smtp.starttls.required=false",
        "spring.mail.properties.mail.smtp.ssl.enable=false",
        "spring.mail.properties.mail.smtp.ssl.checkserveridentity=true",
        "spring.mail.properties.mail.smtp.connectiontimeout=500",
        "spring.mail.properties.mail.smtp.timeout=500",
        "spring.mail.properties.mail.smtp.writetimeout=10000",
        "app.mail.from-email=no-reply@example.invalid",
        "app.mail.from-name=UniAuth Integration Test",
        "app.mail.retry.max-attempts=4",
        "app.mail.rate-limit.enabled=false",
        "app.mail.recovery.enabled=false",
        "app.security.api-key=integration-secret"
    }
)
@ActiveProfiles("test")
@Testcontainers
class EmailServiceEndToEndIntegrationTest {

    private static final GreenMail SMTP = startSmtp();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("email_service_test")
            .withUsername("email_test")
            .withPassword("email_test");

    @DynamicPropertySource
    static void configureInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> SMTP.getSmtp().getPort());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmailQueueRepository emailQueueRepository;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Autowired
    private JavaMailSenderImpl mailSender;

    @Autowired
    private org.dddml.email.service.EmailProcessorService emailProcessorService;

    @Autowired
    private org.dddml.email.config.MailProperties mailProperties;

    @Autowired
    @SpyBean
    private EmailQueueClaimService emailQueueClaimService;

    @Autowired
    private EmailDeliveryService emailDeliveryService;

    @Autowired
    private EmailEventListener emailEventListener;

    @Autowired
    private EmailRateLimiter emailRateLimiter;

    @Autowired
    @Qualifier("emailExecutor")
    private Executor emailExecutor;

    @BeforeEach
    void clearPersistentAndSmtpState() throws Exception {
        emailLogRepository.deleteAll();
        emailQueueRepository.deleteAll();
        SMTP.purgeEmailFromAllMailboxes();
    }

    @AfterAll
    static void stopSmtp() {
        SMTP.stop();
    }

    @Test
    void flywayCreatesSchemaThatPassesHibernateValidation() {
        Integer tableCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_name IN ('email_queue', 'email_logs')
            """,
            Integer.class
        );

        Integer migrationCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM email_service_flyway_schema_history
            WHERE success = TRUE
              AND version IN ('1', '2')
            """,
            Integer.class
        );

        assertThat(tableCount).isEqualTo(2);
        assertThat(migrationCount).isEqualTo(2);
        assertThat(mailSender.getHost()).isEqualTo("127.0.0.1");
        assertThat(mailSender.getPort()).isEqualTo(SMTP.getSmtp().getPort());
        assertThat(mailSender.getJavaMailProperties())
            .containsEntry("mail.smtp.writetimeout", "10000")
            .containsEntry("mail.smtp.ssl.checkserveridentity", "true");
    }

    @Test
    void exposesTheUniAuthHealthAndTemplateContractOverHttp() {
        ResponseEntity<Map<String, Object>> health = exchange(
            "/api/email/health",
            HttpMethod.GET,
            null
        );
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Email-Service-Key", "integration-secret");
        ResponseEntity<List<String>> templates = restTemplate.exchange(
            "/api/email/templates",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<>() {
            }
        );

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).containsEntry("status", "UP");
        assertThat(templates.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(templates.getBody()).contains(
            "email/email-verify",
            "email/password-reset"
        );
    }

    @Test
    void rejectsRequestsWithoutTheConfiguredServiceCredential() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/email/health",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {
            }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody())
            .containsEntry("success", false)
            .containsEntry("message", "Unauthorized");
    }

    @Test
    void rejectsAmbiguousRepeatedServiceCredentials() {
        ResponseEntity<Map<String, Object>> repeatedCorrect = exchangeWithApiKeys(
            "integration-secret",
            "integration-secret"
        );
        ResponseEntity<Map<String, Object>> correctThenWrong = exchangeWithApiKeys(
            "integration-secret",
            "wrong"
        );
        ResponseEntity<Map<String, Object>> wrongThenCorrect = exchangeWithApiKeys(
            "wrong",
            "integration-secret"
        );

        assertUnauthorizedWithSecurityHeaders(repeatedCorrect);
        assertUnauthorizedWithSecurityHeaders(correctThenWrong);
        assertUnauthorizedWithSecurityHeaders(wrongThenCorrect);
    }

    @Test
    void appliesSecurityHeadersAcrossSuccessfulAndRejectedResponses() {
        ResponseEntity<Map<String, Object>> success = exchange(
            "/api/email/health",
            HttpMethod.GET,
            null
        );
        ResponseEntity<Map<String, Object>> unauthorized = restTemplate.exchange(
            "/api/email/health",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {
            }
        );
        ResponseEntity<Map<String, Object>> notFound = exchange(
            "/api/email/not-found",
            HttpMethod.GET,
            null
        );
        ResponseEntity<Map<String, Object>> invalidRequest = exchange(
            "/api/email/logs?size=101",
            HttpMethod.GET,
            null
        );
        ResponseEntity<Map<String, Object>> internalFailure;
        mailProperties.setEnabled(false);
        try {
            internalFailure = exchange(
                "/api/email/simple",
                HttpMethod.POST,
                Map.of(
                    "to", "disabled@example.test",
                    "subject", "Disabled service",
                    "htmlContent", "<p>must not be queued</p>"
                )
            );
        } finally {
            mailProperties.setEnabled(true);
        }

        assertThat(success.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(invalidRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(internalFailure.getStatusCode())
            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertSecurityResponseHeaders(success);
        assertSecurityResponseHeaders(unauthorized);
        assertSecurityResponseHeaders(notFound);
        assertSecurityResponseHeaders(invalidRequest);
        assertSecurityResponseHeaders(internalFailure);
        assertThat(emailQueueRepository.count()).isZero();
    }

    @Test
    void sendsRequiredTemplatesFromHttpThroughQueueAndSmtp() throws Exception {
        long verificationQueueId = enqueueTemplate(
            "verify@example.test",
            "Verify your email",
            "email/email-verify",
            Map.of(
                "code", "314159",
                "verificationCode", "314159",
                "username", "verify@example.test",
                "expiryMinutes", 10
            ),
            "VERIFICATION"
        );
        long resetQueueId = enqueueTemplate(
            "reset@example.test",
            "Reset your password",
            "email/password-reset",
            Map.of(
                "code", "271828",
                "verificationCode", "271828",
                "username", "reset@example.test",
                "expiryMinutes", 15
            ),
            "PASSWORD_RESET"
        );

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertCompletedWithSuccessfulEventLog(verificationQueueId);
            assertCompletedWithSuccessfulEventLog(resetQueueId);
            assertThat(SMTP.getReceivedMessages()).hasSize(2);
        });

        MimeMessage verificationMail = findMessage("verify@example.test");
        MimeMessage resetMail = findMessage("reset@example.test");

        assertThat(verificationMail.getSubject()).isEqualTo("Verify your email");
        assertThat(verificationMail.getHeader("X-Email-Type", null))
            .isEqualTo("VERIFICATION");
        assertThat(verificationMail.getHeader("X-Send-Method", null))
            .isEqualTo("EVENT");
        assertThat(GreenMailUtil.getBody(verificationMail))
            .contains("314159", "verify@example.test", "10");

        assertThat(resetMail.getSubject()).isEqualTo("Reset your password");
        assertThat(resetMail.getHeader("X-Email-Type", null))
            .isEqualTo("PASSWORD_RESET");
        assertThat(resetMail.getHeader("X-Send-Method", null))
            .isEqualTo("EVENT");
        assertThat(GreenMailUtil.getBody(resetMail))
            .contains("271828", "reset@example.test", "15");

        assertThat(emailQueueRepository.findById(verificationQueueId).orElseThrow().getMaxRetries())
            .isEqualTo(4);
        assertThat(emailQueueRepository.findById(resetQueueId).orElseThrow().getMaxRetries())
            .isEqualTo(4);
    }

    @Test
    void rejectsUnknownTemplateWithoutCreatingQueueOrSendingMail() {
        ResponseEntity<Map<String, Object>> response = exchange(
            "/api/email/template",
            HttpMethod.POST,
            Map.of(
                "to", "unknown@example.test",
                "subject", "Unknown template",
                "templateName", "email/does-not-exist",
                "variables", Map.of("verificationCode", "000000"),
                "emailType", "VERIFICATION"
            )
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(emailQueueRepository.count()).isZero();
        assertThat(emailLogRepository.count()).isZero();
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    void rejectsMissingFieldsHeaderInjectionAndUnboundedPagination() {
        ResponseEntity<Map<String, Object>> missingRecipient = exchange(
            "/api/email/template",
            HttpMethod.POST,
            Map.of(
                "subject", "Missing recipient",
                "templateName", "email/email-verify",
                "variables", Map.of("verificationCode", "000000"),
                "emailType", "VERIFICATION"
            )
        );
        ResponseEntity<Map<String, Object>> headerInjection = exchange(
            "/api/email/simple",
            HttpMethod.POST,
            Map.of(
                "to", "recipient@example.test",
                "subject", "Allowed\r\nBcc: attacker@example.test",
                "htmlContent", "<p>content</p>"
            )
        );
        ResponseEntity<Map<String, Object>> oversizedPage = exchange(
            "/api/email/logs?size=1001",
            HttpMethod.GET,
            null
        );
        ResponseEntity<Map<String, Object>> oversizedValidation = exchange(
            "/api/email/validate",
            HttpMethod.POST,
            Map.of("email", "x".repeat(256))
        );
        ResponseEntity<Map<String, Object>> oversizedRenderedTemplate = exchange(
            "/api/email/template",
            HttpMethod.POST,
            Map.of(
                "to", "recipient@example.test",
                "subject", "Oversized output",
                "templateName", "email/email-verify",
                "variables", Map.of(
                    "verificationCode", "000000",
                    "username", "x".repeat(1_000_000),
                    "expiryMinutes", 10
                ),
                "emailType", "VERIFICATION"
            )
        );
        ResponseEntity<Map<String, Object>> missingVerificationCode = exchange(
            "/api/email/template",
            HttpMethod.POST,
            Map.of(
                "to", "recipient@example.test",
                "subject", "Missing verification code",
                "templateName", "email/email-verify",
                "variables", Map.of(
                    "username", "recipient@example.test",
                    "expiryMinutes", 10
                ),
                "emailType", "VERIFICATION"
            )
        );

        assertThat(missingRecipient.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(headerInjection.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(oversizedPage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(oversizedValidation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(oversizedRenderedTemplate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missingVerificationCode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(emailQueueRepository.count()).isZero();
        assertThat(emailLogRepository.count()).isZero();
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    void preservesStandardHttpStatusesForInvalidRoutesMethodsAndMediaTypes() {
        ResponseEntity<Map<String, Object>> invalidPathVariable = exchange(
            "/api/email/queue/not-a-number",
            HttpMethod.GET,
            null
        );
        ResponseEntity<Map<String, Object>> missingResource = exchange(
            "/api/email/not-found",
            HttpMethod.GET,
            null
        );
        ResponseEntity<Map<String, Object>> unsupportedMethod = exchange(
            "/api/email/health",
            HttpMethod.PUT,
            null
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Email-Service-Key", "integration-secret");
        headers.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        ResponseEntity<Map<String, Object>> unsupportedMediaType = restTemplate.exchange(
            "/api/email/simple",
            HttpMethod.POST,
            new HttpEntity<>("not-json", headers),
            new ParameterizedTypeReference<>() {
            }
        );

        assertThat(invalidPathVariable.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missingResource.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unsupportedMethod.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(unsupportedMediaType.getStatusCode())
            .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void paginatesLogsInTheDatabaseAndPreservesTheResponseShape() {
        List<EmailLog> logs = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            logs.add(EmailLog.builder()
                .recipient("page-" + index + "@example.test")
                .subject("Page " + index)
                .status(index % 2 == 0 ? "SUCCESS" : "FAILED")
                .retryCount(0)
                .sentTime(LocalDateTime.now().minusMinutes(index))
                .build());
        }
        emailLogRepository.saveAllAndFlush(logs);

        ResponseEntity<Map<String, Object>> response = exchange(
            "/api/email/logs?page=2&size=10",
            HttpMethod.GET,
            null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
            .containsEntry("total", 25)
            .containsEntry("page", 2)
            .containsEntry("size", 10);
        assertThat((List<?>) response.getBody().get("logs")).hasSize(10);
    }

    @Test
    void recordsSmtpFailureAndSchedulesRetryUsingRealBeans() {
        int workingSmtpPort = mailSender.getPort();
        mailSender.setPort(1);

        try {
            long queueId = enqueueTemplate(
                "retry@example.test",
                "Retry delivery",
                "email/password-reset",
                Map.of(
                    "code", "161803",
                    "verificationCode", "161803",
                    "username", "retry@example.test",
                    "expiryMinutes", 5
                ),
                "PASSWORD_RESET"
            );

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                EmailQueue queue = emailQueueRepository.findById(queueId).orElseThrow();
                List<EmailLog> logs = emailLogRepository.findByQueueId(queueId);

                assertThat(queue.getStatus()).isEqualTo("PENDING");
                assertThat(queue.getRetryCount()).isEqualTo(1);
                assertThat(queue.getNextRetryTime()).isNotNull();
                assertThat(logs).singleElement().satisfies(log -> {
                    assertThat(log.getStatus()).isEqualTo("FAILED");
                    assertThat(log.getSendMethod()).isEqualTo("EVENT");
                    assertThat(log.getErrorMessage()).isNotBlank();
                });
            });

            assertThat(SMTP.getReceivedMessages()).isEmpty();
        } finally {
            mailSender.setPort(workingSmtpPort);
        }
    }

    @Test
    void rejectsMalformedPersistedPayloadsBeforeSmtpDelivery() {
        List<EmailQueue> malformedQueues = emailQueueRepository.saveAllAndFlush(
            List.of(
                EmailQueue.builder()
                    .recipient("not-an-email")
                    .subject("Invalid recipient")
                    .htmlContent("<p>invalid recipient</p>")
                    .emailType("GENERAL")
                    .status("PENDING")
                    .priority(5)
                    .retryCount(0)
                    .maxRetries(4)
                    .build(),
                EmailQueue.builder()
                    .recipient("subject-injection@example.test")
                    .subject("Allowed\r\nBcc: attacker@example.test")
                    .htmlContent("<p>subject injection</p>")
                    .emailType("GENERAL")
                    .status("PENDING")
                    .priority(5)
                    .retryCount(0)
                    .maxRetries(4)
                    .build(),
                EmailQueue.builder()
                    .recipient("oversized-content@example.test")
                    .subject("Oversized content")
                    .htmlContent("x".repeat(1_000_001))
                    .emailType("GENERAL")
                    .status("PENDING")
                    .priority(5)
                    .retryCount(0)
                    .maxRetries(4)
                    .build(),
                EmailQueue.builder()
                    .recipient("header-token@example.test")
                    .subject("Invalid header token")
                    .htmlContent("<p>invalid header token</p>")
                    .emailType("GENERAL\r\nX-Injected: true")
                    .status("PENDING")
                    .priority(5)
                    .retryCount(0)
                    .maxRetries(4)
                    .build()
            )
        );
        EmailQueue malformedSendMethodQueue = emailQueueRepository.saveAndFlush(
            EmailQueue.builder()
                .recipient("send-method-header@example.test")
                .subject("Invalid send method header")
                .htmlContent("<p>invalid send method header</p>")
                .emailType("GENERAL")
                .status("PENDING")
                .priority(5)
                .retryCount(0)
                .maxRetries(4)
                .build()
        );

        for (EmailQueue queue : malformedQueues) {
            assertThat(emailQueueClaimService.claimPending(
                queue.getId(),
                LocalDateTime.now()
            )).isTrue();
            assertThat(emailDeliveryService.deliver(queue.getId(), "SCHEDULED"))
                .isEqualTo(EmailDeliveryService.DeliveryOutcome.FAILED);
        }
        assertThat(emailQueueClaimService.claimPending(
            malformedSendMethodQueue.getId(),
            LocalDateTime.now()
        )).isTrue();
        assertThat(emailDeliveryService.deliver(
            malformedSendMethodQueue.getId(),
            "SCHEDULED\r\nX-Injected: true"
        )).isEqualTo(EmailDeliveryService.DeliveryOutcome.FAILED);

        List<EmailQueue> rejectedQueues = new ArrayList<>(malformedQueues);
        rejectedQueues.add(malformedSendMethodQueue);
        for (EmailQueue queue : rejectedQueues) {
            EmailQueue persisted = emailQueueRepository.findById(queue.getId()).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo("PENDING");
            assertThat(persisted.getRetryCount()).isEqualTo(1);
            assertThat(emailLogRepository.findByQueueId(queue.getId()))
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.getStatus()).isEqualTo("FAILED");
                    assertThat(log.getRecipient()).isEqualTo(
                        "undisclosed@example.invalid"
                    );
                    assertThat(log.getSubject()).isEqualTo(
                        "Invalid queued email data"
                    );
                    assertThat(log.getEmailContent()).isNull();
                    assertThat(log.getEmailType()).isEqualTo("GENERAL");
                    assertThat(log.getSendMethod()).isEqualTo(
                        queue.getId().equals(malformedSendMethodQueue.getId())
                            ? "UNKNOWN"
                            : "SCHEDULED"
                    );
                    assertThat(log.getErrorMessage()).isEqualTo(
                        "Invalid queued email data"
                    );
                });
        }
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    void defaultsMissingPersistedEmailTypeBeforeSmtpDelivery() throws Exception {
        List<EmailQueue> queues = emailQueueRepository.saveAllAndFlush(
            List.of(
                EmailQueue.builder()
                    .recipient("missing-email-type@example.test")
                    .subject("Missing email type")
                    .htmlContent("<p>missing email type</p>")
                    .emailType(null)
                    .status("PENDING")
                    .priority(5)
                    .retryCount(0)
                    .maxRetries(4)
                    .build(),
                EmailQueue.builder()
                    .recipient("blank-email-type@example.test")
                    .subject("Blank email type")
                    .htmlContent("<p>blank email type</p>")
                    .emailType("   ")
                    .status("PENDING")
                    .priority(5)
                    .retryCount(0)
                    .maxRetries(4)
                    .build()
            )
        );

        for (EmailQueue queue : queues) {
            assertThat(emailQueueClaimService.claimPending(
                queue.getId(),
                LocalDateTime.now()
            )).isTrue();
            assertThat(emailDeliveryService.deliver(queue.getId(), "SCHEDULED"))
                .isEqualTo(EmailDeliveryService.DeliveryOutcome.SUCCESS);

            EmailQueue persisted = emailQueueRepository.findById(queue.getId()).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo("COMPLETED");
            assertThat(emailLogRepository.findByQueueId(queue.getId()))
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.getStatus()).isEqualTo("SUCCESS");
                    assertThat(log.getEmailType()).isEqualTo("GENERAL");
                    assertThat(log.getSendMethod()).isEqualTo("SCHEDULED");
                });
            assertThat(findMessage(queue.getRecipient())
                .getHeader("X-Email-Type", null)).isEqualTo("GENERAL");
        }
    }

    @Test
    void recoverySelectsHigherPriorityCandidatesFirst() {
        LocalDateTime now = LocalDateTime.now();
        EmailQueue lowPriority = emailQueueRepository.saveAndFlush(
            EmailQueue.builder()
                .recipient("low-priority@example.test")
                .subject("Low priority")
                .htmlContent("<p>low priority</p>")
                .emailType("GENERAL")
                .status("PENDING")
                .priority(1)
                .retryCount(0)
                .maxRetries(4)
                .build()
        );
        EmailQueue highPriority = emailQueueRepository.saveAndFlush(
            EmailQueue.builder()
                .recipient("high-priority@example.test")
                .subject("High priority")
                .htmlContent("<p>high priority</p>")
                .emailType("GENERAL")
                .status("PENDING")
                .priority(10)
                .retryCount(0)
                .maxRetries(4)
                .build()
        );

        List<EmailQueue> candidates = emailQueueRepository.findFailedOrStuckEmails(
            now.plusSeconds(1),
            now.minusMinutes(10),
            PageRequest.of(0, 10)
        ).getContent();

        assertThat(candidates)
            .extracting(EmailQueue::getId)
            .containsExactly(highPriority.getId(), lowPriority.getId());
    }

    @Test
    void disabledMailOrQueueDoesNotRecoverPendingEmails() {
        EmailQueue pending = emailQueueRepository.saveAndFlush(
            EmailQueue.builder()
                .recipient("disabled-recovery@example.test")
                .subject("Disabled recovery")
                .htmlContent("<p>must stay pending</p>")
                .emailType("GENERAL")
                .status("PENDING")
                .priority(5)
                .retryCount(0)
                .maxRetries(4)
                .build()
        );

        mailProperties.getRecovery().setEnabled(true);
        try {
            mailProperties.setEnabled(false);
            emailProcessorService.recoverFailedEmails();

            mailProperties.setEnabled(true);
            mailProperties.getQueue().setEnabled(false);
            emailProcessorService.recoverFailedEmails();
        } finally {
            mailProperties.setEnabled(true);
            mailProperties.getQueue().setEnabled(true);
            mailProperties.getRecovery().setEnabled(false);
        }

        EmailQueue unchanged = emailQueueRepository.findById(pending.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo("PENDING");
        assertThat(emailLogRepository.findByQueueId(pending.getId())).isEmpty();
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void unavailableAsyncExecutorLeavesCommittedEmailForPersistentRecovery() {
        assertThat(emailExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ((ThreadPoolTaskExecutor) emailExecutor).shutdown();

        long queueId = enqueueTemplate(
            "executor-fallback@example.test",
            "Executor fallback",
            "email/email-verify",
            Map.of(
                "code", "112358",
                "verificationCode", "112358",
                "username", "executor-fallback@example.test",
                "expiryMinutes", 10
            ),
            "VERIFICATION"
        );

        EmailQueue queued = emailQueueRepository.findById(queueId).orElseThrow();
        assertThat(queued.getStatus()).isEqualTo("PENDING");
        assertThat(emailLogRepository.findByQueueId(queueId)).isEmpty();
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    void reclaimsAStuckProcessingEmailAndSendsItExactlyOnce() throws Exception {
        EmailQueue stuck = emailQueueRepository.saveAndFlush(
            EmailQueue.builder()
                .recipient("stuck@example.test")
                .subject("Recover stuck delivery")
                .htmlContent("<p>stuck delivery</p>")
                .emailType("PASSWORD_RESET")
                .status("PROCESSING")
                .priority(5)
                .retryCount(1)
                .maxRetries(4)
                .build()
        );
        jdbcTemplate.update(
            "UPDATE email_queue SET updated_time = ? WHERE id = ?",
            LocalDateTime.now().minusMinutes(30),
            stuck.getId()
        );

        mailProperties.getRecovery().setEnabled(true);
        try {
            emailProcessorService.recoverFailedEmails();
            emailProcessorService.recoverFailedEmails();
        } finally {
            mailProperties.getRecovery().setEnabled(false);
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            EmailQueue recovered = emailQueueRepository.findById(stuck.getId()).orElseThrow();
            assertThat(recovered.getStatus()).isEqualTo("COMPLETED");
            assertThat(emailLogRepository.findByQueueId(stuck.getId()))
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.getStatus()).isEqualTo("SUCCESS");
                    assertThat(log.getSendMethod()).isEqualTo("SCHEDULED");
                });
            assertThat(SMTP.getReceivedMessages()).hasSize(1);
        });
    }

    @Test
    void eventAndRecoveryClaimsRaceWithoutDuplicateDelivery() throws Exception {
        EmailQueue queued = emailQueueRepository.saveAndFlush(
            EmailQueue.builder()
                .recipient("claim-race@example.test")
                .subject("Claim race")
                .htmlContent("<p>claim race</p>")
                .emailType("VERIFICATION")
                .status("PENDING")
                .priority(5)
                .retryCount(0)
                .maxRetries(4)
                .build()
        );
        LocalDateTime now = LocalDateTime.now();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<EmailDeliveryService.DeliveryOutcome> eventOutcome = executor.submit(
                () -> claimAndDeliver(queued.getId(), true, now, ready, start)
            );
            Future<EmailDeliveryService.DeliveryOutcome> recoveryOutcome = executor.submit(
                () -> claimAndDeliver(queued.getId(), false, now, ready, start)
            );

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                eventOutcome.get(15, TimeUnit.SECONDS),
                recoveryOutcome.get(15, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder(
                EmailDeliveryService.DeliveryOutcome.SUCCESS,
                EmailDeliveryService.DeliveryOutcome.SKIPPED
            );
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        EmailQueue delivered = emailQueueRepository.findById(queued.getId()).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo("COMPLETED");
        assertThat(emailLogRepository.findByQueueId(queued.getId()))
            .singleElement()
            .satisfies(log -> assertThat(log.getStatus()).isEqualTo("SUCCESS"));
        assertThat(SMTP.getReceivedMessages()).hasSize(1);
    }

    @Test
    void eventClaimFailureReleasesTheRealRateLimitReservation() throws Exception {
        EmailQueue queued = savePendingQueue(
            "event-claim-failure@example.test",
            "Event claim failure"
        );
        enableSingleSlotRateLimit();
        doThrow(new IllegalStateException("test claim failure"))
            .when(emailQueueClaimService)
            .claimPending(eq(queued.getId()), any(LocalDateTime.class));

        try {
            EmailEventListener target =
                AopTestUtils.getUltimateTargetObject(emailEventListener);
            target.handleEmailQueuedEvent(new EmailQueuedEvent(this, queued.getId()));

            assertSingleRateLimitSlotAvailable();
            assertQueueWasNotDelivered(queued.getId());
        } finally {
            resetRateLimit();
        }
    }

    @Test
    void recoveryClaimFailureReleasesTheRealRateLimitReservation() {
        EmailQueue queued = savePendingQueue(
            "recovery-claim-failure@example.test",
            "Recovery claim failure"
        );
        enableSingleSlotRateLimit();
        mailProperties.getRecovery().setEnabled(true);
        doThrow(new IllegalStateException("test claim failure"))
            .when(emailQueueClaimService)
            .claimRecoverable(
                eq(queued.getId()),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
            );

        try {
            emailProcessorService.recoverFailedEmails();

            assertSingleRateLimitSlotAvailable();
            assertQueueWasNotDelivered(queued.getId());
        } finally {
            mailProperties.getRecovery().setEnabled(false);
            resetRateLimit();
        }
    }

    @Test
    void eventLateReleaseDoesNotFreeTheCurrentRateLimitWindow() throws Exception {
        EmailQueue queued = savePendingQueue(
            "event-window-rollover@example.test",
            "Event window rollover"
        );
        enableSingleSlotRateLimit();
        AtomicReference<EmailRateLimiter.Reservation> currentWindowReservation =
            new AtomicReference<>();
        doAnswer(invocation -> {
            expireRateLimitWindow();
            currentWindowReservation.set(trackRateLimitReservation(
                emailRateLimiter.tryAcquire()
            ));
            return false;
        })
            .when(emailQueueClaimService)
            .claimPending(eq(queued.getId()), any(LocalDateTime.class));

        try {
            EmailEventListener target =
                AopTestUtils.getUltimateTargetObject(emailEventListener);
            target.handleEmailQueuedEvent(new EmailQueuedEvent(this, queued.getId()));

            assertThat(currentWindowReservation.get()).isNotNull();
            assertThat(emailRateLimiter.tryAcquire()).isNull();
            assertQueueWasNotDelivered(queued.getId());
        } finally {
            resetRateLimit();
        }
    }

    @Test
    void recoveryLateReleaseDoesNotFreeTheCurrentRateLimitWindow() {
        EmailQueue queued = savePendingQueue(
            "recovery-window-rollover@example.test",
            "Recovery window rollover"
        );
        enableSingleSlotRateLimit();
        mailProperties.getRecovery().setEnabled(true);
        AtomicReference<EmailRateLimiter.Reservation> currentWindowReservation =
            new AtomicReference<>();
        doAnswer(invocation -> {
            expireRateLimitWindow();
            currentWindowReservation.set(trackRateLimitReservation(
                emailRateLimiter.tryAcquire()
            ));
            return false;
        })
            .when(emailQueueClaimService)
            .claimRecoverable(
                eq(queued.getId()),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
            );

        try {
            emailProcessorService.recoverFailedEmails();

            assertThat(currentWindowReservation.get()).isNotNull();
            assertThat(emailRateLimiter.tryAcquire()).isNull();
            assertQueueWasNotDelivered(queued.getId());
        } finally {
            mailProperties.getRecovery().setEnabled(false);
            resetRateLimit();
        }
    }

    private long enqueueTemplate(String to, String subject, String templateName,
                                 Map<String, Object> variables, String emailType) {
        ResponseEntity<Map<String, Object>> response = exchange(
            "/api/email/template",
            HttpMethod.POST,
            Map.of(
                "to", to,
                "subject", subject,
                "templateName", templateName,
                "variables", variables,
                "emailType", emailType
            )
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("success", true);
        assertThat(response.getBody()).containsKey("queueId");
        return ((Number) response.getBody().get("queueId")).longValue();
    }

    private EmailQueue savePendingQueue(String recipient, String subject) {
        return emailQueueRepository.saveAndFlush(
            EmailQueue.builder()
                .recipient(recipient)
                .subject(subject)
                .htmlContent("<p>must remain pending</p>")
                .emailType("GENERAL")
                .status("PENDING")
                .priority(5)
                .retryCount(0)
                .maxRetries(4)
                .build()
        );
    }

    private void enableSingleSlotRateLimit() {
        mailProperties.getRateLimit().setMaxPerMinute(1);
        mailProperties.getRateLimit().setEnabled(true);
    }

    private void assertSingleRateLimitSlotAvailable() {
        assertThat(trackRateLimitReservation(emailRateLimiter.tryAcquire())).isNotNull();
        assertThat(emailRateLimiter.tryAcquire()).isNull();
    }

    private void resetRateLimit() {
        rateLimitReservations.forEach(EmailRateLimiter.Reservation::release);
        rateLimitReservations.clear();
        mailProperties.getRateLimit().setEnabled(false);
        mailProperties.getRateLimit().setMaxPerMinute(60);
    }

    private final List<EmailRateLimiter.Reservation> rateLimitReservations =
        new ArrayList<>();

    private EmailRateLimiter.Reservation trackRateLimitReservation(
            EmailRateLimiter.Reservation reservation) {
        if (reservation != null) {
            rateLimitReservations.add(reservation);
        }
        return reservation;
    }

    private void expireRateLimitWindow() {
        ReflectionTestUtils.setField(
            emailRateLimiter,
            "windowStartedAt",
            System.nanoTime() - Duration.ofMinutes(1).toNanos()
        );
    }

    private void assertQueueWasNotDelivered(long queueId) {
        EmailQueue persisted = emailQueueRepository.findById(queueId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo("PENDING");
        assertThat(emailLogRepository.findByQueueId(queueId)).isEmpty();
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    private EmailDeliveryService.DeliveryOutcome claimAndDeliver(
            long queueId,
            boolean eventClaim,
            LocalDateTime now,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent claim start timed out");
        }

        boolean claimed = eventClaim
            ? emailQueueClaimService.claimPending(queueId, now)
            : emailQueueClaimService.claimRecoverable(
                queueId,
                now,
                now.minusMinutes(30)
            );
        if (!claimed) {
            return EmailDeliveryService.DeliveryOutcome.SKIPPED;
        }
        return emailDeliveryService.deliver(
            queueId,
            eventClaim ? "EVENT" : "SCHEDULED"
        );
    }

    private ResponseEntity<Map<String, Object>> exchange(
            String path, HttpMethod method, Object requestBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Email-Service-Key", "integration-secret");
        HttpEntity<?> entity = requestBody == null
            ? new HttpEntity<>(headers)
            : new HttpEntity<>(requestBody, headers);
        return restTemplate.exchange(
            path,
            method,
            entity,
            new ParameterizedTypeReference<>() {
            }
        );
    }

    private void assertSecurityResponseHeaders(ResponseEntity<?> response) {
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
            .isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA))
            .isEqualTo("no-cache");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options"))
            .isEqualTo("nosniff");
    }

    private ResponseEntity<Map<String, Object>> exchangeWithApiKeys(String... apiKeys) {
        HttpHeaders headers = new HttpHeaders();
        for (String apiKey : apiKeys) {
            headers.add(EmailSecurityProperties.API_KEY_HEADER, apiKey);
        }
        return restTemplate.exchange(
            "/api/email/health",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<>() {
            }
        );
    }

    private void assertUnauthorizedWithSecurityHeaders(
            ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody())
            .containsEntry("success", false)
            .containsEntry("message", "Unauthorized");
        assertSecurityResponseHeaders(response);
    }

    private void assertCompletedWithSuccessfulEventLog(long queueId) {
        EmailQueue queue = emailQueueRepository.findById(queueId).orElseThrow();
        List<EmailLog> logs = emailLogRepository.findByQueueId(queueId);

        assertThat(queue.getStatus()).isEqualTo("COMPLETED");
        assertThat(queue.getProcessedTime()).isNotNull();
        assertThat(logs).singleElement().satisfies(log -> {
            assertThat(log.getStatus()).isEqualTo("SUCCESS");
            assertThat(log.getSendMethod()).isEqualTo("EVENT");
        });
    }

    private MimeMessage findMessage(String recipient) throws Exception {
        for (MimeMessage message : SMTP.getReceivedMessages()) {
            if (GreenMailUtil.getAddressList(message.getAllRecipients()).contains(recipient)) {
                return message;
            }
        }
        throw new IllegalStateException("No message found for " + recipient);
    }

    private static GreenMail startSmtp() {
        GreenMail smtp = new GreenMail(
            new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP)
        );
        smtp.start();
        return smtp;
    }
}
