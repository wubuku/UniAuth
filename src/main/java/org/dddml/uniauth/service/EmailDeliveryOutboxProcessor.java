package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.service.EmailDeliveryOutboxStateService.DeliveryWork;
import org.dddml.uniauth.service.email.EmailDeliveryClientException;
import org.dddml.uniauth.service.email.EmailDeliveryReceipt;
import org.dddml.uniauth.service.email.EmailService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailDeliveryOutboxProcessor {

    private static final String EMAIL_VERIFY_TEMPLATE = "email/email-verify";
    private static final String PASSWORD_RESET_TEMPLATE = "email/password-reset";

    private final EmailDeliveryOutboxStateService stateService;
    private final EmailVerificationCodeProtector codeProtector;
    private final EmailService emailService;

    public int processAvailable() {
        Instant now = Instant.now();
        int processed = 0;
        for (String candidate : stateService.findCandidates(now)) {
            Optional<DeliveryWork> claimed = stateService.claim(
                    candidate,
                    Instant.now()
            );
            if (claimed.isEmpty()) {
                continue;
            }
            process(claimed.get());
            processed++;
        }
        return processed;
    }

    public boolean processOne(String outboxId) {
        Optional<DeliveryWork> claimed = stateService.claim(
                outboxId,
                Instant.now()
        );
        if (claimed.isEmpty()) {
            return false;
        }
        process(claimed.get());
        return true;
    }

    private void process(DeliveryWork work) {
        Instant now = Instant.now();
        if (!work.deliveryDeadline().isAfter(now)
                || !work.totalExpiresAt().isAfter(now)) {
            stateService.fail(work, "DELIVERY_DEADLINE_EXCEEDED", now);
            return;
        }

        try {
            Optional<EmailDeliveryReceipt> existing =
                    emailService.findDeliveryByIdempotencyKey(
                            work.idempotencyKey()
                    );
            EmailDeliveryReceipt receipt = existing.orElseGet(
                    () -> enqueue(work)
            );
            if (receipt.state()
                    == EmailDeliveryReceipt.DeliveryState.FAILED) {
                stateService.fail(
                        work,
                        "PROVIDER_DELIVERY_FAILED",
                        Instant.now()
                );
                return;
            }
            stateService.accept(
                    work,
                    receipt.deliveryId(),
                    Instant.now()
            );
        } catch (EmailDeliveryClientException exception) {
            if (exception.isRetryable()) {
                stateService.retryOrFail(
                        work,
                        exception.getErrorCode(),
                        Instant.now()
                );
            } else {
                stateService.fail(
                        work,
                        exception.getErrorCode(),
                        Instant.now()
                );
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "Email outbox processing failed [outboxId={}, error={}]",
                    work.outboxId(),
                    exception.getClass().getSimpleName()
            );
            stateService.retryOrFail(
                    work,
                    "DELIVERY_PROCESSING_ERROR",
                    Instant.now()
            );
        }
    }

    private EmailDeliveryReceipt enqueue(DeliveryWork work) {
        String code = codeProtector.deriveCode(
                work.challengeId(),
                work.codeKeyId()
        );
        Map<String, Object> variables = new HashMap<>();
        variables.put("code", code);
        variables.put("verificationCode", code);
        variables.put("username", work.email());

        String subject;
        String template;
        String emailType;
        switch (work.purpose()) {
            case REGISTRATION -> {
                subject = "Verify your email address";
                template = EMAIL_VERIFY_TEMPLATE;
                emailType = "VERIFICATION";
            }
            case PASSWORD_RESET -> {
                subject = "Reset your password";
                template = PASSWORD_RESET_TEMPLATE;
                emailType = "PASSWORD_RESET";
            }
            default -> throw new IllegalStateException(
                    "Unsupported verification purpose"
            );
        }
        long expiryMinutes = Math.max(
                1,
                Duration.between(
                        Instant.now(),
                        work.totalExpiresAt()
                ).toMinutes()
        );
        variables.put("expiryMinutes", expiryMinutes);
        return emailService.enqueueTemplateEmail(
                work.email(),
                subject,
                template,
                variables,
                emailType,
                work.idempotencyKey()
        );
    }
}
