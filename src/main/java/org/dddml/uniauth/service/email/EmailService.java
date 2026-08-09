package org.dddml.uniauth.service.email;

import java.util.Map;
import java.util.Optional;

public interface EmailService {

    EmailSendResult sendTemplateEmail(
        String to,
        String subject,
        String templateName,
        Map<String, Object> variables,
        String emailType
    );

    EmailSendResult sendSimpleEmail(
        String to,
        String subject,
        String htmlContent
    );

    EmailDeliveryReceipt enqueueTemplateEmail(
        String to,
        String subject,
        String templateName,
        Map<String, Object> variables,
        String emailType,
        String idempotencyKey
    );

    Optional<EmailDeliveryReceipt> findDeliveryByIdempotencyKey(
        String idempotencyKey
    );

    boolean isAvailable();
}
