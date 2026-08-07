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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailProcessorServiceTest {

    @Mock
    private EmailQueueRepository emailQueueRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private MailProperties mailProperties;

    @Mock
    private MailProperties.Recovery recoveryConfig;

    @Mock
    private MailProperties.Retry retryConfig;

    @InjectMocks
    private EmailProcessorService emailProcessorService;

    private EmailQueue testQueue;
    private EmailLog testLog;

    @BeforeEach
    void setUp() {
        testQueue = EmailQueue.builder()
                .id(1L)
                .recipient("test@example.com")
                .subject("Test Subject")
                .htmlContent("<p>Test Content</p>")
                .emailType("TEST")
                .status("FAILED")
                .priority(5)
                .retryCount(1)
                .maxRetries(3)
                .build();

        testLog = EmailLog.builder()
                .id(1L)
                .queueId(1L)
                .recipient("test@example.com")
                .subject("Test Subject")
                .status("SUCCESS")
                .emailType("TEST")
                .build();

        when(mailProperties.getRecovery()).thenReturn(recoveryConfig);
        when(mailProperties.getRetry()).thenReturn(retryConfig);
        when(recoveryConfig.getStuckTimeoutMinutes()).thenReturn(10);
        when(retryConfig.getDelayMinutes()).thenReturn(10);
    }

    private Page<EmailQueue> toPage(List<EmailQueue> list) {
        return new PageImpl<>(list, PageRequest.of(0, 50), list.size());
    }

    @Test
    void testRecoverFailedEmails_RecoveryDisabled() {
        when(recoveryConfig.isEnabled()).thenReturn(false);

        emailProcessorService.recoverFailedEmails();

        verify(emailQueueRepository, never()).findFailedOrStuckEmails(any(), any(), any());
    }

    @Test
    void testRecoverFailedEmails_NoEmailsToProcess() {
        when(recoveryConfig.isEnabled()).thenReturn(true);
        when(emailQueueRepository.findFailedOrStuckEmails(any(), any(), any(PageRequest.class)))
                .thenReturn(toPage(Collections.emptyList()));

        emailProcessorService.recoverFailedEmails();

        verify(emailQueueRepository).findFailedOrStuckEmails(any(), any(), any(PageRequest.class));
        verify(emailService, never()).sendEmailDirectly(any(), any());
    }

    @Test
    void testRecoverFailedEmails_SuccessRecovery() {
        when(recoveryConfig.isEnabled()).thenReturn(true);
        when(emailQueueRepository.findFailedOrStuckEmails(any(), any(), any(PageRequest.class)))
                .thenReturn(toPage(List.of(testQueue)));
        when(emailQueueRepository.updateStatusToProcessing(eq(1L), any())).thenReturn(1);
        when(emailQueueRepository.findById(1L)).thenReturn(Optional.of(testQueue));
        when(emailService.sendEmailDirectly(any(), eq("SCHEDULED"))).thenReturn(testLog);

        emailProcessorService.recoverFailedEmails();

        verify(emailQueueRepository).findFailedOrStuckEmails(any(), any(), any(PageRequest.class));
        verify(emailQueueRepository).updateStatusToProcessing(eq(1L), any());
        verify(emailService).sendEmailDirectly(any(), eq("SCHEDULED"));
    }

    @Test
    void testRecoverFailedEmails_EmailAlreadyHandled() {
        when(recoveryConfig.isEnabled()).thenReturn(true);
        when(emailQueueRepository.findFailedOrStuckEmails(any(), any(), any(PageRequest.class)))
                .thenReturn(toPage(List.of(testQueue)));
        when(emailQueueRepository.updateStatusToProcessing(eq(1L), any())).thenReturn(0);

        emailProcessorService.recoverFailedEmails();

        verify(emailQueueRepository).findFailedOrStuckEmails(any(), any(), any(PageRequest.class));
        verify(emailService, never()).sendEmailDirectly(any(), any());
    }

    @Test
    void testRecoverFailedEmails_MultipleEmails() {
        when(recoveryConfig.isEnabled()).thenReturn(true);

        EmailQueue queue1 = EmailQueue.builder().id(1L).recipient("user1@example.com")
                .subject("Subject1").htmlContent("Content1").status("FAILED")
                .retryCount(1).maxRetries(3).build();
        EmailQueue queue2 = EmailQueue.builder().id(2L).recipient("user2@example.com")
                .subject("Subject2").htmlContent("Content2").status("STUCK")
                .retryCount(2).maxRetries(3).build();

        when(emailQueueRepository.findFailedOrStuckEmails(any(), any(), any(PageRequest.class)))
                .thenReturn(toPage(List.of(queue1, queue2)));
        when(emailQueueRepository.updateStatusToProcessing(eq(1L), any())).thenReturn(1);
        when(emailQueueRepository.updateStatusToProcessing(eq(2L), any())).thenReturn(1);
        when(emailQueueRepository.findById(1L)).thenReturn(Optional.of(queue1));
        when(emailQueueRepository.findById(2L)).thenReturn(Optional.of(queue2));
        when(emailService.sendEmailDirectly(any(), eq("SCHEDULED"))).thenReturn(testLog);

        emailProcessorService.recoverFailedEmails();

        verify(emailQueueRepository, times(2)).updateStatusToProcessing(anyLong(), any());
        verify(emailService, times(2)).sendEmailDirectly(any(), eq("SCHEDULED"));
    }

    @Test
    void testRecoverFailedEmails_RetryOnFailure() {
        when(recoveryConfig.isEnabled()).thenReturn(true);
        when(emailQueueRepository.findFailedOrStuckEmails(any(), any(), any(PageRequest.class)))
                .thenReturn(toPage(List.of(testQueue)));
        when(emailQueueRepository.updateStatusToProcessing(eq(1L), any())).thenReturn(1);
        when(emailQueueRepository.findById(1L)).thenReturn(Optional.of(testQueue));

        EmailLog failedLog = EmailLog.builder().id(1L).queueId(1L)
                .recipient("test@example.com").subject("Test").status("FAILED").build();
        when(emailService.sendEmailDirectly(any(), eq("SCHEDULED"))).thenReturn(failedLog);

        emailProcessorService.recoverFailedEmails();

        verify(emailService).sendEmailDirectly(any(), eq("SCHEDULED"));
        assertEquals(2, testQueue.getRetryCount());
        assertEquals("PENDING", testQueue.getStatus());
    }

    @Test
    void testRecoverFailedEmails_PermanentFailure() {
        EmailQueue maxRetriedQueue = EmailQueue.builder()
                .id(3L)
                .recipient("test@example.com")
                .subject("Test")
                .htmlContent("Content")
                .status("FAILED")
                .retryCount(3)
                .maxRetries(3)
                .build();

        when(recoveryConfig.isEnabled()).thenReturn(true);
        when(emailQueueRepository.findFailedOrStuckEmails(any(), any(), any(PageRequest.class)))
                .thenReturn(toPage(List.of(maxRetriedQueue)));
        when(emailQueueRepository.updateStatusToProcessing(eq(3L), any())).thenReturn(1);
        when(emailQueueRepository.findById(3L)).thenReturn(Optional.of(maxRetriedQueue));

        EmailLog failedLog = EmailLog.builder().id(3L).queueId(3L)
                .recipient("test@example.com").subject("Test").status("FAILED").build();
        when(emailService.sendEmailDirectly(any(), eq("SCHEDULED"))).thenReturn(failedLog);

        emailProcessorService.recoverFailedEmails();

        verify(emailService).sendEmailDirectly(any(), eq("SCHEDULED"));
        assertEquals("FAILED", maxRetriedQueue.getStatus());
        assertNotNull(maxRetriedQueue.getErrorMessage());
    }
}
