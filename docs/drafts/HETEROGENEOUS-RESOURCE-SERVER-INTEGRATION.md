# 异构资源服务器集成规划文档

> 状态：Historical。本文是集成规划，不是当前运行手册。
> Python 示例的当前限制见 [配置基线](../CONFIGURATION.md)。

## 📋 文档概述

本文档详细分析了如何将异构资源服务器（如Python开发的RESTful API）集成到当前的OAuth2认证授权体系中。通过本规划，我们将确保异构资源服务器能够安全地验证并接受来自当前认证服务器颁发的Token，从而保护其资源。

## 🎯 目标

- 分析当前项目的认证授权机制
- 设计异构资源服务器的集成方案
- 提出必要的改进措施
- 提供详细的实现步骤和验证方法
- 确保跨域场景下的安全性和可靠性

## 🔍 现状分析

### 当前项目结构

- **认证服务器**：`./`
  - Spring Boot应用，提供认证和授权服务
  - 支持本地登录和Google/GitHub/Twitter SSO
  - 使用JWT Token进行认证

- **客户端**：`./frontend`
  - React SPA应用
  - 部署在与认证服务器相同的域下
  - 负责用户登录和获取Token

### 当前认证机制

1. **Token生成**：
   - 使用`JwtTokenService`生成RSA-SHA256签名的JWT
   - 使用RSA密钥对进行签名和验证
   - Token包含用户ID、邮箱、权限等信息

2. **Token存储**：
   - 存储在HttpOnly Cookie中
   - 支持Access Token和Refresh Token

3. **Token验证**：
   - 当前仅在后端内部验证
   - 没有提供公共的Token验证接口

### 当前项目的局限性

1. **无公共Token验证API**：
   - 异构资源服务器无法验证Token的有效性

2. **无密钥分发机制**：
   - 异构资源服务器无法获取JWT签名密钥

3. **无标准化验证接口**：
   - 缺乏符合OAuth2标准的Token验证端点

4. **跨域配置不足**：
   - 未针对跨域资源服务器进行CORS配置

## 🚀 解决方案设计

### 总体架构

```
┌───────────────┐       ┌────────────────────┐       ┌───────────────────┐
│               │       │                    │       │                   │
│   Web Client  │──────▶│  Auth Server       │◀──────│  Resource Server  │
│   (Client)    │       │  (Authorization    │       │  (Heterogeneous)  │
│               │       │   Server)          │       │                   │
└───────────────┘       └────────────────────┘       └───────────────────┘
        │                         │                         │
        │ 1. Login & Get Token    │ 2. Validate Token       │ 3. Access Protected
        │────────────────────────▶│◀────────────────────────│    Resource
        │                         │                         │
        │ 4. Return Token         │ 5. Return Validation    │ 6. Return Resource
        │◀────────────────────────│────────────────────────▶│    Data
        │                         │         Result          │
        │ 7. Access Resource      │                         │
        │    with Token           │                         │
        │──────────────────────────────────────────────────▶│
```

**流程图说明**（中文）：
1. **登录获取Token**：Web客户端向认证服务器发起登录请求，获取Access Token和Refresh Token
2. **验证Token有效性**：资源服务器向认证服务器验证Token的有效性
3. **访问受保护资源**：Web客户端使用获取的Token访问资源服务器的受保护资源
4. **返回Token**：认证服务器返回Token给Web客户端
5. **返回验证结果**：认证服务器返回Token验证结果给资源服务器
6. **返回资源数据**：资源服务器返回受保护的资源数据给Web客户端
7. **使用Token访问资源**：Web客户端直接使用Token访问资源服务器

### 核心改进措施

#### 1. 提供公共Token验证API

- **端点**：`POST /oauth2/introspect`（符合OAuth2标准的Token内省端点）
- **参数**：`token` (JWT Token)
- **返回**：
  ```json
  {
    "active": true,
    "sub": "user",
    "userId": "1",
    "email": "user@example.com",
    "authorities": ["ROLE_USER"],
    "exp": 1678900000
  }
  ```

#### 2. 实现JWKS端点

- **端点**：`GET /oauth2/jwks`
- **返回**：符合RFC 7517标准的JWKS（JSON Web Key Set）
- **作用**：允许资源服务器获取公钥进行Token验证

