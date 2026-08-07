# Spring Boot 项目集成 SSO + 本地登录指南

> 状态：Needs verification。本文面向复制/移植场景，端口、包名、schema 和安全配置
> 不能直接视为当前仓库事实。当前边界见 [当前架构](../ARCHITECTURE.md)。

## 📌 概述

本指南帮助你将 `./` 项目的**认证和授权模块**集成到自己的 Spring Boot 项目中。

### 集成特性
- ✅ **本地用户登录** - 用户名/密码认证
- ✅ **多 SSO 提供商支持** - Google、GitHub、Twitter（可选）
- ✅ **JWT Token** - 无状态认证
- ✅ **用户绑定管理** - 支持单个用户绑定多个登录方式
- ✅ **数据库隔离** - SQLite（开发）/ PostgreSQL（生产）
- ✅ **环境配置** - Dev / Test / Prod 完整支持

### 集成时间预估
- 完整集成：**1-2 小时**
- 仅本地登录：**30 分钟**
- 添加 OAuth2：**额外 30 分钟**

---

## 🚀 快速开始

### 前置条件

1. **Java 17+** - 确保项目使用 Java 17 或更高版本
2. **PostgreSQL 12+** - 生产环境推荐使用 PostgreSQL
3. **Maven 3.6+** - 构建工具

### 集成方式选择

#### 选项 A：拷贝核心模块（推荐）
适合：希望完全控制认证逻辑的项目
- 时间：1-2 小时
- 灵活性：高
- 维护成本：中等

#### 选项 B：保留原包名（快速）
适合：快速验证功能的项目
- 时间：30 分钟
- 灵活性：低
- 维护成本：低

本指南主要介绍 **选项 A**（推荐）。

---

## 📂 第一步：拷贝代码文件

### 1.1 目录结构准备

假设你的项目包名为 `com.yourcompany.yourproject`，在 `src/main/java/com/yourcompany/yourproject/` 下创建以下目录结构：

```
src/main/java/com/yourcompany/yourproject/
├── auth/
│   ├── config/          ← 认证配置类
│   ├── controller/      ← API 控制器
│   ├── dto/             ← 数据传输对象
│   ├── entity/          ← JPA 实体
│   ├── repository/      ← 数据访问层
│   └── service/         ← 业务逻辑层
└── [其他模块]/
```

### 1.2 拷贝文件清单

| 源文件路径 | 目标路径 | 说明 |
|-----------|--------|------|
| `src/main/java/com/example/oauth2demo/config/*.java` | `auth/config/` | 认证配置 |
| `src/main/java/com/example/oauth2demo/controller/*.java` | `auth/controller/` | API 控制器 |
| `src/main/java/com/example/oauth2demo/dto/*.java` | `auth/dto/` | DTO 类 |
| `src/main/java/com/example/oauth2demo/entity/*.java` | `auth/entity/` | 数据库实体 |
| `src/main/java/com/example/oauth2demo/repository/*.java` | `auth/repository/` | 数据库访问 |
| `src/main/java/com/example/oauth2demo/service/*.java` | `auth/service/` | 业务逻辑 |

### 1.3 拷贝数据库脚本

```bash
# 拷贝到你的项目的 src/main/resources 目录
cp src/main/resources/schema-postgresql.sql     \
   src/main/resources/data-postgresql.sql       \
   your-project/src/main/resources/
```

---

## 🔧 第二步：修改包名

### 2.1 使用 IDE 进行包名重构

**IntelliJ IDEA：**
1. 右键点击 `com.example.oauth2demo` 包
2. 选择 `Refactor` → `Rename...`
3. 输入新的包名：`com.yourcompany.yourproject.auth`
4. IDE 会自动更新所有导入和引用

**Eclipse：**
1. 右键点击 `com.example.oauth2demo` 包
2. 选择 `Refactor` → `Rename...`
3. 输入新的包名

### 2.2 手动修改（如果 IDE 不可用）

使用 `sed` 或 `find & replace` 替换所有文件中的包名：

```bash
find . -name "*.java" -type f -exec sed -i \
  's/com\.example\.oauth2demo/com.yourcompany.yourproject.auth/g' {} \;
```

### 2.3 检查特殊的包名引用

搜索以下路径，检查是否有硬编码的包名：

```bash
# 检查配置类中是否有硬编码的包名
grep -r "com.example.oauth2demo" src/main/java/
grep -r "com.example.oauth2demo" src/main/resources/

# 检查 Spring 扫描配置
grep -r "@ComponentScan\|@EntityScan\|@EnableJpaRepositories" src/
```

