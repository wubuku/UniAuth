package org.dddml.uniauth.config;

import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.service.UserService;
import org.dddml.uniauth.dto.UserDto;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientResponseException;
import org.dddml.uniauth.service.LoginMethodService;
import org.dddml.uniauth.service.AuthCookieService;
import org.dddml.uniauth.service.TokenValidationService;
import org.dddml.uniauth.service.TokenIssuanceFacade;
import org.dddml.uniauth.service.AuthenticationCredentialResolver;
import org.dddml.uniauth.service.AuthRateLimitExceededException;
import org.dddml.uniauth.service.AuthRateLimiter;
import org.dddml.uniauth.service.AuthRateLimiterUnavailableException;
import org.dddml.uniauth.service.OAuth2BindingIntentService;
import org.dddml.uniauth.service.OAuth2ProviderProfile;
import org.dddml.uniauth.service.OAuth2ProviderProfileService;
import org.dddml.uniauth.service.RecentAuthenticationService;
import org.springframework.http.*;
import org.springframework.core.ParameterizedTypeReference;
import java.util.List;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(FrontendProperties.class)
@Slf4j
public class SecurityConfig {

    @Autowired
    private UserService userService;

    @Autowired
    @Qualifier("oauth2RestTemplate")
    private RestTemplate oauth2RestTemplate;

    @Autowired
    private OAuth2AuthorizedClientService authorizedClientService;

    @Autowired
    private ClientRegistrationRepository clientRegistrationRepository;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LoginMethodService loginMethodService;

    @Autowired
    private AuthCookieService authCookieService;

    @Autowired
    private TokenValidationService tokenValidationService;

    @Autowired
    private TokenIssuanceFacade tokenIssuanceFacade;

    @Autowired
    private AuthenticationCredentialResolver credentialResolver;

    @Autowired
    private OAuth2RedirectPolicy oauth2RedirectPolicy;

    @Autowired
    private OAuth2ProviderProfileService oauth2ProviderProfileService;

    @Autowired
    private OAuth2BindingIntentService oauth2BindingIntentService;

    @Autowired
    private RecentAuthenticationService recentAuthenticationService;

    @Autowired
    private AuthRateLimiter authRateLimiter;

