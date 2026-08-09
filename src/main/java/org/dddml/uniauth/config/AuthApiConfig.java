package org.dddml.uniauth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 认证API配置
 * 处理公开的认证相关API端点
 */
@Configuration
public class AuthApiConfig {

    /**
     * 认证API安全过滤器链
     * 只处理认证相关的API端点，不应用JWT验证
     */
    @Bean
    @Order(0)
    public SecurityFilterChain authApiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/auth/**")  // 只匹配认证API
            .cors(cors -> {})  // 启用CORS
            .authorizeHttpRequests(authz -> authz
                .requestMatchers(HttpMethod.OPTIONS, "/api/auth/**").permitAll()
                .requestMatchers(HttpMethod.POST,
                    "/api/auth/register",
                    "/api/auth/login",
                    "/api/auth/logout",
                    "/api/auth/refresh",
                    "/api/auth/check-verification-code",
                    "/api/auth/send-verification-code",
                    "/api/auth/verify-email",
                    "/api/auth/forgot-password",
                    "/api/auth/verify-reset-code",
                    "/api/auth/web3/verify",
                    "/api/auth/web3/bind"
                ).permitAll()
                .requestMatchers(HttpMethod.GET,
                    "/api/auth/csrf",
                    "/api/auth/email/status/*",
                    "/api/auth/web3/nonce/*",
                    "/api/auth/web3/status/*"
                ).permitAll()
                .anyRequest().denyAll()
            )
            .csrf(csrf -> csrf.disable());

        return http.build();
    }
}
