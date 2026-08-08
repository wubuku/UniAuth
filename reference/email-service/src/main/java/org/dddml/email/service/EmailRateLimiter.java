package org.dddml.email.service;

import lombok.RequiredArgsConstructor;
import org.dddml.email.config.MailProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class EmailRateLimiter {

    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();

    private final MailProperties mailProperties;

    private long windowStartedAt = System.nanoTime();
    private int reserved;

    public synchronized boolean tryAcquire() {
        if (!mailProperties.getRateLimit().isEnabled()) {
            return true;
        }
        resetIfNeeded();
        if (reserved >= mailProperties.getRateLimit().getMaxPerMinute()) {
            return false;
        }
        reserved++;
        return true;
    }

    public synchronized void release() {
        if (mailProperties.getRateLimit().isEnabled() && reserved > 0) {
            reserved--;
        }
    }

    private void resetIfNeeded() {
        long now = System.nanoTime();
        if (now - windowStartedAt >= WINDOW_NANOS) {
            reserved = 0;
            windowStartedAt = now;
        }
    }
}
