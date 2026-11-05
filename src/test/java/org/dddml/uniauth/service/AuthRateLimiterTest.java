package org.dddml.uniauth.service;

import org.dddml.uniauth.config.AuthRateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthRateLimiterTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AuthRateLimitProperties properties;
    private AuthRateLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new AuthRateLimitProperties();
        properties.setKeySecret("0123456789abcdef0123456789abcdef");
        limiter = new AuthRateLimiter(jdbcTemplate, properties);
    }

    @Test
    void disabledRateLimiterBypassesTheDatabase() {
        properties.setEnabled(false);

        assertThatCode(() -> limiter.requireAllowed(
                AuthRateLimiter.Policy.LOGIN,
                "10.0.0.1",
                "user"
        )).doesNotThrowAnyException();

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void exhaustedWindowReturnsRetryAfterFromTheStoredExpiry() {
        Instant expiry = Instant.now().plusSeconds(30);
        doReturn(List.of()).when(jdbcTemplate).query(
                anyString(),
                any(RowMapper.class),
                any(),
                any(),
                any(),
                any(),
                any()
        );
        doReturn(expiry).when(jdbcTemplate).queryForObject(
                anyString(),
                any(RowMapper.class),
                any()
        );

        assertThatThrownBy(() -> limiter.requireAllowed(
                AuthRateLimiter.Policy.LOGIN,
                "10.0.0.1",
                "user"
        )).isInstanceOfSatisfying(
                AuthRateLimitExceededException.class,
                exception -> assertThat(exception.getRetryAfterSeconds())
                        .isBetween(1L, 30L)
        );
    }

    @Test
    void databaseFailureWhileReservingFailsClosedAsUnavailable() {
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("database down");
        doThrow(failure).when(jdbcTemplate).query(
                anyString(),
                any(RowMapper.class),
                any(),
                any(),
                any(),
                any(),
                any()
        );

        assertThatThrownBy(() -> limiter.requireAllowed(
                AuthRateLimiter.Policy.LOGIN,
                "10.0.0.1",
                "user"
        )).isInstanceOfSatisfying(
                AuthRateLimiterUnavailableException.class,
                exception -> assertThat(exception.getCause()).isSameAs(failure)
        );
    }

    @Test
    void allowedReservationDoesNotThrowAndConsumesSourceAndIdentity() {
        doReturn(List.of(new Object())).when(jdbcTemplate).query(
                anyString(),
                any(RowMapper.class),
                any(),
                any(),
                any(),
                any(),
                any()
        );

        assertThatCode(() -> limiter.requireAllowed(
                AuthRateLimiter.Policy.LOGIN,
                " 10.0.0.1 ",
                " User.Name "
        )).doesNotThrowAnyException();

        verify(jdbcTemplate, times(2)).query(
                anyString(),
                any(RowMapper.class),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void cleanupExpiredReturnsDeletedCountAndFailsClosedOnDatabaseError() {
        when(jdbcTemplate.update(anyString(), any(Timestamp.class)))
                .thenReturn(3);
        assertThat(limiter.cleanupExpired()).isEqualTo(3);

        doThrow(new DataAccessResourceFailureException("database down"))
                .when(jdbcTemplate)
                .update(anyString(), any(Timestamp.class));
        assertThatThrownBy(() -> limiter.cleanupExpired())
                .isInstanceOfSatisfying(
                        AuthRateLimiterUnavailableException.class,
                        exception -> assertThat(exception.getCause())
                                .isInstanceOf(DataAccessResourceFailureException.class)
                );
    }

    @Test
    void protectedKeyNormalizesAndBoundsItsInput() {
        assertThat(limiter.protectedKey("ns", "ABC"))
                .isEqualTo(limiter.protectedKey("ns", "abc"));
        assertThat(limiter.protectedKey(null, null))
                .isEqualTo(limiter.protectedKey("unknown", "unknown"));
        assertThat(limiter.protectedKey("ns", "value"))
                .isNotEqualTo(limiter.protectedKey("other", "value"));

        // Values longer than 512 characters are truncated, not rejected.
        assertThat(limiter.protectedKey("ns", "a".repeat(512) + "x"))
                .isEqualTo(limiter.protectedKey("ns", "a".repeat(512) + "y"));
        // Namespaces longer than 64 characters are truncated as well.
        assertThat(limiter.protectedKey("n".repeat(64) + "x", "value"))
                .isEqualTo(limiter.protectedKey("n".repeat(64) + "y", "value"));
    }
}