#### 3. 配置跨域资源共享（CORS）

- **配置CORS策略**：允许来自资源服务器域名的请求
- **支持的方法**：GET, POST, OPTIONS
- **支持的头部**：Authorization, Content-Type
- **允许凭证**：true

#### 4. 改进Token生成

- **统一Token格式**：确保Token符合OAuth2标准
- **包含标准声明**：
  - `iss`：Token颁发者（认证服务器URL）
  - `sub`：主题（用户ID）
  - `aud`：受众（资源服务器标识符）
  - `exp`：过期时间
  - `iat`：颁发时间

### 异构资源服务器集成步骤

以Python Flask应用为例：

#### 1. 安装依赖

```bash
pip install flask pyjwt requests
```

#### 2. 实现Token验证

```python
import jwt
import requests
import json
from flask import Flask, request, jsonify

app = Flask(__name__)

# 认证服务器配置
AUTH_SERVER_URL = "https://auth.example.com"
JWKS_URL = f"{AUTH_SERVER_URL}/oauth2/jwks"

# 缓存JWKS以提高性能
jwks_cache = None

# 获取JWKS
def get_jwks():
    global jwks_cache
    if not jwks_cache:
        response = requests.get(JWKS_URL)
        jwks_cache = response.json()
    return jwks_cache

# 验证Token
def validate_token(token):
    try:
        # 从Token中提取头部信息
        header = jwt.get_unverified_header(token)
        kid = header['kid']
        
        # 从JWKS中获取对应的密钥
        jwks = get_jwks()
        key = None
        for jwk in jwks['keys']:
            if jwk['kid'] == kid:
                key = jwt.algorithms.RSAAlgorithm.from_jwk(json.dumps(jwk))
                break
        
        if not key:
            return False, "Key not found"
        
        # 验证Token
        decoded = jwt.decode(
            token,
            key,
            algorithms=[header['alg']],
            audience="resource-server",
            issuer=AUTH_SERVER_URL
        )
        
        return True, decoded
    except Exception as e:
        return False, str(e)

# 受保护的API端点
@app.route('/api/protected', methods=['GET'])
def protected_resource():
    # 从Authorization头获取Token
    auth_header = request.headers.get('Authorization')
    if not auth_header:
        return jsonify({"error": "Authorization header required"}), 401
    
    token = auth_header.split(' ')[1] if auth_header.startswith('Bearer ') else auth_header
    
    # 验证Token
    valid, result = validate_token(token)
    if not valid:
        return jsonify({"error": "Invalid token", "details": result}), 401
    
    # Token有效，返回受保护的资源
    return jsonify({
        "message": "Access granted",
        "user": {
            "id": result.get('userId'),
            "username": result.get('sub'),
            "email": result.get('email')
        },
        "resource": "Protected data"
    })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
```

#### 3. 配置CORS

```python
from flask_cors import CORS

# 配置CORS
CORS(app, resources={
    r"/api/*": {
        "origins": ["https://client.example.com"],  # 客户端域名
        "methods": ["GET", "POST", "OPTIONS"],
        "allow_headers": ["Authorization", "Content-Type"],
        "supports_credentials": True
    }
})
```

## 📝 跨域场景下的Token存储方案

### 问题分析

当资源服务器部署在与客户端不同的域名下时，使用httpOnly Cookie存储Access Token会面临以下限制：

1. **同源策略限制**：浏览器的同源策略会阻止跨域请求携带Cookie
2. **Cookie域名限制**：Cookie只能被创建它的域名访问，无法被不同域名的资源服务器读取
3. **跨域请求配置复杂**：即使设置了`withCredentials: true`，也需要资源服务器设置对应的CORS头

### 解决方案

在跨域场景下，推荐使用以下Token存储方案：

#### 1. Access Token存储

- **使用localStorage/sessionStorage**：将Access Token存储在前端存储中
- **在请求头中添加Token**：在每次请求资源服务器时，手动在Authorization头中添加Token

```javascript
// 存储Token
localStorage.setItem('accessToken', accessToken);

// 发送请求时添加Token
fetch('https://resource.example.com/api/protected', {
  headers: {
    'Authorization': `Bearer ${localStorage.getItem('accessToken')}`
  }
});
```

