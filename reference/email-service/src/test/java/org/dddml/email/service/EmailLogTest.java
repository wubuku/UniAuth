package org.dddml.email.service;

import org.junit.jupiter.api.Test;
import org.dddml.email.entity.EmailLog;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class EmailLogTest {

    @Test
    void testEmailLogBuilder() {
        LocalDateTime now = LocalDateTime.now();

        EmailLog emailLog = EmailLog.builder()
                .id(1L)
                .queueId(100L)
                .recipient("test@example.com")
                .subject("Test Subject")
                .status("SUCCESS")
                .emailContent("<p>Test</p>")
                .emailType("TEST")
                .mailProvider("Gmail")
                .retryCount(0)
                .sendMethod("EVENT")
                .durationMs(150L)
                .sentTime(now)
                .build();

        assertNotNull(emailLog);
        assertEquals(1L, emailLog.getId());
        assertEquals(100L, emailLog.getQueueId());
        assertEquals("test@example.com", emailLog.getRecipient());
        assertEquals("Test Subject", emailLog.getSubject());
        assertEquals("SUCCESS", emailLog.getStatus());
        assertEquals("<p>Test</p>", emailLog.getEmailContent());
        assertEquals("TEST", emailLog.getEmailType());
        assertEquals("Gmail", emailLog.getMailProvider());
        assertEquals(0, emailLog.getRetryCount());
        assertEquals("EVENT", emailLog.getSendMethod());
        assertEquals(150L, emailLog.getDurationMs());
        assertEquals(now, emailLog.getSentTime());
    }

    @Test
    void testEmailLogFailedStatus() {
        EmailLog emailLog = EmailLog.builder()
                .recipient("test@example.com")
                .subject("Test Subject")
                .status("FAILED")
                .errorMessage("SMTP connection timeout")
                .retryCount(2)
                .sendMethod("SCHEDULED")
                .build();

        assertEquals("FAILED", emailLog.getStatus());
        assertEquals("SMTP connection timeout", emailLog.getErrorMessage());
        assertEquals(2, emailLog.getRetryCount());
        assertEquals("SCHEDULED", emailLog.getSendMethod());
    }

    @Test
    void testEmailLogSetters() {
        EmailLog emailLog = new EmailLog();
        emailLog.setId(2L);
        emailLog.setQueueId(200L);
        emailLog.setRecipient("user@example.com");
        emailLog.setSubject("Another Subject");
        emailLog.setStatus("SUCCESS");
        emailLog.setDurationMs(200L);

        assertEquals(2L, emailLog.getId());
        assertEquals(200L, emailLog.getQueueId());
        assertEquals("user@example.com", emailLog.getRecipient());
        assertEquals("Another Subject", emailLog.getSubject());
        assertEquals("SUCCESS", emailLog.getStatus());
        assertEquals(200L, emailLog.getDurationMs());
    }

    @Test
    void entityStringDoesNotExposeRecipientOrEmailContent() {
        EmailLog emailLog = EmailLog.builder()
                .id(3L)
                .recipient("sensitive@example.test")
                .subject("Sensitive subject")
                .emailContent("<p>verification-code-246810</p>")
                .build();

        String result = emailLog.toString();

        assertFalse(result.contains("sensitive@example.test"));
        assertFalse(result.contains("verification-code-246810"));
    }
}
