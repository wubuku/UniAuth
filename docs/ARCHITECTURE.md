# UniAuth 当前架构

> 状态：Live
> 核验日期：2026-08-07
> 主要来源：`pom.xml`、`src/main/java/`、`src/main/resources/`、`frontend/`、
> `python-resource-server/app.py`

## 系统边界

UniAuth 是一个单仓库认证系统，包含三个可运行部分：

| 模块 | 路径 | 责任 |
|------|------|------|
| Spring Boot 后端 | `src/main/java/org/dddml/uniauth/` | 用户、登录方式、OAuth2 Client、自定义 JWT、JWKS、API 安全 |
| React SPA | `frontend/` | 登录、用户状态、登录方式管理、Web3 和资源服务器演示 |
| Flask 资源服务器 | `python-resource-server/` | 通过 JWKS 验证 UniAuth access token 的异构示例 |

生产构建时，Vite 把 SPA 写入 `src/main/resources/static/`，由 Spring Boot
提供静态资源。该目录是生成物，不是前端源码。

## 当前认证能力

- 本地用户名/密码注册与登录。
- Google、GitHub、X OAuth2 Client 登录。
- 多登录方式绑定、移除和主方式选择。
- 邮箱验证码注册与密码重置的部分实现。
- Web3 钱包 nonce、签名验证、登录和绑定。
- 自定义 RS256 access/refresh token。
- JWKS 与 token introspection。
- Spring Security Resource Server 保护 `/api/**`。
- Spring Session JDBC。

Spring Authorization Server 的依赖和部分配置存在，但主要业务 token 由
`JwtTokenService` 和 controller/service 自定义签发。不能据此假设标准
Authorization Server 协议已经完整接通。

## 请求与认证流

### 本地登录

1. `AuthController` 接收用户名和密码。
2. `CustomUserDetailsService` / `UserService` 从 `user_login_methods` 验证本地方式。
3. `JwtTokenService` 生成 access token 和 refresh token。
4. 响应通常同时写入 HttpOnly cookie 和 JSON body。
5. 前端当前还会把 access token 写入 localStorage，用于异构资源服务器演示。

### OAuth2 登录

1. 浏览器访问 `/oauth2/authorization/{google|github|x}`。
2. Spring Security OAuth2 Client 完成 provider 回调。
3. `SecurityConfig.oauth2SuccessHandler` 识别登录或绑定场景。
4. `UserService.getOrCreateOAuthUser` 查找、创建或绑定登录方式。
5. 自定义 JWT 被签发，随后返回 JSON 或重定向到配置的前端地址。

### API 认证

1. `ResourceServerConfig` 从 `Authorization: Bearer` 或 `accessToken` cookie 取 token。
2. `JwtDecoder` 使用当前 RSA 公钥验证签名。
3. `authorities` claim 转换为 Spring Security authority。
4. `/api/user` 和其他 `/api/**` 受资源服务器链保护。

### Python 资源服务器

1. Flask 从 Authorization header 获取 bearer token。
2. 从 UniAuth `/oauth2/jwks` 获取并缓存公钥。
3. 验证签名、`exp`、audience 和 issuer。
4. 响应优先展示 `username` claim；仅为兼容旧 token 回退到 `sub`。
   新 token 的 `sub` 是用户 UUID，不是显示用户名。

## 安全过滤器链

安全规则由四条有序链共同决定：

| Order | 配置 | Matcher | 当前行为 |
|-------|------|---------|----------|
| 0 | `AuthApiConfig` | `/api/auth/**` | method/path 公开 allowlist；其余 `denyAll`；CSRF 禁用 |
| 1 | `AuthorizationServerConfig` | 指定 `/oauth2/*` 端点 | 全部 `permitAll`，CSRF 禁用 |
| 2 | `ResourceServerConfig` | `/api/**` | JWT Resource Server；除认证 API 外默认需要认证 |
| 3 | `SecurityConfig` | 其余请求 | OAuth2 登录、SPA/Web、CSRF 和授权规则 |

高风险边界：

- `/api/auth/**` 不经过 Resource Server 链。该空间内需要身份的接口必须自行验证，
  例如当前 Web3 bind 手工解析 bearer token。
- CORS 同时存在于 `CorsConfig`、`WebConfig`、`WebMvcConfig` 和 YAML。
- cookie 的 `Secure`、SameSite 和写入逻辑分散在多个 controller/config。

