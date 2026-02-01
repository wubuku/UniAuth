# Spring Boot 资源服务器 JWT 保护实现指引

## 📋 概述

这份指引将帮助你在 Spring Boot 应用中实现与 Python 资源服务器**完全相同**的 JWT 验证机制,确保所有微服务(Python、Java 等)使用同一个认证/授权服务。

## 🎯 核心原理

**验证流程(与 Python 版本一致):**

1. 客户端从认证服务器获取 JWT Token
2. 客户端在 `Authorization: Bearer <token>` 头中发送请求
3. 资源服务器从认证服务器的 JWKS 端点获取公钥(缓存 1 小时)
4. 使用公钥验证 Token 签名(RS256 算法)
5. 验证 Token 的过期时间、audience、issuer 等声明
6. 验证通过后返回受保护资源

***

## 📦 步骤 1: 添加 Maven 依赖

在 `pom.xml` 中添加以下依赖:

```xml
<dependencies>
    <!-- Spring Boot Web -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    
    <!-- Spring Security OAuth2 Resource Server -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
    </dependency>
    
    <!-- Spring Security -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    
    <!-- JWT 解析支持 -->
    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-oauth2-jose</artifactId>
    </dependency>
</dependencies>
```


***

## ⚙️ 步骤 2: 配置 application.yml

创建或修改 `src/main/resources/application.yml`:

```yaml
server:
  port: 8082  # 选择你的端口

spring:
  application:
    name: java-resource-server
    
  security:
    oauth2:
      resourceserver:
        jwt:
          # JWKS 端点 URL (与 Python 版本相同)
          jwk-set-uri: https://api.u2511175.nyat.app:55139/oauth2/jwks
          
          # JWT 颁发者 (必须与 Python 版本一致)
          issuer-uri: https://auth.example.com
          
          # 受众声明 (必须与 Python 版本一致)
          audiences:
            - resource-server

# 日志配置(可选,用于调试)
logging:
  level:
    org.springframework.security: DEBUG
    org.springframework.security.oauth2: DEBUG
```

**⚠️ 关键配置说明:**

- `jwk-set-uri`: 认证服务器的 JWKS 端点,Spring Security 会自动从这里获取公钥并缓存
- `issuer-uri`: JWT 的 `iss` 声明,必须与 Token 中的一致
- `audiences`: JWT 的 `aud` 声明,必须与 Python 版本保持一致(`resource-server`)

***

## 🔐 步骤 3: 创建 Security 配置类

创建 `config/SecurityConfig.java`:

```java
package com.example.resourceserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("#{'${spring.security.oauth2.resourceserver.jwt.audiences}'.split(',')}")
    private List<String> audiences;

    /**
     * 配置 HTTP 安全规则
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF (因为是无状态 REST API)
            .csrf(csrf -> csrf.disable())
            
            // 配置 CORS
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            
            // 配置会话管理为无状态(与 Python 版本一致)
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            
            // 配置授权规则
            .authorizeHttpRequests(auth -> auth
                // 健康检查端点无需认证
                .requestMatchers("/health", "/actuator/health").permitAll()
                
                // 所有 /api/** 端点需要认证
                .requestMatchers("/api/**").authenticated()
                
                // 其他请求也需要认证
                .anyRequest().authenticated()
            )
            
            // 配置 OAuth2 资源服务器使用 JWT
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.decoder(jwtDecoder()))
            );

        return http.build();
    }

    /**
     * 配置 JWT 解码器(与 Python 版本的验证逻辑对应)
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        // 从 JWKS 端点创建解码器
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder
            .withJwkSetUri(jwkSetUri)
            .build();

        // 配置 Token 验证器链
        OAuth2TokenValidator<Jwt> audienceValidator = new AudienceValidator(audiences);
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuerUri);
        
        OAuth2TokenValidator<Jwt> withAudience = new DelegatingOAuth2TokenValidator<>(
            issuerValidator,
            audienceValidator
        );

        jwtDecoder.setJwtValidator(withAudience);
        
        return jwtDecoder;
    }

    /**
     * 配置 CORS(与 Python 版本一致)
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // 允许的源
        configuration.setAllowedOrigins(Arrays.asList(
            "http://localhost:5173",
            "http://localhost:8081",
            "https://api.u2511175.nyat.app:55139"
        ));
        
        // 允许的方法
        configuration.setAllowedMethods(Arrays.asList(
            "GET", "POST", "PUT", "DELETE", "OPTIONS"
        ));
        
        // 允许的头
        configuration.setAllowedHeaders(Arrays.asList(
            "Authorization", "Content-Type"
        ));
        
        // 允许携带凭证
        configuration.setAllowCredentials(true);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        
        return source;
    }
}
```


