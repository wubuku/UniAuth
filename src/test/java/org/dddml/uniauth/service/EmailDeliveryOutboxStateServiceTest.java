package org.dddml.uniauth.service;

import org.dddml.uniauth.config.EmailDeliveryProperties;
import org.dddml.uniauth.config.EmailVerificationProperties;
import org.dddml.uniauth.entity.EmailDeliveryOutbox;
import org.dddml.uniauth.entity.EmailVerificationCode;
import org.dddml.uniauth.repository.EmailDeliveryOutboxRepository;
import org.dddml.uniauth.repository.EmailVerificationCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The delivery outbox is the fail-closed bridge between an email provider
 * accepting a message and a challenge becoming usable. Every branch here
 * decides whether a challenge is activated, retried or permanently failed, so
 * a refactor that silently drops one of these guards is a real risk.
 */
@ExtendWith(MockitoExtension.class)
class EmailDeliveryOutboxStateServiceTest {

    @Mock
    private EmailDeliveryOutboxRepository outboxRepository;

    @Mock
    private EmailVerificationCodeRepository challengeRepository;

    @Mock
    private SecurityEventService securityEventService;

    private EmailDeliveryProperties deliveryProperties;
    private EmailVerificationProperties verificationProperties;
    private EmailDeliveryOutboxStateService service;

    @BeforeEach
    void setUp() {
        deliveryProperties = new EmailDeliveryProperties();
        deliveryProperties.setMaxAttempts(5);
        deliveryProperties.setBaseRetrySeconds(5);
        verificationProperties = new EmailVerificationProperties();
        verificationProperties.setExpiryMinutes(10);
        service = new EmailDeliveryOutboxStateService(
                outboxRepository,
                challengeRepository,
                deliveryProperties,
                verificationProperties,
                securityEventService
        );
    }

    @Test
    void claimReturnsEmptyWhenTheConditionalUpdateDoesNotWin() {
        when(outboxRepository.claim(anyString(), any(), any())).thenReturn(0);

        assertThat(service.claim("outbox-1", Instant.now())).isEmpty();

        verify(outboxRepository, never()).findById(anyString());
    }

    @Test
    void claimReturnsTheWorkItemWhenTheConditionalUpdateWins() {
        EmailDeliveryOutbox outbox = EmailDeliveryOutbox.builder()
                .id("outbox-1")
                .challengeId("challenge-1")
                .idempotencyKey("idem-1")
                .attemptCount(2)
                .build();
        EmailVerificationCode challenge = EmailVerificationCode.builder()
                .id("challenge-1")
                .email("user@example.com")
                .purpose(EmailVerificationCode.VerificationPurpose.REGISTRATION)
                .codeKeyId("key-1")
                .deliveryDeadline(Instant.now().plusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(600))
                .usageStatus(EmailVerificationCode.UsageStatus.UNUSED)
                .build();
        when(outboxRepository.claim(anyString(), any(), any())).thenReturn(1);
        when(outboxRepository.findById("outbox-1")).thenReturn(Optional.of(outbox));
        when(challengeRepository.findById("challenge-1"))
                .thenReturn(Optional.of(challenge));

        EmailDeliveryOutboxStateService.DeliveryWork work =
                service.claim("outbox-1", Instant.now()).orElseThrow();

        assertThat(work.outboxId()).isEqualTo("outbox-1");
        assertThat(work.challengeId()).isEqualTo("challenge-1");
        assertThat(work.idempotencyKey()).isEqualTo("idem-1");
        assertThat(work.attemptCount()).isEqualTo(2);
        assertThat(work.email()).isEqualTo("user@example.com");
        assertThat(work.purpose())
                .isEqualTo(EmailVerificationCode.VerificationPurpose.REGISTRATION);
        assertThat(work.usageStatus())
                .isEqualTo(EmailVerificationCode.UsageStatus.UNUSED);
    }

    @Test
    void acceptStopsWithoutActivationWhenTheOutboxWasNotProcessing() {
        EmailDeliveryOutboxStateService.DeliveryWork work = work(1, Instant.now().plusSeconds(60));
        when(outboxRepository.markAccepted(anyString(), anyString(), any()))
                .thenReturn(0);

        service.accept(work, "provider-1", Instant.now());

        verify(challengeRepository, never()).activateAcceptedDelivery(
                anyString(),
                anyString(),
                any(),
                any()
        );
        verify(challengeRepository, never()).failDelivery(
                anyString(),
                anyString(),
                any()
        );
        verifyNoInteractions(securityEventService);
    }

