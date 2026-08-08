package org.dddml.email.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

final class EmailApiRequestMatcher {

    private EmailApiRequestMatcher() {
    }

    static boolean matches(HttpServletRequest request) {
        String servletPath = UrlPathHelper.defaultInstance.removeSemicolonContent(
            request.getServletPath()
        );
        return "/api/email".equals(servletPath)
            || servletPath.startsWith("/api/email/");
    }
}
