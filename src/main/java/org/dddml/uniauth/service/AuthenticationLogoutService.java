package org.dddml.uniauth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.util.BearerTokenUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthenticationLogoutService {

    private final TokenValidationService tokenValidationService;
    private final TokenRevocationService tokenRevocationService;
    private final AuthCookieService authCookieService;

    public LogoutResult logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        boolean revocationComplete = true;
        try {
            Map<String, TokenValidationService.ValidatedToken> tokens =
                    collectRevocableTokens(request);
            tokenRevocationService.revokeTokens(
                    tokens.values(),
                    TokenRevocationService.REASON_LOGOUT
            );
        } catch (RuntimeException exception) {
            revocationComplete = false;
            log.warn("Token revocation could not be persisted during logout");
        } finally {
            clearLocalAuthentication(request, response);
        }
        return new LogoutResult(revocationComplete);
    }

    private Map<String, TokenValidationService.ValidatedToken> collectRevocableTokens(
            HttpServletRequest request) {
        Map<String, TokenValidationService.ValidatedToken> tokens =
                new LinkedHashMap<>();

        String authorization = request.getHeader("Authorization");
        BearerTokenUtils.extract(authorization)
                .flatMap(tokenValidationService::accessTokenForRevocation)
                .ifPresent(token -> tokens.put(token.jti(), token));

        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return tokens;
        }
        for (Cookie cookie : cookies) {
            if (AuthCookieService.ACCESS_TOKEN_COOKIE.equals(cookie.getName())) {
                add(
                        tokens,
                        tokenValidationService.accessTokenForRevocation(
                                cookie.getValue()
                        )
                );
            } else if (AuthCookieService.REFRESH_TOKEN_COOKIE.equals(cookie.getName())) {
                add(
                        tokens,
                        tokenValidationService.refreshTokenForRevocation(
                                cookie.getValue()
                        )
                );
            }
        }
        return tokens;
    }

    private void add(
            Map<String, TokenValidationService.ValidatedToken> tokens,
            java.util.Optional<TokenValidationService.ValidatedToken> candidate) {
        candidate.ifPresent(token -> tokens.putIfAbsent(token.jti(), token));
    }

    private void clearLocalAuthentication(
            HttpServletRequest request,
            HttpServletResponse response) {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        try {
            new SecurityContextLogoutHandler().logout(
                    request,
                    response,
                    authentication
            );
        } finally {
            SecurityContextHolder.clearContext();
            authCookieService.clearAuthenticationCookies(response);
        }
    }

    public record LogoutResult(boolean revocationComplete) {
    }
}
