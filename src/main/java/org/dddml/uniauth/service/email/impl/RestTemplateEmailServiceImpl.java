package org.dddml.uniauth.service.email.impl;

import org.dddml.uniauth.service.email.EmailService;
import org.dddml.uniauth.service.email.EmailSendResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class RestTemplateEmailServiceImpl implements EmailService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    @Value("${app.email.service.url:http://localhost:8095}")
    private String emailServiceUrl;

    private final RestTemplate restTemplate;

    @Override
    public EmailSendResult sendTemplateEmail(
            String to,
            String subject,
            String templateName,
            Map<String, Object> variables,
            String emailType) {

        if (!isValidEmail(to)) {
            log.warn("Invalid email address: {}", to);
            return EmailSendResult.INVALID_EMAIL;
        }

        String url = emailServiceUrl + "/api/email/template";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("to", to);
        body.put("subject", subject);
        body.put("templateName", templateName);
        body.put("variables", variables);
        body.put("emailType", emailType);

        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForEntity(url, request, Map.class).getBody();

            if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                log.info("Email sent successfully to: {}, queueId: {}",
                    to, response.get("queueId"));
                return EmailSendResult.QUEUED;
            }

            log.error("Failed to send email to: {}, response: {}", to, response);
            return EmailSendResult.FAILED;

        } catch (Exception e) {
            log.error("Exception while sending email to: {}", to, e);
            return EmailSendResult.FAILED;
        }
    }

    @Override
    public EmailSendResult sendSimpleEmail(String to, String subject, String htmlContent) {
        String url = emailServiceUrl + "/api/email/simple";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("to", to);
        body.put("subject", subject);
        body.put("htmlContent", htmlContent);

        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForEntity(url, request, Map.class).getBody();

            if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                log.info("Simple email sent successfully to: {}", to);
                return EmailSendResult.QUEUED;
            }

            log.error("Failed to send simple email to: {}, response: {}", to, response);
            return EmailSendResult.FAILED;

        } catch (Exception e) {
            log.error("Exception while sending simple email to: {}", to, e);
            return EmailSendResult.FAILED;
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            String healthUrl = emailServiceUrl + "/api/email/health";
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForEntity(healthUrl, Map.class).getBody();
            return response != null && "UP".equals(response.get("status"));
        } catch (Exception e) {
            log.warn("Email service health check failed", e);
            return false;
        }
    }

    private boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }
}