#### 2. Refresh Token存储

- **仍然使用httpOnly Cookie**：Refresh Token可以继续存储在httpOnly Cookie中
- **由认证服务器管理**：Refresh Token只用于与认证服务器通信，不需要发送到资源服务器

### 安全性考虑
1. **XSS防护**：
   - localStorage/sessionStorage容易受到XSS攻击
   - 建议：
     - 使用Content Security Policy (CSP)限制脚本执行  
     - 对Token设置较短的过期时间
     - 实现Token轮换机制

2. **CSRF防护**：
   - httpOnly Cookie需要CSRF保护
   - 建议：
     - 对认证操作使用CSRF token
     - 验证Origin/Referer头

3. **最佳实践**：
   - 使用httpOnly Cookie存储refresh token
   - 使用localStorage存储access token
   - 设置较短的access token过期时间
   - 实现静默刷新机制

注：CSP 是一种浏览器安全机制，通过设置 HTTP 响应头 `Content-Security-Policy`，可白名单化允许加载脚本、样式、图片等资源的域名与内联方式，从而阻止恶意脚本注入。  

在 Spring Boot 中可在 `WebSecurityConfigurerAdapter` 里统一添加：
```java
http.headers().contentSecurityPolicy(
    "default-src 'self'; " +
    "script-src 'self' 'nonce-{随机值}' https://cdn.jsdelivr.net; " +
    "style-src 'self' 'unsafe-inline'; " +
    "img-src 'self' data:; " +
    "connect-src 'self' https://resource.example.com"
);
```

前端 HTML 模板中给可信脚本加 `nonce`：
```html
<script nonce="${nonce}">/* 业务代码 */</script>
```

这样即便攻击者注入 `<script>alert(document.cookie)</script>`，也会被浏览器拦截，无法读取 localStorage 中的 Token。

## 🔧 具体实现计划

### 第一阶段：改进认证服务器

#### 1. 添加OAuth2 Token管理控制器

- **文件**：`src/main/java/com/example/oauth2demo/controller/OAuth2TokenController.java`
- **功能**：提供JWKS端点和Token验证端点
- **实现**：
  ```java
  package com.example.oauth2demo.controller;
  
  import com.nimbusds.jose.JWKSet;
  import com.nimbusds.jose.jwk.RSAKey;
  import com.example.oauth2demo.service.JwtTokenService;
  import com.example.oauth2demo.repository.TokenBlacklistRepository;
  import io.jsonwebtoken.Claims;
  import io.jsonwebtoken.Jwts;
  import lombok.RequiredArgsConstructor;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.http.ResponseEntity;
  import org.springframework.web.bind.annotation.*;
  
  import java.security.PublicKey;
  import java.security.interfaces.RSAPublicKey;
  import java.util.Map;
  import java.util.UUID;
  
  /**
   * OAuth2 Token管理控制器
   * 提供JWKS端点和Token验证端点
   */
  @RestController
  @RequestMapping("/oauth2")
  @RequiredArgsConstructor
  @Slf4j
  public class OAuth2TokenController {
      
      private final JwtTokenService jwtTokenService;
      private final TokenBlacklistRepository tokenBlacklistRepository;
      
      /**
       * JWKS端点
       * 提供符合RFC 7517标准的JWK Set
       */
      @GetMapping("/jwks")
      public ResponseEntity<JWKSet> jwks() throws Exception {
          log.debug("JWKS endpoint called");
          
          // 使用JwtTokenService的公钥生成JWK Set
          PublicKey publicKey = jwtTokenService.getPublicKey();
          RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) publicKey)
              .keyID(UUID.randomUUID().toString())
              .build();
          
          JWKSet jwkSet = new JWKSet(rsaKey);
          return ResponseEntity.ok(jwkSet);
      }
      
      /**
       * Token内省端点
       * 验证Token有效性并返回Token信息
       */
      @PostMapping("/introspect")
      public ResponseEntity<?> introspect(@RequestParam String token) {
          log.debug("Token introspection request received");
          try {
              // 验证Token
              Claims claims = Jwts.parserBuilder()
                  .setSigningKey(jwtTokenService.getPublicKey())
                  .build()
                  .parseClaimsJws(token)
                  .getBody();
              
              // 检查Token是否在黑名单中
              String jti = claims.get("jti", String.class);
              if (jti != null && tokenBlacklistRepository.existsByJti(jti)) {
                  log.warn("Token is in blacklist: {}", jti);
                  return ResponseEntity.ok(Map.of(
                      "active", false,
                      "error", "Token revoked"
                  ));
              }
              
              // 返回Token信息
              log.debug("Token introspection successful for user: {}", claims.getSubject());
              return ResponseEntity.ok(Map.of(
                  "active", true,
                  "sub", claims.getSubject(),
                  "userId", claims.get("userId"),
                  "email", claims.get("email"),
                  "authorities", claims.get("authorities"),
                  "exp", claims.getExpiration().getTime() / 1000
              ));
          } catch (Exception e) {
              log.warn("Token introspection failed: {}", e.getMessage());
              return ResponseEntity.ok(Map.of(
                  "active", false,
                  "error", e.getMessage()
              ));
          }
      }
  }
  ```

