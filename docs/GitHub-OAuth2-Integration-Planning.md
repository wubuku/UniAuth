# GitHub OAuth2 登录集成规划文档

> 状态：Historical。本文是 GitHub 集成前的规划材料；相关代码已经部分落地，
> 但本文的“准备实施”状态、代码片段和部署值不代表当前实现。
> 当前入口见 [文档导航](README.md)。

## 📋 项目概述

### 目标需求
在现有项目基础上增加 GitHub 账号登录演示，保持代码结构一致性和功能对等性。

## 🔍 技术调研结果

### GitHub OAuth2 特性
- **提供商**: GitHub
- **授权端点**: `https://github.com/login/oauth/authorize`
- **令牌端点**: `https://github.com/login/oauth/access_token`
- **用户信息端点**: `https://api.github.com/user`
- **用户信息端点（邮箱）**: `https://api.github.com/user/emails` （需要 `user:email` 权限）
- **支持的 scopes**: `user`, `user:email`, `user:follow`, `public_repo`, `repo`, `read:org` 等

### 用户信息结构对比

#### Google 用户信息结构
```json
{
  "sub": "123456789",
  "name": "John Doe",
  "given_name": "John",
  "family_name": "Doe",
  "picture": "https://...",
  "email": "john@example.com",
  "email_verified": true,
  "locale": "en"
}
```

#### GitHub 用户信息结构（核心字段）
```json
{
  "login": "johndoe",           // 用户名（主要标识符）
  "id": 12345,                  // 数字ID
  "name": "John Doe",           // 显示名称
  "avatar_url": "https://...",  // 头像URL
  "html_url": "https://github.com/johndoe", // 主页URL
  "email": null,                // 邮箱（通常为null，需单独获取）
  "bio": "...",                 // 个人简介
  "location": "...",            // 位置
  "company": "...",             // 公司
  "blog": "...",                // 博客
  "public_repos": 42,           // 公开仓库数
  "followers": 123,             // 粉丝数
  "following": 456             // 关注数
}
```

**重要说明**：
- `email` 字段通常为 `null`，即使申请了 `user:email` 权限
- 需要调用 `https://api.github.com/user/emails` API 获取完整邮箱列表
- 使用 `login` 字段作为用户的主要标识符（对应Google的 `sub` 字段）

## 🏗️ 实现方案设计

### 1. 配置层修改

#### 1.1 application.yml 配置扩展
```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            # 现有配置保持不变
            client-id: ${GOOGLE_CLIENT_ID:your-client-id}
            client-secret: ${GOOGLE_CLIENT_SECRET:your-client-secret}
            scope:
              - openid
              - profile
              - email
            redirect-uri: https://api.u2511175.nyat.app:55139/oauth2/callback
          github:  # 新增GitHub配置
            client-id: ${GITHUB_CLIENT_ID:your-github-client-id}
            client-secret: ${GITHUB_CLIENT_SECRET:your-github-client-secret}
            scope:
              - user:email
              - read:user
            redirect-uri: https://api.u2511175.nyat.app:55139/oauth2/callback
        provider:
          google:
            # 现有配置保持不变
            authorization-uri: https://accounts.google.com/o/oauth2/v2/auth
            token-uri: https://oauth2.googleapis.com/token
            user-info-uri: https://openidconnect.googleapis.com/v1/userinfo
            user-name-attribute: sub
            jwk-set-uri: https://www.googleapis.com/oauth2/v3/certs
          github:  # 新增GitHub提供商配置
            authorization-uri: https://github.com/login/oauth/authorize
            token-uri: https://github.com/login/oauth/access_token
            user-info-uri: https://api.github.com/user
            user-name-attribute: login
```

#### 1.2 环境变量要求
新增环境变量：
- `GITHUB_CLIENT_ID`: GitHub OAuth App 的 Client ID
- `GITHUB_CLIENT_SECRET`: GitHub OAuth App 的 Client Secret

### 2. 安全配置增强

#### 📋 统一回调URL机制详解
**回答您的问题：使用同一个回调URL是完全合适的！**

Spring Security OAuth2 通过以下机制区分提供商：

**核心机制：`state` 参数 + 会话存储**
- **state参数作用**: OAuth2安全机制，用于防止CSRF攻击和关联请求上下文
- **会话存储**: Spring Security在用户会话中存储`OAuth2AuthorizationRequest`对象，包含提供商信息
- **关联机制**: 通过state参数将回调请求与存储的授权请求关联，确定具体提供商

