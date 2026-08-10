package org.dddml.uniauth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationGuardTest {

    @TempDir
    Path tempDirectory;

    @Test
    void ignoresNonProductionProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        assertThatCode(() -> new ProductionConfigurationGuard(environment)
                .afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void acceptsAnExplicitProductionConfiguration() {
        assertThatCode(() -> new ProductionConfigurationGuard(
                productionEnvironment()
        ).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void rejectsLocalAndPlaceholderProductionValues() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("jwt.token.issuer", "https://auth.example.com");

        assertThatThrownBy(() -> new ProductionConfigurationGuard(environment)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.token.issuer");
    }

    @Test
    void rejectsDuplicateProductionSecrets() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty(
                "app.auth.introspection.client-secret",
                environment.getProperty("app.auth.rate-limit.key-secret")
        );

        assertThatThrownBy(() -> new ProductionConfigurationGuard(environment)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Production secrets must use distinct values");
    }

    @Test
    void rejectsKeyGenerationAndRepositoryLocalKeyPaths() {
        MockEnvironment generationEnvironment = productionEnvironment();
        generationEnvironment.setProperty(
                "jwt.rsa.generate-if-missing",
                "true"
        );
        assertThatThrownBy(() -> new ProductionConfigurationGuard(
                generationEnvironment
        ).afterPropertiesSet())
                .hasMessage(
                        "Production requires jwt.rsa.generate-if-missing=false"
                );

        MockEnvironment pathEnvironment = productionEnvironment();
        pathEnvironment.setProperty(
                "jwt.rsa.key-file",
                System.getProperty("user.dir") + "/signing-key.ser"
        );
        assertThatThrownBy(() -> new ProductionConfigurationGuard(
                pathEnvironment
        ).afterPropertiesSet())
                .hasMessageContaining("outside the application working directory");
    }

    @Test
    void rejectsAnExternalSymlinkThatTargetsTheWorkingDirectory()
            throws Exception {
        Path buildDirectory = Path.of(System.getProperty("user.dir"))
                .resolve("target");
        Files.createDirectories(buildDirectory);
        Path targetDirectory = Files.createTempDirectory(
                buildDirectory,
                "production-guard-"
        );
        Path target = Files.createTempFile(
                targetDirectory,
                "signing-key-",
                ".ser"
        );
        Path link = tempDirectory.resolve("signing-key.ser");
        try {
            Files.createSymbolicLink(link, target);

            MockEnvironment environment = productionEnvironment();
            environment.setProperty("jwt.rsa.key-file", link.toString());

            assertThatThrownBy(() -> new ProductionConfigurationGuard(
                    environment
            ).afterPropertiesSet())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "outside the application working directory"
                    );
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(target);
            Files.deleteIfExists(targetDirectory);
        }
    }

    @Test
    void rejectsDiagnosticsSwaggerAndForwardedHeaderOverrides() {
        assertRejected(
                "app.auth.transport.diagnostics-enabled",
                "true",
                "Production requires app.auth.transport.diagnostics-enabled=false"
        );
        assertRejected(
                "springdoc.swagger-ui.enabled",
                "true",
                "Production requires springdoc.swagger-ui.enabled=false"
        );
        assertRejected(
                "server.forward-headers-strategy",
                "framework",
                "Production requires server.forward-headers-strategy=none"
        );
        assertRejected(
                "server.max-http-request-header-size",
                "64KB",
                "Production requires server.max-http-request-header-size=16KB"
        );
        assertRejected(
                "server.tomcat.max-http-form-post-size",
                "10MB",
                "Production requires server.tomcat.max-http-form-post-size=1MB"
        );
    }

    private void assertRejected(
            String property,
            String value,
            String message) {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty(property, value);
        assertThatThrownBy(() -> new ProductionConfigurationGuard(environment)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(message);
    }

    private MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("app.auth.rate-limit.enabled", "true");
        environment.setProperty(
                "app.auth.transport.expose-access-token",
                "false"
        );
        environment.setProperty(
                "app.auth.transport.diagnostics-enabled",
                "false"
        );
        environment.setProperty("jwt.rsa.generate-if-missing", "false");
        environment.setProperty("springdoc.api-docs.enabled", "false");
        environment.setProperty("springdoc.swagger-ui.enabled", "false");
        environment.setProperty("server.forward-headers-strategy", "none");
        environment.setProperty(
                "server.max-http-request-header-size",
                "16KB"
        );
        environment.setProperty(
                "server.tomcat.max-http-form-post-size",
                "1MB"
        );
        environment.setProperty("server.tomcat.max-swallow-size", "1MB");
        environment.setProperty(
                "app.frontend.url",
                "https://console.uniauth.internal/app"
        );
        environment.setProperty(
                "app.cors.allowed-origins",
                "https://console.uniauth.internal,https://admin.uniauth.internal"
        );
        environment.setProperty(
                "app.email.service.url",
                "https://mail.uniauth.internal"
        );
        environment.setProperty(
                "jwt.token.issuer",
                "https://identity.uniauth.internal"
        );
        environment.setProperty("app.web3.domain", "identity.uniauth.internal");
        environment.setProperty("jwt.token.audience", "uniauth-api");
        environment.setProperty("jwt.token.kid", "uniauth-signing-2026-08");
        environment.setProperty(
                "app.auth.introspection.client-id",
                "uniauth-resource-api"
        );
        environment.setProperty(
                "app.email.verification.hmac-key-id",
                "email-code-2026-08"
        );
        environment.setProperty(
                "app.auth.rate-limit.key-secret",
                "rate-limit-secret-aaaaaaaaaaaaaaaaaaaaaaaa"
        );
        environment.setProperty(
                "app.auth.introspection.client-secret",
                "introspection-secret-bbbbbbbbbbbbbbbbbbbbb"
        );
        environment.setProperty(
                "app.email.verification.hmac-key",
                "verification-secret-cccccccccccccccccccc"
        );
        environment.setProperty(
                "app.email.service.api-key",
                "email-service-secret-ddddddddddddddddddddd"
        );
        environment.setProperty(
                "spring.datasource.password",
                "database-secret-eeeeeeeeeeeeeeeeeeeeeeee"
        );
        environment.setProperty(
                "jwt.rsa.key-file",
                "/var/lib/uniauth/signing-key.ser"
        );

        configureProvider(
                environment,
                "google",
                "google-production-client",
                "google-secret-123456789"
        );
        configureProvider(
                environment,
                "github",
                "github-production-client",
                "github-secret-123456789"
        );
        configureProvider(
                environment,
                "x",
                "x-production-client",
                "x-secret-123456789"
        );
        return environment;
    }

    private void configureProvider(
            MockEnvironment environment,
            String provider,
            String clientId,
            String clientSecret) {
        String prefix = "spring.security.oauth2.client.registration."
                + provider;
        environment.setProperty(prefix + ".client-id", clientId);
        environment.setProperty(prefix + ".client-secret", clientSecret);
        environment.setProperty(
                prefix + ".redirect-uri",
                "https://identity.uniauth.internal/oauth2/callback"
        );
    }
}
