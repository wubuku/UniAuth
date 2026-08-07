# 🚀 X（Twitter）API v2 最新迁移指南

> 状态：Reference。本文保留 X API v2 迁移背景；“最新”仅指成文时点，
> 使用配置片段前必须核对当前 `application.yml` 和
> [配置基线](../CONFIGURATION.md)。

**版本:** 1.0.0  
**日期:** 2026年1月22日  
**仅针对**: X API v2 迁移（从 Twitter API v1.1）

---

## 核心变更（2023 年 - 至今）

| 方面 | v1.1 | v2（当前） |
|------|------|----------|
| **域名** | `api.twitter.com` | `api.x.com` ✅ |
| **登录域名** | `twitter.com` | `x.com` ✅ |
| **状态** | ❌ 已弃用 | ✅ 官方支持 |
| **用户信息端点** | `/1.1/account/verify_credentials.json` | `/2/users/me` |
| **认证** | OAuth 1.0a | OAuth 2.0 with PKCE |
| **响应格式** | 直接对象 | 包装在 `data` 字段 |

---

## 最新配置（application.yml）

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          x:  # ✅ 提供者名改为 'x'
            provider: x
            client-id: ${X_CLIENT_ID}          # 从 X Developer Portal 获取
            client-secret: ${X_CLIENT_SECRET}
            client-authentication-method: basic
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope:
              - tweet.read
              - users.read
              - follows.read
              - like.read
              - offline.access
            
        provider:
          x:
            # ✅ 所有端点使用 x.com 域名
            authorization-uri: https://x.com/i/oauth2/authorize
            token-uri: https://api.x.com/2/oauth2/token
            user-info-uri: https://api.x.com/2/users/me
            user-name-attribute: data.username
            jwk-set-uri: https://x.com/i/oauth2/jwks
```

---

## SecurityConfig 更新

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.oauth2Login()
            .userInfoEndpoint()
                .oauth2UserService(oauth2UserService())
                .and()
            .successHandler(oAuth2SuccessHandler())
            .and()
            .build();
        
        return http.build();
    }

    // ✅ 支持 X API v2
    private OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2UserService() {
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        
        return userRequest -> {
            OAuth2User oauth2User = delegate.loadUser(userRequest);
            String registrationId = userRequest.getClientRegistration().getRegistrationId();
            
            if ("x".equals(registrationId)) {  // ✅ 检查 'x' 而不是 'twitter'
                return loadXUser(userRequest, oauth2User);
            }
            
            return oauth2User;
        };
    }

    // ✅ X API v2 用户加载
    private OAuth2User loadXUser(OAuth2UserRequest userRequest, OAuth2User oauth2User) {
        try {
            String accessToken = userRequest.getAccessToken().getTokenValue();
            RestTemplate restTemplate = new RestTemplate();
            
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.set("User-Agent", "Your-App/1.0");
            
            // ✅ X API v2 端点
            String url = "https://api.x.com/2/users/me?" +
                "user.fields=username,name,profile_image_url,description,verified";
            
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class
            );
            
            if (response.getStatusCode() == HttpStatus.OK) {
                JsonObject json = JsonParser.parseString(response.getBody()).getAsJsonObject();
                JsonObject data = json.getAsJsonObject("data");
                
                Map<String, Object> attributes = new HashMap<>();
                attributes.put("id", data.get("id").getAsString());
                attributes.put("sub", data.get("id").getAsString());
                attributes.put("name", data.get("name").getAsString());
                attributes.put("username", data.get("username").getAsString());
                if (data.has("profile_image_url")) {
                    attributes.put("picture", data.get("profile_image_url").getAsString());
                }
                
                return new DefaultOAuth2User(
                    Collections.singleton(new SimpleGrantedAuthority("ROLE_USER")),
                    attributes,
                    "sub"
                );
            }
        } catch (Exception e) {
            log.error("Failed to load X user", e);
        }
        
        return oauth2User;
    }
}
```

---

