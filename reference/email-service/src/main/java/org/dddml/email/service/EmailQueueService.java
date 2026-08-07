package org.dddml.email.service;

import org.dddml.email.entity.EmailQueue;
import org.dddml.email.event.EmailQueuedEvent;
import org.dddml.email.repository.EmailQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailQueueService {

    private static final Logger log = LoggerFactory.getLogger(EmailQueueService.class);

    @Autowired
    private EmailQueueRepository emailQueueRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Transactional
    public EmailQueue enqueue(String recipient, String subject, String htmlContent,
                             String emailType, Integer priority) {
        EmailQueue emailQueue = EmailQueue.builder()
                .recipient(recipient)
                .subject(subject)
                .htmlContent(htmlContent)
                .emailType(emailType != null ? emailType : "GENERAL")
                .status("PENDING")
                .priority(priority != null ? priority : 5)
                .retryCount(0)
                .maxRetries(3)
                .build();

        EmailQueue saved = emailQueueRepository.save(emailQueue);
        log.info("Email enqueued [ID={}]: {}", saved.getId(), recipient);

        try {
            EmailQueuedEvent event = new EmailQueuedEvent(
                this, saved.getId(), saved.getRecipient(), saved.getSubject()
            );
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            log.error("Event publish failed [ID={}], scheduled task will handle", saved.getId());
        }

        return saved;
    }

    public EmailQueue enqueue(String recipient, String subject, String htmlContent, String emailType) {
        return enqueue(recipient, subject, htmlContent, emailType, 5);
    }

    public QueueStats getStats() {
        return new QueueStats(
            emailQueueRepository.countByStatus("PENDING"),
            emailQueueRepository.countByStatus("PROCESSING"),
            emailQueueRepository.countByStatus("COMPLETED"),
            emailQueueRepository.countByStatus("FAILED")
        );
    }

    public java.util.Optional<EmailQueue> findById(Long id) {
        return emailQueueRepository.findById(id);
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class QueueStats {
        private long pending;
        private long processing;
        private long completed;
        private long failed;
    }
}
