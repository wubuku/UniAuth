package org.dddml.email.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EmailResponseSecurityFilter extends OncePerRequestFilter {

    static final String NO_STORE = "no-store";
    static final String NO_CACHE = "no-cache";
    static final String NOSNIFF = "nosniff";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !EmailApiRequestMatcher.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        response.setHeader(HttpHeaders.PRAGMA, NO_CACHE);
        response.setHeader("X-Content-Type-Options", NOSNIFF);
        filterChain.doFilter(request, response);
    }
}
