# Session 持久化与多服务器支持指南

> 状态：Historical。Spring Session JDBC 已部分落地，但生产建表和多实例行为没有
> 当前回归证据。配置事实见 [配置基线](../CONFIGURATION.md)。

## 📋 问题背景

当前项目采用**混合模式认证**：
- **Web 页面认证**：使用 Spring 默认的 HttpSession（基于内存）
- **API 认证**：使用 JWT Token（无状态）

**问题**：
- ❌ Web 页面 session 存储在内存中，不支持多服务器部署
- ❌ 负载均衡时会导致 session 丢失
- ❌ API 缺少 JWT 验证拦截器，安全性不足

**解决方案**：
- ✅ 使用 **Spring Session JDBC** 将 session 持久化到 SQL 数据库
- ✅ 完全使用 PostgreSQL，无需 Redis
- ✅ 支持多服务器部署

---

## 🚀 第一步：立即实施（30 分钟）- Spring Session JDBC

### 1.1 在 `pom.xml` 中添加依赖

```xml
<!-- Spring Session JDBC -->
<dependency>
    <groupId>org.springframework.session</groupId>
    <artifactId>spring-session-jdbc</artifactId>
</dependency>
```

完整位置（在 `<dependencies>` 块中）：

```xml
<dependencies>
    <!-- ... 现有依赖 ... -->
    
    <!-- Spring Session JDBC - Session 持久化 -->
    <dependency>
        <groupId>org.springframework.session</groupId>
        <artifactId>spring-session-jdbc</artifactId>
    </dependency>
    
    <!-- ... 其他依赖 ... -->
</dependencies>
```

### 1.2 配置 `application.yml`

在 `src/main/resources/application.yml` 中添加：

```yaml
spring:
  # ... 现有配置 ...
  
  # Session 配置 - 使用 JDBC 持久化
  session:
    store-type: jdbc              # 使用 JDBC 存储 session
    jdbc:
      initialize-schema: always   # 应用启动时自动创建表
    timeout: 1800                 # 30分钟超时
    cookie:
      name: JSESSIONID            # Session Cookie 名称
      http-only: true             # HttpOnly，防止 XSS
      path: /                      # Cookie 路径
      domain:                      # 如果多个子域共享，设置此项
      same-site: Lax              # CSRF 防护

logging:
  level:
    org.springframework.session: DEBUG  # 查看 session 操作日志
```

### 1.3 启用 Spring Session（主应用类）

修改 `src/main/java/com/example/oauth2demo/GoogleOAuth2DemoApplication.java`：

```java
package com.example.oauth2demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;  // 新增

@SpringBootApplication
@EnableSpringHttpSession  // 新增：启用 Spring Session
public class GoogleOAuth2DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(GoogleOAuth2DemoApplication.class, args);
    }
}
```

### 1.4 自动创建数据库表

Spring Session JDBC 会自动在应用启动时创建以下表：

```sql
-- SPRING_SESSION 表 - 存储 session 信息
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,      -- 主键
    SESSION_ID CHAR(36) NOT NULL,      -- Session ID（唯一）
    CREATION_TIME BIGINT NOT NULL,     -- 创建时间戳
    LAST_ACCESSED_TIME BIGINT NOT NULL,-- 最后访问时间戳
    MAX_INACTIVE_INTERVAL INT NOT NULL,-- 最大闲置时间（秒）
    EXPIRY_TIME BIGINT NOT NULL,       -- 过期时间戳
    PRINCIPAL_NAME VARCHAR(100),       -- 当前登录用户名
    PRIMARY KEY (PRIMARY_ID),
    UNIQUE (SESSION_ID)
);

-- SPRING_SESSION_ATTRIBUTES 表 - 存储 session 属性
CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,    -- 外键，关联到 SPRING_SESSION
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,   -- 属性名
    ATTRIBUTE_BYTES BYTEA NOT NULL,          -- 属性值（二进制序列化）
    PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID)
);
```

**注意**：表会在第一次运行时自动创建，无需手动执行 SQL。

