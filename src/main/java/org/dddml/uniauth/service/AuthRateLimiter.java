package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.config.AuthRateLimitProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthRateLimiter {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final JdbcTemplate jdbcTemplate;
    private final AuthRateLimitProperties properties;

    public void requireAllowed(
            Policy policy,
            String trustedSource,
            String identity) {
        if (!properties.isEnabled()) {
            return;
        }
        String source = bounded(
                trustedSource == null || trustedSource.isBlank()
                        ? "unknown"
                        : trustedSource.trim(),
                255
        );
        String normalizedIdentity = bounded(
                identity == null
                        ? "anonymous"
                        : identity.trim().toLowerCase(Locale.ROOT),
                512
        );
        reserve(
                "source:" + digest(policy.name() + "|" + source),
                properties.getSourceLimit()
        );
        reserve(
                "identity:" + digest(
                        policy.name()
                                + "|" + source
                                + "|" + normalizedIdentity
                ),
                limit(policy)
        );
    }

    public int cleanupExpired() {
        try {
            return jdbcTemplate.update(
                    "DELETE FROM auth_rate_limits WHERE expires_at <= ?",
                    Timestamp.from(Instant.now())
            );
        } catch (DataAccessException exception) {
            throw new AuthRateLimiterUnavailableException(exception);
        }
    }

    public String protectedKey(String namespace, String value) {
        String normalizedNamespace = bounded(
                namespace == null ? "unknown" : namespace.trim(),
                64
        );
        String normalizedValue = bounded(
                value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT),
                512
        );
        return digest(normalizedNamespace + "|" + normalizedValue);
    }

    private void reserve(String bucketKey, int limit) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.getWindowSeconds());
        try {
            List<Reservation> reservations = jdbcTemplate.query(
                """
                INSERT INTO auth_rate_limits (
                    bucket_key,
                    window_started_at,
                    request_count,
                    expires_at,
                    updated_at
                )
                VALUES (?, ?, 1, ?, ?)
                ON CONFLICT (bucket_key) DO UPDATE
                SET window_started_at = CASE
                        WHEN auth_rate_limits.expires_at <= EXCLUDED.window_started_at
                            THEN EXCLUDED.window_started_at
                        ELSE auth_rate_limits.window_started_at
                    END,
                    request_count = CASE
                        WHEN auth_rate_limits.expires_at <= EXCLUDED.window_started_at
                            THEN 1
                        ELSE auth_rate_limits.request_count + 1
                    END,
                    expires_at = CASE
                        WHEN auth_rate_limits.expires_at <= EXCLUDED.window_started_at
                            THEN EXCLUDED.expires_at
                        ELSE auth_rate_limits.expires_at
                    END,
                    updated_at = EXCLUDED.updated_at
                WHERE auth_rate_limits.expires_at <= EXCLUDED.window_started_at
                   OR auth_rate_limits.request_count < ?
                RETURNING request_count, expires_at
                """,
                (resultSet, rowNumber) -> new Reservation(
                        resultSet.getInt("request_count"),
                        resultSet.getTimestamp("expires_at").toInstant()
                ),
                bucketKey,
                Timestamp.from(now),
                Timestamp.from(expiresAt),
                Timestamp.from(now),
                limit
            );
            if (reservations.isEmpty()) {
                Instant currentExpiry = jdbcTemplate.queryForObject(
                        """
                        SELECT expires_at
                        FROM auth_rate_limits
                        WHERE bucket_key = ?
                        """,
                        (resultSet, rowNumber) ->
                                resultSet.getTimestamp(1).toInstant(),
                        bucketKey
                );
                long retryAfter = Math.max(
                        1,
                        Duration.between(
                                Instant.now(),
                                currentExpiry
                        ).toSeconds()
                );
                throw new AuthRateLimitExceededException(retryAfter);
            }
        } catch (AuthRateLimitExceededException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new AuthRateLimiterUnavailableException(exception);
        }
    }

    private int limit(Policy policy) {
        return switch (policy) {
            case LOGIN -> properties.getLoginLimit();
            case REGISTRATION -> properties.getRegistrationLimit();
            case CHALLENGE_SEND -> properties.getChallengeSendLimit();
            case CHALLENGE_VERIFY -> properties.getChallengeVerifyLimit();
            case PASSWORD_RESET_SEND, PASSWORD_RESET_VERIFY ->
                    properties.getPasswordResetLimit();
            case REFRESH -> properties.getRefreshLimit();
            case INTROSPECTION -> properties.getIntrospectionLimit();
            case OAUTH_AUTHORIZE -> properties.getOauthAuthorizeLimit();
            case WEB3_CHALLENGE -> properties.getWeb3ChallengeLimit();
            case WEB3_VERIFY -> properties.getWeb3VerifyLimit();
            case LOGIN_METHOD_MUTATION ->
                    properties.getLoginMethodMutationLimit();
        };
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.getKeySecret().getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            ));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Authentication rate limit key protection failed",
                    exception
            );
        }
    }

    private String bounded(String value, int maxLength) {
        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }

    public enum Policy {
        LOGIN,
        REGISTRATION,
        CHALLENGE_SEND,
        CHALLENGE_VERIFY,
        PASSWORD_RESET_SEND,
        PASSWORD_RESET_VERIFY,
        REFRESH,
        INTROSPECTION,
        OAUTH_AUTHORIZE,
        WEB3_CHALLENGE,
        WEB3_VERIFY,
        LOGIN_METHOD_MUTATION
    }

    private record Reservation(int count, Instant expiresAt) {
    }
}
