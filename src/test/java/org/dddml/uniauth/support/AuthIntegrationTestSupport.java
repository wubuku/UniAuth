package org.dddml.uniauth.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.dddml.uniauth.config.IntrospectionProperties;
import org.dddml.uniauth.service.TokenIssuanceFacade;
import org.dddml.uniauth.service.TokenSessionSnapshot;
import org.dddml.uniauth.service.TokenSessionTransactionService;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public final class AuthIntegrationTestSupport {

    private AuthIntegrationTestSupport() {
    }

    public static IssuedTokens issueTokens(
            TokenSessionTransactionService transactionService,
            TokenIssuanceFacade issuanceFacade,
            String userId) {
        TokenSessionSnapshot snapshot = transactionService.create(
                userId,
                Instant.now(),
                null
        );
        TokenIssuanceFacade.TokenPair pair = issuanceFacade.sign(snapshot);
        return new IssuedTokens(
                pair.accessToken(),
                pair.refreshToken(),
                snapshot.familyId()
        );
    }

    public static CsrfContext bootstrapCsrf(
            MockMvc mockMvc,
            ObjectMapper objectMapper) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-Token"))
                .andReturn();
        Cookie sessionCookie = result.getResponse().getCookie("JSESSIONID");
        assertThat(sessionCookie).as("JSESSIONID").isNotNull();
        return new CsrfContext(
                sessionCookie,
                objectMapper.readTree(result.getResponse().getContentAsByteArray())
                        .path("token")
                        .asText(),
                "X-CSRF-Token"
        );
    }

    public static MockHttpServletRequestBuilder withCsrf(
            MockHttpServletRequestBuilder request,
            CsrfContext csrf) {
        return request.cookie(csrf.sessionCookie())
                .header(csrf.headerName(), csrf.token());
    }

    public static String responseCookie(MvcResult result, String name) {
        Cookie cookie = result.getResponse().getCookie(name);
        assertThat(cookie).as(name).isNotNull();
        assertThat(cookie.getValue()).as(name + " value").isNotBlank();
        return cookie.getValue();
    }

    public static String basicAuthorization(
            IntrospectionProperties properties) {
        String credentials = properties.getClientId()
                + ":"
                + properties.getClientSecret();
        return "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8)
        );
    }

    public static MockHttpServletRequestBuilder authenticatedIntrospection(
            MockHttpServletRequestBuilder request,
            IntrospectionProperties properties) {
        return request.header(
                HttpHeaders.AUTHORIZATION,
                basicAuthorization(properties)
        );
    }

    public record IssuedTokens(
            String accessToken,
            String refreshToken,
            String familyId) {
    }

    public record CsrfContext(
            Cookie sessionCookie,
            String token,
            String headerName) {
    }
}
