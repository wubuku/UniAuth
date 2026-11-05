package org.dddml.uniauth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.dddml.uniauth.service.AuthCookieService;
import org.dddml.uniauth.service.CsrfBootstrapService;
import org.dddml.uniauth.service.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CsrfProtectionFilterTest {

    private static final String CSRF_TOKEN = "csrf-token-value";

    private CsrfProtectionFilter filter;

    @BeforeEach
    void setUp() {
        AuthCookieProperties cookieProperties = new AuthCookieProperties();
        AuthCookieService authCookieService = new AuthCookieService(
                cookieProperties,
                mock(JwtTokenService.class)
        );
        filter = new CsrfProtectionFilter(
                authCookieService,
                new CsrfBootstrapProperties()
        );
    }

    @Test
    void safeMethodBypassesCsrfEvenWithAuthenticationCookies()
            throws ServletException, IOException {
        MockHttpServletRequest request = request("GET", "/api/user");
        request.addHeader("Cookie", "accessToken=value");

        assertChainInvoked(request, new MockHttpServletResponse());
    }

    @Test
    void requestWithoutAuthenticationCookiesBypassesCsrf()
            throws ServletException, IOException {
        MockHttpServletRequest request = request("POST", "/api/user/password");

        assertChainInvoked(request, new MockHttpServletResponse());
    }

    @Test
    void cookieNameMatchIsExact()
            throws ServletException, IOException {
        MockHttpServletRequest request = request("POST", "/api/user/password");
        request.addHeader("Cookie", "accessTokenExtra=value; refreshTokenSuffix=value");

        assertChainInvoked(request, new MockHttpServletResponse());
    }

    @Test
    void bothAccessAndRefreshCookiesRequireCsrf()
            throws ServletException, IOException {
        for (String cookieHeader : new String[]{
                "accessToken=value",
                "refreshToken=value",
                "other=value; accessToken=value"
        }) {
            MockHttpServletRequest request = request("POST", "/api/user/password");
            request.addHeader("Cookie", cookieHeader);

            assertRejected(request, new MockHttpServletResponse());
        }
    }

    @Test
    void missingSessionTokenIsRejected()
            throws ServletException, IOException {
        MockHttpServletRequest request = authenticatedRequest();
        request.addHeader("X-CSRF-Token", CSRF_TOKEN);

        assertRejected(request, new MockHttpServletResponse());
    }

    @Test
    void exactSingleHeaderAndSessionTokenAreAccepted()
            throws ServletException, IOException {
        MockHttpServletRequest request = authenticatedRequest();
        request.getSession(true).setAttribute(
                CsrfBootstrapService.SESSION_ATTRIBUTE,
                CSRF_TOKEN
        );
        request.addHeader("X-CSRF-Token", CSRF_TOKEN);

        assertChainInvoked(request, new MockHttpServletResponse());
    }

    @Test
    void duplicateIdenticalHeadersAreRejected()
            throws ServletException, IOException {
        MockHttpServletRequest request = authenticatedRequest();
        request.getSession(true).setAttribute(
                CsrfBootstrapService.SESSION_ATTRIBUTE,
                CSRF_TOKEN
        );
        request.addHeader("X-CSRF-Token", CSRF_TOKEN);
        request.addHeader("X-CSRF-Token", CSRF_TOKEN);

        assertRejected(request, new MockHttpServletResponse());
    }

    @Test
    void duplicateDifferingHeadersAreRejected()
            throws ServletException, IOException {
        MockHttpServletRequest request = authenticatedRequest();
        request.getSession(true).setAttribute(
                CsrfBootstrapService.SESSION_ATTRIBUTE,
                CSRF_TOKEN
        );
        request.addHeader("X-CSRF-Token", CSRF_TOKEN);
        request.addHeader("X-CSRF-Token", "other-token");

        assertRejected(request, new MockHttpServletResponse());
    }

    @Test
    void blankOrWrongHeaderIsRejected()
            throws ServletException, IOException {
        MockHttpServletRequest blank = authenticatedRequest();
        blank.getSession(true).setAttribute(
                CsrfBootstrapService.SESSION_ATTRIBUTE,
                CSRF_TOKEN
        );
        blank.addHeader("X-CSRF-Token", "   ");

        MockHttpServletRequest wrong = authenticatedRequest();
        wrong.getSession(true).setAttribute(
                CsrfBootstrapService.SESSION_ATTRIBUTE,
                CSRF_TOKEN
        );
        wrong.addHeader("X-CSRF-Token", "wrong-token");

        assertRejected(blank, new MockHttpServletResponse());
        assertRejected(wrong, new MockHttpServletResponse());
    }

    @Test
    void postRefreshIsExemptButOtherMethodsOnSamePathAreNot()
            throws ServletException, IOException {
        MockHttpServletRequest post = request("POST", "/api/auth/refresh");
        post.addHeader("Cookie", "refreshToken=value");

        MockHttpServletRequest put = request("PUT", "/api/auth/refresh");
        put.addHeader("Cookie", "refreshToken=value");

        assertChainInvoked(post, new MockHttpServletResponse());
        assertRejected(put, new MockHttpServletResponse());
    }

    @Test
    void introspectionIsExemptForAnyMethod()
            throws ServletException, IOException {
        MockHttpServletRequest request = request("POST", "/oauth2/introspect");
        request.addHeader("Cookie", "accessToken=value");

        assertChainInvoked(request, new MockHttpServletResponse());
    }

    @Test
    void rejectionUsesStableJsonErrorContract()
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertRejected(authenticatedRequest(), response);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType())
                .startsWith("application/json");
        assertThat(response.getContentAsString())
                .isEqualTo("{\"error\":\"CSRF_TOKEN_INVALID\"}");
    }

    private MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = request("POST", "/api/user/password");
        request.addHeader("Cookie", "accessToken=value");
        return request;
    }

    private void assertChainInvoked(
            MockHttpServletRequest request,
            MockHttpServletResponse response) throws ServletException, IOException {
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> invoked.set(true);

        filter.doFilterInternal(request, response, chain);

        assertThat(invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private void assertRejected(
            MockHttpServletRequest request,
            MockHttpServletResponse response) throws ServletException, IOException {
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> invoked.set(true);

        filter.doFilterInternal(request, response, chain);

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }
}