**详细工作流程**：
1. **发起授权**: 用户点击登录 → Spring Security创建`OAuth2AuthorizationRequest`（包含registrationId）
2. **存储上下文**: 将授权请求对象存储在HttpSession中，并生成唯一state参数建立关联
3. **重定向授权**: 携带state参数重定向到OAuth2提供商
4. **提供商回调**: 提供商返回code和原样state参数
5. **解析上下文**: Spring Security通过state参数从会话中取出对应的授权请求，确定registrationId

**Spring Security源码机制**：
- `OAuth2AuthorizationRequestRedirectFilter`: 处理授权发起，创建并存储授权请求
- `OAuth2LoginAuthenticationFilter`: 处理回调，通过state参数解析提供商身份
- `HttpSessionOAuth2AuthorizationRequestRepository`: 管理授权请求的会话存储

**实际URL示例**：
```
# Google登录发起
GET /oauth2/authorization/google
→ 创建OAuth2AuthorizationRequest(registrationId="google")
→ 存储到session，生成state="abc123"
→ 重定向: https://accounts.google.com/o/oauth2/auth?state=abc123...

# GitHub登录发起
GET /oauth2/authorization/github
→ 创建OAuth2AuthorizationRequest(registrationId="github")
→ 存储到session，生成state="def456"
→ 重定向: https://github.com/login/oauth/authorize?state=def456...

# 统一回调处理
GET /oauth2/callback?code=auth_code&state=abc123...
→ 通过state="abc123"从session取出OAuth2AuthorizationRequest
→ 确认registrationId="google" → 处理Google认证

GET /oauth2/callback?code=auth_code&state=def456...
→ 通过state="def456"从session取出OAuth2AuthorizationRequest
→ 确认registrationId="github" → 处理GitHub认证
```

**关键优势**：
- ✅ **不依赖URL路径**: 无论使用统一还是分离的回调URL，都能正确区分
- ✅ **安全可靠**: state参数提供CSRF保护，会话存储确保上下文完整性
- ✅ **扩展性强**: 支持任意数量的OAuth2提供商
- ✅ **遵循标准**: 完全符合OAuth2协议规范

#### 2.1 SecurityConfig.java 修改
```java
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
// ... 其他现有导入 ...

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // 现有的 oauth2SuccessHandler() 方法保持不变

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            )
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/", "/login/**", "/oauth2/**", "/css/**", "/js/**", "/images/**", "/static/**", "/error").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .successHandler(oauth2SuccessHandler())
                .userInfoEndpoint(userInfo -> userInfo
                    .userService(oauth2UserService())  // 新增：自定义用户服务处理多提供商
                )
                .redirectionEndpoint(redirection -> redirection
                    .baseUri("/oauth2/callback")
                )
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("id_token", "JSESSIONID")
            );

        return http.build();
    }

    // 新增：自定义OAuth2用户服务
    @Bean
    public OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2UserService() {
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();

        return userRequest -> {
            OAuth2User oauth2User = delegate.loadUser(userRequest);

            // 根据提供商类型处理用户信息
            String registrationId = userRequest.getClientRegistration().getRegistrationId();
            if ("github".equals(registrationId)) {
                return processGitHubUser(oauth2User, userRequest.getAccessToken());
            } else if ("google".equals(registrationId)) {
                return processGoogleUser(oauth2User);
            }

            return oauth2User;
        };
    }

    private OAuth2User processGitHubUser(OAuth2User oauth2User, OAuth2AccessToken accessToken) {
        Map<String, Object> attributes = new HashMap<>(oauth2User.getAttributes());

        // GitHub邮箱获取：如果主用户信息中没有邮箱，尝试获取用户的邮箱列表
        if (attributes.get("email") == null && accessToken.getScopes().contains("user:email")) {
            try {
                String email = getGitHubUserEmail(accessToken.getTokenValue());
                if (email != null) {
                    attributes.put("email", email);
                    System.out.println("Successfully retrieved GitHub user email: " + email);
                } else {
                    System.out.println("No verified primary email found for GitHub user: " + attributes.get("login"));
                }
            } catch (Exception e) {
                System.err.println("Failed to fetch GitHub user email: " + e.getMessage());
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

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
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
}
```

### 3. JWT验证服务增强

#### 3.1 JwtValidationService.java 修改
GitHub OAuth2 返回不透明的访问令牌（不透明字符串），而不是像Google那样的JWT格式ID Token。因此，需要添加GitHub访问令牌验证方法：

