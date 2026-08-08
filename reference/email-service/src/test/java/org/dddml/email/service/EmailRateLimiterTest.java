package org.dddml.email.service;

import org.dddml.email.config.MailProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class EmailRateLimiterTest {

    private MailProperties mailProperties;
    private EmailRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        mailProperties = new MailProperties();
        mailProperties.getRateLimit().setEnabled(true);
        mailProperties.getRateLimit().setMaxPerMinute(1);
        rateLimiter = new EmailRateLimiter(mailProperties);
    }

    @Test
    void previousWindowReleaseDoesNotFreeTheCurrentWindowReservation() {
        EmailRateLimiter.Reservation previousWindow = rateLimiter.tryAcquire();
        assertThat(previousWindow).isNotNull();

        expireCurrentWindow();
        EmailRateLimiter.Reservation currentWindow = rateLimiter.tryAcquire();
        assertThat(currentWindow).isNotNull();

        previousWindow.release();

        assertThat(rateLimiter.tryAcquire()).isNull();
        currentWindow.release();
    }

    @Test
    void releaseAfterTemporaryDisableStillFreesTheOriginalReservation() {
        EmailRateLimiter.Reservation reservation = rateLimiter.tryAcquire();
        assertThat(reservation).isNotNull();

        mailProperties.getRateLimit().setEnabled(false);
        reservation.release();
        mailProperties.getRateLimit().setEnabled(true);

        assertThat(rateLimiter.tryAcquire()).isNotNull();
    }

    @Test
    void currentWindowReleaseMakesTheSlotReusable() {
        EmailRateLimiter.Reservation reservation = rateLimiter.tryAcquire();
        assertThat(reservation).isNotNull();
        assertThat(rateLimiter.tryAcquire()).isNull();

        reservation.release();

        assertThat(rateLimiter.tryAcquire()).isNotNull();
    }

    @Test
    void duplicateReleaseDoesNotFreeAnotherReservation() {
        mailProperties.getRateLimit().setMaxPerMinute(2);
        EmailRateLimiter.Reservation first = rateLimiter.tryAcquire();
        EmailRateLimiter.Reservation second = rateLimiter.tryAcquire();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();

        first.release();
        first.release();
        assertThat(rateLimiter.tryAcquire()).isNotNull();

        assertThat(rateLimiter.tryAcquire()).isNull();
    }

    @Test
    void disabledLimiterReturnsANonCountingReservation() {
        mailProperties.getRateLimit().setEnabled(false);
        EmailRateLimiter.Reservation unlimited = rateLimiter.tryAcquire();
        assertThat(unlimited).isNotNull();
        unlimited.release();

        mailProperties.getRateLimit().setEnabled(true);
        assertThat(rateLimiter.tryAcquire()).isNotNull();
        assertThat(rateLimiter.tryAcquire()).isNull();
    }

    @Test
    void configuredLimitRejectsAdditionalReservations() {
        assertThat(rateLimiter.tryAcquire()).isNotNull();
        assertThat(rateLimiter.tryAcquire()).isNull();
    }

    private void expireCurrentWindow() {
        ReflectionTestUtils.setField(
            rateLimiter,
            "windowStartedAt",
            System.nanoTime() - Duration.ofMinutes(1).toNanos()
        );
    }
}
