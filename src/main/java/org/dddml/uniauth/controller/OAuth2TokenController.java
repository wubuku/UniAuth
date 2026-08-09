package org.dddml.uniauth.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.config.IntrospectionProperties;
import org.dddml.uniauth.service.AuthCookieService;
import org.dddml.uniauth.service.AuthRateLimiter;
import org.dddml.uniauth.service.JwtTokenService;
import org.dddml.uniauth.service.TokenIntrospectionService;
import org.dddml.uniauth.service.TokenValidationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/oauth2")
@RequiredArgsConstructor
public class OAuth2TokenController {

    private final JwtTokenService jwtTokenService;
    private final TokenIntrospectionService tokenIntrospectionService;
    private final IntrospectionProperties introspectionProperties;
    private final AuthRateLimiter authRateLimiter;
    private final AuthCookieService authCookieService;

    @GetMapping("/jwks")
    public ResponseEntity<?> getJwks() {
        try {
            PublicKey publicKey = jwtTokenService.getPublicKey();
            if (!(publicKey instanceof RSAPublicKey rsaKey)) {
                return ResponseEntity.internalServerError().body(Map.of(
                        "error", "JWKS generation failed"
                ));
            }
            RSAKey jwk = new RSAKey.Builder(rsaKey)
                    .keyID(jwtTokenService.getToken().getKid())
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            return ResponseEntity.ok(Map.of(
                    "keys",
                    List.of(jwk.toJSONObject())
            ));
        } catch (RuntimeException exception) {
            log.error("JWKS generation failed");
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "JWKS generation failed"
            ));
        }
    }

    @PostMapping(
        value = "/introspect",
        consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> introspect(
            @RequestBody MultiValueMap<String, String> form,
            HttpServletRequest request) {
        Optional<String> clientId = authenticateClient(request);
        if (clientId.isEmpty()) {
            return ResponseEntity.status(401)
                    .header(
                            HttpHeaders.WWW_AUTHENTICATE,
                            "Basic realm=\"token-introspection\""
                    )
                    .body(Map.of("active", false));
        }
        if (hasAuthenticationCookie(request)
                || request.getQueryString() != null
                || form.size() != 1
                || !form.containsKey("token")
                || form.get("token") == null
                || form.get("token").size() != 1
                || form.getFirst("token") == null
                || form.getFirst("token").isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("active", false));
        }

        authRateLimiter.requireAllowed(
                AuthRateLimiter.Policy.INTROSPECTION,
                request.getRemoteAddr(),
                clientId.orElseThrow()
        );
        Optional<TokenValidationService.IntrospectedToken> result =
                tokenIntrospectionService.introspect(
                        form.getFirst("token")
                );
        if (result.isEmpty()) {
            return ResponseEntity.ok(Map.of("active", false));
        }

        TokenValidationService.IntrospectedToken token = result.orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", true);
        body.put("sub", token.subject());
        body.put("type", token.type());
        body.put("sid", token.familyId());
        body.put("generation", token.generation());
        body.put("ver", token.securityVersion());
        body.put(
                "auth_time",
                token.authTime() == null
                        ? 0
                        : token.authTime().getEpochSecond()
        );
        body.put(
                "iat",
                token.issuedAt() == null
                        ? null
                        : token.issuedAt().getEpochSecond()
        );
        body.put(
                "exp",
                token.expiresAt() == null
                        ? null
                        : token.expiresAt().getEpochSecond()
        );
        if (token.audience() != null) {
            body.put("aud", token.audience());
        }
        return ResponseEntity.ok(body);
    }

    private Optional<String> authenticateClient(HttpServletRequest request) {
        List<String> headers = headerValues(request, HttpHeaders.AUTHORIZATION);
        if (headers.size() != 1) {
            return Optional.empty();
        }
        String authorization = headers.get(0);
        if (!authorization.startsWith("Basic ")
                || authorization.length() <= "Basic ".length()) {
            return Optional.empty();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(
                    authorization.substring("Basic ".length())
            );
            String credentials = new String(
                    decoded,
                    StandardCharsets.UTF_8
            );
            int separator = credentials.indexOf(':');
            if (separator <= 0
                    || separator != credentials.lastIndexOf(':')) {
                return Optional.empty();
            }
            String clientId = credentials.substring(0, separator);
            String clientSecret = credentials.substring(separator + 1);
            if (!constantTimeEquals(
                    clientId,
                    introspectionProperties.getClientId()
            ) || !constantTimeEquals(
                    clientSecret,
                    introspectionProperties.getClientSecret()
            )) {
                return Optional.empty();
            }
            return Optional.of(clientId);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private boolean hasAuthenticationCookie(HttpServletRequest request) {
        String accessName = authCookieService.accessTokenCookieName();
        String refreshName = authCookieService.refreshTokenCookieName();
        for (String header : headerValues(request, HttpHeaders.COOKIE)) {
            for (String part : header.split(";")) {
                String name = part.trim().split("=", 2)[0];
                if (accessName.equals(name) || refreshName.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> headerValues(
            HttpServletRequest request,
            String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null) {
            return List.of();
        }
        return Collections.list(values);
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }
}
