package org.dddml.email.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmailQueuedEventTest {

    @Test
    void testEventCreation() {
        Object source = new Object();
        EmailQueuedEvent event = new EmailQueuedEvent(source, 123L);

        assertEquals(123L, event.getQueueId());
        assertEquals(source, event.getSource());
        assertNotNull(event.getTimestamp());
    }

    @Test
    void eventStringDoesNotContainRecipientOrSubjectData() {
        EmailQueuedEvent event = new EmailQueuedEvent(this, 1L);

        String result = event.toString();

        assertFalse(result.contains("test@example.com"));
        assertFalse(result.contains("Sensitive subject"));
    }
}
