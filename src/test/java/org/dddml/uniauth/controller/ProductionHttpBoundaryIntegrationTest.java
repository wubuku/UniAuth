package org.dddml.uniauth.controller;

import org.dddml.uniauth.service.JwtTokenService;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class ProductionHttpBoundaryIntegrationTest extends PostgreSqlIntegrationTest {

    private static final String FRONTEND_URL =
            "https://console.uniauth.internal";
    private static final String CALLBACK_URL =
            "https://identity.uniauth.internal/oauth2/callback";
    private static final String INTROSPECTION_CLIENT_ID =
            "production-resource-api";
    private static final String INTROSPECTION_CLIENT_SECRET =
            "introspection-secret-bbbbbbbbbbbbbbbbbbbbb";
    private static final Path PRODUCTION_KEY_FILE =
            integrationTestDirectory().resolve("signing-key.ser");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    static {
        new JwtTokenService(PRODUCTION_KEY_FILE.toString());
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void productionProperties(DynamicPropertyRegistry registry) {
        registry.add("jwt.rsa.key-file", PRODUCTION_KEY_FILE::toString);
        registry.add("app.frontend.url", () -> FRONTEND_URL);
        registry.add(
                "app.cors.allowed-origins",
                () -> FRONTEND_URL + ",https://admin.uniauth.internal"
        );
        registry.add(
                "app.email.service.url",
                () -> "https://mail.uniauth.internal"
        );
        registry.add(
                "app.email.service.api-key",
                () -> "email-service-secret-ddddddddddddddddddddd"
        );
        registry.add(
                "app.email.verification.hmac-key",
                () -> "verification-secret-cccccccccccccccccccc"
        );
        registry.add(
                "app.email.verification.hmac-key-id",
                () -> "email-code-2026-08"
        );
        registry.add(
                "app.auth.rate-limit.key-secret",
                () -> "rate-limit-secret-aaaaaaaaaaaaaaaaaaaaaaaa"
        );
        registry.add("app.auth.rate-limit.source-limit", () -> "2");
        registry.add("app.auth.rate-limit.login-limit", () -> "20");
        registry.add(
                "app.auth.introspection.client-id",
                () -> INTROSPECTION_CLIENT_ID
        );
        registry.add(
                "app.auth.introspection.client-secret",
                () -> INTROSPECTION_CLIENT_SECRET
        );
        registry.add(
                "jwt.token.issuer",
                () -> "https://identity.uniauth.internal"
        );
        registry.add("jwt.token.audience", () -> "uniauth-production-api");
        registry.add("jwt.token.kid", () -> "uniauth-signing-2026-08");
        registry.add("app.web3.domain", () -> "identity.uniauth.internal");
        for (String provider : List.of("google", "github", "x")) {
            registry.add(
                    "spring.security.oauth2.client.registration."
                            + provider + ".redirect-uri",
                    () -> CALLBACK_URL
            );
        }
    }

    @Test
    void spoofedForwardedHeadersDoNotChangeRedirectOrSecureCookies()
            throws Exception {
        HttpResponse<String> csrfResponse = send(
                HttpRequest.newBuilder(uri("/api/auth/csrf"))
                        .header("X-Forwarded-For", "203.0.113.25")
                        .header("X-Forwarded-Host", "attacker.example")
                        .header("X-Forwarded-Proto", "http")
                        .GET()
                        .build()
        );

        assertThat(csrfResponse.statusCode()).isEqualTo(200);
        assertThat(csrfResponse.headers().firstValue(
                "Strict-Transport-Security"
        )).contains("max-age=31536000; includeSubDomains");
        assertThat(csrfResponse.headers().allValues("Set-Cookie"))
                .anySatisfy(cookie -> assertThat(cookie)
                        .startsWith("__Host-JSESSIONID=")
                        .contains("; Path=/")
                        .contains("; Secure")
                        .contains("; HttpOnly")
                        .contains("; SameSite=Lax"));

        HttpResponse<String> redirectResponse = send(
                HttpRequest.newBuilder(uri("/oauth2/authorization/google"))
                        .header("Forwarded", "host=attacker.example;proto=http")
                        .header("X-Forwarded-Host", "attacker.example")
                        .header("X-Forwarded-Proto", "http")
                        .GET()
                        .build()
        );

        assertThat(redirectResponse.statusCode()).isEqualTo(302);
        String location = redirectResponse.headers()
                .firstValue("Location")
                .orElseThrow();
        assertThat(location)
                .contains("redirect_uri=")
                .contains("identity.uniauth.internal")
                .doesNotContain("attacker.example");
    }

    @Test
    void spoofedClientAddressesShareTheDirectConnectionRateLimit()
            throws Exception {
        int first = loginWithForwardedAddress("198.51.100.1", "spoof-one");
        int second = loginWithForwardedAddress("198.51.100.2", "spoof-two");
        int third = loginWithForwardedAddress("198.51.100.3", "spoof-three");

        assertThat(first).isEqualTo(401);
        assertThat(second).isEqualTo(401);
        assertThat(third).isEqualTo(429);
    }

    @Test
    void embeddedContainerRejectsOversizedHeadersCookiesAndFormBodies()
            throws Exception {
        String oversized = "a".repeat(17 * 1024);
        HttpResponse<String> headerResponse = send(
                HttpRequest.newBuilder(uri("/actuator/health/liveness"))
                        .header("X-Oversized", oversized)
                        .GET()
                        .build()
        );
        assertThat(headerResponse.statusCode()).isBetween(400, 499);

        HttpResponse<String> cookieResponse = send(
                HttpRequest.newBuilder(uri("/actuator/health/liveness"))
                        .header("Cookie", "oversized=" + oversized)
                        .GET()
                        .build()
        );
        assertThat(cookieResponse.statusCode()).isBetween(400, 499);

        String credentials = Base64.getEncoder().encodeToString(
                (INTROSPECTION_CLIENT_ID + ":" + INTROSPECTION_CLIENT_SECRET)
                        .getBytes(StandardCharsets.UTF_8)
        );
        HttpResponse<String> formResponse = send(
                HttpRequest.newBuilder(uri("/oauth2/introspect"))
                        .header(
                                "Content-Type",
                                "application/x-www-form-urlencoded"
                        )
                        .header("Authorization", "Basic " + credentials)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "token=" + "a".repeat(1024 * 1024 + 4096)
                        ))
                        .build()
        );
        assertThat(formResponse.statusCode()).isBetween(400, 499);
    }

    @Test
    void productionDocumentationAndDiagnosticsRoutesAreUnavailable()
            throws Exception {
        for (String path : List.of(
                "/v3/api-docs",
                "/swagger-ui/index.html",
                "/api/auth/transport-diagnostics"
        )) {
            assertThat(send(
                    HttpRequest.newBuilder(uri(path)).GET().build()
            ).statusCode()).isNotEqualTo(200);
        }
    }

    private int loginWithForwardedAddress(
            String forwardedAddress,
            String username) throws Exception {
        String body = """
                {"username":"%s","password":"wrong-password"}
                """.formatted(username).trim();
        return send(
                HttpRequest.newBuilder(uri("/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .header("X-Forwarded-For", forwardedAddress)
                        .header("X-Real-IP", forwardedAddress)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build()
        ).statusCode();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
    }
}
