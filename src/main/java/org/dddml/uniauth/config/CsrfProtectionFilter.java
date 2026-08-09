package org.dddml.uniauth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.service.AuthCookieService;
import org.dddml.uniauth.service.CsrfBootstrapService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class CsrfProtectionFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS =
            Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final AuthCookieService authCookieService;
    private final CsrfBootstrapProperties properties;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod())
                || "/oauth2/introspect".equals(request.getRequestURI())
                || !hasAuthenticationCookie(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        List<String> submitted = headerValues(
                request,
                properties.getHeaderName()
        );
        Object expected = request.getSession(false) == null
                ? null
                : request.getSession(false).getAttribute(
                        CsrfBootstrapService.SESSION_ATTRIBUTE
                );
        if (submitted.size() != 1
                || submitted.get(0).isBlank()
                || !(expected instanceof String expectedToken)
                || !constantTimeEquals(submitted.get(0), expectedToken)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"error\":\"CSRF_TOKEN_INVALID\"}"
            );
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean hasAuthenticationCookie(HttpServletRequest request) {
        String accessName = authCookieService.accessTokenCookieName();
        String refreshName = authCookieService.refreshTokenCookieName();
        for (String header : headerValues(request, "Cookie")) {
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
