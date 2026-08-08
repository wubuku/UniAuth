package org.dddml.email.event;

import lombok.extern.slf4j.Slf4j;
import org.dddml.email.config.MailProperties;
import lombok.RequiredArgsConstructor;
import org.dddml.email.service.EmailDeliveryService;
import org.dddml.email.service.EmailQueueClaimService;
import org.dddml.email.service.EmailRateLimiter;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class EmailEventListener {

    private final MailProperties mailProperties;
    private final EmailRateLimiter rateLimiter;
    private final EmailQueueClaimService claimService;
    private final EmailDeliveryService deliveryService;

    @Async("emailExecutor")
    @TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true
    )
    public void handleEmailQueuedEvent(EmailQueuedEvent event) {
        if (!mailProperties.getQueue().isEventDriven()) {
            return;
        }

        try {
            if (!rateLimiter.tryAcquire()) {
                log.warn("Rate limit reached [ID={}], handing to scheduled task", event.getQueueId());
                return;
            }

            if (!claimService.claimPending(event.getQueueId(), LocalDateTime.now())) {
                rateLimiter.release();
                log.debug(
                    "Email already processed by another thread, skipping [ID={}]",
                    event.getQueueId()
                );
                return;
            }

            EmailDeliveryService.DeliveryOutcome outcome =
                deliveryService.deliver(event.getQueueId(), "EVENT");
            if (outcome == EmailDeliveryService.DeliveryOutcome.SKIPPED) {
                rateLimiter.release();
            }

        } catch (Exception exception) {
            log.error(
                "Event-driven send failed [ID={}, error={}]",
                event.getQueueId(),
                exception.getClass().getSimpleName()
            );
        }
    }
}
