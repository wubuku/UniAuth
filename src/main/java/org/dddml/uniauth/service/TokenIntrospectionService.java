package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TokenIntrospectionService {

    private final TokenValidationService tokenValidationService;
    private final SecurityEventService securityEventService;

    @Transactional
    public Optional<TokenValidationService.IntrospectedToken> introspect(
            String tokenValue) {
        try {
            TokenValidationService.IntrospectedToken token =
                    tokenValidationService.introspect(tokenValue);
            securityEventService.append(
                    "TOKEN_INTROSPECTION_SUCCEEDED",
                    token.subject(),
                    SecurityEventService.Outcome.SUCCESS,
                    null
            );
            return Optional.of(token);
        } catch (RuntimeException exception) {
            securityEventService.append(
                    "TOKEN_INTROSPECTION_DENIED",
                    null,
                    SecurityEventService.Outcome.DENIED,
                    "INVALID_TOKEN"
            );
            return Optional.empty();
        }
    }
}