    /**
     * 配置AuthenticationManager用于本地用户认证
     */
    @Bean
    public AuthenticationManager authenticationManager() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(authProvider);
    }

    @Bean
    public AuthenticationSuccessHandler oauth2SuccessHandler() {
        return (request, response, authentication) -> {
            OAuth2AuthenticationToken oauthToken =
                    (OAuth2AuthenticationToken) authentication;
            try {
                OAuth2ProviderProfile profile =
                        oauth2ProviderProfileService.resolve(authentication);
                String sessionId = request.getSession(false) == null
                        ? null
                        : request.getSession(false).getId();
                UserService.OAuthAuthenticationResult result =
                        userService.completeOAuth(
                                profile,
                                request.getParameter("state"),
                                sessionId
                        );
                Map<String, Object> issued = result.binding()
                        ? tokenIssuanceFacade.issue(
                                result.user(),
                                response,
                                "Binding successful",
                                result.authTime(),
                                null
                        )
                        : tokenIssuanceFacade.issue(
                                result.user(),
                                request,
                                response,
                                "Login successful",
                                result.authTime()
                        );
                log.info(result.binding()
                        ? "OAuth2 account binding completed"
                        : "OAuth2 login completed");
                if (acceptsJson(request)) {
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    new ObjectMapper().writeValue(response.getWriter(), issued);
                } else {
                    response.sendRedirect(oauth2RedirectPolicy.successRedirect());
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "OAuth2 processing rejected: {}",
                        exception.getClass().getSimpleName()
                );
                writeOAuth2Error(request, response);
            } finally {
                authorizedClientService.removeAuthorizedClient(
                        oauthToken.getAuthorizedClientRegistrationId(),
                        authentication.getName()
                );
            }
        };
    }

    @Bean
    public AuthenticationFailureHandler oauth2FailureHandler() {
        return (request, response, exception) -> {
            log.warn("OAuth2 login failed");
            try {
                authRateLimiter.requireAllowed(
                        AuthRateLimiter.Policy.OAUTH_AUTHORIZE,
                        request.getRemoteAddr(),
                        "failure"
                );
            } catch (AuthRateLimitExceededException rateLimited) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                return;
            } catch (AuthRateLimiterUnavailableException unavailable) {
                response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
                return;
            }
            response.sendRedirect(
                    oauth2RedirectPolicy.loginErrorRedirect("oauth2_failed")
            );
        };
    }

    /**
     * 主安全过滤器链
     * 处理Web页面和OAuth2登录
     */
    @Bean
    @Order(3)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http,
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>
                    oauth2AuthorizationCodeTokenResponseClient)
            throws Exception {
        http
            // CORS配置 - 必须在其他配置之前启用
            .cors(cors -> {})
            // CSRF保护
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringRequestMatchers("/oauth2/**", "/api/auth/**", "/api/logout")
            )
            // 授权规则
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/", "/login/**", "/oauth2/**", "/css/**", "/js/**",
                               "/images/**", "/static/**", "/index.html", "/assets/**",
                               "/favicon.ico", "/error",
                               "/actuator/health/liveness",
                               "/actuator/health/readiness",
                               "/swagger-ui/**", "/swagger-ui.html",
                               "/v3/api-docs/**", "/swagger-resources/**", "/webjars/**").permitAll()
                .requestMatchers("/api/auth/**").permitAll()  // 认证API公开
                .requestMatchers("/api/user").authenticated()  // 所有认证用户都可以访问
                .requestMatchers("/api/admin/**").hasRole("ADMIN")  // 只有ADMIN角色可以访问
                .requestMatchers("/api/manager/**").hasAnyRole("ADMIN", "MANAGER")  // ADMIN或MANAGER角色可以访问
                .anyRequest().authenticated()
            )
            // OAuth2登录配置
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .successHandler(oauth2SuccessHandler())
                .failureHandler(oauth2FailureHandler())
                .authorizationEndpoint(authz -> authz
                    .authorizationRequestResolver(authorizationRequestResolver(clientRegistrationRepository))
                )
                .tokenEndpoint(token -> token
                    .accessTokenResponseClient(
                        oauth2AuthorizationCodeTokenResponseClient
                    )
                )
                .userInfoEndpoint(userInfo -> userInfo
                    .userService(oauth2UserService())
                )
                .redirectionEndpoint(redirection -> redirection
                    .baseUri("/oauth2/callback")
                )
            )
            // 登出配置
            .logout(logout -> logout
                .logoutSuccessUrl("/login")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .addLogoutHandler((request, response, authentication) ->
                        authCookieService.clearAuthenticationCookies(response))
                .permitAll()
            );

        return http.build();
    }

    // 新增：自定义OAuth2用户服务
    @Bean
    public OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2UserService() {
        return userRequest -> {
            String registrationId = userRequest.getClientRegistration().getRegistrationId();

            if ("x".equals(registrationId)) {  // ✅ X API v2：检查 'x' 而不是 'twitter'
                try {
                    OAuth2User xUser = loadXUser(userRequest);

                    // 为 X 手动存储 access token 到 authorizedClientService。
                    // 注意：这里无法直接存储，因为没有Authentication对象
                    // X token 验证暂时无法工作，除非使用其他方法。

                    return xUser;
                } catch (Exception e) {
                    String status = e instanceof RestClientResponseException response
                            ? response.getStatusCode().toString()
                            : "unavailable";
                    log.warn(
                            "X OAuth2 user-info request failed: status={} cause={}",
                            status,
                            e.getClass().getSimpleName()
                    );
                    throw new OAuth2AuthenticationException(
                            new OAuth2Error("invalid_user_info_response"),
                            "Failed to load X user profile",
                            e
                    );
                }
            } else {
                // 对于其他提供商使用默认服务
                DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
                delegate.setRestOperations(oauth2RestTemplate);
                OAuth2User oauth2User = delegate.loadUser(userRequest);

                if ("github".equals(registrationId)) {
                    return processGitHubUser(oauth2User, userRequest.getAccessToken());
                } else if ("google".equals(registrationId)) {
                    return processGoogleUser(oauth2User);
                }

                return oauth2User;
            }
        };
    }

    private OAuth2User loadXUser(OAuth2UserRequest userRequest) throws Exception {  // ✅ X API v2：方法名更新
        // 手动调用Twitter API获取用户信息
        String authorizationHeader = "Bearer " + userRequest.getAccessToken().getTokenValue();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authorizationHeader);

        HttpEntity<?> entity = new HttpEntity<>(headers);

        // 调用Twitter API v2
        ResponseEntity<Map<String, Object>> response = oauth2RestTemplate.exchange(
            "https://api.x.com/2/users/me?user.fields=created_at,description,entities,id,location,name,pinned_tweet_id,profile_image_url,protected,public_metrics,url,username,verified,verified_type,withheld",
            HttpMethod.GET,
            entity,
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );

        if (response.getBody() != null && response.getBody().containsKey("data")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> userData = (Map<String, Object>) response.getBody().get("data");

            // 创建扁平化的属性映射
            Map<String, Object> attributes = new HashMap<>();
            attributes.putAll(userData);

            // 确保username属性存在
            if (!attributes.containsKey("username")) {
                throw new IllegalArgumentException("Twitter API response missing 'username' field");
            }

            return new DefaultOAuth2User(
                Collections.singleton(new SimpleGrantedAuthority("ROLE_USER")),
                attributes,
                "username"  // 使用username作为name属性
            );
        } else {
            throw new IllegalArgumentException("Invalid Twitter API response structure");
        }
    }

    private OAuth2User processGitHubUser(OAuth2User oauth2User, OAuth2AccessToken accessToken) {
        Map<String, Object> attributes = new HashMap<>(oauth2User.getAttributes());
        attributes.remove(OAuth2ProviderProfileService.VERIFIED_GITHUB_EMAIL);

        // Only the verified primary email endpoint can establish contact trust.
        if (accessToken.getScopes().contains("user:email")) {
            try {
                String email = getGitHubUserEmail(accessToken.getTokenValue());
                if (email != null) {
                    attributes.put(
                            OAuth2ProviderProfileService.VERIFIED_GITHUB_EMAIL,
                            email
                    );
                    log.debug("Verified primary GitHub email retrieved");
                } else {
                    log.debug("No verified primary GitHub email available");
                }
            } catch (Exception e) {
                log.warn("GitHub email lookup failed");
                // 不要因为邮箱获取失败而影响整个登录流程
                // 用户仍可以使用其他信息登录
            }
        }

        return new DefaultOAuth2User(
            oauth2User.getAuthorities(),
            attributes,
            "login"  // GitHub的用户名字段是"login"
        );
    }

    // 新增：获取GitHub用户邮箱的方法
    private String getGitHubUserEmail(String accessToken) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<List<Map<String, Object>>> response = oauth2RestTemplate.exchange(
            "https://api.github.com/user/emails",
            HttpMethod.GET,
            entity,
            new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        if (response.getBody() != null) {
            // 查找主要且已验证的邮箱
            return response.getBody().stream()
                .filter(email -> Boolean.TRUE.equals(email.get("primary")) &&
                               Boolean.TRUE.equals(email.get("verified")))
                .findFirst()
                .map(email -> (String) email.get("email"))
                .orElse(null);
        }

        return null;
    }

    private OAuth2User processGoogleUser(OAuth2User oauth2User) {
        // Google用户处理保持现有逻辑
        return oauth2User;
    }


    // 使用 Spring Security 生成的 state，并为支持的客户端启用 PKCE。
    @Bean
    public OAuth2AuthorizationRequestResolver authorizationRequestResolver(ClientRegistrationRepository clientRegistrationRepository) {
        return ExplicitOAuth2AuthorizationRequestResolver.create(
                clientRegistrationRepository,
                credentialResolver,
                tokenValidationService,
                recentAuthenticationService,
                oauth2BindingIntentService,
                authRateLimiter
        );
    }

    private boolean acceptsJson(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE);
    }

    private void writeOAuth2Error(
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        if (acceptsJson(request)) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            new ObjectMapper().writeValue(response.getWriter(), Map.of(
                    "message", "OAuth2 processing failed",
                    "authenticated", false,
                    "error", "oauth2_processing_failed",
                    "timestamp", System.currentTimeMillis(),
                    "path", request.getRequestURI()
            ));
        } else {
            response.sendRedirect(
                    oauth2RedirectPolicy.loginErrorRedirect(
                            "oauth2_processing_failed"
                    )
            );
        }
    }

}