### 1.5 测试 Session 持久化

1. **编译和启动应用**：
```bash
# cd repo-root
mvn clean package -DskipTests
java -jar target/google-oauth2-demo-*.jar
```

2. **登录并验证**：
```bash
# 1. 访问 Google OAuth2 登录页面（通过浏览器）
http://localhost:8080/oauth2/authorization/google

# 2. 完成 Google 登录后，查看数据库
psql -U postgres -d your_project_db -c "SELECT * FROM SPRING_SESSION;"

# 输出示例：
# primary_id | session_id | creation_time | last_accessed_time | max_inactive_interval | expiry_time | principal_name
# -----------|------------|---------------|-------------------|----------------------|-------------|----------------
# abc123...  | def456...  | 1674000000000 | 1674000010000     | 1800                 | 1674001800000 | user@gmail.com
```

3. **验证 session 持久化**：
```bash
# 重启应用，session 应该仍然有效
kill <PID>
java -jar target/google-oauth2-demo-*.jar

# 再次查询数据库，session 记录仍然存在
psql -U postgres -d your_project_db -c "SELECT COUNT(*) FROM SPRING_SESSION;"
```

---

## 🔐 第二步：改进 API 安全性（1-2 小时）- JWT 验证拦截器

### 2.1 创建 JWT 认证过滤器

创建文件 `src/main/java/com/example/oauth2demo/config/JwtAuthenticationFilter.java`：

```java
package com.example.oauth2demo.config;

import com.example.oauth2demo.service.JwtTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT 认证过滤器
 * 在每次请求前验证 JWT Token 并设置安全上下文
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                   HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        try {
            // 1. 从请求中提取 JWT Token
            String token = extractTokenFromRequest(request);

            // 2. 验证 Token
            if (token != null && !token.isEmpty()) {
                try {
                    String username = jwtTokenService.extractUsername(token);
                    Long userId = jwtTokenService.getUserIdFromToken(token);

                    // 3. 设置 Spring Security 认证信息
                    var authentication = new UsernamePasswordAuthenticationToken(
                        username,
                        null,
                        Collections.singleton(new SimpleGrantedAuthority("ROLE_USER"))
                    );

                    // 在主体中存储 userId
                    authentication.setDetails(userId);

                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    log.debug("JWT authentication successful for user: {}", username);

                } catch (Exception e) {
                    log.debug("JWT validation failed: {}", e.getMessage());
                    // Token 无效，SecurityContext 保持为空
                    SecurityContextHolder.clearContext();
                }
            }

        } catch (Exception e) {
            log.error("JWT filter error: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        }

        // 继续处理请求
        filterChain.doFilter(request, response);
    }

    /**
     * 从请求中提取 JWT Token
     *
     * 优先级：
     * 1. Authorization header: "Bearer <token>"
     * 2. accessToken Cookie
     */
    private String extractTokenFromRequest(HttpServletRequest request) {
        // 方法 1：从 Authorization header 中提取
        String authorizationHeader = request.getHeader("Authorization");
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring(7);
        }

        // 方法 2：从 Cookie 中提取
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("accessToken".equals(cookie.getName())) {
                    String tokenValue = cookie.getValue();
                    if (tokenValue != null && !tokenValue.isEmpty()) {
                        return tokenValue;
                    }
                }
            }
        }

        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        // 对于某些路径不应用此过滤器
        String path = request.getServletPath();

        // 认证相关的公开端点，不需要 JWT 验证
        return path.startsWith("/api/auth/") ||
               path.startsWith("/oauth2/") ||
               path.startsWith("/login") ||
               path.startsWith("/static/") ||
               path.startsWith("/css/") ||
               path.startsWith("/js/") ||
               path.equals("/") ||
               path.equals("/favicon.ico");
    }
}
```

### 2.2 注册过滤器到 Spring Security

修改 `src/main/java/com/example/oauth2demo/config/SecurityConfig.java`，在 API 安全过滤器链中添加过滤器：

