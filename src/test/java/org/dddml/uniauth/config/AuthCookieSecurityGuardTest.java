package org.dddml.uniauth.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthCookieSecurityGuardTest {

    @Test
    void allowsInsecureCookiesOutsideProduction() {
        AuthCookieProperties properties = properties(false);
        MockEnvironment environment = environment("test", false);

        assertThatCode(() -> new AuthCookieSecurityGuard(
                environment,
                properties
        ).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void allowsSecureProductionCookies() {
        AuthCookieProperties properties = properties(true);
        MockEnvironment environment = environment("prod", true);

        assertThatCode(() -> new AuthCookieSecurityGuard(
                environment,
                properties
        ).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnInsecureAuthenticationCookieOverrideInProduction() {
        AuthCookieProperties properties = properties(false);
        MockEnvironment environment = environment("prod", true);

        assertThatThrownBy(() -> new AuthCookieSecurityGuard(
                environment,
                properties
        ).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Production requires app.auth.cookie.secure=true");
    }

    @Test
    void rejectsAnInsecureSessionCookieOverrideInProduction() {
        AuthCookieProperties properties = properties(true);
        MockEnvironment environment = environment("prod", false);

        assertThatThrownBy(() -> new AuthCookieSecurityGuard(
                environment,
                properties
        ).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Production requires server.servlet.session.cookie.secure=true"
                );
    }

    private AuthCookieProperties properties(boolean secure) {
        AuthCookieProperties properties = new AuthCookieProperties();
        properties.setSecure(secure);
        return properties;
    }

    private MockEnvironment environment(String profile, boolean sessionCookieSecure) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        environment.setProperty(
                "server.servlet.session.cookie.secure",
                Boolean.toString(sessionCookieSecure)
        );
        return environment;
    }
}
