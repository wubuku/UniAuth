package org.dddml.uniauth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class SensitiveResponseHeadersFilter extends OncePerRequestFilter {

    private final Environment environment;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader(
                "Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=()"
        );
        if (isSensitivePath(request.getRequestURI())) {
            response.setHeader(
                    "Cache-Control",
                    "no-store, no-cache, must-revalidate"
            );
            response.setHeader("Pragma", "no-cache");
        }
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            response.setHeader(
                    "Content-Security-Policy",
                    "default-src 'self'; frame-ancestors 'none'; "
                            + "object-src 'none'; base-uri 'self'"
            );
            response.setHeader(
                    "Strict-Transport-Security",
                    "max-age=31536000; includeSubDomains"
            );
        }
        filterChain.doFilter(request, response);
    }

    private boolean isSensitivePath(String path) {
        return path.startsWith("/api/")
                || path.startsWith("/oauth2/")
                || path.startsWith("/login");
    }
}