```java
@Bean
@Order(1)
public SecurityFilterChain authApiSecurityFilterChain(HttpSecurity http,
                                                     JwtAuthenticationFilter jwtFilter) throws Exception {
    http
        .securityMatcher("/api/auth/**")
        .authorizeHttpRequests(authz -> authz
            .anyRequest().permitAll()  // API 端点公开（由 JwtAuthenticationFilter 验证）
        )
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)  // 新增
        .csrf(csrf -> csrf.disable());

    return http.build();
}
```

### 2.3 配置 JWT Secret Key（使用环境变量）

修改 `src/main/java/com/example/oauth2demo/service/JwtTokenService.java`：

```java
package com.example.oauth2demo.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class JwtTokenService {

    private final SecretKey secretKey;

    /**
     * 从环境变量或配置文件读取 JWT Secret
     * 格式：Base64 编码的 32 字节密钥
     *
     * 生成方法：
     * Base64.getEncoder().encodeToString(Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded())
     */
    public JwtTokenService(@Value("${app.jwt.secret:}") String jwtSecretEnv) {
        this.secretKey = initializeSecretKey(jwtSecretEnv);
        log.info("JWT Secret Key initialized: {}",
            secretKey != null ? "Using configured secret" : "Using default secret");
    }

    /**
     * 初始化 Secret Key
     * 优先级：
     * 1. 使用环境变量的 Secret（多服务器环境推荐）
     * 2. 使用生成的随机 Secret（开发环境）
     */
    private SecretKey initializeSecretKey(String jwtSecretEnv) {
        if (jwtSecretEnv != null && !jwtSecretEnv.isEmpty()) {
            try {
                byte[] decodedKey = Base64.getDecoder().decode(jwtSecretEnv);
                return Keys.hmacShaKeyFor(decodedKey);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid JWT secret format, using generated secret instead");
                return Keys.secretKeyFor(SignatureAlgorithm.HS256);
            }
        } else {
            // 开发环境使用生成的 Secret（每次启动都会改变）
            log.warn("JWT secret not configured, using generated secret (suitable for dev only)");
            return Keys.secretKeyFor(SignatureAlgorithm.HS256);
        }
    }

    /**
     * 获取 Secret Key
     */
    public SecretKey getSecretKey() {
        return secretKey;
    }

    // ... 其他方法保持不变 ...
}
```

### 2.4 在 `application.yml` 中配置 JWT Secret

```yaml
app:
  jwt:
    # 生成 Secret 的命令：
    # java -cp ".:target/*" -c "
    # import io.jsonwebtoken.security.Keys;
    # import io.jsonwebtoken.SignatureAlgorithm;
    # import java.util.Base64;
    # byte[] key = Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded();
    # System.out.println(Base64.getEncoder().encodeToString(key));
    # "
    #
    # 或使用以下 Java 代码生成：
    # String secret = Base64.getEncoder().encodeToString(
    #     Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded()
    # );
    secret: ${JWT_SECRET:}  # 从环境变量读取，如果为空则使用动态生成

# 开发环境配置示例
---
spring:
  config:
    activate:
      on-profile: dev

app:
  jwt:
    secret: ${JWT_SECRET:}  # 开发环境可以不设置

# 生产环境配置示例
---
spring:
  config:
    activate:
      on-profile: prod

app:
  jwt:
    secret: ${JWT_SECRET}  # 生产环境必须设置！
```

### 2.5 设置环境变量（多服务器部署）

对于多个服务器，使用相同的 JWT Secret：

```bash
# 生成 Secret（仅一次）
# 使用以下命令或上面提到的 Java 代码生成 Base64 编码的 Secret

# 导出环境变量
export JWT_SECRET="<生成的Base64编码的Secret>"

# 启动应用
java -jar target/google-oauth2-demo-*.jar

# 所有服务器都使用相同的 JWT_SECRET，这样它们可以相互验证 Token
```

---

## ✅ 完整验证清单

### Spring Session JDBC 验证

