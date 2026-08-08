package org.dddml.email.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.email.config.MailProperties;
import org.dddml.email.entity.EmailLog;
import org.dddml.email.entity.EmailQueue;
import org.dddml.email.repository.EmailQueueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailDeliveryService {

    private final EmailQueueRepository emailQueueRepository;
    private final EmailService emailService;
    private final MailProperties mailProperties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryOutcome deliver(Long queueId, String sendMethod) {
        EmailQueue emailQueue = emailQueueRepository.findById(queueId).orElse(null);
        if (emailQueue == null || !"PROCESSING".equals(emailQueue.getStatus())) {
            return DeliveryOutcome.SKIPPED;
        }

        EmailLog emailLog = emailService.sendEmailDirectly(emailQueue, sendMethod);
        if ("SUCCESS".equals(emailLog.getStatus())) {
            emailQueue.markAsCompleted();
            emailQueueRepository.save(emailQueue);
            log.info("Email delivery completed [ID={}, method={}]", queueId, sendMethod);
            return DeliveryOutcome.SUCCESS;
        }

        if (emailQueue.getRetryCount() < emailQueue.getMaxRetries()) {
            emailQueue.incrementRetry(mailProperties.getRetry().getDelayMinutes());
            log.warn(
                "Email delivery scheduled for retry [ID={}, retry={}/{}]",
                queueId,
                emailQueue.getRetryCount(),
                emailQueue.getMaxRetries()
            );
        } else {
            emailQueue.markAsFailed(emailLog.getErrorMessage());
            log.error("Email delivery permanently failed [ID={}]", queueId);
        }
        emailQueueRepository.save(emailQueue);
        return DeliveryOutcome.FAILED;
    }

    public enum DeliveryOutcome {
        SUCCESS,
        FAILED,
        SKIPPED
    }
}
