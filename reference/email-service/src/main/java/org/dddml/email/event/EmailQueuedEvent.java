package org.dddml.email.event;

import lombok.Getter;
import lombok.ToString;
import org.springframework.context.ApplicationEvent;

@Getter
@ToString
public class EmailQueuedEvent extends ApplicationEvent {

    private final Long queueId;
    private final String recipient;
    private final String subject;

    public EmailQueuedEvent(Object source, Long queueId, String recipient, String subject) {
        super(source);
        this.queueId = queueId;
        this.recipient = recipient;
        this.subject = subject;
    }
}
