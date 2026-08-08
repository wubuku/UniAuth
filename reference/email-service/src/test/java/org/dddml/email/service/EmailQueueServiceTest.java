package org.dddml.email.service;

import org.dddml.email.entity.EmailQueue;
import org.dddml.email.event.EmailQueuedEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class EmailQueueServiceTest {

    @Test
    void testQueueStats_Creation() {
        EmailQueueService.QueueStats stats = new EmailQueueService.QueueStats(10, 2, 100, 5);

        assertEquals(10, stats.getPending());
        assertEquals(2, stats.getProcessing());
        assertEquals(100, stats.getCompleted());
        assertEquals(5, stats.getFailed());
    }

    @Test
    void testEmailQueue_Builder() {
        EmailQueue queue = EmailQueue.builder()
                .id(1L)
                .recipient("test@example.com")
                .subject("Test Subject")
                .htmlContent("<p>Test Content</p>")
                .emailType("TEST")
                .status("PENDING")
                .priority(5)
                .retryCount(0)
                .maxRetries(3)
                .build();

        assertNotNull(queue);
        assertEquals(1L, queue.getId());
        assertEquals("test@example.com", queue.getRecipient());
        assertEquals("Test Subject", queue.getSubject());
        assertEquals("PENDING", queue.getStatus());
        assertEquals("TEST", queue.getEmailType());
        assertEquals(5, queue.getPriority());
        assertEquals(0, queue.getRetryCount());
        assertEquals(3, queue.getMaxRetries());
    }

    @Test
    void testEmailQueue_DefaultValues() {
        EmailQueue queue = EmailQueue.builder()
                .recipient("test@example.com")
                .subject("Test")
                .htmlContent("Content")
                .build();

        assertNotNull(queue);
        assertEquals("GENERAL", queue.getEmailType());
        assertEquals("PENDING", queue.getStatus());
        assertEquals(5, queue.getPriority());
        assertEquals(0, queue.getRetryCount());
        assertEquals(3, queue.getMaxRetries());
    }

    @Test
    void testEmailQueuedEvent_Creation() {
        EmailQueuedEvent event = new EmailQueuedEvent(this, 123L);

        assertEquals(123L, event.getQueueId());
        assertNotNull(event.getTimestamp());
    }

    @Test
    void testEmailQueue_Timestamps() {
        LocalDateTime now = LocalDateTime.now();

        EmailQueue queue = EmailQueue.builder()
                .recipient("test@example.com")
                .subject("Test")
                .htmlContent("Content")
                .createdTime(now)
                .updatedTime(now)
                .build();

        assertEquals(now, queue.getCreatedTime());
        assertEquals(now, queue.getUpdatedTime());
    }
}
