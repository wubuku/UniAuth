package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.config.EmailDeliveryProperties;
import org.dddml.uniauth.config.EmailVerificationProperties;
import org.dddml.uniauth.entity.EmailDeliveryOutbox;
import org.dddml.uniauth.entity.EmailVerificationCode;
import org.dddml.uniauth.repository.EmailDeliveryOutboxRepository;
import org.dddml.uniauth.repository.EmailVerificationCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EmailDeliveryOutboxStateService {

    private final EmailDeliveryOutboxRepository outboxRepository;
    private final EmailVerificationCodeRepository challengeRepository;
    private final EmailDeliveryProperties deliveryProperties;
    private final EmailVerificationProperties verificationProperties;
    private final SecurityEventService securityEventService;

    @Transactional(readOnly = true)
    public List<String> findCandidates(Instant now) {
        return outboxRepository.findClaimCandidates(
                now,
                now.minus(
                        deliveryProperties.getProcessingTimeoutSeconds(),
                        ChronoUnit.SECONDS
                ),
                deliveryProperties.getBatchSize()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<DeliveryWork> claim(String outboxId, Instant now) {
        int claimed = outboxRepository.claim(
                outboxId,
                now,
                now.minus(
                        deliveryProperties.getProcessingTimeoutSeconds(),
                        ChronoUnit.SECONDS
                )
        );
        if (claimed != 1) {
            return Optional.empty();
        }
        EmailDeliveryOutbox outbox = outboxRepository.findById(outboxId)
                .orElseThrow();
        EmailVerificationCode challenge = challengeRepository.findById(
                outbox.getChallengeId()
        ).orElseThrow();
        return Optional.of(new DeliveryWork(
                outbox.getId(),
                challenge.getId(),
                outbox.getIdempotencyKey(),
                outbox.getAttemptCount(),
                challenge.getEmail(),
                challenge.getPurpose(),
                challenge.getCodeKeyId(),
                challenge.getDeliveryDeadline(),
                challenge.getExpiresAt(),
                challenge.getUsageStatus()
        ));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accept(
            DeliveryWork work,
            String providerDeliveryId,
            Instant now) {
        if (outboxRepository.markAccepted(
                work.outboxId(),
                providerDeliveryId,
                now
        ) != 1) {
            return;
        }
        Instant activeExpiresAt = now.plus(
                verificationProperties.getExpiryMinutes(),
                ChronoUnit.MINUTES
        );
        if (activeExpiresAt.isAfter(work.totalExpiresAt())) {
            activeExpiresAt = work.totalExpiresAt();
        }
        int activated = challengeRepository.activateAcceptedDelivery(
                work.challengeId(),
                providerDeliveryId,
                now,
                activeExpiresAt
        );
        if (activated != 1) {
            challengeRepository.failDelivery(
                    work.challengeId(),
                    "CHALLENGE_NOT_ACTIVATABLE",
                    now
            );
            securityEventService.append(
                    "EMAIL_DELIVERY_UNUSABLE",
                    work.challengeId(),
                    SecurityEventService.Outcome.FAILURE,
                    "CHALLENGE_NOT_ACTIVATABLE"
            );
            return;
        }
        securityEventService.append(
                "EMAIL_DELIVERY_ACCEPTED",
                work.challengeId(),
                SecurityEventService.Outcome.SUCCESS,
                null
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryOrFail(
            DeliveryWork work,
            String errorCode,
            Instant now) {
        String boundedError = boundedErrorCode(errorCode);
        if (work.attemptCount() >= deliveryProperties.getMaxAttempts()
                || !work.deliveryDeadline().isAfter(now)
                || work.usageStatus()
                    != EmailVerificationCode.UsageStatus.UNUSED) {
            fail(work, boundedError, now);
            return;
        }

        long multiplier = 1L << Math.min(work.attemptCount() - 1, 10);
        long delaySeconds = Math.multiplyExact(
                deliveryProperties.getBaseRetrySeconds(),
                multiplier
        );
        Instant nextAttempt = now.plus(delaySeconds, ChronoUnit.SECONDS);
        if (!nextAttempt.isBefore(work.deliveryDeadline())) {
            fail(work, boundedError, now);
            return;
        }
        outboxRepository.markPending(
                work.outboxId(),
                nextAttempt,
                boundedError,
                now
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(
            DeliveryWork work,
            String errorCode,
            Instant now) {
        String boundedError = boundedErrorCode(errorCode);
        outboxRepository.markFailed(work.outboxId(), boundedError, now);
        challengeRepository.failDelivery(
                work.challengeId(),
                boundedError,
                now
        );
        securityEventService.append(
                "EMAIL_DELIVERY_FAILED",
                work.challengeId(),
                SecurityEventService.Outcome.FAILURE,
                boundedError
        );
    }

    private String boundedErrorCode(String errorCode) {
        String value = errorCode == null || errorCode.isBlank()
                ? "DELIVERY_FAILED"
                : errorCode;
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    public record DeliveryWork(
            String outboxId,
            String challengeId,
            String idempotencyKey,
            int attemptCount,
            String email,
            EmailVerificationCode.VerificationPurpose purpose,
            String codeKeyId,
            Instant deliveryDeadline,
            Instant totalExpiresAt,
            EmailVerificationCode.UsageStatus usageStatus) {
    }
}
