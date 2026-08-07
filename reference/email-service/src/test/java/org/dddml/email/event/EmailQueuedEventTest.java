package org.dddml.email.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmailQueuedEventTest {

    @Test
    void testEventCreation() {
        Object source = new Object();
        EmailQueuedEvent event = new EmailQueuedEvent(source, 123L, "test@example.com", "Test Subject");

        assertEquals(123L, event.getQueueId());
        assertEquals("test@example.com", event.getRecipient());
        assertEquals("Test Subject", event.getSubject());
        assertEquals(source, event.getSource());
        assertNotNull(event.getTimestamp());
    }

    @Test
    void testEventToString() {
        EmailQueuedEvent event = new EmailQueuedEvent(this, 1L, "test@example.com", "Subject");

        String result = event.toString();

        assertTrue(result.contains("1"));
        assertTrue(result.contains("test@example.com"));
        assertTrue(result.contains("Subject"));
    }
}
