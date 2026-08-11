package org.dddml.uniauth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.service.OAuth2BindingIntentService;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@RequiredArgsConstructor
public final class OAuth2BindingSessionRotationFilter
        extends OncePerRequestFilter {

    private static final String CALLBACK_PATH = "/oauth2/callback";

    private final OAuth2BindingIntentService bindingIntentService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(
                request.getContextPath().length()
        );
        return !CALLBACK_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(
                new HttpServletRequestWrapper(request) {
                    @Override
                    public String changeSessionId() {
                        HttpSession session = getSession(false);
                        String previousSessionId =
                                session == null ? null : session.getId();
                        String currentSessionId = super.changeSessionId();
                        if (previousSessionId != null
                                && !previousSessionId.equals(currentSessionId)) {
                            bindingIntentService.rotateSession(
                                    previousSessionId,
                                    currentSessionId
                            );
                        }
                        return currentSessionId;
                    }
                },
                response
        );
    }
}
