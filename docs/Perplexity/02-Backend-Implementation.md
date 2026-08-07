# 🔧 后端完整实现指南 (Spring Authorization Server Native)

> 状态：Historical。本文包含从零实现示例，包名、依赖和认证路径不等于当前仓库。
> 当前后端边界见 [当前架构](../ARCHITECTURE.md)。

**版本:** 3.0.0  
**重点:** 充分利用 Spring Authorization Server 内置方案

---

## 目录

1. [项目初始化](#项目初始化)
2. [依赖配置](#依赖配置)
3. [核心实体与 Repository](#核心实体与-repository)
4. [认证服务实现](#认证服务实现)
5. [Authorization Server 配置](#authorization-server-配置)
6. [业务 API 实现](#业务-api-实现)

---

## 项目初始化

### 步骤 1: 创建 Spring Boot 项目

```bash
# 使用 Spring Initializr
# 访问 https://start.spring.io
# 选择:
# - Spring Boot 3.2.1
# - Java 17+
# - Packaging: jar
# - Group: com.example
# - Artifact: user-auth-system

# 或使用 Maven
mvn archetype:generate \
  -DgroupId=com.example \
  -DartifactId=user-auth-system \
  -DarchetypeArtifactId=maven-archetype-quickstart
```

### 步骤 2: 项目结构

```
user-auth-system/
├── src/main/java/com/example/auth/
│   ├── config/
│   │   ├── AuthorizationServerConfig.java       # 授权服务器配置
│   │   ├── ResourceServerConfig.java            # 资源服务器配置
│   │   └── CorsConfig.java                      # CORS 配置
│   ├── entity/
│   │   ├── UserEntity.java                      # 用户实体
│   │   └── TokenBlacklistEntity.java            # Token 黑名单实体
│   ├── repository/
│   │   ├── UserRepository.java                  # 用户 Repository
│   │   └── TokenBlacklistRepository.java        # Token 黑名单 Repository
│   ├── service/
│   │   ├── CustomUserDetailsService.java        # 用户认证服务
│   │   ├── UserService.java                     # 用户业务逻辑
│   │   └── TokenBlacklistService.java           # Token 黑名单服务
│   ├── dto/
│   │   ├── LoginRequest.java
│   │   ├── RegisterRequest.java
│   │   └── UserDto.java
│   ├── controller/
│   │   ├── AuthController.java                  # 认证端点
│   │   └── UserController.java                  # 用户端点
│   └── AuthApplication.java
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   ├── application-prod.yml
│   └── schema-postgresql.sql
└── pom.xml
```

---

## 依赖配置

### pom.xml 完整配置

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>user-auth-system</artifactId>
    <version>3.0.0</version>
    <packaging>jar</packaging>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.1</version>
        <relativePath/>
    </parent>

    <properties>
        <java.version>17</java.version>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <spring-authorization-server.version>1.1.5</spring-authorization-server.version>
    </properties>

    <dependencies>
        <!-- Spring Boot Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- ✅ Spring Authorization Server (核心) -->
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-oauth2-authorization-server</artifactId>
            <version>${spring-authorization-server.version}</version>
        </dependency>

        <!-- Spring Security -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>

        <!-- Spring OAuth2 Client (Google SSO) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-client</artifactId>
        </dependency>

        <!-- Spring Data JPA -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!-- PostgreSQL 驱动 (生产) -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <version>42.7.0</version>
            <scope>runtime</scope>
        </dependency>

        <!-- SQLite 驱动 (开发) -->
        <dependency>
            <groupId>org.xerial</groupId>
            <artifactId>sqlite-jdbc</artifactId>
            <version>3.44.0.0</version>
            <scope>runtime</scope>
        </dependency>

        <!-- Hibernate Community Dialect (SQLite support) -->
        <dependency>
            <groupId>org.hibernate.orm</groupId>
            <artifactId>hibernate-community-dialect</artifactId>
            <version>6.4.0.Final</version>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Spring Boot DevTools -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-devtools</artifactId>
            <scope>runtime</scope>
            <optional>true</optional>
        </dependency>

        <!-- Spring Boot Actuator -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Jackson (JSON 处理) -->
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>

        <!-- 测试 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 核心实体与 Repository

### UserEntity.java

```java
package com.example.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 255)
    private String username;

    @Column(unique = true, nullable = false, length = 255)
    private String email;

    @Column(length = 255)
    private String passwordHash;  // BCrypt 加密

    @Column(length = 255)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String avatarUrl;

    @Column(nullable = false)
    private boolean emailVerified = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private AuthProvider authProvider = AuthProvider.LOCAL;

    @Column(length = 255)
    private String providerUserId;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    // ✅ 权限关联 (Spring Security GrantedAuthority)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_authorities", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "authority")
    @Builder.Default
    private Set<String> authorities = new HashSet<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    private LocalDateTime lastLoginAt;

    public enum AuthProvider {
        LOCAL, GOOGLE
    }

    @PrePersist
    protected void onCreate() {
        if (authorities.isEmpty()) {
            authorities.add("ROLE_USER");
        }
    }
}
```

### TokenBlacklistEntity.java

```java
package com.example.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "token_blacklist", indexes = {
    @Index(name = "idx_jti", columnList = "jti"),
    @Index(name = "idx_expires_at", columnList = "expires_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenBlacklistEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String jti;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TokenType tokenType;

    @Column
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime blacklistedAt;

    @Column(length = 255)
    private String reason;

    public enum TokenType {
        ACCESS, REFRESH, ID
    }
}
```

### Repository 接口

```java
package com.example.auth.repository;

import com.example.auth.entity.UserEntity;
import com.example.auth.entity.TokenBlacklistEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsername(String username);
    Optional<UserEntity> findByEmail(String email);
    Optional<UserEntity> findByAuthProviderAndProviderUserId(
        UserEntity.AuthProvider authProvider, String providerUserId);
}

@Repository
public interface TokenBlacklistRepository extends JpaRepository<TokenBlacklistEntity, Long> {
    boolean existsByJti(String jti);
    Optional<TokenBlacklistEntity> findByJti(String jti);
}
```

---

## 认证服务实现

### CustomUserDetailsService.java

```java
package com.example.auth.service;

import com.example.auth.entity.UserEntity;
import com.example.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // ✅ Spring Authorization Server 会调用这个方法进行本地认证
        UserEntity user = userRepository.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        if (!user.isEnabled()) {
            throw new UsernameNotFoundException("User is disabled: " + username);
        }

        // ✅ 将 UserEntity 的 authorities 转换为 Spring Security 的 GrantedAuthority
        var grantedAuthorities = user.getAuthorities().stream()
            .map(SimpleGrantedAuthority::new)
            .toList();

        return User.builder()
            .username(user.getUsername())
            .password(user.getPasswordHash())  // BCrypt hash
            .authorities(grantedAuthorities)   // ✅ 从数据库读取权限
            .accountExpired(false)
            .accountLocked(false)
            .credentialsExpired(false)
            .disabled(!user.isEnabled())
            .build();
    }
}
```

### UserService.java

```java
package com.example.auth.service;

import com.example.auth.dto.RegisterRequest;
import com.example.auth.dto.UserDto;
import com.example.auth.entity.UserEntity;
import com.example.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 本地注册
     */
    public UserDto register(RegisterRequest request) {
        // 检查用户是否存在
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new IllegalArgumentException("Username already exists");
        }

        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }

        // ✅ 创建用户
        UserEntity user = UserEntity.builder()
            .username(request.getUsername())
            .email(request.getEmail())
            .passwordHash(passwordEncoder.encode(request.getPassword()))
            .displayName(request.getDisplayName())
            .authProvider(UserEntity.AuthProvider.LOCAL)
            .authorities(Set.of("ROLE_USER"))  // 默认权限
            .enabled(true)
            .build();

        userRepository.save(user);
        return convertToDto(user);
    }

    /**
     * 本地登录
     */
    @Transactional(readOnly = true)
    public UserDto login(String username) {
        UserEntity user = userRepository.findByUsername(username)
            .orElseThrow(() -> new RuntimeException("User not found"));

        // 更新最后登录时间
        user.setLastLoginAt(java.time.LocalDateTime.now());
        userRepository.save(user);

        return convertToDto(user);
    }

    /**
     * 获取或创建 Google SSO 用户
     */
    public UserDto getOrCreateGoogleUser(String providerUserId, String email, String name, String picture) {
        // 尝试找到已有的用户
        var existingUser = userRepository
            .findByAuthProviderAndProviderUserId(UserEntity.AuthProvider.GOOGLE, providerUserId);

        if (existingUser.isPresent()) {
            return convertToDto(existingUser.get());
        }

        // ✅ 创建新的 Google 用户
        UserEntity newUser = UserEntity.builder()
            .email(email)
            .username(email)  // 使用邮箱作为用户名
            .displayName(name)
            .avatarUrl(picture)
            .authProvider(UserEntity.AuthProvider.GOOGLE)
            .providerUserId(providerUserId)
            .emailVerified(true)  // Google 用户邮箱已验证
            .authorities(Set.of("ROLE_USER"))
            .enabled(true)
            .build();

        userRepository.save(newUser);
        return convertToDto(newUser);
    }

    private UserDto convertToDto(UserEntity user) {
        return UserDto.builder()
            .id(user.getId())
            .username(user.getUsername())
            .email(user.getEmail())
            .displayName(user.getDisplayName())
            .avatarUrl(user.getAvatarUrl())
            .build();
    }
}
```

---

## Authorization Server 配置

### AuthorizationServerConfig.java

```java
package com.example.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.web.SecurityFilterChain;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Duration;
import java.util.UUID;

@Configuration
@EnableWebSecurity
public class AuthorizationServerConfig {

    @Bean
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        return http.build();
    }

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
                .accessTokenTimeToLive(Duration.ofHours(1))  // ✅ 1 小时
                .refreshTokenTimeToLive(Duration.ofDays(7))  // ✅ 7 天
                .build())
            .clientSettings(ClientSettings.builder()
                .requireProofKey(false)  // 不需要 PKCE (前端不支持)
                .build())
            .build();

        return new InMemoryRegisteredClientRepository(registeredClient);
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        PublicKey publicKey = keyPair.getPublic();
        java.security.PrivateKey privateKey = keyPair.getPrivate();

        RSAKey rsaKey = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) publicKey)
            .privateKey((java.security.interfaces.RSAPrivateKey) privateKey)
            .keyID(UUID.randomUUID().toString())
            .build();

        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }
}
```

---

## 业务 API 实现

### UserController.java

```java
package com.example.auth.controller;

import com.example.auth.dto.LoginRequest;
import com.example.auth.dto.RegisterRequest;
import com.example.auth.dto.UserDto;
import com.example.auth.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/register")
    public ResponseEntity<UserDto> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(userService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginRequest> login(@RequestBody LoginRequest request) {
        // ✅ 验证用户名/密码由 Spring Authorization Server 处理
        UserDto user = userService.login(request.getUsername());
        return ResponseEntity.ok(request);
    }

    @GetMapping("/user/me")
    public ResponseEntity<UserDto> getCurrentUser() {
        // ✅ 从 SecurityContext 获取当前用户信息
        var authentication = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        String username = authentication.getName();
        UserDto user = userService.getCurrentUser(username);
        return ResponseEntity.ok(user);
    }
}
```

---

**下一步:** 查看 [04-Database-Setup.md] 获取数据库设置