#### 2. 配置Authorization Server

- **文件**：`src/main/java/com/example/oauth2demo/config/AuthorizationServerConfig.java`
- **功能**：配置OAuth2客户端和Token设置
- **实现**：移除JWKSource bean，因为我们现在使用JwtTokenService来管理密钥
  ```java
  package com.example.oauth2demo.config;
  
  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Configuration;
  import org.springframework.core.annotation.Order;
  import org.springframework.security.oauth2.core.AuthorizationGrantType;
  import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
  import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
  import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
  import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
  import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
  import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
  import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
  import org.springframework.security.web.SecurityFilterChain;
  
  import java.time.Duration;
  import java.util.UUID;
  
  /**
   * Spring Authorization Server 配置
   * 负责配置OAuth2客户端和认证流程
   */
  @Configuration
  public class AuthorizationServerConfig {

      /**
       * Authorization Server 安全过滤器链
       */
      @Bean
      @Order(1)
      public SecurityFilterChain authorizationServerSecurityFilterChain(org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
          OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
          return http.build();
      }

      /**
       * OAuth2 客户端配置
       * 在内存中配置客户端，用于本地认证
       */
      @Bean
      public RegisteredClientRepository registeredClientRepository() {
          RegisteredClient registeredClient = RegisteredClient.withId(UUID.randomUUID().toString())
              .clientId("auth-client")
              .clientSecret("{noop}auth-secret")  // 开发环境，生产环境应加密
              .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
              .authorizationGrantType(AuthorizationGrantType.PASSWORD)  // 本地登录
              .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)  // Token 刷新
              .redirectUri("http://localhost:5173/callback")  // 前端回调地址
              .scope("openid")
              .scope("profile")
              .scope("email")
              .tokenSettings(TokenSettings.builder()
                  .accessTokenTimeToLive(Duration.ofHours(1))  // accessToken 1小时
                  .refreshTokenTimeToLive(Duration.ofDays(7))  // refreshToken 7天
                  .build())
              .clientSettings(ClientSettings.builder()
                  .requireProofKey(false)  // 不需要PKCE
                  .build())
              .build();

          return new InMemoryRegisteredClientRepository(registeredClient);
      }
  }
  ```

#### 3. 改进Token生成和密钥管理