## 身份模型

`users` 保存统一用户；`user_login_methods` 保存认证来源。

核心不变量：

- `users.id` 是 36 字符 UUID string。
- provider 枚举为 `LOCAL`、`GOOGLE`、`GITHUB`、`TWITTER`、`WEB3`。
- Spring registration id 是 `x`，数据库枚举仍是 `TWITTER`。
- 本地用户名全局唯一。
- provider + provider user id 全局唯一。
- 同一用户同一 provider 只能有一个登录方式。
- 不能移除最后一个登录方式。
- 每个用户预期只有一个 primary 登录方式。

这些约束一部分由 service 检查，一部分由数据库唯一索引保证；当前没有自动化测试证明并发场景。

## JWT 模型

access token 使用 RS256，当前默认：

| 项目 | 值 |
|------|----|
| `sub` | 用户 UUID |
| `userId` | 用户 UUID |
| `username` | 实际用户名 |
| `email` | 用户邮箱 |
| `authorities` | 权限集合 |
| `type` | `access` 或 `refresh` |
| `jti` | 每个 token 的唯一 ID |
| issuer | `https://auth.example.com` |
| audience | `resource-server`（access token） |
| `kid` | `key-1` |

access token 默认 1 小时，refresh token 默认 7 天。

`JwtTokenService` 构造阶段读取 `jwt.rsa.key-file`。默认路径是 ignored 的
`.local/uniauth/rsa-keys.ser`，新生成文件在 POSIX 文件系统上限制为 owner read/write。
该格式仍是本地二进制文件而非生产密钥库；历史提交中的根目录私钥必须视为已暴露。

## 数据持久化

| Profile | 数据库 | schema 行为 |
|---------|--------|-------------|
| `dev` | SQLite | SQL init 执行 `schema-sqlite.sql` 和 `data-sqlite.sql`；Hibernate `none` |
| `test` | PostgreSQL | SQL init 执行 `schema-postgresql.sql`；Hibernate `update` |
| `prod` | PostgreSQL | SQL init 关闭；Hibernate `validate` |

当前问题：

- 演示数据默认关闭；显式启用时只允许 disposable test/demo 数据库并只 upsert 受管账户。
- `src/main/resources/db/migration/V*.sql` 没有迁移工具执行。
- SQLite schema 缺少 `web3_nonces`、`email_verification_codes` 和部分登录方式列。
- Spring Session 生产表需要外部预置。

详细启动风险见 [配置基线](CONFIGURATION.md)。

## 代码所有权

| 主题 | 主要代码 |
|------|----------|
| 本地认证 | `AuthController`、`UserService`、`CustomUserDetailsService` |
| OAuth2 登录/绑定 | `SecurityConfig`、`UserService`、`LoginMethodService` |
| JWT | `JwtTokenService`、`TokenController`、`OAuth2TokenController` |
| API 认证 | `ResourceServerConfig`、`ApiAuthController` |
| 邮箱验证码 | `EmailAuthController`、`EmailVerificationCodeService` |
| 密码重置 | `ForgotPasswordController`、`ForgotPasswordService` |
| Web3 | `Web3AuthController`、`Web3AuthService`、`Web3NonceService` |
| 前端 API 边界 | `frontend/src/services/authService.ts` |
| 前端认证状态 | `frontend/src/hooks/useAuth.ts` |
| Python JWT 验证 | `python-resource-server/app.py` |

## 跨模块修改规则

- JWT claim 变更：同步后端 decoder/introspection、前端、Python 示例和文档。
- API 响应变更：同步前端 service、types、页面和脚本。
- provider 命名变更：同时处理 registration id `x` 与 enum `TWITTER`。
- schema/entity 变更：同时检查 PostgreSQL、SQLite、profile init 和导出脚本。
- CORS/cookie/callback 变更：检查全部安全链、YAML、前端代理和部署配置。

## 相关历史材料

- [前后端契约](FRONTEND_BACKEND_CONTRACT.md)
- [多登录方式设计与实施记录](drafts/README.md#多登录方式)
- [异构资源服务器材料](drafts/README.md#异构资源服务器与微服务)
- [Perplexity 历史架构](Perplexity/01-Architecture-Design.md)
