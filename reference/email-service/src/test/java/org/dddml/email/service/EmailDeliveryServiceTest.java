package org.dddml.email.service;

import org.dddml.email.config.MailProperties;
import org.dddml.email.entity.EmailLog;
import org.dddml.email.entity.EmailQueue;
import org.dddml.email.repository.EmailQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailDeliveryServiceTest {

    @Mock
    private EmailQueueRepository emailQueueRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private MailProperties mailProperties;

    @Mock
    private MailProperties.Retry retryConfig;

    @InjectMocks
    private EmailDeliveryService deliveryService;

    private EmailQueue queue;

    @BeforeEach
    void setUp() {
        queue = EmailQueue.builder()
            .id(1L)
            .recipient("test@example.com")
            .subject("Subject")
            .htmlContent("<p>Content</p>")
            .status("PROCESSING")
            .retryCount(0)
            .maxRetries(3)
            .build();
        when(emailQueueRepository.findById(1L)).thenReturn(Optional.of(queue));
    }

    @Test
    void successfulDeliveryMarksTheQueueCompleted() {
        queue.setNextRetryTime(LocalDateTime.now().plusMinutes(10));
        queue.setErrorMessage("stale failure");
        queue.setMetadata("{\"verificationCode\":\"123456\"}");
        when(emailService.sendEmailDirectly(queue, "EVENT"))
            .thenReturn(EmailLog.builder().status("SUCCESS").build());

        assertThat(deliveryService.deliver(1L, "EVENT"))
            .isEqualTo(EmailDeliveryService.DeliveryOutcome.SUCCESS);
        assertThat(queue.getStatus()).isEqualTo("COMPLETED");
        assertThat(queue.getProcessedTime()).isNotNull();
        assertThat(queue.getNextRetryTime()).isNull();
        assertThat(queue.getErrorMessage()).isNull();
        assertThat(queue.getHtmlContent())
            .isEqualTo(EmailQueue.REDACTED_HTML_CONTENT);
        assertThat(queue.getMetadata()).isNull();
        verify(emailQueueRepository).save(queue);
    }

    @Test
    void failedDeliverySchedulesTheExistingRetryPolicy() {
        when(mailProperties.getRetry()).thenReturn(retryConfig);
        when(retryConfig.getDelayMinutes()).thenReturn(10);
        when(emailService.sendEmailDirectly(queue, "EVENT"))
            .thenReturn(EmailLog.builder().status("FAILED").errorMessage("SMTP failed").build());

        assertThat(deliveryService.deliver(1L, "EVENT"))
            .isEqualTo(EmailDeliveryService.DeliveryOutcome.FAILED);
        assertThat(queue.getStatus()).isEqualTo("PENDING");
        assertThat(queue.getRetryCount()).isEqualTo(1);
        assertThat(queue.getNextRetryTime()).isNotNull();
        assertThat(queue.getProcessedTime()).isNull();
        assertThat(queue.getErrorMessage()).isNull();
        assertThat(queue.getHtmlContent()).isEqualTo("<p>Content</p>");
    }

    @Test
    void exhaustedDeliveryMarksTheQueueFailed() {
        queue.setRetryCount(3);
        queue.setNextRetryTime(LocalDateTime.now().plusMinutes(10));
        queue.setMetadata("{\"verificationCode\":\"654321\"}");
        when(emailService.sendEmailDirectly(queue, "SCHEDULED"))
            .thenReturn(EmailLog.builder().status("FAILED").errorMessage("SMTP failed").build());

        assertThat(deliveryService.deliver(1L, "SCHEDULED"))
            .isEqualTo(EmailDeliveryService.DeliveryOutcome.FAILED);
        assertThat(queue.getStatus()).isEqualTo("FAILED");
        assertThat(queue.getErrorMessage()).isEqualTo("SMTP failed");
        assertThat(queue.getProcessedTime()).isNotNull();
        assertThat(queue.getNextRetryTime()).isNull();
        assertThat(queue.getHtmlContent())
            .isEqualTo(EmailQueue.REDACTED_HTML_CONTENT);
        assertThat(queue.getMetadata()).isNull();
    }
}
