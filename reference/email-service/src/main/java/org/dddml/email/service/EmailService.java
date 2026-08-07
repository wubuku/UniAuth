package org.dddml.email.service;

import org.dddml.email.config.MailProperties;
import org.dddml.email.entity.EmailLog;
import org.dddml.email.entity.EmailQueue;
import org.dddml.email.repository.EmailLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Map;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private TemplateEngine templateEngine;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Autowired
    private EmailQueueService emailQueueService;

    @Autowired
    private MailProperties mailProperties;

    @Value("${spring.mail.host}")
    private String mailHost;

    @Value("${spring.profiles.active:unspecified}")
    private String activeProfile;

    public EmailQueue sendEmailAsync(String to, String subject, String templateName,
                                    Map<String, Object> variables, String emailType) {
        try {
            if (!mailProperties.isEnabled()) {
                log.info("Email sending disabled, skipping: {}", to);
                return null;
            }

            Context context = new Context();
            context.setVariables(variables);
            String htmlContent = templateEngine.process(templateName, context);

            return emailQueueService.enqueue(to, subject, htmlContent, emailType);

        } catch (Exception e) {
            log.error("Email enqueue failed: {} - {}", to, e.getMessage(), e);
            throw new RuntimeException("Email enqueue failed", e);
        }
    }

    public EmailQueue sendSimpleHtmlEmail(String to, String subject, String htmlContent) {
        return emailQueueService.enqueue(to, subject, htmlContent, "SIMPLE");
    }

    public EmailLog sendEmailDirectly(EmailQueue emailQueue, String sendMethod) {
        long startTime = System.currentTimeMillis();

        EmailLog emailLog = EmailLog.builder()
                .queueId(emailQueue.getId())
                .recipient(emailQueue.getRecipient())
                .subject(emailQueue.getSubject())
                .emailContent(emailQueue.getHtmlContent())
                .emailType(emailQueue.getEmailType())
                .retryCount(emailQueue.getRetryCount())
                .mailProvider(getMailProvider())
                .sendMethod(sendMethod)
                .build();

        try {
            if (!isValidEmail(emailQueue.getRecipient())) {
                throw new IllegalArgumentException("Invalid email address: " + emailQueue.getRecipient());
            }

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
            message.addHeader("X-Email-Type", emailQueue.getEmailType());
            message.addHeader("X-Queue-ID", String.valueOf(emailQueue.getId()));
            message.addHeader("X-Send-Method", sendMethod);

            mailSender.send(message);

            long duration = System.currentTimeMillis() - startTime;
            emailLog.setStatus("SUCCESS");
            emailLog.setDurationMs(duration);

            log.info("Email sent successfully [{}][{}][ID={}]: {} (duration: {}ms)",
                     sendMethod, activeProfile, emailQueue.getId(),
                     emailQueue.getRecipient(), duration);

        } catch (MailSendException e) {
            emailLog.setStatus("FAILED");
            emailLog.setErrorMessage(e.getMessage());
            log.error("Email send failed: {}", emailQueue.getRecipient());
        } catch (MessagingException e) {
            emailLog.setStatus("FAILED");
            emailLog.setErrorMessage("Email format error: " + e.getMessage());
            log.error("Email format error: {}", emailQueue.getRecipient());
        } catch (Exception e) {
            emailLog.setStatus("FAILED");
            emailLog.setErrorMessage("Unknown error: " + e.getMessage());
            log.error("Unknown error: {}", emailQueue.getRecipient());
        }

        return emailLogRepository.save(emailLog);
    }

    public boolean isValidEmail(String email) {
        try {
            InternetAddress ia = new InternetAddress(email);
            ia.validate();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String getMailProvider() {
        if (mailHost.contains("gmail")) return "Gmail";
        if (mailHost.contains("163")) return "163";
        if (mailHost.contains("qq")) return "QQ";
        if (mailHost.contains("sendgrid")) return "SendGrid";
        if (mailHost.contains("amazonses")) return "AWS SES";
        if (mailHost.contains("aliyun")) return "Aliyun";
        return "Unknown";
    }
}
