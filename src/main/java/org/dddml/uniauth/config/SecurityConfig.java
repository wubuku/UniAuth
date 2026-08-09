package org.dddml.uniauth.config;

import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.service.UserService;
import org.dddml.uniauth.dto.UserDto;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
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
import org.dddml.uniauth.service.LoginMethodService;
import org.dddml.uniauth.service.AuthCookieService;
import org.dddml.uniauth.service.TokenValidationService;
import org.dddml.uniauth.service.TokenIssuanceFacade;
import org.dddml.uniauth.service.AuthenticationCredentialResolver;
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

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

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

    /**
     * OAuth2登录成功处理器 - 智能路由版本
     * 根据用户登录状态自动选择登录或绑定流程
     * 支持Token双重传递（cookie + JSON响应体）
     */
    @Bean
    public AuthenticationSuccessHandler oauth2SuccessHandler() {
        return new AuthenticationSuccessHandler() {
            @Override
            public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                              Authentication authentication) throws IOException {
                log.debug("OAuth2 authentication callback received");

                try {
                    // 🎯 核心：检查用户是否已登录
                    String currentUserId = getCurrentUserIdFromRequest(request);
                    boolean isUserLoggedIn = false;
                    
                    // 验证用户是否真正存在（防止无效token导致的绑定失败）
                    if (currentUserId != null) {
                        try {
                            if (userService.getUserById(currentUserId) != null) {
                                isUserLoggedIn = true;
                            } else {
                                log.warn("OAuth2 binding context referenced a missing user");
                                currentUserId = null;
                            }
                        } catch (Exception e) {
                            log.warn("OAuth2 binding context could not be verified");
                            currentUserId = null;
                        }
                    }
                    
                    log.debug("OAuth2 callback mode: {}", isUserLoggedIn ? "binding" : "login");

                    UserDto userDto = null;
                    // 处理Google用户（OpenID Connect）
                    if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
                        String providerUserId = oidcUser.getSubject();
                        String email = oidcUser.getEmail();
                        String name = oidcUser.getFullName();
                        String picture = oidcUser.getPicture();

                        // 调用新的方法，传入isBinding和currentUserId
                        userDto = userService.getOrCreateOAuthUser(
                            "GOOGLE",
                            providerUserId, email, name, picture,
                            isUserLoggedIn, currentUserId
                        );

                        log.debug("OAuth2 provider resolved: google");
                    }
                    // 处理GitHub和Twitter用户（OAuth2）
                    else if (authentication.getPrincipal() instanceof OAuth2User oauth2User) {
                        String provider = determineProvider(oauth2User);
                        // ✅ 最小修复：registrationId 'x' 对应枚举值 'TWITTER'（UserService 需要枚举值）
                        if (authentication instanceof org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken oauth2Token) {
                            String registrationId = oauth2Token.getAuthorizedClientRegistrationId();
                            if ("x".equals(registrationId)) {
                                provider = "TWITTER";  // UserService 需要枚举值 "TWITTER"
                            } else if ("github".equals(registrationId)) {
                                provider = "GITHUB";  // UserService 需要枚举值 "GITHUB"
                            }
                        }
                        log.debug("OAuth2 provider resolved: {}", provider);
                        String providerUserId = getProviderUserId(oauth2User, provider);
                        String email = getProviderEmail(oauth2User, provider);
                        String name = getProviderName(oauth2User, provider);
                        String picture = getProviderPicture(oauth2User, provider);

                        userDto = userService.getOrCreateOAuthUser(
                            provider, providerUserId, email, name, picture,
                            isUserLoggedIn, currentUserId
                        );

                    }

                    if (userDto != null) {
                        Map<String, Object> issued = tokenIssuanceFacade.issue(
                                userDto,
                                request,
                                response,
                                isUserLoggedIn
                                        ? "Binding successful"
                                        : "Login successful",
                                null
                        );

                        if (isUserLoggedIn) {
                            log.info("OAuth2 account binding completed");
                        } else {
                            log.info("OAuth2 login completed");
                        }
                        
                        // 检测回调模式：使用Accept头判断
                        String callbackMode = "redirect";
                        // 如果没有指定回调模式，使用Accept头判断
                        if ("redirect".equals(callbackMode)) {
                            String acceptHeader = request.getHeader("Accept");
                            if (acceptHeader != null && acceptHeader.contains("application/json")) {
                                callbackMode = "json";
                            }
                        }
                        
                        if ("json".equals(callbackMode)) {
                            // 返回JSON响应 - 无头服务模式
                            response.setContentType("application/json");
                            response.setCharacterEncoding("UTF-8");
                            
                            // 构建响应数据
                            // 序列化并写入响应
                            ObjectMapper objectMapper = new ObjectMapper();
                            objectMapper.writeValue(response.getWriter(), issued);
                        } else {
                            // 重定向目标只来自经过校验的服务端部署配置。
                            log.debug("OAuth2 redirect response selected");
                            response.sendRedirect(oauth2RedirectPolicy.successRedirect());
                        }
                    }

                } catch (IllegalArgumentException e) {
                    // 业务逻辑错误（如账户已被绑定）
                    log.warn("OAuth2 processing rejected by account policy");
                    handleOAuth2Error(request, response, e.getMessage());
                } catch (Exception e) {
                    // 系统错误
                    log.error("OAuth2 processing failed");
                    handleOAuth2Error(request, response, "oauth2_processing_failed");
                }
            }

            /**
             * 从请求中获取当前登录用户ID
             * 通过JWT Cookie判断
             */
            private String getCurrentUserIdFromRequest(HttpServletRequest request) {
                try {
                    // 尝试提取userId，异常则返回null（不是登录状态）
                    try {
                        return credentialResolver.resolveAccessToken(request)
                                .map(tokenValidationService::getUserIdFromAccessToken)
                                .orElse(null);
                    } catch (RuntimeException e) {
                        log.debug("OAuth2 binding cookie was invalid or expired");
                        return null;
                    }
                } catch (Exception e) {
                    log.debug("OAuth2 binding cookie could not be processed");
                    return null;
                }
            }

            /**
             * 处理OAuth2错误，支持JSON响应和重定向
             */
            private void handleOAuth2Error(HttpServletRequest request, HttpServletResponse response, String errorMessage) throws IOException {
                // 默认 resolver 生成 opaque state；这里只防御性兼容旧 handler 输入。
                String callbackMode = "redirect";
                String requestedRedirect = null;
                
                // 即使旧输入携带 redirect URI，最终目标仍必须通过服务端 allowlist。
                String state = request.getParameter("state");
                if (state != null) {
                    try {
                        // 解码state参数
                        String decodedState = java.net.URLDecoder.decode(state, "UTF-8");
                        // 尝试解析为JSON
                        ObjectMapper objectMapper = new ObjectMapper();
                        Map<String, Object> stateData = objectMapper.readValue(decodedState, Map.class);
                        
                        // 获取回调模式
                        if (stateData.containsKey("response_type")) {
                            String responseType = stateData.get("response_type").toString();
                            if ("json".equals(responseType)) {
                                callbackMode = "json";
                            }
                        }
                        
                        // 获取重定向URI
                        if (stateData.containsKey("redirect_uri")) {
                            requestedRedirect = stateData.get("redirect_uri").toString();
                        }
                    } catch (Exception e) {
                        // 解析失败，使用默认值
                        log.debug("OAuth2 state could not be parsed");
                    }
                }
                
                // 如果没有指定回调模式，使用Accept头判断
                if ("redirect".equals(callbackMode)) {
                    String acceptHeader = request.getHeader("Accept");
                    if (acceptHeader != null && acceptHeader.contains("application/json")) {
                        callbackMode = "json";
                    }
                }
                
                if ("json".equals(callbackMode)) {
                    // 返回JSON响应 - 无头服务模式
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    
                    // 构建错误响应数据
                    Map<String, Object> responseData = new HashMap<>();
                    responseData.put("message", "OAuth2 processing failed");
                    responseData.put("authenticated", false);
                    responseData.put("error", errorMessage);
                    responseData.put("timestamp", System.currentTimeMillis());
                    responseData.put("path", request.getRequestURI());
                    
                    // 序列化并写入响应
                    ObjectMapper objectMapper = new ObjectMapper();
                    objectMapper.writeValue(response.getWriter(), responseData);
                } else {
                    response.sendRedirect(
                            oauth2RedirectPolicy.errorRedirect(requestedRedirect, errorMessage)
                    );
                }
            }
        };
    }

    @Bean
    public AuthenticationFailureHandler oauth2FailureHandler() {
        return (request, response, exception) -> {
            log.warn("OAuth2 login failed");
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
                                                     ClientRegistrationRepository clientRegistrationRepository) throws Exception {
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
                // 自定义Twitter用户信息获取
                try {
                    OAuth2User xUser = loadXUser(userRequest);  // ✅ X API v2：变量名和方法名更新

                    // 为Twitter手动存储access token到authorizedClientService
                    // 注意：这里无法直接存储，因为没有Authentication对象
                    // Twitter token验证暂时无法工作，除非使用其他方法

                    return xUser;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to load Twitter user", e);
                }
            } else {
                // 对于其他提供商使用默认服务
                DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
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
        ResponseEntity<Map<String, Object>> response = restTemplate().exchange(
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

        // GitHub邮箱获取：如果主用户信息中没有邮箱，尝试获取用户的邮箱列表
        if (attributes.get("email") == null && accessToken.getScopes().contains("user:email")) {
            try {
                String email = getGitHubUserEmail(accessToken.getTokenValue());
                if (email != null) {
                    attributes.put("email", email);
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

        ResponseEntity<List<Map<String, Object>>> response = restTemplate().exchange(
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
        DefaultOAuth2AuthorizationRequestResolver defaultResolver = 
            new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, "/oauth2/authorization");

        // 配置自定义的授权请求参数 - 先启用PKCE
        defaultResolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        return defaultResolver;
    }

    /**
     * 辅助方法：确定OAuth2提供商（后备方案，当无法从 Authentication 获取 registrationId 时使用）
     * 返回小写字符串（如 "github", "x"），与前端和 ApiAuthController 保持一致
     */
    private String determineProvider(OAuth2User oauth2User) {
        if (oauth2User.getAttribute("login") != null) {
            return "github";
        } else if (oauth2User.getAttribute("username") != null) {
            return "x";  // ✅ 返回注册ID 'x'，与前端和 ApiAuthController 保持一致
        }
        return "unknown";
    }

    /**
     * 辅助方法：获取提供商用户ID
     */
    private String getProviderUserId(OAuth2User oauth2User, String provider) {
        switch (provider.toLowerCase()) {
            case "github": return oauth2User.getAttribute("id").toString();
            case "twitter":
            case "x": return oauth2User.getAttribute("id");  // ✅ 支持 "twitter" 和 "x"
            default: return null;
        }
    }

    /**
     * 辅助方法：获取提供商邮箱
     */
    private String getProviderEmail(OAuth2User oauth2User, String provider) {
        switch (provider.toLowerCase()) {
            case "github": return oauth2User.getAttribute("email");
            case "twitter":
            case "x": return null; // Twitter/X不提供邮箱
            default: return null;
        }
    }

    /**
     * 辅助方法：获取提供商用户名
     */
    private String getProviderName(OAuth2User oauth2User, String provider) {
        switch (provider.toLowerCase()) {
            case "github": return (String) oauth2User.getAttribute("login");
            case "twitter":
            case "x": return (String) oauth2User.getAttribute("username");  // ✅ 支持 "twitter" 和 "x"
            default: return oauth2User.getName();
        }
    }

    /**
     * 辅助方法：获取提供商头像
     */
    private String getProviderPicture(OAuth2User oauth2User, String provider) {
        switch (provider.toLowerCase()) {
            case "github": return (String) oauth2User.getAttribute("avatar_url");
            case "twitter":
            case "x": return (String) oauth2User.getAttribute("profile_image_url");  // ✅ 支持 "twitter" 和 "x"
            default: return null;
        }
    }

}
