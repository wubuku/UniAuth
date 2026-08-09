package org.dddml.uniauth.service;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.dto.UserDto;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TokenIssuanceFacade {

    private final JwtTokenService jwtTokenService;
    private final AuthCookieService authCookieService;

    public Map<String, Object> issue(
            UserDto user,
            HttpServletResponse response,
            String message) {
        String accessToken = jwtTokenService.generateAccessToken(
                user.getUsername(),
                user.getEmail(),
                user.getId(),
                user.getAuthorities()
        );
        String refreshToken = jwtTokenService.generateRefreshToken(
                user.getUsername(),
                user.getId()
        );
        authCookieService.writeTokenCookies(response, accessToken, refreshToken);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", user);
        body.put("message", message);
        body.put("authenticated", true);
        body.put("accessToken", accessToken);
        body.put("refreshToken", refreshToken);
        body.put("accessTokenExpiresIn", 3600);
        body.put("refreshTokenExpiresIn", 604800);
        body.put("tokenType", "Bearer");
        return body;
    }
}
