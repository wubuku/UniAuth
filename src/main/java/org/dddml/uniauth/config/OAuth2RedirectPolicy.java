package org.dddml.uniauth.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class OAuth2RedirectPolicy {

    private final FrontendProperties frontendProperties;

    public String successRedirect() {
        return frontendBaseUrl();
    }

    public String errorRedirect(String requestedRedirect, String error) {
        String baseUrl = resolveAllowedRedirect(requestedRedirect);
        if (baseUrl == null) {
            baseUrl = frontendLoginUrl();
        }
        return appendError(baseUrl, error);
    }

    public String loginErrorRedirect(String error) {
        return appendError(frontendLoginUrl(), error);
    }

    private String resolveAllowedRedirect(String requestedRedirect) {
        if (!StringUtils.hasText(requestedRedirect)) {
            return null;
        }
        try {
            URI requestedUri = HttpUrlSafety.parseRedirectUri(requestedRedirect);
            boolean allowed = allowedOrigins()
                    .map(HttpUrlSafety::parseRedirectUri)
                    .anyMatch(allowedOrigin ->
                            HttpUrlSafety.hasSameOrigin(requestedUri, allowedOrigin));
            return allowed ? requestedUri.toASCIIString() : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Stream<String> allowedOrigins() {
        return Stream.concat(
                Stream.of(frontendProperties.getUrl()),
                frontendProperties.getAllowedRedirectOrigins().stream()
        );
    }

    private String frontendLoginUrl() {
        return frontendBaseUrl() + "login";
    }

    private String frontendBaseUrl() {
        String frontendUrl = frontendProperties.getUrl();
        int end = frontendUrl.length();
        while (end > 0 && frontendUrl.charAt(end - 1) == '/') {
            end--;
        }
        return frontendUrl.substring(0, end) + "/";
    }

    private String appendError(String baseUrl, String error) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("error", error)
                .build()
                .encode()
                .toUriString();
    }
}
