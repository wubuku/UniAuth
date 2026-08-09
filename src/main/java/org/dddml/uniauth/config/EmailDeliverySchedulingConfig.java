package org.dddml.uniauth.config;

import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.service.EmailDeliveryOutboxProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.email.delivery.worker-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class EmailDeliverySchedulingConfig {

    private final EmailDeliveryOutboxProcessor processor;

    @Scheduled(
        fixedDelayString = "${app.email.delivery.worker-delay-ms:1000}"
    )
    public void processOutbox() {
        processor.processAvailable();
    }
}
