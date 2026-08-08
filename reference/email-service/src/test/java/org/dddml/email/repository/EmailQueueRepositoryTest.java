package org.dddml.email.repository;

import org.dddml.email.entity.EmailLog;
import org.dddml.email.entity.EmailQueue;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
class EmailQueueRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("email_service_queue_repository_test")
            .withUsername("email_queue_test")
            .withPassword("email_queue_test");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EmailQueueRepository emailQueueRepository;

    @Test
    void testSaveAndFind() {
        EmailQueue queue = EmailQueue.builder()
                .recipient("test@example.com")
                .subject("Test Subject")
                .htmlContent("<p>Test Content</p>")
                .emailType("TEST")
                .status("PENDING")
                .priority(5)
                .retryCount(0)
                .maxRetries(3)
                .build();

        entityManager.persistAndFlush(queue);
        entityManager.clear();

        Optional<EmailQueue> found = emailQueueRepository.findById(queue.getId());
        assertTrue(found.isPresent());
        assertEquals("test@example.com", found.get().getRecipient());
        assertEquals("PENDING", found.get().getStatus());
    }

    @Test
    void testFindPendingEmails() {
        EmailQueue pending1 = EmailQueue.builder()
                .recipient("user1@example.com")
                .subject("Subject1")
                .htmlContent("Content1")
                .status("PENDING")
                .priority(5)
                .retryCount(0)
                .maxRetries(3)
                .build();

        EmailQueue pending2 = EmailQueue.builder()
                .recipient("user2@example.com")
                .subject("Subject2")
                .htmlContent("Content2")
                .status("PENDING")
                .priority(10)
                .retryCount(0)
                .maxRetries(3)
                .build();

        EmailQueue completed = EmailQueue.builder()
                .recipient("user3@example.com")
                .subject("Subject3")
                .htmlContent("Content3")
                .status("COMPLETED")
                .priority(5)
                .retryCount(0)
                .maxRetries(3)
                .build();

        entityManager.persist(pending1);
        entityManager.persist(pending2);
        entityManager.persist(completed);
        entityManager.flush();

        List<EmailQueue> pendingEmails = emailQueueRepository.findByStatus("PENDING");
        assertEquals(2, pendingEmails.size());
    }

    @Test
    void testFindByStatusOrderByPriorityDesc() {
        EmailQueue lowPriority = EmailQueue.builder()
                .recipient("user1@example.com")
                .subject("Low Priority")
                .htmlContent("Content")
                .status("PENDING")
                .priority(1)
                .retryCount(0)
                .maxRetries(3)
                .build();

        EmailQueue highPriority = EmailQueue.builder()
                .recipient("user2@example.com")
                .subject("High Priority")
                .htmlContent("Content")
                .status("PENDING")
                .priority(10)
                .retryCount(0)
                .maxRetries(3)
                .build();

        entityManager.persist(lowPriority);
        entityManager.persist(highPriority);
        entityManager.flush();

        List<EmailQueue> result = emailQueueRepository.findByStatusOrderByPriorityDesc("PENDING");
        assertEquals(2, result.size());
        assertEquals(10, result.get(0).getPriority());
        assertEquals(1, result.get(1).getPriority());
    }

    @Test
    void testFindFailedOrStuckEmails() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oldTime = now.minusMinutes(15);

        EmailQueue pending = EmailQueue.builder()
                .recipient("user1@example.com")
                .subject("Pending")
                .htmlContent("Content")
                .status("PENDING")
                .priority(5)
                .retryCount(0)
                .maxRetries(3)
                .build();

        EmailQueue processing = EmailQueue.builder()
                .recipient("user2@example.com")
                .subject("Processing")
                .htmlContent("Content")
                .status("PROCESSING")
                .priority(5)
                .retryCount(0)
                .maxRetries(3)
                .createdTime(oldTime)
                .updatedTime(oldTime)
                .build();

        entityManager.persist(pending);
        entityManager.persist(processing);
        entityManager.flush();
        entityManager.clear();

        Page<EmailQueue> result = emailQueueRepository.findFailedOrStuckEmails(
                now, now.minusMinutes(10), PageRequest.of(0, 10));
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void testCountByStatus() {
        EmailQueue pending = EmailQueue.builder()
                .recipient("user1@example.com")
                .subject("Pending")
                .htmlContent("Content")
                .status("PENDING")
                .priority(5)
                .retryCount(0)
                .maxRetries(3)
                .build();

        EmailQueue completed = EmailQueue.builder()
                .recipient("user2@example.com")
                .subject("Completed")
                .htmlContent("Content")
                .status("COMPLETED")
                .priority(5)
                .retryCount(0)
                .maxRetries(3)
                .build();

        entityManager.persist(pending);
        entityManager.persist(completed);
        entityManager.flush();

        long pendingCount = emailQueueRepository.countByStatus("PENDING");
        long completedCount = emailQueueRepository.countByStatus("COMPLETED");

        assertEquals(1, pendingCount);
        assertEquals(1, completedCount);
    }

    @Test
    void claimPendingDoesNotBypassTheScheduledRetryTime() {
        LocalDateTime now = LocalDateTime.now();
        EmailQueue pendingRetry = EmailQueue.builder()
                .recipient("retry@example.com")
                .subject("Retry later")
                .htmlContent("<p>Retry later</p>")
                .status("PENDING")
                .priority(5)
                .retryCount(1)
                .maxRetries(3)
                .nextRetryTime(now.plusMinutes(5))
                .build();
        entityManager.persistAndFlush(pendingRetry);
        entityManager.clear();

        assertEquals(0, emailQueueRepository.claimPending(pendingRetry.getId(), now));
        assertEquals(
            1,
            emailQueueRepository.claimPending(
                pendingRetry.getId(),
                now.plusMinutes(6)
            )
        );
    }

    @Test
    void postgresSchemaRejectsRetryCountsAboveTheConfiguredMaximum() {
        EmailQueue invalidQueue = EmailQueue.builder()
                .recipient("invalid@example.com")
                .subject("Invalid retry state")
                .htmlContent("<p>Invalid retry state</p>")
                .status("PENDING")
                .priority(5)
                .retryCount(4)
                .maxRetries(3)
                .build();

        assertThrows(
            ConstraintViolationException.class,
            () -> entityManager.persistAndFlush(invalidQueue)
        );
    }
}
