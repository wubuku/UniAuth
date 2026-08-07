package org.dddml.email.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "email_logs", indexes = {
    @Index(name = "idx_email_logs_status", columnList = "status"),
    @Index(name = "idx_email_logs_recipient", columnList = "recipient"),
    @Index(name = "idx_email_logs_sent_time", columnList = "sent_time"),
    @Index(name = "idx_email_logs_queue_id", columnList = "queue_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "queue_id")
    private Long queueId;

    @Column(nullable = false, length = 255)
    private String recipient;

    @Column(nullable = false, length = 500)
    private String subject;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(nullable = false)
    private LocalDateTime sentTime;

    @Column(nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(columnDefinition = "TEXT")
    private String emailContent;

    @Column(length = 50)
    private String emailType;

    @Column(length = 100)
    private String mailProvider;

    private Long durationMs;

    @Column(length = 20)
    private String sendMethod;
}
