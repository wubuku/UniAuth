package org.dddml.uniauth.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "app.auth.recent-auth")
@Validated
public class RecentAuthenticationProperties {

    @Min(30)
    @Max(86400)
    private long maxAgeSeconds = 600;

    @Min(0)
    @Max(300)
    private long futureSkewSeconds = 30;

    public long getMaxAgeSeconds() {
        return maxAgeSeconds;
    }

    public void setMaxAgeSeconds(long maxAgeSeconds) {
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public long getFutureSkewSeconds() {
        return futureSkewSeconds;
    }

    public void setFutureSkewSeconds(long futureSkewSeconds) {
        this.futureSkewSeconds = futureSkewSeconds;
    }
}
