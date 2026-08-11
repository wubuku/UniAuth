package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.config.OAuth2BindingProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OAuth2BindingIntentService {

    private final JdbcTemplate jdbcTemplate;
    private final OAuth2BindingProperties properties;

    @Transactional
    public void create(
            String state,
            String sessionId,
            String provider,
            TokenValidationService.ValidatedToken token) {
        requireValue(state, 512);
        requireValue(sessionId, 512);
        requireProvider(provider);
        if (token.authTime() == null) {
            throw new RecentAuthenticationRequiredException();
        }
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                DELETE FROM oauth2_binding_intents
                WHERE expires_at <= ?
                   OR consumed_at IS NOT NULL
                """,
                Timestamp.from(now)
        );
        jdbcTemplate.update(
                """
                INSERT INTO oauth2_binding_intents (
                    id,
                    state_hash,
                    session_id_hash,
                    provider,
                    user_id,
                    security_version,
                    auth_time,
                    expires_at,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                digest(state),
                digest(sessionId),
                provider,
                token.userId(),
                token.securityVersion(),
                Timestamp.from(token.authTime()),
                Timestamp.from(now.plusSeconds(
                        properties.getExpirationSeconds()
                )),
                Timestamp.from(now)
        );
    }

    @Transactional
    public int rotateSession(String previousSessionId, String currentSessionId) {
        requireValue(previousSessionId, 512);
        requireValue(currentSessionId, 512);
        if (previousSessionId.equals(currentSessionId)) {
            return 0;
        }
        return jdbcTemplate.update(
                """
                UPDATE oauth2_binding_intents
                SET session_id_hash = ?
                WHERE session_id_hash = ?
                  AND consumed_at IS NULL
                  AND expires_at > ?
                """,
                digest(currentSessionId),
                digest(previousSessionId),
                Timestamp.from(Instant.now())
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BindingContext> consume(
            String state,
            String sessionId,
            String provider) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        requireValue(state, 512);
        requireValue(sessionId, 512);
        requireProvider(provider);
        String stateHash = digest(state);
        Integer existing = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM oauth2_binding_intents
                WHERE state_hash = ?
                """,
                Integer.class,
                stateHash
        );
        if (existing == null || existing == 0) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        List<BindingContext> consumed = jdbcTemplate.query(
                """
                UPDATE oauth2_binding_intents i
                SET consumed_at = ?
                FROM users u
                WHERE i.state_hash = ?
                  AND i.session_id_hash = ?
                  AND i.provider = ?
                  AND i.user_id = u.id
                  AND i.consumed_at IS NULL
                  AND i.expires_at > ?
                  AND u.enabled IS TRUE
                  AND u.token_security_version = i.security_version
                RETURNING
                    i.user_id,
                    i.security_version,
                    i.auth_time
                """,
                (resultSet, rowNumber) -> new BindingContext(
                        resultSet.getString("user_id"),
                        resultSet.getLong("security_version"),
                        resultSet.getTimestamp("auth_time").toInstant()
                ),
                Timestamp.from(now),
                stateHash,
                digest(sessionId),
                provider,
                Timestamp.from(now)
        );
        if (consumed.size() != 1) {
            throw new OAuth2BindingIntentRejectedException();
        }
        return Optional.of(consumed.get(0));
    }

    private void requireProvider(String provider) {
        if (!List.of("google", "github", "x").contains(provider)) {
            throw new OAuth2BindingIntentRejectedException();
        }
    }

    private void requireValue(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new OAuth2BindingIntentRejectedException();
        }
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record BindingContext(
            String userId,
            long securityVersion,
            Instant authTime) {
    }
}
