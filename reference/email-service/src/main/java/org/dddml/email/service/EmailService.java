package org.dddml.email.service;

import lombok.RequiredArgsConstructor;
import org.dddml.email.config.MailProperties;
import org.dddml.email.entity.EmailLog;
import org.dddml.email.entity.EmailQueue;
import org.dddml.email.repository.EmailLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final Set<String> ALLOWED_TEMPLATES = Set.of(
        "email/welcome",
        "email/password-reset",
        "email/email-verify"
    );
    private static final int MAX_HTML_CONTENT_LENGTH = 1_000_000;
    private static final String INVALID_QUEUED_EMAIL_MESSAGE =
        "Invalid queued email data";
    private static final String UNDISCLOSED_RECIPIENT =
        "undisclosed@example.invalid";
    private static final String UNKNOWN_SEND_METHOD = "UNKNOWN";

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final EmailLogRepository emailLogRepository;
    private final EmailQueueService emailQueueService;
    private final MailProperties mailProperties;

    @Value("${spring.mail.host}")
    private String mailHost;

    @Value("${spring.profiles.active:unspecified}")
    private String activeProfile;

    public EmailQueue sendEmailAsync(String to, String subject, String templateName,
                                    Map<String, Object> variables, String emailType) {
        return sendEmailAsync(
            to,
            subject,
            templateName,
            variables,
            emailType,
            null
        );
    }

    public EmailQueue sendEmailAsync(
            String to,
            String subject,
            String templateName,
            Map<String, Object> variables,
            String emailType,
            String idempotencyKey) {
        try {
            ensureEnqueueEnabled();
            validateEnvelope(to, subject);
            if (!ALLOWED_TEMPLATES.contains(templateName)) {
                throw new IllegalArgumentException("Unsupported email template");
            }
            if (variables == null) {
                throw new IllegalArgumentException("Template variables are required");
            }
            validateTemplateVariables(templateName, variables);

            Context context = new Context();
            context.setVariables(variables);
            String htmlContent = templateEngine.process(templateName, context);
            validateHtmlContent(htmlContent);

            if (idempotencyKey == null) {
                return emailQueueService.enqueue(
                    to,
                    subject,
                    htmlContent,
                    emailType
                );
            }
            return emailQueueService.enqueueIdempotent(
                to,
                subject,
                htmlContent,
                emailType,
                idempotencyKey
            );

        } catch (Exception exception) {
            log.error(
                "Email enqueue failed [error={}]",
                exception.getClass().getSimpleName()
            );
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            if (exception instanceof IdempotencyConflictException conflict) {
                throw conflict;
            }
            throw new RuntimeException("Email enqueue failed", exception);
        }
    }

    public EmailQueue sendSimpleHtmlEmail(String to, String subject, String htmlContent) {
        ensureEnqueueEnabled();
        validateEnvelope(to, subject);
        validateHtmlContent(htmlContent);
        return emailQueueService.enqueue(to, subject, htmlContent, "SIMPLE");
    }

    public EmailLog sendEmailDirectly(EmailQueue emailQueue, String sendMethod) {
        long startTime = System.currentTimeMillis();
        String emailType = normalizedEmailType(emailQueue.getEmailType());

        try {
            validateDeliveryPayload(emailQueue, emailType, sendMethod);
        } catch (IllegalArgumentException exception) {
            EmailLog rejectedLog = rejectedDeliveryLog(emailQueue, sendMethod);
            log.warn("Rejected invalid queued email [ID={}]", emailQueue.getId());
            return emailLogRepository.save(rejectedLog);
        }

        EmailLog emailLog = deliveryLog(emailQueue, emailType, sendMethod);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(new InternetAddress(
                mailProperties.getFromEmail(),
                mailProperties.getFromName(), "UTF-8"
            ));
            helper.setTo(emailQueue.getRecipient());
            helper.setSubject(emailQueue.getSubject());
            helper.setText(emailQueue.getHtmlContent(), true);

            message.addHeader("X-Mailer", "Spring Boot Email System");
            message.addHeader("X-Environment", activeProfile);
            message.addHeader("X-Email-Type", emailType);
            message.addHeader("X-Queue-ID", String.valueOf(emailQueue.getId()));
            message.addHeader("X-Send-Method", sendMethod);

            mailSender.send(message);

            long duration = System.currentTimeMillis() - startTime;
            emailLog.setStatus("SUCCESS");
            emailLog.setDurationMs(duration);

            log.info(
                "Email sent successfully [{}][{}][ID={}] (duration: {}ms)",
                sendMethod,
                activeProfile,
                emailQueue.getId(),
                duration
            );

        } catch (MailSendException e) {
            emailLog.setStatus("FAILED");
            emailLog.setErrorMessage("SMTP delivery failed");
            log.error("Email send failed [ID={}]", emailQueue.getId());
        } catch (MessagingException e) {
            emailLog.setStatus("FAILED");
            emailLog.setErrorMessage("Email format error");
            log.error("Email format error [ID={}]", emailQueue.getId());
        } catch (Exception exception) {
            emailLog.setStatus("FAILED");
            emailLog.setErrorMessage("Email delivery failed");
            log.error(
                "Email delivery failed [ID={}, error={}]",
                emailQueue.getId(),
                exception.getClass().getSimpleName()
            );
        }

        return emailLogRepository.save(emailLog);
    }

    private EmailLog deliveryLog(
            EmailQueue emailQueue,
            String emailType,
            String sendMethod) {
        return EmailLog.builder()
            .queueId(emailQueue.getId())
            .recipient(emailQueue.getRecipient())
            .subject(emailQueue.getSubject())
            .emailType(emailType)
            .retryCount(emailQueue.getRetryCount())
            .mailProvider(getMailProvider())
            .sendMethod(sendMethod)
            .build();
    }

    private EmailLog rejectedDeliveryLog(
            EmailQueue emailQueue,
            String sendMethod) {
        return EmailLog.builder()
            .queueId(emailQueue.getId())
            .recipient(UNDISCLOSED_RECIPIENT)
            .subject(INVALID_QUEUED_EMAIL_MESSAGE)
            .status("FAILED")
            .errorMessage(INVALID_QUEUED_EMAIL_MESSAGE)
            .emailType("GENERAL")
            .retryCount(emailQueue.getRetryCount())
            .mailProvider(getMailProvider())
            .sendMethod(
                isValidHeaderToken(sendMethod, 20)
                    ? sendMethod
                    : UNKNOWN_SEND_METHOD
            )
            .build();
    }

    private void validateDeliveryPayload(
            EmailQueue emailQueue,
            String emailType,
            String sendMethod) {
        validateEnvelope(emailQueue.getRecipient(), emailQueue.getSubject());
        validateHtmlContent(emailQueue.getHtmlContent());
        validateHeaderToken(emailType, 50);
        validateHeaderToken(sendMethod, 20);
    }

    private String normalizedEmailType(String emailType) {
        return emailType == null || emailType.isBlank() ? "GENERAL" : emailType;
    }

    private void validateHeaderToken(String value, int maxLength) {
        if (!isValidHeaderToken(value, maxLength)) {
            throw new IllegalArgumentException("Invalid email header token");
        }
    }

    private boolean isValidHeaderToken(String value, int maxLength) {
        return value != null
            && !value.isBlank()
            && value.length() <= maxLength
            && value.codePoints().allMatch(this::isAsciiHeaderTokenCharacter);
    }

    private boolean isAsciiHeaderTokenCharacter(int character) {
        return character < 128
            && (Character.isLetterOrDigit(character)
                || character == '_'
                || character == '-');
    }

    public boolean isValidEmail(String email) {
        try {
            if (email == null || email.length() > 255) {
                return false;
            }
            InternetAddress ia = new InternetAddress(email, true);
            ia.validate();
            return email.equals(ia.getAddress());
        } catch (Exception e) {
            return false;
        }
    }

    private String getMailProvider() {
        String normalizedHost = mailHost == null ? "" : mailHost.toLowerCase(Locale.ROOT);
        if (normalizedHost.contains("gmail")) return "Gmail";
        if (normalizedHost.contains("163")) return "163";
        if (normalizedHost.contains("qq")) return "QQ";
        if (normalizedHost.contains("sendgrid")) return "SendGrid";
        if (normalizedHost.contains("amazonses")) return "AWS SES";
        if (normalizedHost.contains("aliyun")) return "Aliyun";
        return "Unknown";
    }

    private void validateEnvelope(String recipient, String subject) {
        if (!isValidEmail(recipient)) {
            throw new IllegalArgumentException("Invalid email address");
        }
        if (subject == null
                || subject.isBlank()
                || subject.length() > 500
                || subject.indexOf('\r') >= 0
                || subject.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid email subject");
        }
    }

    private void ensureEnqueueEnabled() {
        if (!mailProperties.isEnabled() || !mailProperties.getQueue().isEnabled()) {
            throw new IllegalStateException("Email enqueue is disabled");
        }
    }

    private void validateHtmlContent(String htmlContent) {
        if (htmlContent == null
                || htmlContent.isBlank()
                || htmlContent.length() > MAX_HTML_CONTENT_LENGTH) {
            throw new IllegalArgumentException("Invalid email content");
        }
    }

    private void validateTemplateVariables(
            String templateName,
            Map<String, Object> variables) {
        if (!"email/email-verify".equals(templateName)
                && !"email/password-reset".equals(templateName)) {
            return;
        }

        requireNonBlankVariable(variables, "username", 255);
        requireNonBlankVariable(variables, "verificationCode", 128);

        Object expiryMinutes = variables.get("expiryMinutes");
        if (!(expiryMinutes instanceof Number expiry)
                || expiry.longValue() < 1
                || expiry.longValue() > 10080) {
            throw new IllegalArgumentException("Invalid template expiryMinutes");
        }
    }

    private void requireNonBlankVariable(
            Map<String, Object> variables,
            String name,
            int maxLength) {
        Object value = variables.get(name);
        if (!(value instanceof CharSequence text)
                || text.toString().isBlank()
                || text.length() > maxLength) {
            throw new IllegalArgumentException("Invalid template variable: " + name);
        }
    }
}