```java
@Service
public class JwtValidationService {

    private final RestTemplate restTemplate;

    public JwtValidationService() {
        this.restTemplate = new RestTemplate();
    }

    // 现有验证Google ID Token的方法保持不变
    public Map<String, Object> validateIdToken(String idToken) throws Exception {
        // 现有Google JWT验证逻辑
        // ...
    }

    // 新增：验证GitHub访问令牌的方法
    public Map<String, Object> validateGitHubToken(String accessToken) throws Exception {
        Map<String, Object> result = new HashMap<>();

        try {
            // 使用访问令牌调用GitHub用户信息API进行验证
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                "https://api.github.com/user",
                HttpMethod.GET,
                entity,
                Map.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> userInfo = response.getBody();

                result.put("valid", true);
                result.put("login", userInfo.get("login"));
                result.put("id", userInfo.get("id"));
                result.put("name", userInfo.get("name"));
                result.put("email", userInfo.get("email"));
                result.put("avatar_url", userInfo.get("avatar_url"));
                result.put("html_url", userInfo.get("html_url"));
                result.put("verified", true); // 如果API调用成功，说明令牌有效

                System.out.println("GitHub token validation successful for user: " + userInfo.get("login"));
            } else {
                result.put("valid", false);
                result.put("error", "Invalid access token");
            }

        } catch (HttpClientErrorException.Unauthorized e) {
            result.put("valid", false);
            result.put("error", "Access token unauthorized");
            throw new Exception("GitHub access token is invalid or expired");
        } catch (Exception e) {
            result.put("valid", false);
            result.put("error", e.getMessage());
            throw e;
        }

        return result;
    }
}
```

### 4. 控制器层增强

#### 4.1 AuthController.java 修改
```java
@Controller
public class AuthController {

    @Autowired
    private JwtValidationService jwtValidationService;

    // 现有方法保持不变

    @GetMapping("/test")
    public String test(@AuthenticationPrincipal OAuth2User oauth2User, Model model) {
        if (oauth2User != null) {
            // 增强用户信息显示，支持多提供商
            String provider = getProviderFromUser(oauth2User);
            model.addAttribute("provider", provider);
            model.addAttribute("userName", getUserName(oauth2User, provider));
            model.addAttribute("userEmail", getUserEmail(oauth2User, provider));
            model.addAttribute("userId", getUserId(oauth2User, provider));
            model.addAttribute("userAvatar", getUserAvatar(oauth2User, provider));

            // GitHub特定属性
            if ("github".equals(provider)) {
                model.addAttribute("userHtmlUrl", getUserHtmlUrl(oauth2User, provider));
                model.addAttribute("userPublicRepos", getUserPublicRepos(oauth2User, provider));
                model.addAttribute("userFollowers", getUserFollowers(oauth2User, provider));
            }

            model.addAttribute("isLoggedIn", true);
        } else {
            model.addAttribute("isLoggedIn", false);
        }
        return "test";
    }

    // 新增：从用户对象中提取提供商信息
    private String getProviderFromUser(OAuth2User user) {
        // 从用户属性或安全上下文中获取提供商信息
        // 可以通过自定义属性或会话信息实现
    }

    // 新增：根据提供商获取用户名
    private String getUserName(OAuth2User user, String provider) {
        if ("github".equals(provider)) {
            return user.getAttribute("name"); // GitHub的name字段
        } else if ("google".equals(provider)) {
            return user.getAttribute("name"); // Google的name字段
        }
        return user.getAttribute("name");
    }

    // 新增：根据提供商获取邮箱
    private String getUserEmail(OAuth2User user, String provider) {
        if ("github".equals(provider)) {
            return user.getAttribute("email"); // GitHub的email字段
        } else if ("google".equals(provider)) {
            return user.getAttribute("email"); // Google的email字段
        }
        return user.getAttribute("email");
    }

    // 新增：根据提供商获取用户ID
    private String getUserId(OAuth2User user, String provider) {
        if ("github".equals(provider)) {
            return user.getAttribute("login"); // GitHub的用户ID是login字段
        } else if ("google".equals(provider)) {
            return user.getAttribute("sub"); // Google的用户ID是sub字段
        }
        return user.getAttribute("sub");
    }

    // 新增：根据提供商获取头像
    private String getUserAvatar(OAuth2User user, String provider) {
        if ("github".equals(provider)) {
            return user.getAttribute("avatar_url"); // GitHub的头像字段
        } else if ("google".equals(provider)) {
            return user.getAttribute("picture"); // Google的头像字段
        }
        return null;
    }

    // 新增：获取GitHub主页URL
    private String getUserHtmlUrl(OAuth2User user, String provider) {
        if ("github".equals(provider)) {
            return user.getAttribute("html_url");
        }
        return null;
    }

    // 新增：获取GitHub公开仓库数
    private Integer getUserPublicRepos(OAuth2User user, String provider) {
        if ("github".equals(provider)) {
            return (Integer) user.getAttribute("public_repos");
        }
        return null;
    }

    // 新增：获取GitHub粉丝数
    private Integer getUserFollowers(OAuth2User user, String provider) {
        if ("github".equals(provider)) {
            return (Integer) user.getAttribute("followers");
        }
        return null;
    }

    // 新增：GitHub令牌验证端点
    @PostMapping("/api/validate-github-token")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> validateGitHubToken(HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 从请求参数获取访问令牌（前端传递）
            String accessToken = request.getParameter("accessToken");

            if (accessToken == null || accessToken.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "未提供访问令牌");
                return ResponseEntity.badRequest().body(response);
            }

            System.out.println("=== GitHub Token Validation Request ===");
            System.out.println("Access Token: " + accessToken.substring(0, Math.min(20, accessToken.length())) + "...");

            // 验证GitHub访问令牌
            Map<String, Object> validationResult = jwtValidationService.validateGitHubToken(accessToken);

            response.put("success", true);
            response.put("validation", validationResult);
            response.put("message", "GitHub 访问令牌验证成功");

            System.out.println("GitHub token validation successful");

        } catch (Exception e) {
            System.err.println("GitHub token validation failed: " + e.getMessage());
            e.printStackTrace();

            response.put("success", false);
            response.put("message", "GitHub 访问令牌验证失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }

        return ResponseEntity.ok(response);
    }
}
```

