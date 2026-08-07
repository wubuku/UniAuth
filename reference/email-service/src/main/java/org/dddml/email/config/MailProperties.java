package org.dddml.email.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.mail")
@Data
public class MailProperties {

    private String fromEmail;
    private String fromName;
    private boolean enabled = true;

    private Queue queue = new Queue();
    private Retry retry = new Retry();
    private RateLimit rateLimit = new RateLimit();
    private Recovery recovery = new Recovery();

    @Data
    public static class Queue {
        private boolean enabled = true;
        private boolean eventDriven = true;
    }

    @Data
    public static class Retry {
        private int maxAttempts = 3;
        private int delayMinutes = 10;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private int maxPerMinute = 60;
    }

    @Data
    public static class Recovery {
        private boolean enabled = true;
        private int scanIntervalMinutes = 5;
        private int stuckTimeoutMinutes = 10;
    }
}
