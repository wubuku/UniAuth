package org.dddml.uniauth.service.email;

import java.util.Map;

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

    boolean isAvailable();
}
