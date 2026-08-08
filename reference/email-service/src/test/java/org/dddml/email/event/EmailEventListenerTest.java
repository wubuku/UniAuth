package org.dddml.email.event;

import org.dddml.email.config.MailProperties;
import org.dddml.email.service.EmailDeliveryService;
import org.dddml.email.service.EmailQueueClaimService;
import org.dddml.email.service.EmailRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailEventListenerTest {

    @Mock
    private MailProperties mailProperties;

    @Mock
    private MailProperties.Queue queueConfig;

    @Mock
    private EmailRateLimiter rateLimiter;

    @Mock
    private EmailQueueClaimService claimService;

    @Mock
    private EmailDeliveryService deliveryService;

    @InjectMocks
    private EmailEventListener emailEventListener;

    private EmailQueuedEvent event;

    @BeforeEach
    void setUp() {
        event = new EmailQueuedEvent(this, 1L);
        when(mailProperties.getQueue()).thenReturn(queueConfig);
    }

    @Test
    void eventDrivenDisabledLeavesTheQueueForRecovery() {
        when(queueConfig.isEventDriven()).thenReturn(false);

        emailEventListener.handleEmailQueuedEvent(event);

        verify(rateLimiter, never()).tryAcquire();
        verify(claimService, never()).claimPending(eq(1L), any(LocalDateTime.class));
        verify(deliveryService, never()).deliver(1L, "EVENT");
    }

    @Test
    void rateLimitDenialLeavesTheQueuePending() {
        when(queueConfig.isEventDriven()).thenReturn(true);
        when(rateLimiter.tryAcquire()).thenReturn(false);

        emailEventListener.handleEmailQueuedEvent(event);

        verify(claimService, never()).claimPending(eq(1L), any(LocalDateTime.class));
        verify(deliveryService, never()).deliver(1L, "EVENT");
    }

    @Test
    void failedClaimReleasesTheReservedRateLimitSlot() {
        when(queueConfig.isEventDriven()).thenReturn(true);
        when(rateLimiter.tryAcquire()).thenReturn(true);
        when(claimService.claimPending(eq(1L), any(LocalDateTime.class))).thenReturn(false);

        emailEventListener.handleEmailQueuedEvent(event);

        verify(rateLimiter).release();
        verify(deliveryService, never()).deliver(1L, "EVENT");
    }

    @Test
    void claimedQueueIsDeliveredByTheTransactionalDeliveryBean() {
        when(queueConfig.isEventDriven()).thenReturn(true);
        when(rateLimiter.tryAcquire()).thenReturn(true);
        when(claimService.claimPending(eq(1L), any(LocalDateTime.class))).thenReturn(true);
        when(deliveryService.deliver(1L, "EVENT"))
            .thenReturn(EmailDeliveryService.DeliveryOutcome.SUCCESS);

        emailEventListener.handleEmailQueuedEvent(event);

        verify(deliveryService).deliver(1L, "EVENT");
    }

    @Test
    void skippedDeliveryReleasesTheReservedRateLimitSlot() {
        when(queueConfig.isEventDriven()).thenReturn(true);
        when(rateLimiter.tryAcquire()).thenReturn(true);
        when(claimService.claimPending(eq(1L), any(LocalDateTime.class))).thenReturn(true);
        when(deliveryService.deliver(1L, "EVENT"))
            .thenReturn(EmailDeliveryService.DeliveryOutcome.SKIPPED);

        emailEventListener.handleEmailQueuedEvent(event);

        verify(rateLimiter).release();
    }
}
