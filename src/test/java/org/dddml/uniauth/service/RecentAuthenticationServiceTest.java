package org.dddml.uniauth.service;

import org.dddml.uniauth.config.RecentAuthenticationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecentAuthenticationServiceTest {

    private RecentAuthenticationService service;

    @BeforeEach
    void setUp() {
        RecentAuthenticationProperties properties = new RecentAuthenticationProperties();
        properties.setMaxAgeSeconds(600);
        properties.setFutureSkewSeconds(30);
        service = new RecentAuthenticationService(properties);
    }

    @Test
    void currentTimeIsAccepted() {
        assertThatCode(() -> service.requireRecent(Instant.now()))
                .doesNotThrowAnyException();
    }

    @Test
    void authenticationOlderThanMaxAgeIsRejected() {
        assertThatThrownBy(() -> service.requireRecent(
                Instant.now().minusSeconds(601)))
                .isInstanceOf(RecentAuthenticationRequiredException.class);
    }

    @Test
    void authenticationJustInsideMaxAgeIsAccepted() {
        assertThatCode(() -> service.requireRecent(
                Instant.now().minusSeconds(599)))
                .doesNotThrowAnyException();
    }

    @Test
    void authenticationBeyondFutureSkewIsRejected() {
        assertThatThrownBy(() -> service.requireRecent(
                Instant.now().plusSeconds(31)))
                .isInstanceOf(RecentAuthenticationRequiredException.class);
    }

    @Test
    void authenticationInsideFutureSkewIsAccepted() {
        assertThatCode(() -> service.requireRecent(
                Instant.now().plusSeconds(29)))
                .doesNotThrowAnyException();
    }

    @Test
    void missingAuthenticationTimeIsRejected() {
        assertThatThrownBy(() -> service.requireRecent((Instant) null))
                .isInstanceOf(RecentAuthenticationRequiredException.class);
    }

    @Test
    void jwtWithoutNumericAuthenticationTimeIsRejected() {
        Jwt missingClaim = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "user")
                .build();
        Jwt nonNumericClaim = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("auth_time", "not-a-number")
                .build();

        assertThatThrownBy(() -> service.requireRecent(missingClaim))
                .isInstanceOf(RecentAuthenticationRequiredException.class);
        assertThatThrownBy(() -> service.requireRecent(nonNumericClaim))
                .isInstanceOf(RecentAuthenticationRequiredException.class);
    }

    @Test
    void numericJwtAuthenticationTimeIsEvaluated() {
        Jwt stale = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("auth_time", Instant.now().minusSeconds(601).getEpochSecond())
                .build();
        Jwt fresh = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("auth_time", Instant.now().getEpochSecond())
                .build();

        assertThatThrownBy(() -> service.requireRecent(stale))
                .isInstanceOf(RecentAuthenticationRequiredException.class);
        assertThatCode(() -> service.requireRecent(fresh))
                .doesNotThrowAnyException();
    }
}
