package org.dddml.email.service;

import org.dddml.email.config.MailProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class EmailRateLimiter {

    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();
    private static final Reservation UNLIMITED_RESERVATION =
        new Reservation(null, 0, false);

    private final MailProperties mailProperties;

    private long windowStartedAt = System.nanoTime();
    private long windowGeneration;
    private int reserved;

    public EmailRateLimiter(MailProperties mailProperties) {
        this.mailProperties = mailProperties;
    }

    public synchronized Reservation tryAcquire() {
        if (!mailProperties.getRateLimit().isEnabled()) {
            return UNLIMITED_RESERVATION;
        }
        resetIfNeeded();
        if (reserved >= mailProperties.getRateLimit().getMaxPerMinute()) {
            return null;
        }
        reserved++;
        return new Reservation(this, windowGeneration, true);
    }

    private synchronized void release(long reservationGeneration) {
        if (windowGeneration == reservationGeneration && reserved > 0) {
            reserved--;
        }
    }

    private void resetIfNeeded() {
        long now = System.nanoTime();
        if (now - windowStartedAt >= WINDOW_NANOS) {
            reserved = 0;
            windowStartedAt = now;
            windowGeneration++;
        }
    }

    public static final class Reservation {

        private final EmailRateLimiter owner;
        private final long windowGeneration;
        private boolean active;

        private Reservation(
                EmailRateLimiter owner,
                long windowGeneration,
                boolean active) {
            this.owner = owner;
            this.windowGeneration = windowGeneration;
            this.active = active;
        }

        public synchronized void release() {
            if (!active) {
                return;
            }
            active = false;
            owner.release(windowGeneration);
        }
    }
}