    @Test
    void acceptFailsTheChallengeWhenItCannotBeActivated() {
        Instant now = Instant.now();
        EmailDeliveryOutboxStateService.DeliveryWork work = work(1, now.plusSeconds(60));
        when(outboxRepository.markAccepted(anyString(), anyString(), any()))
                .thenReturn(1);
        when(challengeRepository.activateAcceptedDelivery(
                anyString(),
                anyString(),
                any(),
                any()
        )).thenReturn(0);

        service.accept(work, "provider-1", now);

        verify(challengeRepository).failDelivery(
                "challenge-1",
                "CHALLENGE_NOT_ACTIVATABLE",
                now
        );
        verify(securityEventService).append(
                "EMAIL_DELIVERY_UNUSABLE",
                "challenge-1",
                SecurityEventService.Outcome.FAILURE,
                "CHALLENGE_NOT_ACTIVATABLE"
        );
    }

    @Test
    void acceptUsesTheVerificationExpiryWhenItIsEarlierThanTheTotalExpiry() {
        Instant now = Instant.now();
        EmailDeliveryOutboxStateService.DeliveryWork work =
                work(1, now.plusSeconds(60));
        when(outboxRepository.markAccepted(anyString(), anyString(), any()))
                .thenReturn(1);
        when(challengeRepository.activateAcceptedDelivery(
                anyString(),
                anyString(),
                any(),
                any()
        )).thenReturn(1);

        service.accept(work, "provider-1", now);

        ArgumentCaptor<Instant> activeExpiry = ArgumentCaptor.forClass(Instant.class);
        verify(challengeRepository).activateAcceptedDelivery(
                eq("challenge-1"),
                eq("provider-1"),
                eq(now),
                activeExpiry.capture()
        );
        assertThat(activeExpiry.getValue())
                .isEqualTo(now.plus(10, ChronoUnit.MINUTES));
        verify(securityEventService).append(
                "EMAIL_DELIVERY_ACCEPTED",
                "challenge-1",
                SecurityEventService.Outcome.SUCCESS,
                null
        );
    }

    @Test
    void acceptCapsTheActiveExpiryAtTheChallengeTotalExpiry() {
        Instant now = Instant.now();
        Instant totalExpiry = now.plusSeconds(300);
        // verification expiry (10 minutes) is longer than the total expiry.
        EmailDeliveryOutboxStateService.DeliveryWork work =
                new EmailDeliveryOutboxStateService.DeliveryWork(
                        "outbox-1",
                        "challenge-1",
                        "idem-1",
                        1,
                        "user@example.com",
                        EmailVerificationCode.VerificationPurpose.REGISTRATION,
                        "key-1",
                        now.plusSeconds(60),
                        totalExpiry,
                        EmailVerificationCode.UsageStatus.UNUSED
                );
        when(outboxRepository.markAccepted(anyString(), anyString(), any()))
                .thenReturn(1);
        when(challengeRepository.activateAcceptedDelivery(
                anyString(),
                anyString(),
                any(),
                any()
        )).thenReturn(1);

        service.accept(work, "provider-1", now);

        ArgumentCaptor<Instant> activeExpiry = ArgumentCaptor.forClass(Instant.class);
        verify(challengeRepository).activateAcceptedDelivery(
                eq("challenge-1"),
                eq("provider-1"),
                eq(now),
                activeExpiry.capture()
        );
        assertThat(activeExpiry.getValue()).isEqualTo(totalExpiry);
    }

    @Test
    void retryOrFailPermanentlyFailsAfterTheLastAttemptAndBoundsTheErrorCode() {
        Instant now = Instant.now();
        EmailDeliveryOutboxStateService.DeliveryWork work =
                work(5, now.plusSeconds(300));

        service.retryOrFail(work, null, now);

        verify(outboxRepository).markFailed("outbox-1", "DELIVERY_FAILED", now);
        verify(challengeRepository).failDelivery("challenge-1", "DELIVERY_FAILED", now);
        verify(securityEventService).append(
                "EMAIL_DELIVERY_FAILED",
                "challenge-1",
                SecurityEventService.Outcome.FAILURE,
                "DELIVERY_FAILED"
        );
    }

