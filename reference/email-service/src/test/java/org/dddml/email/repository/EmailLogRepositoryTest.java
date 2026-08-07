package org.dddml.email.repository;

import org.dddml.email.entity.EmailLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false"
})
class EmailLogRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Test
    void testSaveAndFind() {
        EmailLog log = EmailLog.builder()
                .queueId(1L)
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
                .queueId(1L)
                .recipient("user1@example.com")
                .subject("Success")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        EmailLog failedLog = EmailLog.builder()
                .queueId(2L)
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
                .queueId(1L)
                .recipient("user@example.com")
                .subject("Email 1")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        EmailLog log2 = EmailLog.builder()
                .queueId(2L)
                .recipient("user@example.com")
                .subject("Email 2")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        EmailLog log3 = EmailLog.builder()
                .queueId(3L)
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
                .queueId(1L)
                .recipient("user1@example.com")
                .subject("Success1")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        EmailLog success2 = EmailLog.builder()
                .queueId(2L)
                .recipient("user2@example.com")
                .subject("Success2")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        EmailLog failed = EmailLog.builder()
                .queueId(3L)
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
                .queueId(1L)
                .recipient("user1@example.com")
                .subject("Event")
                .status("SUCCESS")
                .sendMethod("EVENT")
                .emailType("TEST")
                .sentTime(LocalDateTime.now())
                .build();

        EmailLog scheduledLog = EmailLog.builder()
                .queueId(2L)
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
                .queueId(1L)
                .recipient("user1@example.com")
                .subject("Email 1")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        EmailLog log2 = EmailLog.builder()
                .queueId(2L)
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
}
