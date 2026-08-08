package org.dddml.uniauth.service;

import jakarta.servlet.http.Cookie;
import org.dddml.uniauth.config.AuthCookieProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthCookieServiceTest {

    @Test
    void writesConfiguredExpiryAndProfileAttributes() {
        AuthCookieProperties properties = new AuthCookieProperties();
        properties.setSecure(true);
        JwtTokenService jwtTokenService = mock(JwtTokenService.class);
        JwtTokenService.ExpiresConfig expires = new JwtTokenService.ExpiresConfig();
        expires.setAccessToken(90_000);
        expires.setRefreshToken(180_000);
        when(jwtTokenService.getExpires()).thenReturn(expires);
        AuthCookieService service = new AuthCookieService(
                properties,
                jwtTokenService
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.writeTokenCookies(response, "access-value", "refresh-value");

        assertCookie(response.getCookie("accessToken"), "access-value", 90, true);
        assertCookie(response.getCookie("refreshToken"), "refresh-value", 180, true);
    }

    @Test
    void clearsEveryAuthenticationCookieWithTheWritingAttributes() {
        AuthCookieProperties properties = new AuthCookieProperties();
        JwtTokenService jwtTokenService = mock(JwtTokenService.class);
        AuthCookieService service = new AuthCookieService(
                properties,
                jwtTokenService
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.clearAuthenticationCookies(response);

        assertThat(Arrays.stream(response.getCookies())
                .map(Cookie::getName))
                .containsExactly(
                        "JSESSIONID",
                        "accessToken",
                        "refreshToken",
                        "id_token",
                        "google_access_token",
                        "github_access_token",
                        "twitter_access_token"
                );
        Arrays.stream(response.getCookies())
                .forEach(cookie -> assertCookie(cookie, "", 0, false));
    }

    private void assertCookie(
            Cookie cookie,
            String value,
            int maxAge,
            boolean secure) {
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo(value);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isEqualTo(secure);
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isEqualTo(maxAge);
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    }
}
