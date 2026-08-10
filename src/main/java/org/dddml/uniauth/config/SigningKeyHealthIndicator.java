package org.dddml.uniauth.config;

import org.dddml.uniauth.service.JwtTokenService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("signingKeyHealthIndicator")
public class SigningKeyHealthIndicator implements HealthIndicator {

    private final JwtTokenService jwtTokenService;

    public SigningKeyHealthIndicator(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @Override
    public Health health() {
        if (jwtTokenService.getPrivateKey() == null
                || jwtTokenService.getPublicKey() == null
                || jwtTokenService.getToken() == null
                || jwtTokenService.getToken().getKid() == null
                || jwtTokenService.getToken().getKid().isBlank()) {
            return Health.down().build();
        }
        return Health.up().build();
    }
}
