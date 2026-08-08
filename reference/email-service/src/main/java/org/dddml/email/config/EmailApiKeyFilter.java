package org.dddml.email.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class EmailApiKeyFilter extends OncePerRequestFilter {

    private final EmailSecurityProperties securityProperties;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !EmailApiRequestMatcher.matches(request)
            || !StringUtils.hasText(securityProperties.getApiKey());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String suppliedKey = uniqueSuppliedKey(request);
        if (!matchesConfiguredKey(suppliedKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(
                response.getOutputStream(),
                Map.of("success", false, "message", "Unauthorized")
            );
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String uniqueSuppliedKey(HttpServletRequest request) {
        Enumeration<String> suppliedKeys = request.getHeaders(
            EmailSecurityProperties.API_KEY_HEADER
        );
        if (suppliedKeys == null || !suppliedKeys.hasMoreElements()) {
            return null;
        }

        String suppliedKey = suppliedKeys.nextElement();
        return suppliedKeys.hasMoreElements() ? null : suppliedKey;
    }

    private boolean matchesConfiguredKey(String suppliedKey) {
        if (suppliedKey == null) {
            return false;
        }
        return MessageDigest.isEqual(
            securityProperties.getApiKey().getBytes(StandardCharsets.UTF_8),
            suppliedKey.getBytes(StandardCharsets.UTF_8)
        );
    }
}
