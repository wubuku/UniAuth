package org.dddml.email.service;

import lombok.extern.slf4j.Slf4j;
import org.dddml.email.config.MailProperties;
import org.dddml.email.entity.EmailLog;
import org.dddml.email.entity.EmailQueue;
import org.dddml.email.repository.EmailQueueRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class EmailProcessorService {

    @Autowired
    private EmailQueueRepository emailQueueRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private MailProperties mailProperties;

    @Scheduled(fixedDelayString = "#{${app.mail.recovery.scan-interval-minutes:5} * 60000}",
               initialDelay = 60000)
    @Transactional
    public void recoverFailedEmails() {
        if (!mailProperties.getRecovery().isEnabled()) {
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
                    int updated = emailQueueRepository.updateStatusToProcessing(
                        emailQueue.getId(), LocalDateTime.now());

                    if (updated == 0) {
                        log.debug("Email already handled by event, skipping [ID={}]", emailQueue.getId());
                        continue;
                    }

                    log.info("Recovering email [ID={}]: {}", emailQueue.getId(), emailQueue.getRecipient());

                    emailQueue = emailQueueRepository.findById(emailQueue.getId()).orElse(null);
                    if (emailQueue == null) continue;

                    EmailLog emailLog = emailService.sendEmailDirectly(emailQueue, "SCHEDULED");

                    if ("SUCCESS".equals(emailLog.getStatus())) {
                        emailQueue.markAsCompleted();
                        successCount++;
                    } else {
                        handleFailure(emailQueue);
                        failedCount++;
                    }

                    emailQueueRepository.save(emailQueue);

                } catch (Exception e) {
                    log.error("Recovery send failed [ID={}]: {}", emailQueue.getId(), e.getMessage());
                    failedCount++;
                }
            }

            log.info("Recovery scan complete - success: {}, failed: {}", successCount, failedCount);

        } catch (Exception e) {
            log.error("Recovery scan error", e);
        }
    }

    private void handleFailure(EmailQueue emailQueue) {
        if (emailQueue.getRetryCount() < emailQueue.getMaxRetries()) {
            emailQueue.incrementRetry(mailProperties.getRetry().getDelayMinutes());
            log.warn("Email will retry [ID={}, retry: {}/{}]",
                     emailQueue.getId(), emailQueue.getRetryCount(),
                     emailQueue.getMaxRetries());
        } else {
            emailQueue.markAsFailed("Max retry attempts exceeded");
            log.error("Email permanently failed [ID={}]", emailQueue.getId());
        }
    }
}
