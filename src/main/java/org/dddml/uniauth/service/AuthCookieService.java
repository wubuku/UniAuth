package org.dddml.uniauth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.config.AuthCookieProperties;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthCookieService {

    public static final String ACCESS_TOKEN_COOKIE = "accessToken";
    public static final String REFRESH_TOKEN_COOKIE = "refreshToken";
    private static final String COOKIE_PATH = "/";
    private static final String SAME_SITE = "Lax";
    private static final String[] AUTH_COOKIE_NAMES = {
        "JSESSIONID",
        "__Host-JSESSIONID",
        ACCESS_TOKEN_COOKIE,
        REFRESH_TOKEN_COOKIE,
        "id_token",
        "google_access_token",
        "github_access_token",
        "twitter_access_token"
    };

    private final AuthCookieProperties properties;
    private final JwtTokenService jwtTokenService;

    public void writeTokenCookies(
            HttpServletResponse response,
            String accessToken,
            String refreshToken) {
        response.addCookie(createCookie(
                accessTokenCookieName(),
                accessToken,
                maxAgeSeconds(jwtTokenService.getExpires().getAccessToken())
        ));
        response.addCookie(createCookie(
                refreshTokenCookieName(),
                refreshToken,
                maxAgeSeconds(jwtTokenService.getExpires().getRefreshToken())
        ));
    }

    public void clearAuthenticationCookies(HttpServletResponse response) {
        for (String cookieName : AUTH_COOKIE_NAMES) {
            response.addCookie(createCookie(cookieName, "", 0));
        }
        if (!ACCESS_TOKEN_COOKIE.equals(accessTokenCookieName())) {
            response.addCookie(createCookie(accessTokenCookieName(), "", 0));
        }
        if (!REFRESH_TOKEN_COOKIE.equals(refreshTokenCookieName())) {
            response.addCookie(createCookie(refreshTokenCookieName(), "", 0));
        }
    }

    public String accessTokenCookieName() {
        return properties.getAccessTokenName();
    }

    public String refreshTokenCookieName() {
        return properties.getRefreshTokenName();
    }

    private Cookie createCookie(String name, String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(name, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(properties.isSecure());
        cookie.setPath(COOKIE_PATH);
        cookie.setMaxAge(maxAgeSeconds);
        cookie.setAttribute("SameSite", SAME_SITE);
        return cookie;
    }

    private int maxAgeSeconds(long milliseconds) {
        long seconds = milliseconds / 1000;
        if (seconds <= 0 || seconds > Integer.MAX_VALUE) {
            throw new IllegalStateException("JWT expiry must fit a positive cookie Max-Age");
        }
        return (int) seconds;
    }
}