### 5. 前端界面增强

#### 5.1 login.html 修改
更新登录页面以支持多提供商选择：

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <title>OAuth2 Demo - 登录</title>
    <!-- 现有样式保持不变 -->
</head>
<body>
    <div class="container">
        <h1>选择登录方式</h1>

        <div class="description">
            <p>请选择您喜欢的登录方式：</p>
        </div>

        <!-- Google登录选项 -->
        <a href="/oauth2/authorization/google" class="google-btn">
            使用 Google 账户登录
        </a>

        <!-- 新增：GitHub登录选项 -->
        <a href="/oauth2/authorization/github" class="github-btn">
            使用 GitHub 账户登录
        </a>

        <div>
            <a href="/" class="back-link">返回首页</a>
        </div>
    </div>

    <style>
        .github-btn {
            display: inline-block;
            background-color: #24292e;
            color: white;
            padding: 12px 24px;
            text-decoration: none;
            border-radius: 5px;
            font-size: 16px;
            transition: background-color 0.3s;
            margin: 10px 0;
        }
        .github-btn:hover {
            background-color: #1a1e22;
        }
        .google-btn {
            /* 现有Google按钮样式 */
        }
    </style>
</body>
</html>
```

#### 5.2 test.html 修改
更新测试页面以显示提供商信息和相应的验证功能：

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">

    <!-- CSRF Token 元数据配置 -->
    <meta name="_csrf" th:content="${_csrf.token}"/>
    <meta name="_csrf_header" th:content="${_csrf.headerName}"/>

    <title>Google OAuth2 Demo - 测试页面</title>
    <style>
        body {
            font-family: Arial, sans-serif;
            max-width: 900px;
            margin: 0 auto;
            padding: 20px;
            background-color: #f5f5f5;
        }
        .container {
            background: white;
            padding: 30px;
            border-radius: 10px;
            box-shadow: 0 2px 10px rgba(0,0,0,0.1);
            margin-bottom: 20px;
        }
        h1 {
            color: #333;
            text-align: center;
            margin-bottom: 30px;
        }
        .user-info {
            background-color: #e8f5e8;
            padding: 20px;
            border-radius: 5px;
            margin-bottom: 30px;
            border-left: 4px solid #4CAF50;
        }
        .user-info h2 {
            color: #2e7d32;
            margin-top: 0;
        }
        .user-info p {
            margin: 8px 0;
            color: #333;
        }
        .button {
            background-color: #2196F3;
            color: white;
            padding: 12px 24px;
            border: none;
            border-radius: 5px;
            font-size: 16px;
            cursor: pointer;
            margin: 10px 5px;
            transition: background-color 0.3s;
        }
        .button:hover {
            background-color: #1976D2;
        }
        .button.danger {
            background-color: #f44336;
        }
        .button.danger:hover {
            background-color: #d32f2f;
        }
        .result {
            background-color: #f5f5f5;
            border: 1px solid #ddd;
            border-radius: 5px;
            padding: 20px;
            margin-top: 20px;
            font-family: 'Courier New', monospace;
            white-space: pre-wrap;
            max-height: 400px;
            overflow-y: auto;
        }
        .success {
            border-left: 4px solid #4CAF50;
            background-color: #e8f5e8;
        }
        .error {
            border-left: 4px solid #f44336;
            background-color: #ffebee;
        }
        .loading {
            text-align: center;
            color: #666;
            font-style: italic;
        }
        .actions {
            text-align: center;
            margin: 30px 0;
        }
        .logout-link {
            color: #f44336;
            text-decoration: none;
            font-size: 14px;
        }
        .logout-link:hover {
            text-decoration: underline;
        }
        .avatar-section {
            margin-top: 15px;
        }
        .avatar-section img {
            margin-top: 5px;
        }
        .github-info {
            margin-top: 15px;
            padding: 10px;
            background-color: #f8f9fa;
            border-radius: 5px;
            border-left: 3px solid #24292e;
        }
        .github-info a {
            color: #0366d6;
            text-decoration: none;
        }
        .github-info a:hover {
            text-decoration: underline;
        }
        .provider-info {
            background-color: #e3f2fd;
            padding: 10px;
            border-radius: 5px;
            margin-bottom: 20px;
            border-left: 4px solid #2196F3;
        }
    </style>
</head>
<body>
<div class="container">
    <h1>OAuth2 ID Token 验证测试</h1>

    <!-- 显示当前登录的提供商 -->
    <div class="provider-info">
        <p><strong>登录提供商：</strong><span th:text="${provider}">未知</span></p>
    </div>

    <div class="user-info">
        <h2>用户信息</h2>
        <!-- 根据提供商显示不同字段 -->
        <p><strong>用户名：</strong><span th:text="${userName}">未知</span></p>
        <p><strong>邮箱：</strong><span th:text="${userEmail != null ? userEmail : '未提供'}">未知</span></p>
        <p><strong>用户ID：</strong><span th:text="${userId}">未知</span></p>
        <!-- 显示头像（如果有） -->
        <div th:if="${userAvatar}" class="avatar-section">
            <p><strong>头像：</strong></p>
            <img th:src="${userAvatar}" alt="用户头像" style="width: 50px; height: 50px; border-radius: 50%; border: 2px solid #ddd;">
        </div>

        <!-- GitHub特定信息 -->
        <div th:if="${provider == 'github'}" class="github-info">
            <p><strong>GitHub主页：</strong><a th:href="${userHtmlUrl}" th:text="${userHtmlUrl}" target="_blank">查看GitHub</a></p>
            <p><strong>公开仓库数：</strong><span th:text="${userPublicRepos}">未知</span></p>
            <p><strong>粉丝数：</strong><span th:text="${userFollowers}">未知</span></p>
        </div>
    </div>

    <div class="actions">
        <!-- 根据提供商显示不同的验证按钮 -->
        <button id="validateBtn" th:if="${provider == 'google'}" class="button">验证 Google ID Token</button>
        <button id="validateGitHubBtn" th:if="${provider == 'github'}" class="button">验证 GitHub 访问令牌</button>
        <a href="/logout" class="logout-link">登出</a>
    </div>

    <!-- 验证结果显示区域 -->
    <div id="resultContainer" style="display: none;">
        <h3>验证结果</h3>
        <div id="result" class="result"></div>
    </div>
</div>

<script>
    // Google ID Token验证逻辑（现有）
    document.getElementById('validateBtn').addEventListener('click', function() {
        // 现有Google验证逻辑
    });

    // 新增：GitHub访问令牌验证逻辑
    document.getElementById('validateGitHubBtn').addEventListener('click', function() {
        const resultContainer = document.getElementById('resultContainer');
        const resultDiv = document.getElementById('result');

        // 显示加载状态
        resultContainer.style.display = 'block';
        resultDiv.className = 'result loading';
        resultDiv.textContent = '正在验证 GitHub 访问令牌...';

        // GitHub访问令牌不像Google ID Token那样存储在Cookie中
        // 在生产环境中，访问令牌应该安全存储在服务端会话中
        // 这里为了演示目的，使用prompt()获取用户输入

        // 安全说明：在实际应用中，应该：
        // 1. 在OAuth2成功回调时将访问令牌存储在服务端会话中
        // 2. 验证时从服务端会话中获取令牌，而不是让用户输入
        // 3. 绝对不要将访问令牌暴露给客户端JavaScript

        // 演示用途：提示用户输入访问令牌
        const tokenInput = prompt('请输入GitHub访问令牌进行验证：\n\n⚠️  注意：这只是演示功能。在生产环境中，令牌应安全存储在服务端。');
        if (!tokenInput) {
            resultDiv.className = 'result error';
            resultDiv.textContent = '❌ 验证已取消';
            return;
        }

        // 发送验证请求
        const headers = {
            'Content-Type': 'application/x-www-form-urlencoded',
        };
        headers[getCsrfHeader()] = getCsrfToken();

        fetch('/api/validate-github-token', {
            method: 'POST',
            body: new URLSearchParams({
                'accessToken': tokenInput
            }),
            headers: headers
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                resultDiv.className = 'result success';
                resultDiv.textContent = '✅ GitHub验证成功!\n\n' + JSON.stringify(data.validation, null, 2);
            } else {
                resultDiv.className = 'result error';
                resultDiv.textContent = '❌ GitHub验证失败: ' + data.message;
            }
        })
        .catch(error => {
            resultDiv.className = 'result error';
            resultDiv.textContent = '❌ 请求失败: ' + error.message;
            console.error('Error:', error);
        });
    });
</script>
</div>
</body>
</html>
```

