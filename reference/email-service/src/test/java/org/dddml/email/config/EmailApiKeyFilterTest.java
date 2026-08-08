package org.dddml.email.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class EmailApiKeyFilterTest {

    private EmailApiKeyFilter filter;

    @BeforeEach
    void setUp() {
        EmailSecurityProperties properties = new EmailSecurityProperties();
        properties.setApiKey("filter-secret");
        filter = new EmailApiKeyFilter(properties, new ObjectMapper());
    }

    @Test
    void contextPathCannotBypassEmailApiAuthentication() throws Exception {
        MockHttpServletRequest request = emailRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
            chainInvoked.set(true)
        );

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainInvoked).isFalse();
    }

    @Test
    void contextPathRequestWithTheConfiguredKeyContinues() throws Exception {
        MockHttpServletRequest request = emailRequest();
        request.addHeader(EmailSecurityProperties.API_KEY_HEADER, "filter-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
            chainInvoked.set(true)
        );

        assertThat(chainInvoked).isTrue();
    }

    @Test
    void repeatedCredentialHeadersAreRejectedEvenWhenEveryValueMatches() throws Exception {
        MockHttpServletRequest request = emailRequest();
        request.addHeader(EmailSecurityProperties.API_KEY_HEADER, "filter-secret");
        request.addHeader(EmailSecurityProperties.API_KEY_HEADER, "filter-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
            chainInvoked.set(true)
        );

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainInvoked).isFalse();
    }

    @Test
    void matrixParametersCannotBypassEmailApiAuthentication() throws Exception {
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

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainInvoked).isFalse();
    }

    @Test
    void matrixParameterRequestWithTheConfiguredKeyContinues() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET",
            "/mail/api;version=1/email;tenant=test/health"
        );
        request.setContextPath("/mail");
        request.setServletPath("/api;version=1/email;tenant=test/health");
        request.addHeader(EmailSecurityProperties.API_KEY_HEADER, "filter-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
            chainInvoked.set(true)
        );

        assertThat(chainInvoked).isTrue();
    }

    @Test
    void unrelatedServletPathDoesNotRequireTheEmailServiceKey() throws Exception {
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
    }

    @Test
    void securityPropertiesDoNotExposeTheConfiguredKeyInObjectStrings() {
        EmailSecurityProperties properties = new EmailSecurityProperties();
        properties.setApiKey("filter-secret-value");

        assertThat(properties.toString()).doesNotContain("filter-secret-value");
    }

    private MockHttpServletRequest emailRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET",
            "/mail/api/email/health"
        );
        request.setContextPath("/mail");
        request.setServletPath("/api/email/health");
        return request;
    }
}
