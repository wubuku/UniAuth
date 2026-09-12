package org.dddml.uniauth.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

@ConfigurationProperties(prefix = "app.oauth2.http")
@Validated
@Getter
@Setter
public class OAuth2HttpClientProperties {

    @Min(100)
    @Max(60_000)
    private long connectTimeoutMs = 5_000;

    @Min(100)
    @Max(60_000)
    private long readTimeoutMs = 10_000;

    private ProxyMode proxyMode = ProxyMode.AUTO;

    private String proxyUrl = "";

    @AssertTrue(message = "OAuth2 HTTP proxy mode and URL are inconsistent")
    public boolean isProxyConfigurationValid() {
        try {
            if (proxyMode == null) {
                return false;
            }
            if (proxyMode == ProxyMode.DIRECT) {
                return true;
            }
            URI proxyUri = proxyUri();
            return switch (proxyMode) {
                case AUTO -> true;
                case DIRECT -> throw new IllegalStateException(
                        "DIRECT mode was handled before proxy URL parsing"
                );
                case HTTP -> proxyUri != null;
            };
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    URI proxyUri() {
        if (proxyUrl == null || proxyUrl.isBlank()) {
            return null;
        }
        if (!proxyUrl.equals(proxyUrl.trim()) || proxyUrl.length() > 2_048) {
            throw new IllegalArgumentException("Invalid OAuth2 proxy URL");
        }
        for (int index = 0; index < proxyUrl.length(); index++) {
            char character = proxyUrl.charAt(index);
            if (Character.isWhitespace(character)
                    || Character.isISOControl(character)) {
                throw new IllegalArgumentException("Invalid OAuth2 proxy URL");
            }
        }

        URI uri = URI.create(proxyUrl);
        if (!"http".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getPort() < 1
                || uri.getPort() > 65_535
                || uri.getRawUserInfo() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid OAuth2 proxy URL");
        }
        return uri;
    }

    String proxyRouteDescription() {
        if (proxyMode == ProxyMode.DIRECT) {
            return "direct";
        }
        URI uri = proxyUri();
        return switch (proxyMode) {
            case AUTO -> uri == null
                    ? "system"
                    : "auto-http@" + uri.getHost() + ":" + uri.getPort();
            case DIRECT -> throw new IllegalStateException(
                    "DIRECT mode was handled before proxy URL parsing"
            );
            case HTTP -> "http@" + uri.getHost() + ":" + uri.getPort();
        };
    }

    public enum ProxyMode {
        AUTO,
        DIRECT,
        HTTP
    }
}
