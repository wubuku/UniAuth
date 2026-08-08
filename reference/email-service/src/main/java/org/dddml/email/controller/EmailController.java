package org.dddml.email.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.dddml.email.entity.EmailQueue;
import org.dddml.email.entity.EmailLog;
import org.dddml.email.repository.EmailLogRepository;
import org.dddml.email.service.EmailQueueService;
import org.dddml.email.service.EmailService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/email")
@Slf4j
@Validated
@RequiredArgsConstructor
public class EmailController {

    private final EmailService emailService;
    private final EmailQueueService emailQueueService;
    private final EmailLogRepository emailLogRepository;

    @PostMapping("/simple")
    public ResponseEntity<Map<String, Object>> sendSimpleEmail(
            @Valid @RequestBody SimpleEmailRequest request) {
        EmailQueue queue = emailService.sendSimpleHtmlEmail(
            request.getTo(),
            request.getSubject(),
            request.getHtmlContent()
        );

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Email enqueued, will be sent immediately");
        response.put("queueId", queue.getId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/template")
    public ResponseEntity<Map<String, Object>> sendTemplateEmail(
            @Valid @RequestBody TemplateEmailRequest request) {
        EmailQueue queue = emailService.sendEmailAsync(
            request.getTo(),
            request.getSubject(),
            request.getTemplateName(),
            request.getVariables(),
            request.getEmailType()
        );

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Template email enqueued");
        response.put("queueId", queue.getId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> sendBatchEmails(
            @Valid @RequestBody BatchEmailRequest request) {
        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (BatchEmailRequest.EmailItem item : request.getEmails()) {
            try {
                EmailQueue queue = emailService.sendSimpleHtmlEmail(
                    item.getTo(),
                    item.getSubject(),
                    item.getHtmlContent()
                );
                Map<String, Object> result = new HashMap<>();
                result.put("to", item.getTo());
                result.put("success", true);
                result.put("queueId", queue.getId());
                results.add(result);
                successCount++;
            } catch (Exception exception) {
                log.warn("Batch email item was rejected");
                Map<String, Object> result = new HashMap<>();
                result.put("to", item.getTo());
                result.put("success", false);
                result.put("error", "Email enqueue failed");
                results.add(result);
                failCount++;
            }
        }

        response.put("success", failCount == 0);
        response.put("total", request.getEmails().size());
        response.put("successCount", successCount);
        response.put("failCount", failCount);
        response.put("results", results);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateEmail(
            @Valid @RequestBody ValidateEmailRequest request) {
        String email = request.getEmail();
        boolean valid = emailService.isValidEmail(email);

        Map<String, Object> response = new HashMap<>();
        response.put("email", email);
        response.put("valid", valid);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/queue/stats")
    public ResponseEntity<Map<String, Object>> getQueueStats() {
        EmailQueueService.QueueStats stats = emailQueueService.getStats();

        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        List<Object[]> sendMethodStats = emailLogRepository.countBySendMethodSince(oneHourAgo);

        Map<String, Long> methodCounts = new HashMap<>();
        for (Object[] row : sendMethodStats) {
            methodCounts.put((String) row[0], (Long) row[1]);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("pending", stats.getPending());
        response.put("processing", stats.getProcessing());
        response.put("completed", stats.getCompleted());
        response.put("failed", stats.getFailed());
        response.put("eventDrivenCount", methodCounts.getOrDefault("EVENT", 0L));
        response.put("scheduledCount", methodCounts.getOrDefault("SCHEDULED", 0L));

        return ResponseEntity.ok(response);
    }

    @GetMapping("/queue/{id}")
    public ResponseEntity<Map<String, Object>> getQueueDetail(@Positive @PathVariable Long id) {
        return emailQueueService.findById(id)
            .map(queue -> {
                Map<String, Object> response = new HashMap<>();
                response.put("id", queue.getId());
                response.put("recipient", queue.getRecipient());
                response.put("subject", queue.getSubject());
                response.put("status", queue.getStatus());
                response.put("emailType", queue.getEmailType());
                response.put("priority", queue.getPriority());
                response.put("retryCount", queue.getRetryCount());
                response.put("maxRetries", queue.getMaxRetries());
                response.put("errorMessage", queue.getErrorMessage());
                response.put("createdTime", queue.getCreatedTime());
                response.put("updatedTime", queue.getUpdatedTime());
                response.put("processedTime", queue.getProcessedTime());
                return ResponseEntity.ok(response);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> getEmailLogs(
            @Size(max = 20) @RequestParam(required = false) String status,
            @Min(1) @RequestParam(defaultValue = "1") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(
            page - 1,
            size,
            Sort.by(Sort.Direction.DESC, "sentTime")
                .and(Sort.by(Sort.Direction.DESC, "id"))
        );
        Page<EmailLog> logs;
        if (status != null) {
            logs = emailLogRepository.findByStatus(status, pageable);
        } else {
            logs = emailLogRepository.findAll(pageable);
        }

        List<Map<String, Object>> logList = logs.getContent().stream()
            .map(log -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", log.getId());
                item.put("queueId", log.getQueueId());
                item.put("recipient", log.getRecipient());
                item.put("subject", log.getSubject());
                item.put("status", log.getStatus());
                item.put("emailType", log.getEmailType());
                item.put("mailProvider", log.getMailProvider());
                item.put("sendMethod", log.getSendMethod());
                item.put("durationMs", log.getDurationMs());
                item.put("sentTime", log.getSentTime());
                return item;
            })
            .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("logs", logList);
        response.put("total", logs.getTotalElements());
        response.put("page", page);
        response.put("size", size);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/templates")
    public ResponseEntity<List<String>> getAvailableTemplates() {
        List<String> templates = List.of(
            "email/welcome",
            "email/password-reset",
            "email/email-verify"
        );
        return ResponseEntity.ok(templates);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "email-service");
        response.put("timestamp", LocalDateTime.now());
        return ResponseEntity.ok(response);
    }

    @Getter
    @Setter
    public static class SimpleEmailRequest {
        @NotBlank
        @Email
        @Size(max = 255)
        private String to;

        @NotBlank
        @Size(max = 500)
        @Pattern(regexp = "^[^\\r\\n]*$")
        private String subject;

        @NotBlank
        @Size(max = 1_000_000)
        private String htmlContent;
    }

    @Getter
    @Setter
    public static class TemplateEmailRequest {
        @NotBlank
        @Email
        @Size(max = 255)
        private String to;

        @NotBlank
        @Size(max = 500)
        @Pattern(regexp = "^[^\\r\\n]*$")
        private String subject;

        @NotBlank
        @Pattern(regexp = "^email/(welcome|password-reset|email-verify)$")
        private String templateName;

        @NotNull
        @Size(max = 50)
        private Map<String, Object> variables;

        @Size(max = 50)
        @Pattern(regexp = "^[A-Za-z0-9_-]*$")
        private String emailType;
    }

    @Getter
    @Setter
    public static class ValidateEmailRequest {
        @Size(max = 255)
        private String email;
    }

    @Getter
    @Setter
    public static class BatchEmailRequest {
        @NotEmpty
        @Size(max = 100)
        @Valid
        private List<EmailItem> emails;

        @Getter
        @Setter
        public static class EmailItem {
            @NotBlank
            @Email
            @Size(max = 255)
            private String to;

            @NotBlank
            @Size(max = 500)
            @Pattern(regexp = "^[^\\r\\n]*$")
            private String subject;

            @NotBlank
            @Size(max = 1_000_000)
            private String htmlContent;
        }
    }
}