- **文件**：`src/main/java/com/example/oauth2demo/service/JwtTokenService.java`
- **功能**：使用RSA密钥对生成Token，确保与JWKS端点使用相同的密钥
- **实现**：
  ```java
  package com.example.oauth2demo.service;
  
  import io.jsonwebtoken.Jwts;
  import io.jsonwebtoken.SignatureAlgorithm;
  import org.springframework.stereotype.Service;
  
  import java.security.KeyPair;
  import java.security.KeyPairGenerator;
  import java.security.NoSuchAlgorithmException;
  import java.security.PrivateKey;
  import java.security.PublicKey;
  import java.util.Date;
  import java.util.HashMap;
  import java.util.Map;
  import java.util.UUID;
  
  /**
   * JWT Token生成和管理服务
   */
  @Service
  public class JwtTokenService {

      private final PrivateKey privateKey;
      private final PublicKey publicKey;

      public JwtTokenService() throws NoSuchAlgorithmException {
          // 生成RSA密钥对
          KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
          keyPairGenerator.initialize(2048);
          KeyPair keyPair = keyPairGenerator.generateKeyPair();
          this.privateKey = keyPair.getPrivate();
          this.publicKey = keyPair.getPublic();
      }

      /**
       * 获取私钥（用于签名）
       */
      public PrivateKey getPrivateKey() {
          return privateKey;
      }

      /**
       * 获取公钥（用于验证）
       */
      public PublicKey getPublicKey() {
          return publicKey;
      }

      /**
       * 生成Access Token
       */
      public String generateAccessToken(String username, String email, String userId, java.util.Set<String> authorities) {
          Map<String, Object> claims = new HashMap<>();
          claims.put("userId", userId);
          claims.put("email", email);
          claims.put("authorities", authorities);
          claims.put("type", "access");
          claims.put("iss", "https://auth.example.com");  // Token颁发者
          claims.put("aud", "resource-server");  // Token受众
          claims.put("jti", UUID.randomUUID().toString());  // Token唯一标识

          return Jwts.builder()
                  .setClaims(claims)
                  .setSubject(username)
                  .setIssuedAt(new Date())
                  .setExpiration(new Date(System.currentTimeMillis() + 3600000)) // 1小时
                  .signWith(privateKey, SignatureAlgorithm.RS256)
                  .compact();
      }

      /**
       * 生成Refresh Token
       */
      public String generateRefreshToken(String username, String userId) {
          Map<String, Object> claims = new HashMap<>();
          claims.put("userId", userId);
          claims.put("type", "refresh");
          claims.put("jti", UUID.randomUUID().toString());  // Token唯一标识

          return Jwts.builder()
                  .setClaims(claims)
                  .setSubject(username)
                  .setIssuedAt(new Date())
                  .setExpiration(new Date(System.currentTimeMillis() + 604800000)) // 7天
                  .signWith(privateKey, SignatureAlgorithm.RS256)
                  .compact();
      }

      /**
       * 从Token中提取用户名
       */
      public String extractUsername(String token) {
          return Jwts.parserBuilder()
                  .setSigningKey(publicKey)
                  .build()
                  .parseClaimsJws(token)
                  .getBody()
                  .getSubject();
      }

      /**
       * 验证Token
       */
      public boolean validateToken(String token, String username) {
          try {
              String extractedUsername = extractUsername(token);
              return username.equals(extractedUsername);
          } catch (Exception e) {
              return false;
          }
      }

      /**
       * 验证Refresh Token（检查类型和过期时间）
       */
      public boolean validateRefreshToken(String token) {
          try {
              var claims = Jwts.parserBuilder()
                  .setSigningKey(publicKey)
                  .build()
                  .parseClaimsJws(token)
                  .getBody();

              // 检查token类型
              String tokenType = claims.get("type", String.class);
              if (!"refresh".equals(tokenType)) {
                  return false;
              }

              // 检查是否过期
              return !claims.getExpiration().before(new Date());
          } catch (Exception e) {
              return false;
          }
      }

      /**
       * 从Token中提取用户ID
       */
      public String getUserIdFromToken(String token) {
          try {
              var claims = Jwts.parserBuilder()
                  .setSigningKey(publicKey)
                  .build()
                  .parseClaimsJws(token)
                  .getBody();

              return claims.get("userId", String.class);
          } catch (Exception e) {
              throw new RuntimeException("无法从token中提取用户ID", e);
          }
      }
  }
  ```

- **注意**：需要确保所有Token生成方法都使用RSA私钥签名，并包含jti声明以便支持Token黑名单功能

#### 4. 配置CORS

