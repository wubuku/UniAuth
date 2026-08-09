package org.dddml.uniauth.config;

import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.service.AuthRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.auth.rate-limit.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class AuthRateLimitSchedulingConfig {

    private final AuthRateLimiter rateLimiter;

    @Scheduled(fixedDelayString = "${app.auth.rate-limit.cleanup-delay-ms:60000}")
    public void cleanupExpiredBuckets() {
        rateLimiter.cleanupExpired();
    }
}