### 6. 启动脚本增强

#### 6.1 start.sh 修改
更新启动脚本以支持GitHub相关的环境变量。由于现有脚本比较复杂（从JSON文件读取Google凭据），需要添加GitHub凭据的处理：

```bash
#!/bin/bash

# Google OAuth2 Demo 启动脚本
echo "=== Google + GitHub OAuth2 Demo 启动脚本 ==="

# 项目根目录
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLIENT_SECRET_FILE="$PROJECT_DIR/../docs/client_secret_864964610919-fe6l31cv6ervqflfjpd9ov9sun9olqa7.apps.googleusercontent.com.json"

echo "项目目录: $PROJECT_DIR"
echo "客户端密钥文件: $CLIENT_SECRET_FILE"

# 检查客户端密钥文件是否存在
if [ ! -f "$CLIENT_SECRET_FILE" ]; then
    echo "错误: 客户端密钥文件不存在: $CLIENT_SECRET_FILE"
    exit 1
fi

# 读取Google客户端配置
echo "读取 Google OAuth2 客户端配置..."
CLIENT_ID=$(cat "$CLIENT_SECRET_FILE" | grep -o '"client_id":"[^"]*"' | cut -d'"' -f4)
CLIENT_SECRET=$(cat "$CLIENT_SECRET_FILE" | grep -o '"client_secret":"[^"]*"' | cut -d'"' -f4)

if [ -z "$CLIENT_ID" ] || [ -z "$CLIENT_SECRET" ]; then
    echo "错误: 无法从配置文件中读取客户端 ID 或密钥"
    exit 1
fi

echo "Google客户端 ID: $CLIENT_ID"
echo "Google客户端密钥: ${CLIENT_SECRET:0:10}..."

# 设置Google环境变量
export GOOGLE_CLIENT_ID="$CLIENT_ID"
export GOOGLE_CLIENT_SECRET="$CLIENT_SECRET"

# GitHub环境变量设置（可以从环境变量或配置文件读取）
# 注意：GitHub OAuth App需要手动创建，无法从现有文件读取
export GITHUB_CLIENT_ID="${GITHUB_CLIENT_ID:-your-github-client-id}"
export GITHUB_CLIENT_SECRET="${GITHUB_CLIENT_SECRET:-your-github-client-secret}"

if [ "$GITHUB_CLIENT_ID" = "your-github-client-id" ] || [ "$GITHUB_CLIENT_SECRET" = "your-github-client-secret" ]; then
    echo "警告: GitHub客户端ID和密钥未设置，请设置GITHUB_CLIENT_ID和GITHUB_CLIENT_SECRET环境变量"
    echo "       或者在GitHub开发者设置中创建OAuth App并设置这些变量"
fi

echo "环境变量设置完成:"
echo "  GOOGLE_CLIENT_ID=$GOOGLE_CLIENT_ID"
echo "  GOOGLE_CLIENT_SECRET=***"
echo "  GITHUB_CLIENT_ID=$GITHUB_CLIENT_ID"
echo "  GITHUB_CLIENT_SECRET=***"

# 编译并启动应用
echo "编译项目..."
cd "$PROJECT_DIR"
mvn clean compile

if [ $? -ne 0 ]; then
    echo "错误: Maven 编译失败"
    exit 1
fi

echo "启动 Spring Boot 应用..."
echo "应用将在 http://localhost:8081 上运行"
echo "Google登录: http://localhost:8081/oauth2/authorization/google"
echo "GitHub登录: http://localhost:8081/oauth2/authorization/github"
echo "按 Ctrl+C 停止应用"
echo ""

mvn spring-boot:run
```