- **文件**：`src/main/java/com/example/oauth2demo/config/WebConfig.java`
- **功能**：允许来自资源服务器和前端的跨域请求
- **实现**：
  ```java
  package com.example.oauth2demo.config;

  import org.springframework.context.annotation.Configuration;
  import org.springframework.web.servlet.config.annotation.CorsRegistry;
  import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

  /**
   * Web配置类
   * 配置CORS和其他Web相关设置
   */
  @Configuration
  public class WebConfig implements WebMvcConfigurer {

      @Override
      public void addCorsMappings(CorsRegistry registry) {
          // 配置CORS，允许前端访问API
          registry.addMapping("/api/**")
                  .allowedOrigins(
                      "http://localhost:5173",
                      "http://localhost:3000",
                      "https://api.u2511175.nyat.app:55139"  // 外部隧道域
                  ) // 允许的前端域名
                  .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                  .allowedHeaders("*")
                  .allowCredentials(true) // 允许发送Cookie
                  .maxAge(3600);
          
          // 配置CORS，允许资源服务器访问Token验证端点
          registry.addMapping("/oauth2/**")
                  .allowedOrigins(
                      "http://localhost:5000",  // 本地Python资源服务器
                      "https://resource.example.com"  // 生产环境资源服务器域名
                  )
                  .allowedMethods("GET", "POST", "OPTIONS")
                  .allowedHeaders("*")
                  .allowCredentials(true)
                  .maxAge(3600);
      }
  }
  ```

### 第二阶段：创建示例Python资源服务器

#### 1. 项目结构

```
python-resource-server/
├── app.py              # 主应用
├── requirements.txt    # 依赖
└── README.md           # 文档
```

#### 2. 依赖配置

```
Flask==2.0.1
Flask-CORS==3.0.10
PyJWT==2.4.0
requests==2.26.0
cryptography==36.0.2
```

#### 3. 实现Token验证

如前面的Python代码示例所示。

### 第三阶段：改进前端应用

#### 1. 添加资源服务器API调用

- **文件**：`frontend/src/services/apiService.ts`
- **功能**：调用资源服务器的受保护API
- **实现**：
  ```typescript
  import axios from 'axios';
  
  export const resourceApi = axios.create({
    baseURL: 'https://resource.example.com/api',
    timeout: 10000,
  });
  
  // 添加请求拦截器，自动添加Token
  resourceApi.interceptors.request.use(
    (config) => {
      // 从Cookie中获取Token
      const token = document.cookie
        .split('; ') 
        .find(row => row.startsWith('accessToken='))
        ?.split('=')[1];
      
      if (token) {
        config.headers.Authorization = `Bearer ${token}`;
      }
      return config;
    },
    (error) => {
      return Promise.reject(error);
    }
  );
  
  // 调用受保护的资源
  export const getProtectedResource = async () => {
    try {
      const response = await resourceApi.get('/protected');
      return response.data;
    } catch (error) {
      throw error;
    }
  };
  ```

#### 2. 添加测试页面

- **文件**：`frontend/src/pages/ResourceTestPage.tsx`
- **功能**：测试访问资源服务器的受保护API
- **实现**：
  ```tsx
  import React, { useState, useEffect } from 'react';
  import { getProtectedResource } from '../services/apiService';
  
  const ResourceTestPage: React.FC = () => {
    const [resourceData, setResourceData] = useState<any>(null);
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);
    
    const fetchResource = async () => {
      setLoading(true);
      setError(null);
      try {
        const data = await getProtectedResource();
        setResourceData(data);
      } catch (err: any) {
        setError(err.response?.data?.error || 'Failed to access resource');
      } finally {
        setLoading(false);
      }
    };
    
    return (
      <div className="container mx-auto p-4">
        <h1 className="text-2xl font-bold mb-4">Resource Server Test</h1>
        
        <button 
          className="bg-blue-500 text-white px-4 py-2 rounded mb-4"
          onClick={fetchResource}
          disabled={loading}
        >
          {loading ? 'Loading...' : 'Access Protected Resource'}
        </button>
        
        {error && (
          <div className="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4">
            Error: {error}
          </div>
        )}
        
        {resourceData && (
          <div className="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded">
            <h2 className="font-bold">Resource Data:</h2>
            <pre className="whitespace-pre-wrap">{JSON.stringify(resourceData, null, 2)}</pre>
          </div>
        )}
      </div>
    );
  };
  
  export default ResourceTestPage;
  ```

## 🧪 验证方法

### 1. 功能验证

#### 步骤1：启动服务

1. 启动认证服务器：`./start.sh`
2. 启动Python资源服务器：`python app.py`
3. 启动前端开发服务器：`npm run dev`

#### 步骤2：测试流程

1. **登录**：
   - 在前端应用中登录
   - 获取并存储Token