    @Test
    void retryOrFailTruncatesAnOverlongProviderError() {
        Instant now = Instant.now();
        EmailDeliveryOutboxStateService.DeliveryWork work =
                work(5, now.plusSeconds(300));
        String error = "E".repeat(100);

        service.retryOrFail(work, error, now);

        ArgumentCaptor<String> errorCode = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository).markFailed(eq("outbox-1"), errorCode.capture(), eq(now));
        assertThat(errorCode.getValue()).hasSize(64);
    }

    @Test
    void retryOrFailFailsWhenTheDeliveryDeadlineHasPassed() {
        Instant now = Instant.now();
        EmailDeliveryOutboxStateService.DeliveryWork work =
                work(1, now.minusSeconds(1));

        service.retryOrFail(work, "PROVIDER_TIMEOUT", now);

        verify(outboxRepository).markFailed("outbox-1", "PROVIDER_TIMEOUT", now);
        verify(outboxRepository, never()).markPending(
                anyString(),
                any(),
                anyString(),
                any()
        );
    }

    @Test
    void retryOrFailFailsWhenTheChallengeIsNoLongerUnused() {
        Instant now = Instant.now();
        EmailDeliveryOutboxStateService.DeliveryWork work = work(
                1,
                now.plusSeconds(300),
                EmailVerificationCode.UsageStatus.USED
        );

        service.retryOrFail(work, "PROVIDER_TIMEOUT", now);

        verify(outboxRepository).markFailed("outbox-1", "PROVIDER_TIMEOUT", now);
        verify(outboxRepository, never()).markPending(
                anyString(),
                any(),
                anyString(),
                any()
        );
    }

    @Test
    void retryOrFailFailsWhenTheBackoffWouldPassTheDeadline() {
        Instant now = Instant.now();
        EmailDeliveryOutboxStateService.DeliveryWork work =
                work(3, now.plusSeconds(10)); // backoff is 5s * 2^2 = 20s

        service.retryOrFail(work, "PROVIDER_TIMEOUT", now);

        verify(outboxRepository).markFailed("outbox-1", "PROVIDER_TIMEOUT", now);
        verify(outboxRepository, never()).markPending(
                anyString(),
                any(),
                anyString(),
                any()
        );
    }

    @Test
    void retryOrFailSchedulesAnExponentialBackoffOtherwise() {
        Instant now = Instant.now();
        EmailDeliveryOutboxStateService.DeliveryWork work =
                work(2, now.plusSeconds(300)); // backoff is 5s * 2^1 = 10s

        service.retryOrFail(work, "PROVIDER_TIMEOUT", now);

        ArgumentCaptor<Instant> nextAttempt = ArgumentCaptor.forClass(Instant.class);
        verify(outboxRepository).markPending(
                eq("outbox-1"),
                nextAttempt.capture(),
                eq("PROVIDER_TIMEOUT"),
                eq(now)
        );
        assertThat(nextAttempt.getValue())
                .isEqualTo(now.plus(10, ChronoUnit.SECONDS));
        verify(outboxRepository, never()).markFailed(
                anyString(),
                anyString(),
                any()
        );
    }

    private EmailDeliveryOutboxStateService.DeliveryWork work(
            int attemptCount,
            Instant deliveryDeadline) {
        return work(
                attemptCount,
                deliveryDeadline,
                EmailVerificationCode.UsageStatus.UNUSED
        );
    }

    private EmailDeliveryOutboxStateService.DeliveryWork work(
            int attemptCount,
            Instant deliveryDeadline,
            EmailVerificationCode.UsageStatus usageStatus) {
        return new EmailDeliveryOutboxStateService.DeliveryWork(
                "outbox-1",
                "challenge-1",
                "idem-1",
                attemptCount,
                "user@example.com",
                EmailVerificationCode.VerificationPurpose.REGISTRATION,
                "key-1",
                deliveryDeadline,
                Instant.now().plus(1, ChronoUnit.DAYS),
                usageStatus
        );
    }
}
