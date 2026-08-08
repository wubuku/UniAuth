package org.dddml.uniauth.service.email.impl;

import org.dddml.uniauth.config.EmailServiceClientProperties;
import org.dddml.uniauth.service.email.EmailService;
import org.dddml.uniauth.service.email.EmailSendResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
public class RestTemplateEmailServiceImpl implements EmailService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final String API_KEY_HEADER = "X-Email-Service-Key";

    private final RestTemplate restTemplate;
    private final EmailServiceClientProperties properties;

    public RestTemplateEmailServiceImpl(
            @Qualifier("emailRestTemplate") RestTemplate restTemplate,
            EmailServiceClientProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    @Override
    public EmailSendResult sendTemplateEmail(
            String to,
            String subject,
            String templateName,
            Map<String, Object> variables,
            String emailType) {

        if (!isValidEmail(to)) {
            log.warn("Rejected invalid email destination");
            return EmailSendResult.INVALID_EMAIL;
        }

        String url = serviceUrl("/api/email/template");
        HttpHeaders headers = requestHeaders();

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
                log.info("Template email accepted by email service");
                return EmailSendResult.QUEUED;
            }

            log.warn("Template email was rejected by email service");
            return EmailSendResult.FAILED;

        } catch (Exception e) {
            log.warn("Template email request failed");
            return EmailSendResult.FAILED;
        }
    }

    @Override
    public EmailSendResult sendSimpleEmail(String to, String subject, String htmlContent) {
        String url = serviceUrl("/api/email/simple");
        HttpHeaders headers = requestHeaders();

        Map<String, Object> body = new HashMap<>();
        body.put("to", to);
        body.put("subject", subject);
        body.put("htmlContent", htmlContent);

        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForEntity(url, request, Map.class).getBody();

            if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                log.info("Simple email accepted by email service");
                return EmailSendResult.QUEUED;
            }

            log.warn("Simple email was rejected by email service");
            return EmailSendResult.FAILED;

        } catch (Exception e) {
            log.warn("Simple email request failed");
            return EmailSendResult.FAILED;
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            String healthUrl = serviceUrl("/api/email/health");
            HttpEntity<Void> request = new HttpEntity<>(requestHeaders());
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.exchange(
                healthUrl,
                HttpMethod.GET,
                request,
                Map.class
            ).getBody();
            return response != null && "UP".equals(response.get("status"));
        } catch (Exception e) {
            log.warn("Email service health check failed");
            return false;
        }
    }

    private boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    private HttpHeaders requestHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(properties.getApiKey())) {
            headers.set(API_KEY_HEADER, properties.getApiKey());
        }
        return headers;
    }

    private String serviceUrl(String path) {
        String baseUrl = properties.getUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }
}
