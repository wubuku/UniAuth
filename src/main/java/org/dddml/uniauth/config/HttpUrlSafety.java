package org.dddml.uniauth.config;

import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class HttpUrlSafety {

    private HttpUrlSafety() {
    }

    static boolean isValidHttpOrigin(String value) {
        try {
            URI uri = parseHttpUrl(value);
            return !StringUtils.hasText(uri.getRawPath())
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    static boolean isValidFrontendBaseUrl(String value) {
        try {
            URI uri = parseHttpUrl(value);
            return uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    static URI parseRedirectUri(String value) {
        URI uri = parseHttpUrl(value);
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Redirect URI fragments are not supported");
        }
        return uri;
    }

    static boolean hasSameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static URI parseHttpUrl(String value) {
        if (!StringUtils.hasText(value) || !value.equals(value.trim())
                || containsControlCharacters(value)) {
            throw new IllegalArgumentException("URL is blank or contains unsafe characters");
        }

        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equals(scheme.toLowerCase(Locale.ROOT))
                && !"https".equals(scheme.toLowerCase(Locale.ROOT)))) {
            throw new IllegalArgumentException("URL must use HTTP or HTTPS");
        }
        if (!StringUtils.hasText(uri.getHost()) || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("URL must have a host and no user info");
        }
        String rawAuthority = uri.getRawAuthority();
        int port = uri.getPort();
        if ((rawAuthority != null && rawAuthority.endsWith(":"))
                || port == 0
                || port > 65535) {
            throw new IllegalArgumentException("URL must use a valid TCP port");
        }
        return uri;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean containsControlCharacters(String value) {
        String candidate = value;
        for (int pass = 0; pass < 3; pass++) {
            if (candidate.chars().anyMatch(character -> character < 32 || character == 127)) {
                return true;
            }
            try {
                String decoded = URLDecoder.decode(candidate, StandardCharsets.UTF_8);
                if (decoded.equals(candidate)) {
                    return false;
                }
                candidate = decoded;
            } catch (IllegalArgumentException exception) {
                return true;
            }
        }
        return candidate.chars().anyMatch(character -> character < 32 || character == 127);
    }
}