---

## 📦 第三步：更新 Maven 依赖

### 3.1 在 `pom.xml` 中添加或更新以下依赖

```xml
<dependencies>
    <!-- Spring Boot Starters -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <!-- Spring Authorization Server（如果需要 SSO） -->
    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-oauth2-authorization-server</artifactId>
        <version>1.3.0</version>
    </dependency>

    <!-- Spring OAuth2 Client（Google/GitHub/Twitter SSO） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-oauth2-client</artifactId>
    </dependency>

    <!-- JPA + PostgreSQL -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>

    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- JWT Support -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.11.5</version>
    </dependency>

    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.11.5</version>
        <scope>runtime</scope>
    </dependency>

    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.11.5</version>
        <scope>runtime</scope>
    </dependency>

    <!-- Spring Session JDBC（可选但推荐，用于 session 持久化和多服务器共享） -->
    <dependency>
        <groupId>org.springframework.session</groupId>
        <artifactId>spring-session-jdbc</artifactId>
    </dependency>

    <!-- Lombok（可选，但推荐） -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

---

## 🗄️ 第四步：数据库初始化

### 4.1 创建 PostgreSQL 数据库

```sql
CREATE DATABASE your_project_db;
\c your_project_db;

-- 执行 schema-postgresql.sql 创建表
\i src/main/resources/schema-postgresql.sql
```

### 4.2 表结构说明

#### 认证相关表

| 表名 | 用途 | 关键字段 |
|-----|------|---------|
| `users` | 用户账户 | `id`, `username`, `email`, `enabled` |
| `user_login_methods` | 登录方式（本地/SSO） | `auth_provider`, `local_password_hash`, `provider_user_id` |
| `user_authorities` | 用户权限 | `authority` （ROLE_USER, ROLE_ADMIN 等） |
| `token_blacklist` | Token 黑名单（登出） | `token`, `blacklist_reason` |

#### Session 持久化表（Spring Session JDBC）

如果启用 Spring Session JDBC（可选但推荐），还会自动创建以下表：

| 表名 | 用途 | 说明 |
|-----|------|------|
| `SPRING_SESSION` | 存储 session 信息 | 包含 session ID、创建时间、过期时间等 |
| `SPRING_SESSION_ATTRIBUTES` | 存储 session 属性 | 存储 session 中的属性值（序列化） |

**这些表会在应用启动时自动创建**（如果配置了 `initialize-schema: always`）。

### 4.3 初始化测试数据（可选）

```bash
# 本地开发环境，通过代码创建测试用户（不需要执行 data-postgresql.sql）
# 参考：DevEnvironmentInitializer.java 或 TestEnvironmentInitializer.java
```

---

## ⚙️ 第五步：配置 Spring Boot 应用

### 5.1 在主应用类中启用 JPA 和 Spring Session

```java
package com.yourcompany.yourproject;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;

@SpringBootApplication
@EnableJpaRepositories(
    basePackages = "com.yourcompany.yourproject.auth.repository"
)
@ComponentScan(
    basePackages = {"com.yourcompany.yourproject"}
)
@EnableSpringHttpSession  // ← 启用 Spring Session JDBC（可选但推荐）
public class YourProjectApplication {
    public static void main(String[] args) {
        SpringApplication.run(YourProjectApplication.class, args);
    }
}
```

**说明**：
- `@EnableSpringHttpSession` 注解启用 Spring Session JDBC
- 将 HttpSession 持久化到数据库（而不是内存）
- 支持多服务器部署时 session 自动共享
- 应用重启后 session 仍然保留

### 5.2 创建 `application.yml` 配置

```yaml
server:
  port: 8080

spring:
  application:
    name: your-project

  # 数据库配置
  datasource:
    url: jdbc:postgresql://localhost:5432/your_project_db
    username: postgres
    password: your_password
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: validate  # 或 update（首次运行）
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    properties:
      hibernate:
        format_sql: true
        use_sql_comments: true

  # Spring Session JDBC 配置（可选但推荐）- Session 持久化到数据库
  session:
    store-type: jdbc                # 使用 JDBC 存储 session
    jdbc:
      initialize-schema: always     # 自动创建 SPRING_SESSION 表
    timeout: 1800                   # 30分钟超时
    cookie:
      http-only: true               # 防止 XSS
      same-site: Lax                # 防止 CSRF

  # OAuth2 配置（可选，跳过此步骤则仅支持本地登录）
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID:your-client-id}
            client-secret: ${GOOGLE_CLIENT_SECRET:your-client-secret}
            scope:
              - openid
              - profile
              - email
        provider:
          google:
            authorization-uri: https://accounts.google.com/o/oauth2/v2/auth?prompt=select_account
            token-uri: https://oauth2.googleapis.com/token
            user-info-uri: https://openidconnect.googleapis.com/v1/userinfo
            user-name-attribute: sub
            jwk-set-uri: https://www.googleapis.com/oauth2/v3/certs

