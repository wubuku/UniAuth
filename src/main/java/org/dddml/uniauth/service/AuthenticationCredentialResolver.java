package org.dddml.uniauth.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.util.BearerTokenUtils;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthenticationCredentialResolver {

    private final AuthCookieService authCookieService;
    private final TokenValidationService tokenValidationService;

    public Optional<String> resolveAccessToken(HttpServletRequest request) {
        Optional<String> headerToken = authorizationToken(request);
        Optional<String> cookieToken = singleCookie(
                request,
                authCookieService.accessTokenCookieName()
        );
        if (headerToken.isPresent()
                && cookieToken.isPresent()
                && !headerToken.get().equals(cookieToken.get())) {
            throw invalidRequest();
        }
        return headerToken.isPresent() ? headerToken : cookieToken;
    }

    public Optional<String> resolveRefreshToken(HttpServletRequest request) {
        return singleCookie(
                request,
                authCookieService.refreshTokenCookieName()
        );
    }

    public Optional<String> activeFamilyForNewLogin(
            HttpServletRequest request,
            String targetUserId) {
        List<TokenValidationService.ValidatedToken> tokens =
                new ArrayList<>();
        resolveAccessToken(request).flatMap(token -> {
            try {
                return Optional.of(tokenValidationService
                        .decodeAccessToken(token));
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }).map(jwt -> tokenValidationService.accessTokenForRevocation(
                jwt.getTokenValue()
        )).flatMap(value -> value).ifPresent(tokens::add);

        resolveRefreshToken(request).flatMap(token -> {
            try {
                return Optional.of(
                        tokenValidationService.decodeRefreshToken(token)
                );
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }).ifPresent(tokens::add);

        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        String familyId = tokens.get(0).familyId();
        String userId = tokens.get(0).userId();
        for (TokenValidationService.ValidatedToken token : tokens) {
            if (!familyId.equals(token.familyId())
                    || !userId.equals(token.userId())) {
                throw new TokenRejectedException(
                        "Existing browser credentials are inconsistent"
                );
            }
        }
        if (!targetUserId.equals(userId)) {
            throw new TokenRejectedException(
                    "A different user must be logged out first"
            );
        }
        return Optional.of(familyId);
    }

    private Optional<String> authorizationToken(
            HttpServletRequest request) {
        List<String> headers = headerValues(request, "Authorization");
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        if (headers.size() != 1) {
            throw invalidRequest();
        }
        String header = headers.get(0);
        Optional<String> token = BearerTokenUtils.extract(header);
        if (token.isEmpty() || token.get().isBlank()) {
            throw invalidRequest();
        }
        return token;
    }

    private Optional<String> singleCookie(
            HttpServletRequest request,
            String name) {
        List<String> values = new ArrayList<>();
        for (String cookieHeader : headerValues(request, "Cookie")) {
            for (String part : cookieHeader.split(";")) {
                String candidate = part.trim();
                int separator = candidate.indexOf('=');
                if (separator < 0) {
                    continue;
                }
                if (candidate.substring(0, separator).trim().equals(name)) {
                    values.add(candidate.substring(separator + 1));
                }
            }
        }
        if (values.isEmpty()) {
            return Optional.empty();
        }
        if (values.size() != 1 || values.get(0).isBlank()) {
            throw invalidRequest();
        }
        return Optional.of(values.get(0));
    }

    private List<String> headerValues(
            HttpServletRequest request,
            String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null) {
            return List.of();
        }
        return Collections.list(values);
    }

    private OAuth2AuthenticationException invalidRequest() {
        return new OAuth2AuthenticationException(new OAuth2Error(
                "invalid_request",
                "Authentication credentials are ambiguous",
                null
        ));
    }
}
