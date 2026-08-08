package org.dddml.email.config;

import lombok.Data;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "app.mail")
@Validated
@Data
public class MailProperties {

    @NotBlank
    @Email
    @Size(max = 255)
    private String fromEmail;

    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "^[^\\r\\n]*$")
    private String fromName;

    private boolean enabled = true;

    @Valid
    private Queue queue = new Queue();

    @Valid
    private Retry retry = new Retry();

    @Valid
    private RateLimit rateLimit = new RateLimit();

    @Valid
    private Recovery recovery = new Recovery();

    @Data
    public static class Queue {
        private boolean enabled = true;
        private boolean eventDriven = true;
    }

    @Data
    public static class Retry {
        @Min(0)
        @Max(100)
        private int maxAttempts = 3;

        @Min(0)
        @Max(10080)
        private int delayMinutes = 10;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;

        @Min(1)
        private int maxPerMinute = 60;
    }

    @Data
    public static class Recovery {
        private boolean enabled = true;

        @Min(1)
        @Max(10080)
        private int scanIntervalMinutes = 5;

        @Min(1)
        @Max(10080)
        private int stuckTimeoutMinutes = 10;
    }
}