logging:
  level:
    com.yourcompany.yourproject.auth: DEBUG
    org.springframework.security: DEBUG
```

### 5.3 创建 `application-prod.yml`（生产配置）

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:your_project_db}
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:}
    hikari:
      maximum-pool-size: 20

  jpa:
    hibernate:
      ddl-auto: validate  # 生产环境不要用 update

logging:
  level:
    root: WARN
    com.yourcompany.yourproject.auth: INFO
```

---

## 🔐 第六步：配置认证和授权

### 6.1 基本认证流程

项目已实现以下认证流程：

```
1. 用户登录 (POST /api/auth/login)
   ↓
2. Spring Security 验证用户名/密码
   ↓
3. 生成 JWT Access Token + Refresh Token
   ↓
4. 返回 Token 给客户端（通常存储在 HttpOnly Cookie 中）
   ↓
5. 后续请求在 Authorization header 中携带 Token
   ↓
6. Spring Security 验证 Token 并建立会话
```

### 6.2 启用 CORS（如果前端单独部署）

在你的配置类中添加：

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("http://localhost:3000", "https://yourfrontend.com")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true);
    }
}
```

---

## 🧪 第七步：测试认证流程

### 7.1 测试本地登录

```bash
# 1. 注册新用户
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "test@example.com",
    "password": "password123",
    "displayName": "Test User"
  }'

# 2. 登录
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=testuser&password=password123"

# 响应示例：
# {
#   "id": 1,
#   "username": "testuser",
#   "email": "test@example.com",
#   "displayName": "Test User",
#   "accessToken": "eyJhbGc...",
#   "refreshToken": "eyJhbGc..."
# }

# 3. 使用 Token 访问受保护的资源
curl -X GET http://localhost:8080/api/auth/current-user \
  -H "Authorization: Bearer eyJhbGc..."
```

### 7.2 测试用户绑定

```bash
# 1. 本地用户通过 SSO 登录后自动绑定
# （需要配置 OAuth2 provider）

# 2. 查看用户的所有登录方式
curl -X GET http://localhost:8080/api/auth/user/1/login-methods \
  -H "Authorization: Bearer eyJhbGc..."

# 3. 解绑登录方式
curl -X DELETE http://localhost:8080/api/auth/user/1/login-method/GOOGLE \
  -H "Authorization: Bearer eyJhbGc..."
```

---

## 🔌 可选：配置 OAuth2 SSO

### 8.1 Google OAuth2 配置

1. **创建 Google Cloud 项目**
   - 访问 https://console.cloud.google.com
   - 创建新项目
   - 启用 Google+ API

2. **创建 OAuth2 凭证**
   - 进入 "Credentials" 页面
   - 创建 "OAuth client ID" (Web application)
   - 添加授权重定向 URI：
     ```
     http://localhost:8080/oauth2/callback/google
     https://yourdomain.com/oauth2/callback/google
     ```

3. **在环境变量中设置凭证**
   ```bash
   export GOOGLE_CLIENT_ID="your-client-id.apps.googleusercontent.com"
   export GOOGLE_CLIENT_SECRET="your-client-secret"
   ```

4. **在 `application.yml` 中配置**
   ```yaml
   spring:
     security:
       oauth2:
         client:
           registration:
             google:
               client-id: ${GOOGLE_CLIENT_ID}
               client-secret: ${GOOGLE_CLIENT_SECRET}
   ```

### 8.2 其他提供商（GitHub、Twitter）

参考 `application.yml` 中的注释部分，按照类似方式配置。

---

## 🛠️ 常见集成问题排查

### 问题 1：编译失败，找不到 `com.example.oauth2demo` 包

**原因**：包名未完全重构

**解决**：
```bash
# 搜索所有残留的包名引用
grep -r "com.example.oauth2demo" src/