## X API v2 服务

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class XTokenService {
    
    private final RestTemplate restTemplate;
    
    @Value("${x.client-id}")
    private String xClientId;
    
    @Value("${x.client-secret}")
    private String xClientSecret;

    /**
     * ✅ 获取用户信息（X API v2）
     */
    public XUserInfo getUserInfo(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.set("User-Agent", "Your-App/1.0");
            
            // ✅ 使用 api.x.com
            String url = "https://api.x.com/2/users/me?" +
                "user.fields=username,name,profile_image_url,description,created_at,verified";
            
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class
            );
            
            if (response.getStatusCode() == HttpStatus.OK) {
                return parseResponse(response.getBody());
            }
            
        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("X token 已过期");
            throw new RuntimeException("Token 已过期");
        } catch (Exception e) {
            log.error("Failed to get X user info", e);
            throw new RuntimeException("获取用户信息失败");
        }
        
        throw new RuntimeException("获取用户信息失败");
    }

    /**
     * ✅ 获取用户推文（X API v2）
     */
    public List<XTweet> getUserTweets(String accessToken, String userId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.set("User-Agent", "Your-App/1.0");
            
            // ✅ 使用 api.x.com
            String url = "https://api.x.com/2/users/" + userId + "/tweets?" +
                "tweet.fields=created_at,author_id,public_metrics&max_results=10";
            
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class
            );
            
            if (response.getStatusCode() == HttpStatus.OK) {
                return parseTweets(response.getBody());
            }
        } catch (Exception e) {
            log.error("Failed to get tweets", e);
        }
        
        return Collections.emptyList();
    }

    private XUserInfo parseResponse(String response) {
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        JsonObject data = json.getAsJsonObject("data");
        
        return XUserInfo.builder()
            .id(data.get("id").getAsString())
            .username(data.get("username").getAsString())
            .name(data.get("name").getAsString())
            .profileImageUrl(
                data.has("profile_image_url") ? 
                data.get("profile_image_url").getAsString() : null
            )
            .description(
                data.has("description") ? 
                data.get("description").getAsString() : null
            )
            .verified(data.has("verified") && data.get("verified").getAsBoolean())
            .build();
    }

    private List<XTweet> parseTweets(String response) {
        List<XTweet> tweets = new ArrayList<>();
        try {
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            if (json.has("data")) {
                for (JsonElement element : json.getAsJsonArray("data")) {
                    JsonObject tweet = element.getAsJsonObject();
                    tweets.add(XTweet.builder()
                        .id(tweet.get("id").getAsString())
                        .text(tweet.get("text").getAsString())
                        .build());
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse tweets", e);
        }
        return tweets;
    }
}

// DTO
@Data @Builder
public class XUserInfo {
    private String id;
    private String username;
    private String name;
    private String profileImageUrl;
    private String description;
    private boolean verified;
}

@Data @Builder
public class XTweet {
    private String id;
    private String text;
}
```

---

## OAuth2SuccessHandler 处理 X 登录

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final UserService userService;
    private final XTokenService xTokenService;
    private final TokenGenerator tokenGenerator;

    @Override
    public void onAuthenticationSuccess(
        HttpServletRequest request,
        HttpServletResponse response,
        Authentication authentication) throws IOException {

        try {
            String registrationId = extractRegistrationId(request);
            
            if ("x".equals(registrationId)) {  // ✅ 检查 'x'
                handleXLogin(authentication, response);
            }
        } catch (Exception e) {
            log.error("Authentication failed", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void handleXLogin(Authentication auth, HttpServletResponse response) 
        throws IOException {
        
        String accessToken = ((OAuth2AuthenticationToken) auth).getCredentials().toString();
        
        // ✅ 通过 X API v2 获取用户
        XUserInfo xUser = xTokenService.getUserInfo(accessToken);
        
        // 创建本地用户
        UserEntity user = userService.getOrCreateXUser(
            xUser.getId(),
            xUser.getUsername(),
            xUser.getName(),
            xUser.getProfileImageUrl()
        );
        
        // 返回成功
        response.setContentType("application/json");
        response.getWriter().write(new ObjectMapper().writeValueAsString(
            Map.of(
                "user", user,
                "provider", "x",
                "token", tokenGenerator.generateAccessToken(user)
            )
        ));
    }

    private String extractRegistrationId(HttpServletRequest request) {
        String referer = request.getHeader("referer");
        if (referer != null && referer.contains("x.com")) {
            return "x";
        }
        return "unknown";
    }
}
```

---

## Controller 端点

```java
@RestController
@RequestMapping("/api/x")
@RequiredArgsConstructor
@Slf4j
public class XController {
    
    private final XTokenService xTokenService;

    @GetMapping("/user")
    public ResponseEntity<?> getUserInfo(@RequestHeader("Authorization") String bearerToken) {
        try {
            String accessToken = bearerToken.replace("Bearer ", "");
            XUserInfo userInfo = xTokenService.getUserInfo(accessToken);
            return ResponseEntity.ok(userInfo);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Failed: " + e.getMessage());
        }
    }

    @GetMapping("/tweets")
    public ResponseEntity<?> getTweets(@RequestHeader("Authorization") String bearerToken) {
        try {
            String accessToken = bearerToken.replace("Bearer ", "");
            XUserInfo userInfo = xTokenService.getUserInfo(accessToken);
            List<XTweet> tweets = xTokenService.getUserTweets(accessToken, userInfo.getId());
            
            return ResponseEntity.ok(Map.of(
                "user", userInfo,
                "tweets", tweets
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Failed: " + e.getMessage());
        }
    }
}
```

---

## 测试命令

```bash
# ✅ 获取用户信息（使用 api.x.com）
curl -X GET "https://api.x.com/2/users/me?user.fields=username,name" \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "User-Agent: Your-App/1.0"

# ✅ 获取推文
curl -X GET "https://api.x.com/2/users/{user_id}/tweets?max_results=10" \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "User-Agent: Your-App/1.0"

# 预期响应（200 OK）
{
  "data": {
    "id": "123456789",
    "name": "John Doe",
    "username": "johndoe",
    "verified": false
  }
}
```

---

## 快速检查清单

```
✅ API 端点：api.x.com（不是 api.twitter.com）
✅ 登录域名：x.com/i/oauth2/authorize（不是 twitter.com）
✅ Provider 名：'x'（不是 'twitter'）
✅ 响应格式：{ "data": { ... } }（不是直接对象）
✅ User-Agent header：必须添加
✅ Bearer token 格式：Authorization: Bearer <token>
```

---

## 常见错误和解决

| 错误 | 原因 | 解决 |
|------|------|------|
| 400 Bad Request | 使用了旧域名 `api.twitter.com` | 改用 `api.x.com` |
| 401 Unauthorized | Token 无效 | 确保 scope 正确，重新授权 |
| 无法解析响应 | 预期直接对象但获得 `{ "data": {...} }` | 从 `data` 字段提取 |
| Cannot find provider | SecurityConfig 中注册名错误 | 确保是 'x' 而不是 'twitter' |

---

## 环境变量

```bash
export X_CLIENT_ID=your-client-id
export X_CLIENT_SECRET=your-client-secret
```

---

**完成！你的应用现在支持最新的 X API v2。** ✅