- [ ] pom.xml 中添加了 spring-session-jdbc 依赖
- [ ] application.yml 中配置了 session 存储类型为 jdbc
- [ ] 主应用类中添加了 @EnableSpringHttpSession 注解
- [ ] 应用启动时自动创建了 SPRING_SESSION 和 SPRING_SESSION_ATTRIBUTES 表
- [ ] 登录后能在数据库中查看 session 记录
- [ ] 重启应用后，session 仍然有效
- [ ] 多个服务器实例可以共享同一个 session

### JWT 验证拦截器验证（可选，后期）

- [ ] 创建了 JwtAuthenticationFilter 类
- [ ] 过滤器正确提取 Token（从 header 和 cookie）
- [ ] 过滤器正确验证 Token
- [ ] 过滤器设置了 Spring Security 认证信息
- [ ] API 端点能正确识别已认证的用户
- [ ] JWT Secret Key 从环境变量读取
- [ ] 多个服务器使用相同的 JWT Secret Key

---

## 📊 架构变化

### 现在（使用 Spring Session JDBC）

```
多个 Web 服务器:

服务器 A                    服务器 B
   |                           |
   +---- PostgreSQL Session Store ----+
   |                           |
   v                           v
用户登录 A ---> Session 存储到 DB <--- 用户访问 B
             （共享）
       任何服务器都能读取相同的 session
```

### 优势

| 特性 | 内存 Session | Spring Session JDBC |
|-----|------------|------------------|
| 单服务器 | ✅ | ✅ |
| 多服务器 | ❌ 丢失 | ✅ 共享 |
| 重启后持久 | ❌ 丢失 | ✅ 保留 |
| 额外技术 | 无 | PostgreSQL（已有） |
| 查询速度 | 快 | 中等（可接受） |
| 推荐度 | 开发 | 生产 ✅ |

---

## 🛠️ 故障排查

### 问题 1：应用启动时出现 "No suitable driver found"

**原因**：Spring Session JDBC 需要数据库驱动

**解决**：
```xml
<!-- 确保 pom.xml 中有 PostgreSQL 驱动 -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

### 问题 2：Session 表没有自动创建

**原因**：`spring.session.jdbc.initialize-schema` 配置不正确

**解决**：检查 application.yml：
```yaml
spring:
  session:
    jdbc:
      initialize-schema: always  # 必须设置为 always
```

### 问题 3：登出后 session 没有被删除

**原因**：可能是缓存问题

**解决**：检查数据库中是否真的删除了：
```bash
psql -U postgres -d your_project_db -c \
  "SELECT COUNT(*) FROM SPRING_SESSION WHERE expiry_time < EXTRACT(EPOCH FROM NOW()) * 1000;"
```

### 问题 4：不同服务器上的用户看不到对方的 session

**原因**：可能没有正确配置数据库连接或使用了不同的数据库

**解决**：
```yaml
spring:
  datasource:
    url: jdbc:postgresql://shared-db-host:5432/your_project_db
    username: ${DB_USER}
    password: ${DB_PASSWORD}
```

确保所有服务器指向**同一个数据库**！

---

## 📚 参考资源

- [Spring Session JDBC 官方文档](https://docs.spring.io/spring-session/docs/current/reference/html5/)
- [Spring Security Session 管理](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- [JDBC Session Repository 源代码](https://github.com/spring-projects/spring-session/tree/main/spring-session-jdbc)

---

## 🎯 下一步建议

1. **立即实施**（30 分钟）：
   - 添加 Spring Session JDBC 依赖
   - 配置 JDBC 存储
   - 验证 session 持久化

2. **后续改进**（1-2 小时）：
   - 实现 JWT 验证拦截器
   - 配置 JWT Secret Key
   - 测试多服务器 Token 共享

3. **监控和维护**（持续）：
   - 监控 SPRING_SESSION 表的大小
   - 定期清理过期 session
   - 收集性能指标

---

**完成后，你的项目将完全支持多服务器部署，无需 Redis！** ✨
