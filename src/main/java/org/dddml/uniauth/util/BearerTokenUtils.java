package org.dddml.uniauth.util;

import java.util.Optional;

public final class BearerTokenUtils {

    private static final String BEARER_PREFIX = "Bearer ";

    private BearerTokenUtils() {
    }

    public static Optional<String> extract(String authorizationHeader) {
        if (authorizationHeader == null
                || authorizationHeader.length() <= BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(
                        true,
                        0,
                        BEARER_PREFIX,
                        0,
                        BEARER_PREFIX.length()
                )) {
            return Optional.empty();
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length());
        return token.isBlank() ? Optional.empty() : Optional.of(token);
    }
}