***

## 🎫 步骤 4: 创建自定义 Audience 验证器

创建 `config/AudienceValidator.java`:

```java
package com.example.resourceserver.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * 自定义 Audience 验证器
 * 对应 Python 版本中的 audience="resource-server" 验证
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final List<String> audiences;

    public AudienceValidator(List<String> audiences) {
        this.audiences = audiences;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> tokenAudiences = jwt.getAudience();
        
        // 检查 Token 的 audience 是否包含我们期望的值
        if (tokenAudiences != null && tokenAudiences.stream().anyMatch(audiences::contains)) {
            return OAuth2TokenValidatorResult.success();
        }
        
        OAuth2Error error = new OAuth2Error(
            "invalid_token",
            "The required audience is missing",
            null
        );
        
        return OAuth2TokenValidatorResult.failure(error);
    }
}
```


***

## 🎮 步骤 5: 创建受保护的 REST Controller

创建 `controller/ProtectedResourceController.java`:

```java
package com.example.resourceserver.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ProtectedResourceController {

    /**
     * 健康检查端点(无需认证)
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("service", "java-resource-server");
        response.put("auth_server", "https://api.u2511175.nyat.app:55139");
        return response;
    }

    /**
     * 受保护资源端点(需要有效的 JWT Token)
     * 对应 Python 版本的 /api/protected
     * 
     * @param jwt Spring Security 自动注入的已验证 JWT Token
     */
    @GetMapping("/protected")
    public Map<String, Object> protectedResource(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> response = new HashMap<>();
        
        response.put("message", "Access granted");
        response.put("timestamp", Instant.now().toString());
        
        // 用户信息(从 JWT claims 提取)
        Map<String, Object> user = new HashMap<>();
        user.put("id", jwt.getClaim("userId"));
        user.put("username", jwt.getSubject());  // sub claim
        user.put("email", jwt.getClaim("email"));
        user.put("authorities", jwt.getClaimAsStringList("authorities"));
        response.put("user", user);
        
        // 资源数据
        Map<String, Object> resource = new HashMap<>();
        resource.put("data", "This is protected data from Java resource server");
        resource.put("accessed_at", Instant.now().toString());
        
        // Token claims 信息
        Map<String, Object> tokenClaims = new HashMap<>();
        tokenClaims.put("aud", jwt.getAudience());
        tokenClaims.put("iss", jwt.getIssuer().toString());
        tokenClaims.put("iat", jwt.getIssuedAt());
        tokenClaims.put("exp", jwt.getExpiresAt());
        resource.put("token_claims", tokenClaims);
        
        response.put("resource", resource);
        
        return response;
    }

    /**
     * 受保护资源信息端点
     * 对应 Python 版本的 /api/protected/info
     */
    @GetMapping("/protected/info")
    public Map<String, Object> protectedInfo(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> response = new HashMap<>();
        
        response.put("info", "This resource is protected by Spring Boot OAuth2 server");
        response.put("current_user", jwt.getSubject());
        response.put("allowed_resources", List.of("/api/protected", "/api/protected/info"));
        response.put("auth_server", "https://api.u2511175.nyat.app:55139");
        
        return response;
    }
}
```


***

## 🚨 步骤 6: 创建全局异常处理器(可选但推荐)

创建 `exception/GlobalExceptionHandler.java`:

```java
package com.example.resourceserver.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 * 对应 Python 版本中的错误处理
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理认证异常(401)
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleAuthenticationException(
            AuthenticationException ex) {
        Map<String, String> error = new HashMap<>();
        error.put("error", "Unauthorized");
        error.put("details", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    /**
     * 处理授权异常(403)
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDeniedException(
            AccessDeniedException ex) {
        Map<String, String> error = new HashMap<>();
        error.put("error", "Forbidden");
        error.put("details", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    /**
     * 处理通用异常(500)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneralException(Exception ex) {
        Map<String, String> error = new HashMap<>();
        error.put("error", "Internal server error");
        error.put("details", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
```