### 7. 文档更新

#### 7.1 README.md 更新
在现有README.md中添加GitHub登录的相关说明：

#### GitHub OAuth App 创建详细步骤

1. **访问GitHub开发者设置**
   - 登录GitHub账号
   - 点击右上角头像 → "Settings"
   - 左侧栏选择 "Developer settings" → "OAuth apps"

2. **创建新的OAuth应用**
   - 点击 "New OAuth App" 或 "Register a new application"
   - 填写应用信息：
     - **Application name**: `Google+GitHub OAuth2 Demo`
     - **Homepage URL**: `http://localhost:8081` （本地开发）或生产域名
     - **Application description**: `Spring Boot OAuth2 demo with Google and GitHub login`
     - **Authorization callback URL**: `https://api.u2511175.nyat.app:55139/oauth2/callback` （与Google保持一致）

3. **获取应用凭据**
   - 创建成功后，记录 **Client ID**
   - 点击 "Generate a new client secret" 生成 **Client Secret**
   - **重要**: Client Secret 只显示一次，请立即保存到安全位置

4. **配置环境变量**
   ```bash
   export GITHUB_CLIENT_ID="your-github-client-id"
   export GITHUB_CLIENT_SECRET="your-github-client-secret"
   ```

#### 配置说明
- 双OAuth2提供商配置
- 统一的回调URL处理
- 多提供商用户信息展示

