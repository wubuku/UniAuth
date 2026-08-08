package org.dddml.email.service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.dddml.email.config.MailProperties;
import org.dddml.email.entity.EmailQueue;
import org.dddml.email.repository.EmailQueueRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmailProcessorService {

    private final EmailQueueRepository emailQueueRepository;
    private final EmailQueueClaimService claimService;
    private final EmailDeliveryService deliveryService;
    private final EmailRateLimiter rateLimiter;
    private final MailProperties mailProperties;

    @Scheduled(fixedDelayString = "#{${app.mail.recovery.scan-interval-minutes:5} * 60000L}",
               initialDelay = 60000)
    public void recoverFailedEmails() {
        if (!mailProperties.isEnabled()
                || !mailProperties.getQueue().isEnabled()
                || !mailProperties.getRecovery().isEnabled()) {
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime stuckTime = now.minusMinutes(
                mailProperties.getRecovery().getStuckTimeoutMinutes()
            );

            List<EmailQueue> failedEmails = emailQueueRepository.findFailedOrStuckEmails(
                now, stuckTime, PageRequest.of(0, 50)
            ).getContent();

            if (failedEmails.isEmpty()) {
                log.debug("Recovery scan complete, no emails to process");
                return;
            }

            log.info("Recovery scan found {} failed emails", failedEmails.size());

            int successCount = 0, failedCount = 0;

            for (EmailQueue emailQueue : failedEmails) {
                try {
                    if (!rateLimiter.tryAcquire()) {
                        log.warn("Recovery rate limit reached; remaining candidates stay pending");
                        break;
                    }

                    LocalDateTime claimTime = LocalDateTime.now();
                    if (!claimService.claimRecoverable(
                            emailQueue.getId(),
                            claimTime,
                            stuckTime)) {
                        rateLimiter.release();
                        log.debug("Email already handled by event, skipping [ID={}]", emailQueue.getId());
                        continue;
                    }

                    log.info("Recovering email [ID={}]", emailQueue.getId());
                    EmailDeliveryService.DeliveryOutcome outcome =
                        deliveryService.deliver(emailQueue.getId(), "SCHEDULED");
                    if (outcome == EmailDeliveryService.DeliveryOutcome.SUCCESS) {
                        successCount++;
                    } else if (outcome == EmailDeliveryService.DeliveryOutcome.FAILED) {
                        failedCount++;
                    } else {
                        rateLimiter.release();
                    }

                } catch (Exception exception) {
                    log.error(
                        "Recovery send failed [ID={}, error={}]",
                        emailQueue.getId(),
                        exception.getClass().getSimpleName()
                    );
                    failedCount++;
                }
            }

            log.info("Recovery scan complete - success: {}, failed: {}", successCount, failedCount);

        } catch (Exception exception) {
            log.error(
                "Recovery scan error [error={}]",
                exception.getClass().getSimpleName()
            );
        }
    }

}
