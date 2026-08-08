package org.dddml.email.service;

import org.dddml.email.config.MailProperties;
import org.dddml.email.entity.EmailQueue;
import org.dddml.email.repository.EmailQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailProcessorServiceTest {

    @Mock
    private EmailQueueRepository emailQueueRepository;

    @Mock
    private EmailQueueClaimService claimService;

    @Mock
    private EmailDeliveryService deliveryService;

    @Mock
    private EmailRateLimiter rateLimiter;

    @Mock
    private MailProperties mailProperties;

    @Mock
    private MailProperties.Recovery recoveryConfig;

    @Mock
    private MailProperties.Queue queueConfig;

    @InjectMocks
    private EmailProcessorService emailProcessorService;

    private EmailQueue queue;

    @BeforeEach
    void setUp() {
        queue = EmailQueue.builder()
            .id(1L)
            .recipient("test@example.com")
            .subject("Test Subject")
            .htmlContent("<p>Test Content</p>")
            .status("PENDING")
            .retryCount(1)
            .maxRetries(3)
            .build();
        when(mailProperties.isEnabled()).thenReturn(true);
        when(mailProperties.getQueue()).thenReturn(queueConfig);
        when(queueConfig.isEnabled()).thenReturn(true);
        when(mailProperties.getRecovery()).thenReturn(recoveryConfig);
    }

    @Test
    void recoveryDisabledDoesNotReadTheQueue() {
        when(recoveryConfig.isEnabled()).thenReturn(false);

        emailProcessorService.recoverFailedEmails();

        verify(emailQueueRepository, never()).findFailedOrStuckEmails(any(), any(), any());
    }

    @Test
    void emptyRecoveryScanDoesNotClaimOrDeliver() {
        enableRecovery();
        when(emailQueueRepository.findFailedOrStuckEmails(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        emailProcessorService.recoverFailedEmails();

        verify(claimService, never()).claimRecoverable(eq(1L), any(), any());
        verify(deliveryService, never()).deliver(1L, "SCHEDULED");
    }

    @Test
    void claimedCandidateIsDelivered() {
        enableRecovery();
        when(emailQueueRepository.findFailedOrStuckEmails(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(queue), PageRequest.of(0, 50), 1));
        when(rateLimiter.tryAcquire()).thenReturn(true);
        when(claimService.claimRecoverable(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(true);
        when(deliveryService.deliver(1L, "SCHEDULED"))
            .thenReturn(EmailDeliveryService.DeliveryOutcome.SUCCESS);

        emailProcessorService.recoverFailedEmails();

        verify(deliveryService).deliver(1L, "SCHEDULED");
    }

    @Test
    void candidateClaimedElsewhereIsSkipped() {
        enableRecovery();
        when(emailQueueRepository.findFailedOrStuckEmails(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(queue), PageRequest.of(0, 50), 1));
        when(rateLimiter.tryAcquire()).thenReturn(true);
        when(claimService.claimRecoverable(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(false);

        emailProcessorService.recoverFailedEmails();

        verify(rateLimiter).release();
        verify(deliveryService, never()).deliver(1L, "SCHEDULED");
    }

    @Test
    void rateLimitDenialLeavesCandidatesUnclaimed() {
        enableRecovery();
        when(emailQueueRepository.findFailedOrStuckEmails(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(queue), PageRequest.of(0, 50), 1));
        when(rateLimiter.tryAcquire()).thenReturn(false);

        emailProcessorService.recoverFailedEmails();

        verify(claimService, never()).claimRecoverable(eq(1L), any(), any());
        verify(deliveryService, never()).deliver(1L, "SCHEDULED");
    }

    @Test
    void eachClaimedCandidateIsDeliveredOnce() {
        EmailQueue second = EmailQueue.builder()
            .id(2L)
            .recipient("second@example.com")
            .subject("Second")
            .htmlContent("<p>Second</p>")
            .status("PROCESSING")
            .retryCount(1)
            .maxRetries(3)
            .build();
        enableRecovery();
        when(emailQueueRepository.findFailedOrStuckEmails(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(queue, second), PageRequest.of(0, 50), 2));
        when(rateLimiter.tryAcquire()).thenReturn(true);
        when(claimService.claimRecoverable(any(), any(), any())).thenReturn(true);
        when(deliveryService.deliver(any(), eq("SCHEDULED")))
            .thenReturn(EmailDeliveryService.DeliveryOutcome.SUCCESS);

        emailProcessorService.recoverFailedEmails();

        verify(deliveryService, times(2)).deliver(any(), eq("SCHEDULED"));
    }

    @Test
    void claimFailureReleasesTheReservedRateLimitSlot() {
        enableRecovery();
        when(emailQueueRepository.findFailedOrStuckEmails(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(queue), PageRequest.of(0, 50), 1));
        when(rateLimiter.tryAcquire()).thenReturn(true);
        doThrow(new IllegalStateException("claim unavailable"))
            .when(claimService)
            .claimRecoverable(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class));

        emailProcessorService.recoverFailedEmails();

        verify(rateLimiter).release();
        verify(deliveryService, never()).deliver(1L, "SCHEDULED");
    }

    @Test
    void deliveryFailureKeepsTheConsumedRateLimitSlot() {
        enableRecovery();
        when(emailQueueRepository.findFailedOrStuckEmails(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(queue), PageRequest.of(0, 50), 1));
        when(rateLimiter.tryAcquire()).thenReturn(true);
        when(claimService.claimRecoverable(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(true);
        doThrow(new IllegalStateException("delivery unavailable"))
            .when(deliveryService)
            .deliver(1L, "SCHEDULED");

        emailProcessorService.recoverFailedEmails();

        verify(rateLimiter, never()).release();
    }

    private void enableRecovery() {
        when(recoveryConfig.isEnabled()).thenReturn(true);
        when(recoveryConfig.getStuckTimeoutMinutes()).thenReturn(10);
    }
}
