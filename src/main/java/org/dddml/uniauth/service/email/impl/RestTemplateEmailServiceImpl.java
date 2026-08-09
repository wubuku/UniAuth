package org.dddml.uniauth.service.email.impl;

import org.dddml.uniauth.config.EmailServiceClientProperties;
import org.dddml.uniauth.service.email.EmailDeliveryClientException;
import org.dddml.uniauth.service.email.EmailDeliveryReceipt;
import org.dddml.uniauth.service.email.EmailService;
import org.dddml.uniauth.service.email.EmailSendResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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

        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Template email request was rate limited");
            return EmailSendResult.RATE_LIMITED;
        } catch (RestClientResponseException e) {
            log.warn(
                "Template email request was rejected with HTTP status {}",
                e.getStatusCode().value()
            );
            return EmailSendResult.FAILED;
        } catch (Exception e) {
            log.warn(
                "Template email request failed with {}",
                e.getClass().getSimpleName()
            );
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

        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Simple email request was rate limited");
            return EmailSendResult.RATE_LIMITED;
        } catch (RestClientResponseException e) {
            log.warn(
                "Simple email request was rejected with HTTP status {}",
                e.getStatusCode().value()
            );
            return EmailSendResult.FAILED;
        } catch (Exception e) {
            log.warn(
                "Simple email request failed with {}",
                e.getClass().getSimpleName()
            );
            return EmailSendResult.FAILED;
        }
    }

    @Override
    public EmailDeliveryReceipt enqueueTemplateEmail(
            String to,
            String subject,
            String templateName,
            Map<String, Object> variables,
            String emailType,
            String idempotencyKey) {
        if (!isValidEmail(to)) {
            throw new EmailDeliveryClientException("INVALID_EMAIL", false);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("to", to);
        body.put("subject", subject);
        body.put("templateName", templateName);
        body.put("variables", variables);
        body.put("emailType", emailType);
        body.put("idempotencyKey", idempotencyKey);

        try {
            HttpEntity<Map<String, Object>> request =
                    new HttpEntity<>(body, requestHeaders());
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForEntity(
                    serviceUrl("/api/email/template"),
                    request,
                    Map.class
            ).getBody();
            return deliveryReceipt(response);
        } catch (HttpClientErrorException.TooManyRequests exception) {
            throw new EmailDeliveryClientException(
                    "EMAIL_SERVICE_RATE_LIMITED",
                    true,
                    exception
            );
        } catch (RestClientResponseException exception) {
            throw new EmailDeliveryClientException(
                    "EMAIL_SERVICE_HTTP_" + exception.getStatusCode().value(),
                    exception.getStatusCode().is5xxServerError(),
                    exception
            );
        } catch (EmailDeliveryClientException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new EmailDeliveryClientException(
                    "EMAIL_SERVICE_UNAVAILABLE",
                    true,
                    exception
            );
        }
    }

    @Override
    public Optional<EmailDeliveryReceipt> findDeliveryByIdempotencyKey(
            String idempotencyKey) {
        try {
            HttpEntity<Void> request = new HttpEntity<>(requestHeaders());
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.exchange(
                    serviceUrl("/api/email/delivery/status")
                            + "?idempotencyKey={idempotencyKey}",
                    HttpMethod.GET,
                    request,
                    Map.class,
                    idempotencyKey
            ).getBody();
            return Optional.of(deliveryReceipt(response));
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (RestClientResponseException exception) {
            throw new EmailDeliveryClientException(
                    "EMAIL_STATUS_HTTP_" + exception.getStatusCode().value(),
                    exception.getStatusCode().is5xxServerError(),
                    exception
            );
        } catch (EmailDeliveryClientException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new EmailDeliveryClientException(
                    "EMAIL_STATUS_UNAVAILABLE",
                    true,
                    exception
            );
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

    private EmailDeliveryReceipt deliveryReceipt(Map<String, Object> response) {
        if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
            throw new EmailDeliveryClientException(
                    "EMAIL_SERVICE_REJECTED",
                    false
            );
        }
        Object rawId = response.get("queueId");
        Object rawStatus = response.get("status");
        if (rawId == null || rawStatus == null) {
            throw new EmailDeliveryClientException(
                    "EMAIL_SERVICE_INVALID_RESPONSE",
                    true
            );
        }
        try {
            return new EmailDeliveryReceipt(
                    rawId.toString(),
                    EmailDeliveryReceipt.DeliveryState.valueOf(
                            rawStatus.toString()
                    )
            );
        } catch (IllegalArgumentException exception) {
            throw new EmailDeliveryClientException(
                    "EMAIL_SERVICE_INVALID_RESPONSE",
                    true,
                    exception
            );
        }
    }

    private String serviceUrl(String path) {
        String baseUrl = properties.getUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }
}
