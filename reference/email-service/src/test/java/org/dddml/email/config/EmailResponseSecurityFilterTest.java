package org.dddml.email.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class EmailResponseSecurityFilterTest {

    private EmailResponseSecurityFilter filter;

    @BeforeEach
    void setUp() {
        filter = new EmailResponseSecurityFilter();
    }

    @Test
    void contextPathAndMatrixParametersReceiveSecurityHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET",
            "/mail/api;version=1/email;tenant=test/health"
        );
        request.setContextPath("/mail");
        request.setServletPath("/api;version=1/email;tenant=test/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
            chainInvoked.set(true)
        );

        assertThat(chainInvoked).isTrue();
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void unrelatedResponsesAreNotModified() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET",
            "/mail/actuator/health"
        );
        request.setContextPath("/mail");
        request.setServletPath("/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
            chainInvoked.set(true)
        );

        assertThat(chainInvoked).isTrue();
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isNull();
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isNull();
        assertThat(response.getHeader("X-Content-Type-Options")).isNull();
    }
}