#### 功能演示
- Google和GitHub登录选项
- 统一的测试页面体验
- 不同令牌类型的验证演示

#### 故障排除
- GitHub OAuth App配置问题
- 权限范围设置
- 回调URL不匹配错误

## 🛠️ 实施步骤

### 阶段1：依赖和配置（1天）
1. 检查并添加必要的依赖（RestTemplate, HttpHeaders, ParameterizedTypeReference等）
2. 验证Spring Boot OAuth2客户端版本兼容性
3. 更新 `application.yml` 添加GitHub提供商配置
4. 修改 `SecurityConfig.java` 支持多提供商
5. 更新 `start.sh` 添加GitHub环境变量

### 阶段2：用户服务增强（1天）
1. 实现自定义 `OAuth2UserService`
2. 添加GitHub用户信息处理逻辑
3. 更新控制器以支持多提供商

### 阶段3：前端界面更新（1天）
1. 修改登录页面添加GitHub选项
2. 更新测试页面支持多提供商显示
3. 添加GitHub令牌验证功能

### 阶段4：JWT验证增强（1天）
1. 扩展 `JwtValidationService` 支持GitHub令牌验证
2. 添加GitHub API调用逻辑
3. 更新验证端点

### 阶段5：测试和文档（2-3天）
1. 功能测试
2. 集成测试
3. 文档更新
4. 部署验证

## 🔒 安全考虑

### 1. 令牌存储
- Google：ID Token存储在HttpOnly Cookie中（JWT格式，可离线验证）
- GitHub：访问令牌为不透明字符串，**不应存储在客户端**，应安全存储在服务端会话中

### 1.1 GitHub访问令牌安全处理
GitHub OAuth2 返回不透明的访问令牌（不透明字符串），而不是像Google那样的JWT格式ID Token。

**安全存储策略：**
- ✅ **HttpOnly Cookie存储**：将访问令牌存储在HttpOnly Cookie中
- ✅ **防止XSS攻击**：`httpOnly=true` 使JavaScript无法访问
- ✅ **HTTPS传输保护**：`secure=true` 确保只在HTTPS下传输
- ✅ **自动过期**：设置1小时过期时间，与会话同步
- ✅ **自动清理**：登出时清除令牌Cookie

**实现代码：**
```java
// 在OAuth2成功处理器中存储访问令牌
@Override
public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                  Authentication authentication) throws IOException {
    // 处理GitHub用户
    if (authentication.getPrincipal() instanceof OAuth2User oauth2User) {
        OAuth2AuthorizedClient authorizedClient = authorizedClientService
            .loadAuthorizedClient("github", authentication.getName());

        if (authorizedClient != null && authorizedClient.getAccessToken() != null) {
            String accessToken = authorizedClient.getAccessToken().getTokenValue();

            // 安全存储在HttpOnly Cookie中
            Cookie accessTokenCookie = new Cookie("github_access_token", accessToken);
            accessTokenCookie.setHttpOnly(true);
            accessTokenCookie.setSecure(true);
            accessTokenCookie.setPath("/");
            accessTokenCookie.setMaxAge(3600); // 1小时过期

            response.addCookie(accessTokenCookie);
        }
    }
    response.sendRedirect("/test");
}
```

