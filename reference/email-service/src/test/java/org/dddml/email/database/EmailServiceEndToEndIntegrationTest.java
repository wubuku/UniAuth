package org.dddml.email.database;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.dddml.email.entity.EmailLog;
import org.dddml.email.entity.EmailQueue;
import org.dddml.email.repository.EmailLogRepository;
import org.dddml.email.repository.EmailQueueRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/postgresql",
        "spring.flyway.table=email_service_flyway_schema_history",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.mail.username=",
        "spring.mail.password=",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "spring.mail.properties.mail.smtp.starttls.required=false",
        "spring.mail.properties.mail.smtp.ssl.enable=false",
        "app.mail.from-email=no-reply@example.invalid",
        "app.mail.from-name=UniAuth Integration Test",
        "app.mail.rate-limit.enabled=false",
        "app.mail.recovery.enabled=false"
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
              AND version = '1'
            """,
            Integer.class
        );

        assertThat(tableCount).isEqualTo(2);
        assertThat(migrationCount).isEqualTo(1);
    }

    @Test
    void exposesTheUniAuthHealthAndTemplateContractOverHttp() {
        ResponseEntity<Map<String, Object>> health = exchange(
            "/api/email/health",
            HttpMethod.GET,
            null
        );
        ResponseEntity<List<String>> templates = restTemplate.exchange(
            "/api/email/templates",
            HttpMethod.GET,
            null,
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

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(emailQueueRepository.count()).isZero();
        assertThat(emailLogRepository.count()).isZero();
        assertThat(SMTP.getReceivedMessages()).isEmpty();
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

    private ResponseEntity<Map<String, Object>> exchange(
            String path, HttpMethod method, Object requestBody) {
        HttpEntity<?> entity = requestBody == null
            ? HttpEntity.EMPTY
            : new HttpEntity<>(requestBody);
        return restTemplate.exchange(
            path,
            method,
            entity,
            new ParameterizedTypeReference<>() {
            }
        );
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
