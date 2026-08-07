package org.dddml.email.event;

import lombok.extern.slf4j.Slf4j;
import org.dddml.email.config.MailProperties;
import org.dddml.email.entity.EmailLog;
import org.dddml.email.entity.EmailQueue;
import org.dddml.email.repository.EmailQueueRepository;
import org.dddml.email.service.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class EmailEventListener {

    @Autowired
    private EmailQueueRepository emailQueueRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private MailProperties mailProperties;

    private final AtomicInteger sentInLastMinute = new AtomicInteger(0);
    private LocalDateTime lastResetTime = LocalDateTime.now();

    @Async("emailExecutor")
    @TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleEmailQueuedEvent(EmailQueuedEvent event) {
        if (!mailProperties.getQueue().isEventDriven()) {
            return;
        }

        try {
            resetRateLimiterIfNeeded();

            if (!checkRateLimit()) {
                log.warn("Rate limit reached [ID={}], handing to scheduled task", event.getQueueId());
                return;
            }

            EmailQueue emailQueue = emailQueueRepository.findById(event.getQueueId())
                    .orElse(null);

            if (emailQueue == null) {
                log.error("Email queue record not found [ID={}]", event.getQueueId());
                return;
            }

            if (!"PENDING".equals(emailQueue.getStatus())) {
                return;
            }

            int updated = emailQueueRepository.updateStatusToProcessing(
                emailQueue.getId(), LocalDateTime.now());

            if (updated == 0) {
                log.debug("Email already processed by another thread, skipping [ID={}]", emailQueue.getId());
                return;
            }

            log.info("Sending email via event-driven [ID={}]: {}", emailQueue.getId(), emailQueue.getRecipient());

            emailQueue = emailQueueRepository.findById(emailQueue.getId()).orElse(null);
            if (emailQueue == null) {
                return;
            }

            EmailLog emailLog = emailService.sendEmailDirectly(emailQueue, "EVENT");

            if ("SUCCESS".equals(emailLog.getStatus())) {
                emailQueue.markAsCompleted();
                log.info("Event-driven send successful [ID={}]", emailQueue.getId());
            } else {
                handleFailure(emailQueue, emailLog.getErrorMessage());
            }

            emailQueueRepository.save(emailQueue);
            sentInLastMinute.incrementAndGet();

        } catch (Exception e) {
            log.error("Event-driven send failed [ID={}]: {}", event.getQueueId(), e.getMessage(), e);
        }
    }

    private void handleFailure(EmailQueue emailQueue, String errorMessage) {
        if (emailQueue.getRetryCount() < emailQueue.getMaxRetries()) {
            emailQueue.incrementRetry(mailProperties.getRetry().getDelayMinutes());
            log.warn("Send failed, will retry in {} minutes [ID={}, retry: {}/{}]",
                     mailProperties.getRetry().getDelayMinutes(),
                     emailQueue.getId(), emailQueue.getRetryCount(), emailQueue.getMaxRetries());
        } else {
            emailQueue.markAsFailed(errorMessage);
            log.error("Email permanently failed [ID={}]", emailQueue.getId());
        }
    }

    private boolean checkRateLimit() {
        if (!mailProperties.getRateLimit().isEnabled()) {
            return true;
        }
        return sentInLastMinute.get() < mailProperties.getRateLimit().getMaxPerMinute();
    }

    private void resetRateLimiterIfNeeded() {
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(lastResetTime.plusMinutes(1))) {
            sentInLastMinute.set(0);
            lastResetTime = now;
        }
    }
}
