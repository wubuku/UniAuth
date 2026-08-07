package org.dddml.email.event;

import org.dddml.email.config.MailProperties;
import org.dddml.email.entity.EmailLog;
import org.dddml.email.entity.EmailQueue;
import org.dddml.email.repository.EmailQueueRepository;
import org.dddml.email.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailEventListenerTest {

    private static class TestEventSource {}

    @Mock
    private EmailQueueRepository emailQueueRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private MailProperties mailProperties;

    @Mock
    private MailProperties.Queue queueConfig;

    @Mock
    private MailProperties.RateLimit rateLimitConfig;

    @Mock
    private MailProperties.Retry retryConfig;

    @InjectMocks
    private EmailEventListener emailEventListener;

    private EmailQueue testQueue;
    private EmailQueuedEvent testEvent;
    private EmailLog successLog;

    @BeforeEach
    void setUp() {
        testQueue = EmailQueue.builder()
                .id(1L)
                .recipient("test@example.com")
                .subject("Test Subject")
                .htmlContent("<p>Test Content</p>")
                .emailType("TEST")
                .status("PENDING")
                .priority(5)
                .retryCount(0)
                .maxRetries(3)
                .build();

        testEvent = new EmailQueuedEvent(new TestEventSource(), 1L, "test@example.com", "Test Subject");

        successLog = EmailLog.builder()
                .id(1L)
                .queueId(1L)
                .recipient("test@example.com")
                .subject("Test Subject")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        when(mailProperties.getQueue()).thenReturn(queueConfig);
        when(mailProperties.getRateLimit()).thenReturn(rateLimitConfig);
        when(mailProperties.getRetry()).thenReturn(retryConfig);
        when(retryConfig.getDelayMinutes()).thenReturn(10);
    }

    @Test
    void testHandleEmailQueuedEvent_EventDrivenDisabled() {
        when(queueConfig.isEventDriven()).thenReturn(false);

        emailEventListener.handleEmailQueuedEvent(testEvent);

        verify(emailQueueRepository, never()).findById(any());
        verify(emailService, never()).sendEmailDirectly(any(), any());
    }

    @Test
    void testHandleEmailQueuedEvent_QueueNotFound() {
        when(queueConfig.isEventDriven()).thenReturn(true);
        when(rateLimitConfig.isEnabled()).thenReturn(false);
        when(emailQueueRepository.findById(1L)).thenReturn(Optional.empty());

        emailEventListener.handleEmailQueuedEvent(testEvent);

        verify(emailQueueRepository).findById(1L);
        verify(emailService, never()).sendEmailDirectly(any(), any());
    }

    @Test
    void testHandleEmailQueuedEvent_NotPendingStatus() {
        when(queueConfig.isEventDriven()).thenReturn(true);
        when(rateLimitConfig.isEnabled()).thenReturn(false);
        testQueue.setStatus("PROCESSING");
        when(emailQueueRepository.findById(1L)).thenReturn(Optional.of(testQueue));

        emailEventListener.handleEmailQueuedEvent(testEvent);

        verify(emailQueueRepository, never()).updateStatusToProcessing(anyLong(), any());
        verify(emailService, never()).sendEmailDirectly(any(), any());
    }

    @Test
    void testHandleEmailQueuedEvent_AlreadyProcessedByAnotherThread() {
        when(queueConfig.isEventDriven()).thenReturn(true);
        when(rateLimitConfig.isEnabled()).thenReturn(false);
        when(emailQueueRepository.findById(1L)).thenReturn(Optional.of(testQueue));
        when(emailQueueRepository.updateStatusToProcessing(eq(1L), any())).thenReturn(0);

        emailEventListener.handleEmailQueuedEvent(testEvent);

        verify(emailQueueRepository).updateStatusToProcessing(eq(1L), any());
        verify(emailService, never()).sendEmailDirectly(any(), any());
    }

    @Test
    void testHandleEmailQueuedEvent_Success() {
        when(queueConfig.isEventDriven()).thenReturn(true);
        when(rateLimitConfig.isEnabled()).thenReturn(false);
        when(emailQueueRepository.findById(1L)).thenReturn(Optional.of(testQueue));
        when(emailQueueRepository.updateStatusToProcessing(eq(1L), any())).thenReturn(1);
        when(emailService.sendEmailDirectly(any(), eq("EVENT"))).thenReturn(successLog);

        emailEventListener.handleEmailQueuedEvent(testEvent);

        verify(emailQueueRepository, times(2)).findById(1L);
        verify(emailService).sendEmailDirectly(any(), eq("EVENT"));
        verify(emailQueueRepository).save(any());
        assertEquals("COMPLETED", testQueue.getStatus());
    }

    @Test
    void testHandleEmailQueuedEvent_FailureWithRetry() {
        when(queueConfig.isEventDriven()).thenReturn(true);
        when(rateLimitConfig.isEnabled()).thenReturn(false);
        when(emailQueueRepository.findById(1L)).thenReturn(Optional.of(testQueue));
        when(emailQueueRepository.updateStatusToProcessing(eq(1L), any())).thenReturn(1);

        EmailLog failedLog = EmailLog.builder()
                .id(1L)
                .queueId(1L)
                .status("FAILED")
                .errorMessage("SMTP error")
                .build();
        when(emailService.sendEmailDirectly(any(), eq("EVENT"))).thenReturn(failedLog);

        emailEventListener.handleEmailQueuedEvent(testEvent);

        verify(emailService).sendEmailDirectly(any(), eq("EVENT"));
        verify(emailQueueRepository).save(any());
        assertEquals(1, testQueue.getRetryCount());
        assertEquals("PENDING", testQueue.getStatus());
    }

    @Test
    void testHandleEmailQueuedEvent_PermanentFailure() {
        when(queueConfig.isEventDriven()).thenReturn(true);
        when(rateLimitConfig.isEnabled()).thenReturn(false);
        testQueue.setRetryCount(3);
        when(emailQueueRepository.findById(1L)).thenReturn(Optional.of(testQueue));
        when(emailQueueRepository.updateStatusToProcessing(eq(1L), any())).thenReturn(1);

        EmailLog failedLog = EmailLog.builder()
                .id(1L)
                .queueId(1L)
                .status("FAILED")
                .errorMessage("SMTP error")
                .build();
        when(emailService.sendEmailDirectly(any(), eq("EVENT"))).thenReturn(failedLog);

        emailEventListener.handleEmailQueuedEvent(testEvent);

        verify(emailService).sendEmailDirectly(any(), eq("EVENT"));
        verify(emailQueueRepository).save(any());
        assertEquals("FAILED", testQueue.getStatus());
        assertEquals("SMTP error", testQueue.getErrorMessage());
    }

    @Test
    void testHandleEmailQueuedEvent_RateLimitExceeded() {
        when(queueConfig.isEventDriven()).thenReturn(true);
        when(rateLimitConfig.isEnabled()).thenReturn(true);
        when(rateLimitConfig.getMaxPerMinute()).thenReturn(60);
        when(emailQueueRepository.findById(1L)).thenReturn(Optional.of(testQueue));

        emailEventListener.handleEmailQueuedEvent(testEvent);

        verify(emailService, never()).sendEmailDirectly(any(), any());
    }

    @Test
    void testHandleEmailQueuedEvent_RateLimitDisabled() {
        when(queueConfig.isEventDriven()).thenReturn(true);
        when(rateLimitConfig.isEnabled()).thenReturn(false);
        when(emailQueueRepository.findById(1L)).thenReturn(Optional.of(testQueue));
        when(emailQueueRepository.updateStatusToProcessing(eq(1L), any())).thenReturn(1);
        when(emailService.sendEmailDirectly(any(), eq("EVENT"))).thenReturn(successLog);

        emailEventListener.handleEmailQueuedEvent(testEvent);

        verify(emailService).sendEmailDirectly(any(), eq("EVENT"));
    }
}