2. **访问资源**：
   - 打开`/resource-test`页面
   - 点击"Access Protected Resource"按钮
   - 验证是否成功获取资源

3. **Token验证**：
   - 直接调用`/oauth2/introspect`端点
   - 验证Token状态

4. **JWKS验证**：
   - 访问`/oauth2/jwks`端点
   - 验证返回的JWKS格式

### 2. 安全性验证

#### 步骤1：跨域测试

- 确保资源服务器部署在不同域名下
- 验证CORS配置是否正确
- 测试跨域请求是否成功

#### 步骤2：Token过期测试

- 生成过期Token
- 测试资源服务器是否拒绝访问

#### 步骤3：Token撤销测试

- 登出用户（Token进入黑名单）
- 测试资源服务器是否拒绝访问

## 🔒 安全考虑

### 1. 密钥管理

- **生产环境**：使用环境变量或密钥管理服务存储JWT密钥
- **密钥轮换**：定期轮换JWT密钥
- **密钥分发**：通过JWKS安全分发公钥

### 2. 跨域安全

- **CORS配置**：仅允许受信任的域名
- **CSRF保护**：为前端和资源服务器启用CSRF保护
- **SameSite Cookie**：设置适当的SameSite属性

### 3. Token安全

- **Token存储**：使用HttpOnly Cookie存储Token
- **Token过期**：设置合理的Token过期时间
- **Token撤销**：实现Token黑名单机制

## 📊 性能考虑

### 1. JWKS缓存

- 资源服务器应缓存JWKS以减少网络请求
- 实现缓存过期机制

### 2. Token验证优化

- 优先使用本地验证（JWKS + 公钥）
- 仅在必要时使用`/oauth2/introspect`端点

## 🎯 成功标准

1. **功能完整性**：
   - 异构资源服务器能够验证Token
   - 前端能够访问受保护的资源

2. **安全性**：
   - 跨域请求安全
   - Token验证安全
   - 密钥管理安全

3. **可靠性**：
   - 服务正常运行
   - 错误处理完善
   - 性能良好

## 📋 实施时间表

| 阶段 | 任务 | 预计时间 |
|------|------|----------|
| 1 | 改进认证服务器 | 2-3小时 |
| 2 | 创建Python资源服务器 | 1-2小时 |
| 3 | 改进前端应用 | 1小时 |
| 4 | 测试验证 | 2小时 |
| 5 | 文档完善 | 1小时 |

## 🔄 迭代检查

### 第一次检查

- [ ] 认证服务器改进方案完整
- [ ] 资源服务器实现方案清晰
- [ ] 前端改进方案明确
- [ ] 验证方法全面
- [ ] 安全考虑充分

### 第二次检查

- [ ] 代码实现细节明确
- [ ] 跨域配置合理
- [ ] 密钥管理方案安全
- [ ] 性能优化措施有效
- [ ] 错误处理机制完善

### 第三次检查

- [ ] 方案可行性高
- [ ] 实施步骤清晰
- [ ] 测试覆盖全面
- [ ] 文档内容完整
- [ ] 无遗漏的安全问题

## 📚 参考资料

- [RFC 7519: JSON Web Token (JWT)](https://tools.ietf.org/html/rfc7519)
- [RFC 7517: JSON Web Key (JWK)](https://tools.ietf.org/html/rfc7517)
- [OAuth 2.0 Token Introspection](https://tools.ietf.org/html/rfc7662)
- [Spring Security Reference](https://docs.spring.io/spring-security/reference/)
- [PyJWT Documentation](https://pyjwt.readthedocs.io/)

## 📝 结论

通过本规划，我们已经设计了一个完整的异构资源服务器集成方案。该方案通过添加JWKS端点和Token验证API，改进Token生成机制，配置CORS等措施，确保了异构资源服务器能够安全地验证并接受来自当前认证服务器颁发的Token。

同时，我们提供了详细的实现步骤和验证方法，确保方案的可行性和可靠性。通过创建示例Python资源服务器和改进前端应用，我们展示了如何在实际场景中应用该方案。

本方案不仅解决了异构资源服务器的集成问题，还考虑了安全性、性能和可靠性等因素，为实际项目提供了全面的参考。
