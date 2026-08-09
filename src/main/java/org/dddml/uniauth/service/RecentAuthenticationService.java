package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.config.RecentAuthenticationProperties;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RecentAuthenticationService {

    private final RecentAuthenticationProperties properties;

    public void requireRecent(Jwt jwt) {
        Object claim = jwt.getClaims().get("auth_time");
        if (!(claim instanceof Number number)) {
            throw new RecentAuthenticationRequiredException();
        }
        requireRecent(Instant.ofEpochSecond(number.longValue()));
    }

    public void requireRecent(TokenValidationService.ValidatedToken token) {
        requireRecent(token.authTime());
    }

    public void requireRecent(Instant authTime) {
        if (authTime == null) {
            throw new RecentAuthenticationRequiredException();
        }
        Instant now = Instant.now();
        if (authTime.isAfter(now.plusSeconds(properties.getFutureSkewSeconds()))
                || authTime.isBefore(now.minusSeconds(properties.getMaxAgeSeconds()))) {
            throw new RecentAuthenticationRequiredException();
        }
    }
}
