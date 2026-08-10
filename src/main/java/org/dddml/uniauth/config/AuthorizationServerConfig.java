package org.dddml.uniauth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security boundary for the repository's custom OAuth2 interoperability
 * endpoints. This is not a Spring Authorization Server.
 */
@Configuration
public class AuthorizationServerConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(
        HttpSecurity http) throws Exception {
        http
            .securityMatcher(
                "/oauth2/jwks",
                "/oauth2/introspect",
                "/oauth2/authorize",
                "/oauth2/token",
                "/oauth2/revoke"
            )
            .cors(cors -> {})
            .authorizeHttpRequests(authz -> authz
                .requestMatchers(HttpMethod.GET, "/oauth2/jwks").permitAll()
                .requestMatchers(HttpMethod.POST, "/oauth2/introspect").permitAll()
                .anyRequest().denyAll()
            )
            .csrf(csrf -> csrf.disable());

        return http.build();
    }
}
