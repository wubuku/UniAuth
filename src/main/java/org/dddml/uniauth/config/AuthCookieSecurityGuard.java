package org.dddml.uniauth.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthCookieSecurityGuard implements InitializingBean {

    private static final String SESSION_COOKIE_SECURE_PROPERTY =
            "server.servlet.session.cookie.secure";
    private static final String SESSION_COOKIE_NAME_PROPERTY =
            "server.servlet.session.cookie.name";

    private final Environment environment;
    private final AuthCookieProperties authCookieProperties;

    @Override
    public void afterPropertiesSet() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        if (!authCookieProperties.isSecure()) {
            throw new IllegalStateException(
                    "Production requires app.auth.cookie.secure=true"
            );
        }
        if (!"__Host-accessToken".equals(
                authCookieProperties.getAccessTokenName()
        ) || !"__Host-refreshToken".equals(
                authCookieProperties.getRefreshTokenName()
        )) {
            throw new IllegalStateException(
                    "Production authentication cookies require __Host- names"
            );
        }
        Boolean sessionCookieSecure = environment.getProperty(
                SESSION_COOKIE_SECURE_PROPERTY,
                Boolean.class
        );
        if (!Boolean.TRUE.equals(sessionCookieSecure)) {
            throw new IllegalStateException(
                    "Production requires server.servlet.session.cookie.secure=true"
            );
        }
        if (!"__Host-JSESSIONID".equals(environment.getProperty(
                SESSION_COOKIE_NAME_PROPERTY
        ))) {
            throw new IllegalStateException(
                    "Production session cookie requires the __Host-JSESSIONID name"
            );
        }
    }
}
