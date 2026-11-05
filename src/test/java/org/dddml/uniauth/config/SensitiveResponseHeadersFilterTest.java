package org.dddml.uniauth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveResponseHeadersFilterTest {

    @Test
    void baselineHeadersAreAppliedToEveryResponse()
            throws ServletException, IOException {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        SensitiveResponseHeadersFilter filter =
                new SensitiveResponseHeadersFilter(environment);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, noopChain());

        assertThat(response.getHeader("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options"))
                .isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy"))
                .isEqualTo("no-referrer");
        assertThat(response.getHeader("Permissions-Policy"))
                .isEqualTo("camera=(), microphone=(), geolocation=(), payment=()");
    }

    @Test
    void cacheSuppressionIsScopedToSensitivePaths()
            throws ServletException, IOException {
        SensitiveResponseHeadersFilter filter =
                new SensitiveResponseHeadersFilter(new MockEnvironment());

        for (String path : new String[]{"/api/user", "/api/auth/login", "/oauth2/jwks", "/login"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(
                    new MockHttpServletRequest("GET", path),
                    response,
                    noopChain()
            );
            assertThat(response.getHeader("Cache-Control"))
                    .as(path)
                    .isEqualTo("no-store, no-cache, must-revalidate");
            assertThat(response.getHeader("Pragma")).as(path).isEqualTo("no-cache");
        }

        MockHttpServletResponse publicResponse = new MockHttpServletResponse();
        filter.doFilterInternal(
                new MockHttpServletRequest("GET", "/test"),
                publicResponse,
                noopChain()
        );
        assertThat(publicResponse.getHeader("Cache-Control")).isNull();
        assertThat(publicResponse.getHeader("Pragma")).isNull();
    }

    @Test
    void productionProfileAddsCspAndHsts()
            throws ServletException, IOException {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        SensitiveResponseHeadersFilter filter =
                new SensitiveResponseHeadersFilter(environment);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(
                new MockHttpServletRequest("GET", "/login"),
                response,
                noopChain()
        );

        assertThat(response.getHeader("Content-Security-Policy"))
                .isEqualTo("default-src 'self'; frame-ancestors 'none'; "
                        + "object-src 'none'; base-uri 'self'");
        assertThat(response.getHeader("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000; includeSubDomains");
    }

    @Test
    void nonProductionProfileDoesNotAdvertiseHsts()
            throws ServletException, IOException {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        SensitiveResponseHeadersFilter filter =
                new SensitiveResponseHeadersFilter(environment);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(
                new MockHttpServletRequest("GET", "/login"),
                response,
                noopChain()
        );

        assertThat(response.getHeader("Content-Security-Policy")).isNull();
        assertThat(response.getHeader("Strict-Transport-Security")).isNull();
    }

    private FilterChain noopChain() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        return (request, response) -> invoked.set(true);
    }
}
