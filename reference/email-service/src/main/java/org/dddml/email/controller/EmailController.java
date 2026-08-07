package org.dddml.email.controller;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.dddml.email.entity.EmailQueue;
import org.dddml.email.entity.EmailLog;
import org.dddml.email.repository.EmailLogRepository;
import org.dddml.email.service.EmailQueueService;
import org.dddml.email.service.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/email")
@Slf4j
public class EmailController {

    @Autowired
    private EmailService emailService;

    @Autowired
    private EmailQueueService emailQueueService;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @PostMapping("/simple")
    public ResponseEntity<Map<String, Object>> sendSimpleEmail(@RequestBody SimpleEmailRequest request) {
        try {
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
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/template")
    public ResponseEntity<Map<String, Object>> sendTemplateEmail(@RequestBody TemplateEmailRequest request) {
        try {
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
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> sendBatchEmails(@RequestBody BatchEmailRequest request) {
        try {
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
                } catch (Exception e) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("to", item.getTo());
                    result.put("success", false);
                    result.put("error", e.getMessage());
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
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateEmail(@RequestBody Map<String, String> request) {
        String email = request.get("email");
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
    public ResponseEntity<Map<String, Object>> getQueueDetail(@PathVariable Long id) {
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
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<EmailLog> logs;
        if (status != null) {
            logs = emailLogRepository.findByStatus(status);
        } else {
            logs = emailLogRepository.findAll();
        }

        int start = (page - 1) * size;
        int end = Math.min(start + size, logs.size());
        List<EmailLog> pageLogs = logs.subList(Math.min(start, logs.size()), Math.min(end, logs.size()));

        List<Map<String, Object>> logList = pageLogs.stream()
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
        response.put("total", logs.size());
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

    @Data
    public static class SimpleEmailRequest {
        private String to;
        private String subject;
        private String htmlContent;
    }

    @Data
    public static class TemplateEmailRequest {
        private String to;
        private String subject;
        private String templateName;
        private Map<String, Object> variables;
        private String emailType;
    }

    @Data
    public static class BatchEmailRequest {
        private List<EmailItem> emails;

        @Data
        public static class EmailItem {
            private String to;
            private String subject;
            private String htmlContent;
        }
    }
}