# 使用 IDE 的 Find and Replace 功能替换所有引用
```

### 问题 2：数据库连接失败

**原因**：PostgreSQL 未启动或连接配置不正确

**解决**：
```bash
# 检查 PostgreSQL 是否运行
psql -U postgres -c "SELECT version();"

# 检查数据库是否存在
psql -U postgres -l | grep your_project_db

# 测试连接
psql -h localhost -U postgres -d your_project_db
```

### 问题 3：登录返回 401 Unauthorized

**原因**：
1. 密码哈希不匹配（使用了错误的密码编码器）
2. JWT 签名密钥配置不正确
3. 用户不存在或被禁用

**排查步骤**：
```bash
# 1. 查看日志中的认证错误
tail -f logs/your-app.log | grep -i "authentication\|401\|unauthorized"

# 2. 验证数据库中用户的密码哈希是否正确
# 在 DevEnvironmentInitializer 中创建的用户密码应该是正确的

# 3. 测试密码编码器
# 在你的代码中添加调试代码
PasswordEncoder encoder = new BCryptPasswordEncoder();
String hash = encoder.encode("password123");
System.out.println("Password hash: " + hash);
```

### 问题 4：Token 过期或验证失败

**原因**：
1. Token 已过期（默认 1 小时）
2. JWT 签名密钥不一致
3. Token 格式不正确

**解决**：
```bash
# 使用 refresh token 获取新的 access token
curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "your-refresh-token"
  }'
```

### 问题 5：无法绑定 SSO 账户

**原因**：
1. OAuth2 提供商配置不正确
2. 重定向 URI 不匹配
3. Scope 权限不足

**排查步骤**：
```bash
# 1. 检查 application.yml 中的配置
# 2. 确保重定向 URI 在 OAuth2 提供商的配置中白名单
# 3. 查看日志中的 OAuth2 错误信息
grep -i "oauth2\|provider" logs/your-app.log
```

---

## 📝 API 接口参考

### 认证 API

| 方法 | 端点 | 说明 |
|-----|------|------|
| POST | `/api/auth/register` | 注册新用户 |
| POST | `/api/auth/login` | 本地用户登录 |
| POST | `/api/auth/logout` | 登出 |
| POST | `/api/auth/refresh` | 刷新 Token |
| GET | `/api/auth/current-user` | 获取当前用户信息 |

### 登录方式管理 API

| 方法 | 端点 | 说明 |
|-----|------|------|
| GET | `/api/auth/user/{userId}/login-methods` | 获取用户的所有登录方式 |
| DELETE | `/api/auth/user/{userId}/login-method/{provider}` | 解绑指定的登录方式 |
| POST | `/api/auth/user/{userId}/bind-sso` | 绑定 SSO 账户 |

---

## 🎯 下一步

1. **集成前端**
   - 使用 axios 或 fetch 调用认证 API
   - 在 localStorage 或 HttpOnly Cookie 中存储 Token
   - 在请求 header 中添加 Authorization

2. **自定义用户信息**
   - 在 `UserEntity` 中添加你项目需要的字段
   - 更新 `UserDto` 和相关 DTO
   - 迁移数据库 schema

3. **实现权限管理**
   - 根据业务需求扩展 `ROLE_*` 权限
   - 在 controller 中使用 `@PreAuthorize` 注解

4. **生产部署**
   - 配置生产数据库（PostgreSQL）
   - 使用环境变量管理敏感信息
   - 配置 HTTPS 和安全 cookie 设置
   - 设置合适的 Token 过期时间

---

## 📚 相关文件参考

- **核心配置**：`src/main/resources/application.yml`
- **安全配置**：`src/main/java/com/example/oauth2demo/config/SecurityConfig.java`
- **API 文档**：`docs/` 目录
- **数据库 Schema**：`src/main/resources/schema-postgresql.sql`

---

## ❓ 获取帮助

如有问题，请参考以下资源：

- [Spring Security 文档](https://docs.spring.io/spring-security/reference/)
- [Spring OAuth2 文档](https://docs.spring.io/spring-security/reference/servlet/oauth2/index.html)
- [JWT.io](https://jwt.io/)
- [项目 GitHub Issues](https://github.com/your-repo/issues)

---

## 📄 版本历史

| 版本 | 日期 | 更新内容 |
|-----|------|---------|
| 1.0 | 2026-01-24 | 初始版本，包含本地登录 + OAuth2 SSO 集成指南 |