***

## 📁 步骤 7: 项目结构总览

```
src/main/java/com/example/resourceserver/
├── ResourceServerApplication.java      # 主启动类
├── config/
│   ├── SecurityConfig.java            # Security 配置(步骤 3)
│   └── AudienceValidator.java         # Audience 验证器(步骤 4)
├── controller/
│   └── ProtectedResourceController.java # REST 控制器(步骤 5)
└── exception/
    └── GlobalExceptionHandler.java    # 异常处理器(步骤 6)

src/main/resources/
└── application.yml                    # 配置文件(步骤 2)
```


***

## 🏃 步骤 8: 启动应用

创建主启动类 `ResourceServerApplication.java`:

```java
package com.example.resourceserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ResourceServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ResourceServerApplication.class, args);
    }
}
```

启动应用:

```bash
mvn spring-boot:run
```

或使用 IDE 运行 `ResourceServerApplication` 类。

***

## 🧪 步骤 9: 测试验证

### 1️⃣ 测试健康检查(无需认证)

```bash
curl http://localhost:8082/health
```

**预期响应:**

```json
{
  "status": "ok",
  "service": "java-resource-server",
  "auth_server": "https://api.u2511175.nyat.app:55139"
}
```


### 2️⃣ 测试受保护端点(需要 Token)

**获取 Token:**

```bash
TOKEN=$(curl -s -X POST "https://api.u2511175.nyat.app:55139/api/auth/login?username=testboth&password=password123" \
  -H "Content-Type: application/json" | jq -r '.accessToken')

echo $TOKEN
```

**访问受保护资源:**

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8082/api/protected
```

**预期响应:**

```json
{
  "message": "Access granted",
  "timestamp": "2026-02-01T11:47:00.123Z",
  "user": {
    "id": "user-id",
    "username": "testboth",
    "email": "test@example.com",
    "authorities": ["ROLE_USER"]
  },
  "resource": {
    "data": "This is protected data from Java resource server",
    "accessed_at": "2026-02-01T11:47:00.123Z",
    "token_claims": {
      "aud": ["resource-server"],
      "iss": "https://auth.example.com",
      "iat": "...",
      "exp": "..."
    }
  }
}
```


### 3️⃣ 测试无 Token 访问(应返回 401)

```bash
curl -v http://localhost:8082/api/protected
```

**预期响应:** HTTP 401 Unauthorized

***

## 🔧 常见问题排查

### ❌ 问题 1: Token 验证失败 - Issuer 不匹配

**错误信息:**

```
The iss claim is not valid
```

**解决方案:**
检查 `application.yml` 中的 `issuer-uri` 是否与认证服务器颁发的 Token 中的 `iss` 声明完全一致。

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com  # 必须与 Token 中的 iss 一致
```


### ❌ 问题 2: Token 验证失败 - Audience 不匹配

**错误信息:**

```
The required audience is missing
```

**解决方案:**
确保 `application.yml` 中的 `audiences` 配置与 Token 中的 `aud` 声明匹配:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          audiences:
            - resource-server  # 必须与 Token 中的 aud 一致
