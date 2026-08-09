package org.dddml.email.repository;

import org.dddml.email.entity.EmailLog;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.fail-on-missing-locations=true",
    "spring.flyway.locations=classpath:db/migration/postgresql",
    "spring.flyway.table=email_service_flyway_schema_history",
    "spring.flyway.default-schema=public",
    "spring.flyway.schemas=public",
    "spring.flyway.baseline-on-migrate=false",
    "spring.flyway.clean-disabled=true",
    "spring.flyway.validate-migration-naming=true",
    "spring.flyway.validate-on-migrate=true",
    "spring.flyway.out-of-order=false",
    "spring.sql.init.mode=never",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.show-sql=false"
})
@Testcontainers
class EmailLogRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("email_service_log_repository_test")
            .withUsername("email_log_test")
            .withPassword("email_log_test");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Test
    void testSaveAndFind() {
        EmailLog log = EmailLog.builder()
                .recipient("test@example.com")
                .subject("Test Subject")
                .status("SUCCESS")
                .emailType("TEST")
                .mailProvider("Gmail")
                .sendMethod("EVENT")
                .durationMs(100L)
                .build();

        entityManager.persistAndFlush(log);
        entityManager.clear();

        Optional<EmailLog> found = emailLogRepository.findById(log.getId());
        assertTrue(found.isPresent());
        assertEquals("test@example.com", found.get().getRecipient());
        assertEquals("SUCCESS", found.get().getStatus());
    }

    @Test
    void testFindByStatus() {
        EmailLog successLog = EmailLog.builder()
                .recipient("user1@example.com")
                .subject("Success")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        EmailLog failedLog = EmailLog.builder()
                .recipient("user2@example.com")
                .subject("Failed")
                .status("FAILED")
                .emailType("TEST")
                .build();

        entityManager.persist(successLog);
        entityManager.persist(failedLog);
        entityManager.flush();

        List<EmailLog> successLogs = emailLogRepository.findByStatus("SUCCESS");
        assertEquals(1, successLogs.size());
        assertEquals("SUCCESS", successLogs.get(0).getStatus());
    }

    @Test
    void testFindByRecipient() {
        EmailLog log1 = EmailLog.builder()
                .recipient("user@example.com")
                .subject("Email 1")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        EmailLog log2 = EmailLog.builder()
                .recipient("user@example.com")
                .subject("Email 2")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        EmailLog log3 = EmailLog.builder()
                .recipient("other@example.com")
                .subject("Other")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        entityManager.persist(log1);
        entityManager.persist(log2);
        entityManager.persist(log3);
        entityManager.flush();

        List<EmailLog> userLogs = emailLogRepository.findByRecipient("user@example.com");
        assertEquals(2, userLogs.size());
    }

    @Test
    void testCountByStatus() {
        EmailLog success1 = EmailLog.builder()
                .recipient("user1@example.com")
                .subject("Success1")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        EmailLog success2 = EmailLog.builder()
                .recipient("user2@example.com")
                .subject("Success2")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        EmailLog failed = EmailLog.builder()
                .recipient("user3@example.com")
                .subject("Failed")
                .status("FAILED")
                .emailType("TEST")
                .build();

        entityManager.persist(success1);
        entityManager.persist(success2);
        entityManager.persist(failed);
        entityManager.flush();

        long successCount = emailLogRepository.countByStatus("SUCCESS");
        long failedCount = emailLogRepository.countByStatus("FAILED");

        assertEquals(2, successCount);
        assertEquals(1, failedCount);
    }

    @Test
    void testCountBySendMethodSince() {
        EmailLog eventLog = EmailLog.builder()
                .recipient("user1@example.com")
                .subject("Event")
                .status("SUCCESS")
                .sendMethod("EVENT")
                .emailType("TEST")
                .sentTime(LocalDateTime.now())
                .build();

        EmailLog scheduledLog = EmailLog.builder()
                .recipient("user2@example.com")
                .subject("Scheduled")
                .status("SUCCESS")
                .sendMethod("SCHEDULED")
                .emailType("TEST")
                .sentTime(LocalDateTime.now())
                .build();

        entityManager.persist(eventLog);
        entityManager.persist(scheduledLog);
        entityManager.flush();

        List<Object[]> result = emailLogRepository.countBySendMethodSince(
                LocalDateTime.now().minusHours(1));

        assertFalse(result.isEmpty());
    }

    @Test
    void testFindAll() {
        EmailLog log1 = EmailLog.builder()
                .recipient("user1@example.com")
                .subject("Email 1")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        EmailLog log2 = EmailLog.builder()
                .recipient("user2@example.com")
                .subject("Email 2")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        entityManager.persist(log1);
        entityManager.persist(log2);
        entityManager.flush();

        List<EmailLog> allLogs = emailLogRepository.findAll();
        assertEquals(2, allLogs.size());
    }

    @Test
    void postgresSchemaRejectsOrphanQueueReferences() {
        EmailLog orphanLog = EmailLog.builder()
                .queueId(999L)
                .recipient("orphan@example.com")
                .subject("Orphan")
                .status("FAILED")
                .build();

        assertThrows(
            ConstraintViolationException.class,
            () -> entityManager.persistAndFlush(orphanLog)
        );
    }

    @Test
    void postgresSchemaRejectsPersistedEmailContent() {
        EmailLog sensitiveLog = EmailLog.builder()
                .recipient("sensitive@example.test")
                .subject("Sensitive")
                .status("SUCCESS")
                .emailContent("<p>verification-code-246810</p>")
                .build();

        assertThrows(
            ConstraintViolationException.class,
            () -> entityManager.persistAndFlush(sensitiveLog)
        );
    }
}
