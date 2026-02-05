# Spring Boot OAuth2 重定向问题

您已经分析得很透彻了。让我给您提供几个**生产级别的实用解决方案**：

## 推荐方案：自定义 `state` 参数（最可靠）

这是业界最佳实践，完美解决您的问题。通过自定义 `AuthorizationRequestRepository` 来扩展 `state` 参数的功能：

```java
@Component
public class CustomAuthorizationRequestRepository 
    implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    
    private static final String REDIRECT_URI_COOKIE = "oauth2_redirect_uri";
    private static final int COOKIE_EXPIRE_SECONDS = 180;
    
    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return getCookie(request, REDIRECT_URI_COOKIE)
            .map(cookie -> deserialize(cookie))
            .orElse(null);
    }
    
    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        if (authorizationRequest == null) {
            removeCookie(request, response, REDIRECT_URI_COOKIE);
            return;
        }
        
        // 从请求参数中获取前端传来的重定向地址
        String redirectUri = request.getParameter("redirect_uri");
        if (redirectUri != null) {
            // 将重定向地址存入 Cookie（跨域安全）
            addCookie(response, REDIRECT_URI_COOKIE, redirectUri, COOKIE_EXPIRE_SECONDS);
        }
        
        // 保存原始的 authorization request
        String stateKey = authorizationRequest.getState();
        addCookie(response, stateKey, serialize(authorizationRequest), COOKIE_EXPIRE_SECONDS);
    }
    
    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request) {
        return loadAuthorizationRequest(request);
    }
    
    public String getRedirectUri(HttpServletRequest request) {
        return getCookie(request, REDIRECT_URI_COOKIE)
            .map(Cookie::getValue)
            .orElse(null);
    }
    
    // Cookie 工具方法
    private Optional<Cookie> getCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName())) {
                    return Optional.of(cookie);
                }
            }
        }
        return Optional.empty();
    }
    
    private void addCookie(HttpServletResponse response, String name, String value, int maxAge) {
        Cookie cookie = new Cookie(name, value);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(maxAge);
        // 生产环境启用
        // cookie.setSecure(true);
        response.addCookie(cookie);
    }
    
    private void removeCookie(HttpServletRequest request, HttpServletResponse response, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName())) {
                    cookie.setValue("");
                    cookie.setPath("/");
                    cookie.setMaxAge(0);
                    response.addCookie(cookie);
                }
            }
        }
    }
}
```


## 配置 SecurityConfig

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Autowired
    private CustomAuthorizationRequestRepository authorizationRequestRepository;
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .oauth2Login(oauth2 -> oauth2
                .authorizationEndpoint(authorization -> authorization
                    .authorizationRequestRepository(authorizationRequestRepository)
                )
                .successHandler(oAuth2AuthenticationSuccessHandler())
            );
        return http.build();
    }
    
    @Bean
    public AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler() {
        return new OAuth2AuthenticationSuccessHandler(authorizationRequestRepository);
    }
}
```


## 自定义成功处理器

```java
public class OAuth2AuthenticationSuccessHandler 
    extends SimpleUrlAuthenticationSuccessHandler {
    
    private final CustomAuthorizationRequestRepository authorizationRequestRepository;
    
    @Value("${app.frontend.url}")
    private String defaultRedirectUri;
    
    public OAuth2AuthenticationSuccessHandler(
            CustomAuthorizationRequestRepository authorizationRequestRepository) {
        this.authorizationRequestRepository = authorizationRequestRepository;
    }
    
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                      HttpServletResponse response,
                                      Authentication authentication) throws IOException {
        
        // 从 Cookie 中获取原始重定向地址
        String targetUrl = authorizationRequestRepository.getRedirectUri(request);
        
        if (targetUrl == null || targetUrl.isEmpty()) {
            targetUrl = defaultRedirectUri;
        }
        
        // 验证重定向地址安全性（防止开放重定向攻击）
        if (!isAuthorizedRedirectUri(targetUrl)) {
            throw new IllegalArgumentException("Unauthorized redirect URI");
        }
        
        // 清理 Cookie
        clearAuthenticationAttributes(request, response);
        
        // 可以在 URL 中添加 token
        String token = generateJwtToken(authentication);
        targetUrl = UriComponentsBuilder.fromUriString(targetUrl)
                .queryParam("token", token)
                .build().toUriString();
        
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
    
    private boolean isAuthorizedRedirectUri(String uri) {
        // 配置允许的重定向域名白名单
        List<String> authorizedRedirectUris = Arrays.asList(
            "https://api.u2511175.nyat.app:55139",
            "http://localhost:3000",
            "http://localhost:8080"
        );
        
        return authorizedRedirectUris.stream()
            .anyMatch(authorizedUri -> uri.startsWith(authorizedUri));
    }
    
    private void clearAuthenticationAttributes(HttpServletRequest request, 
                                               HttpServletResponse response) {
        super.clearAuthenticationAttributes(request);
        authorizationRequestRepository.removeAuthorizationRequest(request);
    }
}
```


## 前端调用方式

```typescript
// authService.ts
export const loginWithGitHub = (currentPath: string) => {
  // 将当前页面路径作为参数传递
  const redirectUri = encodeURIComponent(
    `${window.location.origin}${currentPath}`
  );
  
  // 后端会从这个参数读取并存入 Cookie
  window.location.href = 
    `/oauth2/authorization/github?redirect_uri=${redirectUri}`;
};

// 使用示例
loginWithGitHub(window.location.pathname); // 传递当前路径
```


## 方案优势

✅ **安全可靠**：使用 Cookie 存储，OAuth2 回调时浏览器会自动携带
✅ **完全兼容**：不修改 Spring Security 核心逻辑，符合 OAuth2 规范
✅ **动态重定向**：完美实现"从哪里来回哪里去"
✅ **防护攻击**：内置重定向 URI 白名单验证
✅ **跨域支持**：Cookie 配置适当的 Domain 和 Path

## 其他快速方案（简化版）

如果不想写太多代码，可以使用 **JWT + 前端 LocalStorage** 方案：

```java
// 后端只需返回 JWT token
@GetMapping("/login/oauth2/code/github")
public ResponseEntity<String> handleCallback(@AuthenticationPrincipal OAuth2User user) {
    String token = jwtService.generateToken(user);
    
    // 重定向到前端通用回调页
    return ResponseEntity
        .status(HttpStatus.FOUND)
        .location(URI.create(frontendUrl + "/auth/callback?token=" + token))
        .build();
}
```

```typescript
// 前端：登录前保存路径
localStorage.setItem('auth_redirect', window.location.pathname);
window.location.href = '/oauth2/authorization/github';

// 前端：回调页处理
const token = new URLSearchParams(window.location.search).get('token');
const redirectPath = localStorage.getItem('auth_redirect') || '/';
localStorage.removeItem('auth_redirect');

// 保存 token 并跳转
localStorage.setItem('token', token);
window.location.href = redirectPath;
```

您觉得哪个方案更适合您的场景？第一个方案更安全健壮，第二个方案实现更简单。
<span style="display:none">[^1]</span>

<div align="center">⁂</div>

[^1]: oauth2-redirect-issue-analysis.md

