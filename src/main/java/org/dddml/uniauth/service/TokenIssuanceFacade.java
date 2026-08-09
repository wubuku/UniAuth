package org.dddml.uniauth.service;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.config.AuthTransportProperties;
import org.dddml.uniauth.dto.UserDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TokenIssuanceFacade {

    private final TokenSessionTransactionService tokenSessionTransactionService;
    private final JwtTokenService jwtTokenService;
    private final AuthCookieService authCookieService;
    private final AuthTransportProperties transportProperties;
    private final AuthenticationCredentialResolver credentialResolver;

    public Map<String, Object> issue(
            UserDto user,
            HttpServletResponse response,
            String message) {
        return issue(user, response, message, Instant.now(), null);
    }

    public Map<String, Object> issue(
            UserDto user,
            HttpServletRequest request,
            HttpServletResponse response,
            String message,
            Instant authTime) {
        String familyToReplace = credentialResolver
                .activeFamilyForNewLogin(request, user.getId())
                .orElse(null);
        return issue(
                user,
                response,
                message,
                authTime,
                familyToReplace
        );
    }

    public Map<String, Object> issue(
            UserDto user,
            HttpServletResponse response,
            String message,
            Instant authTime,
            String familyToReplace) {
        TokenSessionSnapshot session = tokenSessionTransactionService.create(
                user.getId(),
                authTime,
                familyToReplace
        );
        TokenPair tokenPair = sign(session);
        authCookieService.writeTokenCookies(
                response,
                tokenPair.accessToken(),
                tokenPair.refreshToken()
        );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", user);
        body.put("message", message);
        body.put("authenticated", true);
        if (transportProperties.isExposeAccessToken()) {
            body.put("accessToken", tokenPair.accessToken());
        }
        body.put(
                "accessTokenExpiresIn",
                jwtTokenService.getExpires().getAccessToken() / 1000
        );
        body.put(
                "refreshTokenExpiresIn",
                jwtTokenService.getExpires().getRefreshToken() / 1000
        );
        body.put("tokenType", "Bearer");
        return body;
    }

    public TokenPair sign(TokenSessionSnapshot session) {
        return new TokenPair(
                jwtTokenService.generateAccessToken(session),
                jwtTokenService.generateRefreshToken(session)
        );
    }

    public record TokenPair(String accessToken, String refreshToken) {
    }
}
