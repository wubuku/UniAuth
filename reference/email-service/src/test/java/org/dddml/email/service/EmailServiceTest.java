package org.dddml.email.service;

import org.dddml.email.config.MailProperties;
import org.dddml.email.entity.EmailQueue;
import org.dddml.email.repository.EmailLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EmailServiceValidationTest {

    @Test
    void testIsValidEmail_Valid() {
        EmailService emailService = newEmailService();

        assertTrue(emailService.isValidEmail("test@example.com"));
        assertTrue(emailService.isValidEmail("user.name@domain.co.uk"));
        assertTrue(emailService.isValidEmail("test+tag@example.org"));
        assertTrue(emailService.isValidEmail("a@b.co"));
        assertTrue(emailService.isValidEmail("user_name@test.example.com"));
    }

    @Test
    void testIsValidEmail_Invalid() {
        EmailService emailService = newEmailService();

        assertFalse(emailService.isValidEmail(""));
        assertFalse(emailService.isValidEmail("not-an-email"));
        assertFalse(emailService.isValidEmail("@example.com"));
        assertFalse(emailService.isValidEmail("test@"));
        assertFalse(emailService.isValidEmail("test @example.com"));
        assertFalse(emailService.isValidEmail("test@ example.com"));
        assertFalse(emailService.isValidEmail("test@exam ple.com"));
    }

    @Test
    void testGetMailProvider_Various() throws Exception {
        EmailService emailService = newEmailService();

        assertEquals("Gmail", getMailProvider(emailService, "smtp.gmail.com"));
        assertEquals("163", getMailProvider(emailService, "smtp.163.com"));
        assertEquals("QQ", getMailProvider(emailService, "smtp.qq.com"));
        assertEquals("SendGrid", getMailProvider(emailService, "smtp.sendgrid.net"));
        assertEquals("AWS SES", getMailProvider(emailService, "email.amazonses.com"));
        assertEquals("Aliyun", getMailProvider(emailService, "smtp.aliyun.com"));
        assertEquals("Unknown", getMailProvider(emailService, "smtp.unknown.com"));
    }

    private String getMailProvider(EmailService emailService, String host) throws Exception {
        java.lang.reflect.Field field = EmailService.class.getDeclaredField("mailHost");
        field.setAccessible(true);
        field.set(emailService, host);

        java.lang.reflect.Method method = EmailService.class.getDeclaredMethod("getMailProvider");
        method.setAccessible(true);
        return (String) method.invoke(emailService);
    }

    private EmailService newEmailService() {
        return newEmailService(new MailProperties());
    }

    private EmailService newEmailService(MailProperties mailProperties) {
        return new EmailService(
            mock(JavaMailSender.class),
            mock(TemplateEngine.class),
            mock(EmailLogRepository.class),
            mock(EmailQueueService.class),
            mailProperties
        );
    }

    @Test
    void disabledQueueRejectsSimpleEmailInsteadOfSilentlyPersistingIt() {
        MailProperties mailProperties = new MailProperties();
        mailProperties.getQueue().setEnabled(false);

        EmailService emailService = newEmailService(mailProperties);

        assertThrows(
            IllegalStateException.class,
            () -> emailService.sendSimpleHtmlEmail(
                "test@example.com",
                "Subject",
                "<p>Content</p>"
            )
        );
    }

    @Test
    void testEmailQueue_Builder() {
        EmailQueue queue = EmailQueue.builder()
                .id(1L)
                .recipient("test@example.com")
                .subject("Test Subject")
                .htmlContent("<p>Content</p>")
                .emailType("TEST")
                .status("PENDING")
                .priority(5)
                .retryCount(0)
                .maxRetries(3)
                .build();

        assertNotNull(queue);
        assertEquals("test@example.com", queue.getRecipient());
        assertEquals("PENDING", queue.getStatus());
        assertEquals(5, queue.getPriority());
    }

    @Test
    void testEmailQueue_DefaultValues() {
        EmailQueue queue = EmailQueue.builder()
                .recipient("test@example.com")
                .subject("Test")
                .htmlContent("Content")
                .build();

        assertNotNull(queue);
        assertEquals("GENERAL", queue.getEmailType());
        assertEquals("PENDING", queue.getStatus());
        assertEquals(5, queue.getPriority());
        assertEquals(0, queue.getRetryCount());
        assertEquals(3, queue.getMaxRetries());
    }

    @Test
    void emailQueueStringDoesNotExposeRecipientOrRenderedContent() {
        EmailQueue queue = EmailQueue.builder()
                .id(2L)
                .recipient("sensitive@example.test")
                .subject("Sensitive subject")
                .htmlContent("<p>verification-code-135790</p>")
                .build();

        String result = queue.toString();

        assertFalse(result.contains("sensitive@example.test"));
        assertFalse(result.contains("verification-code-135790"));
    }
}
