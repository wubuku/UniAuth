package org.dddml.uniauth.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ProductionConfigurationGuard implements InitializingBean {

    private static final List<String> PROVIDERS = List.of(
            "google",
            "github",
            "x"
    );
    private static final List<String> STRONG_SECRET_PROPERTIES = List.of(
            "app.auth.rate-limit.key-secret",
            "app.auth.introspection.client-secret",
            "app.email.verification.hmac-key",
            "app.email.service.api-key",
            "spring.datasource.password"
    );

    private final Environment environment;

    public ProductionConfigurationGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }

        requireBoolean("app.auth.rate-limit.enabled", true);
        requireBoolean("app.auth.transport.expose-access-token", false);
        requireBoolean("app.auth.transport.diagnostics-enabled", false);
        requireBoolean("jwt.rsa.generate-if-missing", false);
        requireBoolean("springdoc.api-docs.enabled", false);
        requireBoolean("springdoc.swagger-ui.enabled", false);
        requireExact("server.forward-headers-strategy", "none");
        requireExact("server.max-http-request-header-size", "16KB");
        requireExact("server.tomcat.max-http-form-post-size", "1MB");
        requireExact("server.tomcat.max-swallow-size", "1MB");

        requireHttpsBaseUrl("app.frontend.url");
        requireHttpsOriginList("app.cors.allowed-origins");
        requireHttpsBaseUrl("app.email.service.url");
        requireHttpsBaseUrl("jwt.token.issuer");
        requireWeb3Domain("app.web3.domain");

        requireIdentifier("jwt.token.audience", 3);
        requireIdentifier("jwt.token.kid", 3);
        requireIdentifier("app.auth.introspection.client-id", 3);
        requireIdentifier("app.email.verification.hmac-key-id", 3);

        for (String provider : PROVIDERS) {
            String prefix = "spring.security.oauth2.client.registration."
                    + provider;
            requireIdentifier(prefix + ".client-id", 4);
            requireSecret(prefix + ".client-secret", 12);
            requireHttpsRedirect(prefix + ".redirect-uri");
        }

        List<String> strongSecrets = new ArrayList<>();
        for (String property : STRONG_SECRET_PROPERTIES) {
            strongSecrets.add(requireSecret(property, 32));
        }
        if (new HashSet<>(strongSecrets).size() != strongSecrets.size()) {
            throw new IllegalStateException(
                    "Production secrets must use distinct values"
            );
        }

        requireExternalKeyPath();
    }

    private void requireBoolean(String property, boolean expected) {
        Boolean value = environment.getProperty(property, Boolean.class);
        if (value == null || value != expected) {
            throw new IllegalStateException(
                    "Production requires " + property + "=" + expected
            );
        }
    }

    private void requireExact(String property, String expected) {
        String value = required(property);
        if (!expected.equalsIgnoreCase(value)) {
            throw new IllegalStateException(
                    "Production requires " + property + "=" + expected
            );
        }
    }

    private void requireHttpsBaseUrl(String property) {
        String value = required(property);
        if (!HttpUrlSafety.isValidFrontendBaseUrl(value)) {
            throw unsafe(property);
        }
        requireProductionHttpsHost(property, URI.create(value));
    }

    private void requireHttpsRedirect(String property) {
        String value = required(property);
        URI uri;
        try {
            uri = HttpUrlSafety.parseRedirectUri(value);
        } catch (IllegalArgumentException exception) {
            throw unsafe(property);
        }
        if (uri.getRawQuery() != null) {
            throw unsafe(property);
        }
        requireProductionHttpsHost(property, uri);
    }

    private void requireHttpsOriginList(String property) {
        String value = required(property);
        for (String origin : value.split(",")) {
            String candidate = origin.trim();
            if (!HttpUrlSafety.isValidHttpOrigin(candidate)) {
                throw unsafe(property);
            }
            requireProductionHttpsHost(property, URI.create(candidate));
        }
    }

    private void requireProductionHttpsHost(String property, URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || isReservedHost(uri.getHost())) {
            throw unsafe(property);
        }
    }

    private void requireWeb3Domain(String property) {
        String value = required(property);
        URI uri;
        try {
            uri = URI.create("https://" + value);
        } catch (IllegalArgumentException exception) {
            throw unsafe(property);
        }
        if (!value.equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1
                || StringUtils.hasText(uri.getRawPath())
                || isReservedHost(uri.getHost())) {
            throw unsafe(property);
        }
    }

    private String requireSecret(String property, int minimumLength) {
        String value = required(property);
        if (value.length() < minimumLength || isPlaceholder(value)) {
            throw new IllegalStateException(
                    "Production requires a non-placeholder secret for " + property
            );
        }
        return value;
    }

    private void requireIdentifier(String property, int minimumLength) {
        String value = required(property);
        if (value.length() < minimumLength || isPlaceholder(value)) {
            throw new IllegalStateException(
                    "Production requires a non-placeholder value for " + property
            );
        }
    }

    private void requireExternalKeyPath() {
        Path configured = Path.of(required("jwt.rsa.key-file"));
        if (!configured.isAbsolute()) {
            throw new IllegalStateException(
                    "Production jwt.rsa.key-file must be an absolute external path"
            );
        }
        Path normalized = configured.normalize();
        Path workingDirectory = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        Path resolved = normalized;
        if (Files.exists(normalized)) {
            try {
                resolved = normalized.toRealPath();
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "Production jwt.rsa.key-file could not be resolved",
                        exception
                );
            }
        }
        if (normalized.startsWith(workingDirectory)
                || resolved.startsWith(workingDirectory)) {
            throw new IllegalStateException(
                    "Production jwt.rsa.key-file must be outside the application working directory"
            );
        }
    }

    private String required(String property) {
        String value = environment.getProperty(property);
        if (!StringUtils.hasText(value) || !value.equals(value.trim())) {
            throw new IllegalStateException(
                    "Production requires an explicit value for " + property
            );
        }
        return value;
    }

    private boolean isPlaceholder(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("change-me")
                || normalized.contains("replace-me")
                || normalized.contains("local-only")
                || normalized.contains("example")
                || normalized.equals("key-1")
                || normalized.equals("resource-server")
                || normalized.equals("password")
                || normalized.startsWith("${");
    }

    private boolean isReservedHost(String host) {
        if (!StringUtils.hasText(host)) {
            return true;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost")
                || normalized.endsWith(".localhost")
                || normalized.endsWith(".local")
                || normalized.endsWith(".test")
                || normalized.endsWith(".invalid")
                || normalized.endsWith(".example")
                || normalized.equals("example.com")
                || normalized.endsWith(".example.com")
                || normalized.equals("127.0.0.1")
                || normalized.equals("::1");
    }

    private IllegalStateException unsafe(String property) {
        return new IllegalStateException(
                "Production requires a non-local HTTPS value for " + property
        );
    }
}