**验证时自动获取：**
```java
// 从Cookie自动获取令牌进行验证
String accessToken = null;
if (request.getCookies() != null) {
    for (Cookie cookie : request.getCookies()) {
        if ("github_access_token".equals(cookie.getName())) {
            accessToken = cookie.getValue();
            break;
        }
    }
}
```

**安全优势对比：**
- ✅ **服务端会话存储**：完全安全，但需要会话管理
- ✅ **HttpOnly Cookie存储**：安全便捷，自动处理过期
- ❌ **LocalStorage存储**：易受XSS攻击，不推荐
- ❌ **用户手动输入**：演示用途，不适合生产

### 2. 权限范围
- Google：`openid profile email`
- GitHub：`user:email read:user`（最小权限原则）

### 3. 重定向URI
- 统一使用自定义回调路径：`/oauth2/callback`
- 确保在两个OAuth应用中都正确配置
- **提供商区分**: 通过OAuth2 `state` 参数自动区分，不需要URL路径区分
- **安全机制**: `state` 参数同时提供CSRF保护和提供商识别功能

### 4. CSRF保护
- 保持现有的CSRF防护机制
- 确保所有POST请求都包含CSRF令牌

## 📋 检查清单

### 配置检查
- [ ] GitHub OAuth App已创建
- [ ] Client ID和Secret已配置
- [ ] 重定向URI已正确设置
- [ ] 环境变量已配置

### 代码检查
- [ ] `application.yml` 配置正确
- [ ] `SecurityConfig.java` 支持多提供商
- [ ] `OAuth2UserService` 正确处理GitHub用户
- [ ] 控制器支持多提供商逻辑
- [ ] 前端页面正确显示提供商信息

### 功能测试
- [ ] Google登录功能正常
- [ ] GitHub登录功能正常
- [ ] 用户信息正确显示
- [ ] 令牌验证功能正常
- [ ] 登出功能正常

### 文档更新
- [ ] README.md已更新
- [ ] 配置说明完整
- [ ] 故障排除指南完善

## 🚀 部署考虑

### 环境变量配置
生产环境需要配置以下环境变量：
- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`
- `GITHUB_CLIENT_ID`
- `GITHUB_CLIENT_SECRET`

### HTTPS要求
- 生产环境必须使用HTTPS
- Cookie设置 `secure=true`
- 重定向URI必须使用HTTPS

### 监控和日志
- OAuth2登录成功/失败日志
- 令牌验证日志
- 错误处理和监控

---

## 📋 实施总结

### 文档完整性检查
✅ **技术调研**: 已完成GitHub OAuth2技术调研和Google实现对比
✅ **架构设计**: 已设计多提供商OAuth2集成架构
✅ **代码实现**: 已提供完整的代码修改方案
✅ **前端界面**: 已更新界面以支持多提供商显示
✅ **安全考虑**: 已详细说明安全最佳实践和生产环境部署要求
✅ **文档更新**: 已提供README.md和故障排除指南

### 关键技术要点
1. **多提供商支持**: Spring Security原生支持，无需复杂配置
2. **统一回调URL**: 通过`state`参数智能区分提供商，既简化配置又增强安全
3. **用户信息处理**: GitHub邮箱需要额外API调用获取
4. **令牌验证差异**: Google使用JWT离线验证，GitHub使用API在线验证
5. **安全存储**: GitHub访问令牌必须服务端存储，防止泄露
6. **用户体验**: 统一的登录界面和测试页面

### 实施建议
- **优先级**: 先实现基础配置和安全功能，再优化用户体验
- **测试策略**: 先测试Google功能保持不变，再逐步添加GitHub功能
- **回滚计划**: 保留原有Google配置，确保可以快速回滚
- **监控要点**: 重点监控OAuth2登录成功率和令牌验证错误

### 风险评估
- **低风险**: 配置修改和UI更新
- **中风险**: 多提供商用户信息处理和令牌验证逻辑
- **高风险**: 生产环境访问令牌安全存储（已提供解决方案）

---

**规划制定日期**: 2026-01-06
**文档版本**: v1.0（五轮迭代检查完成）
**预计实施时间**: 7-10天
**风险等级**: 中等（主要为配置和集成风险）
**依赖条件**: 需要创建GitHub OAuth App并获取凭据
**文档状态**: ✅ 准备实施