```


### ❌ 问题 3: 无法连接到 JWKS 端点

**错误信息:**

```
Unable to fetch JWK Set from...
```

**解决方案:**

1. **检查网络连接:** 确保应用可以访问认证服务器
2. **SSL 证书问题:** 如果是自签名证书,添加以下配置:
```java
// 仅开发环境使用,生产环境不推荐
@Bean
public RestTemplate restTemplate() throws Exception {
    TrustStrategy acceptingTrustStrategy = (cert, authType) -> true;
    SSLContext sslContext = SSLContexts.custom()
        .loadTrustMaterial(null, acceptingTrustStrategy)
        .build();
    SSLConnectionSocketFactory csf = new SSLConnectionSocketFactory(sslContext, 
        NoopHostnameVerifier.INSTANCE);
    
    CloseableHttpClient httpClient = HttpClients.custom()
        .setSSLSocketFactory(csf)
        .build();
    
    HttpComponentsClientHttpRequestFactory requestFactory = 
        new HttpComponentsClientHttpRequestFactory();
    requestFactory.setHttpClient(httpClient);
    
    return new RestTemplate(requestFactory);
}
```


### ❌ 问题 4: CORS 错误

**错误信息:**

```
Access to XMLHttpRequest has been blocked by CORS policy
```

**解决方案:**
确保 `SecurityConfig` 中的 CORS 配置包含前端应用的域名:

```java
configuration.setAllowedOrigins(Arrays.asList(
    "http://localhost:5173",  // 前端开发服务器
    "http://localhost:8081",
    "https://api.u2511175.nyat.app:55139"
));
```


***

## 📊 关键配置对照表

| 配置项 | Python 版本 | Spring Boot 版本 | 说明 |
| :-- | :-- | :-- | :-- |
| **JWKS URL** | `JWKS_URL` 变量 | `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | 公钥端点 |
| **Issuer** | `jwt.decode()` 的 `issuer` 参数 | `spring.security.oauth2.resourceserver.jwt.issuer-uri` | Token 颁发者 |
| **Audience** | `jwt.decode()` 的 `audience` 参数 | `AudienceValidator` | Token 受众 |
| **算法** | `alg = header.get('alg', 'RS256')` | 自动从 JWKS 获取 | RS256 |
| **缓存时长** | `CACHE_DURATION = 3600` | Spring 默认缓存 | 1 小时 |
| **CORS** | `CORS(app, resources={...})` | `corsConfigurationSource()` | 跨域配置 |


***

## 🎯 核心要点总结

1. **依赖添加:** 使用 `spring-boot-starter-oauth2-resource-server`
2. **JWKS 配置:** 指向统一认证服务器的 `/oauth2/jwks` 端点
3. **Issuer/Audience 验证:** 必须与 Python 版本保持一致
4. **无状态会话:** `SessionCreationPolicy.STATELESS`
5. **CORS 配置:** 允许前端跨域访问
6. **自动验证:** Spring Security 自动处理 Token 验证,开发者只需在 Controller 中使用 `@AuthenticationPrincipal Jwt jwt`

***

## 🚀 生产环境建议

### 1. 使用环境变量

将敏感配置移到环境变量:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${JWT_JWK_SET_URI:https://api.u2511175.nyat.app:55139/oauth2/jwks}
          issuer-uri: ${JWT_ISSUER_URI:https://auth.example.com}
```


### 2. 启用 HTTPS

生产环境必须使用 HTTPS:

```yaml
server:
  port: 8443
  ssl:
    enabled: true
    key-store: classpath:keystore.p12
    key-store-password: ${KEYSTORE_PASSWORD}
    key-store-type: PKCS12
```


### 3. 添加监控端点

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```


### 4. 添加日志

```yaml
logging:
  level:
    com.example.resourceserver: INFO
    org.springframework.security: WARN
  file:
    name: logs/resource-server.log
```


***

## ✅ 验证清单

在部署之前,确保完成以下检查:

- [ ] Maven 依赖正确添加
- [ ] `application.yml` 配置正确(JWKS URL、Issuer、Audience)
- [ ] `SecurityConfig` 配置完整
- [ ] `AudienceValidator` 已实现
- [ ] REST Controller 端点已创建
- [ ] CORS 配置包含所有需要的域名
- [ ] 健康检查端点测试通过
- [ ] 使用有效 Token 测试受保护端点成功
- [ ] 无 Token 访问返回 401
- [ ] 过期 Token 返回 401
- [ ] 日志输出正常

***

## 📚 额外资源

- [Spring Security OAuth2 Resource Server 官方文档](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html)
- [JWT.io - JWT 调试工具](https://jwt.io/)
- [Spring Security Architecture](https://spring.io/guides/topicals/spring-security-architecture)

***

现在你的 Java 后端开发人员可以按照这份指引,快速实现与 Python 资源服务器**完全一致**的 JWT 保护机制! 🎉
<span style="display:none">[^1][^2][^3]</span>

<div align="center">⁂</div>

