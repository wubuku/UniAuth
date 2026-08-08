package org.dddml.email.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class EmailQueuedEvent extends ApplicationEvent {

    private final Long queueId;

    public EmailQueuedEvent(Object source, Long queueId) {
        super(source);
        this.queueId = queueId;
    }
}
