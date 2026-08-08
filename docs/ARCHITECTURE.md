# UniAuth 当前架构

> 状态：Live
> 核验日期：2026-08-08
> 主要来源：`pom.xml`、`src/main/java/`、`src/main/resources/`、`frontend/`、
> `python-resource-server/app.py`

## 系统边界

UniAuth 是一个单仓库认证系统，包含三个主要运行部分和一个独立参考组件：

| 模块 | 路径 | 责任 |
|------|------|------|
| Spring Boot 后端 | `src/main/java/org/dddml/uniauth/` | 用户、登录方式、OAuth2 Client、自定义 JWT、JWKS、API 安全 |
| React SPA | `frontend/` | 登录、用户状态、登录方式管理、Web3 和资源服务器演示 |
| Flask 资源服务器 | `python-resource-server/` | 通过 JWKS 验证 UniAuth access token 的异构示例 |
| 邮件服务参考实现 | `reference/email-service/` | 独立 REST/队列/SMTP 示例；不纳入根 Maven 构建 |

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

### 邮箱注册、密码登录与密码重置

1. 邮箱地址注册先由 `EmailAuthController` 请求验证码。
2. `EmailVerificationCodeService` 生成验证码，并通过 `EmailService` 请求外部邮件服务。
3. 只有外部服务同步返回 `SUCCESS`/`QUEUED`，后端才把 challenge 保存到
   `email_verification_codes`；拒绝、限流、超时、网络异常和空结果都失败关闭。
4. 用户提交验证码后，`verifyCode` 按已选 challenge id 和 code 做 PostgreSQL
   条件更新；注册事务随后创建用户及 `LOCAL` 登录方式并签发 JWT。controller 不再
   按 email/purpose 二次标记，避免消费验证期间新建的 challenge。
5. 已建立账户后的登录走普通本地用户名/密码流程，邮箱只是 `local_username`，
   不会在每次登录时发送验证码。
6. 密码重置复用同一邮件服务边界和验证码表，purpose 为 `PASSWORD_RESET`。

UniAuth 主应用内的 `RestTemplateEmailServiceImpl` 只是 HTTP 客户端，不直接连接
SMTP 或邮件供应商。外部服务必须提供 health、模板邮件端点、模板和约定的 JSON 响应；
仓库提供一个独立的[邮件服务参考实现](../reference/email-service/README.md)，其 schema
由独立 Flyway V1/V2/V3 管理，并通过真实 HTTP、PostgreSQL、Spring Beans 和本地 SMTP
E2E 验证。该参考实现的 `dev`、`test`、`prod` profile 均只接受独立 PostgreSQL，
非 PostgreSQL datasource 会在 Flyway 前失败。参考服务的所有邮件 API 响应还统一
禁止缓存和 MIME 嗅探。客户端使用专用
`RestTemplate`，统一应用 connect/read timeout，并可向
所有邮件服务请求各发送一个 `X-Email-Service-Key`；配置密钥时，兼容服务必须只
接受恰好一个该 header 且整值精确匹配，缺失、错误或重复同名凭据都返回 `401`，
不能选择首值或末值继续处理。类型化配置在 ApplicationContext 启动时拒绝无 host、
非 HTTP/HTTPS、含 userinfo/query/fragment 的 URL 和越界 timeout，也拒绝超过
1024 字符或包含 CR/LF 的 API key。详细契约见
[配置基线](CONFIGURATION.md#邮件服务依赖)。虽然
`VerificationPurpose.LOGIN` 和前端联合类型仍存在，当前没有受支持的邮箱验证码
无密码登录 endpoint。

当前发送流程只把外部 `success=true` 解释为“已接受/入队”，不证明最终送达。
同步拒绝、限流、超时、网络异常和空结果不会保存 challenge，也不会返回发送成功。
该流程仍不是可靠投递状态机：外部服务已经接受后，如果本地 challenge 事务失败，
用户可能收到无法验证的验证码；外部服务后续异步投递失败也不会自动撤销已保存的
challenge。解决这些窗口需要 transactional outbox 或 delivery/challenge 双状态机，
不属于当前实现。
参考服务自己的恢复 worker 只有在邮件总开关、队列和 recovery 都启用时才处理
存量，避免停用投递后定时任务继续发送。参考服务提供至少一次而非恰好一次投递：
SMTP 已接受后若数据库提交或进程失败，stuck recovery 可能使用相同 queue id 再次发送。
event 与 recovery 共用单进程限流器；reservation 只在队列 claim 未成功或 delivery
返回 `SKIPPED` 时释放。一旦调用 delivery bean 就视为一次投递尝试，即使后续 SMTP
或数据库路径失败、抛异常也会消耗当前窗口配额。reservation 绑定取得额度时的窗口
generation 并幂等释放，因此旧窗口迟到释放不会误释放新窗口额度；释放也不依赖
执行时配置开关是否仍为 enabled。
参考服务 V3 还在数据库层固定队列生命周期：终态必须有处理时间，只有 `PENDING`
可以保留下次重试时间，只有 `FAILED` 可以保留最终错误；worker claim 和所有状态转换
会清除对新状态已无意义的元数据。该约束属于参考实现内部持久化模型，不要求其他兼容
REST 服务采用相同表结构。
参考实现另提供只读、owner-only、同 PostgreSQL major 的 custom backup 工具，并在
disposable 空库中恢复后启动真实 Spring 应用验证 Flyway history、数据、约束和继续写入；
这属于参考实现运维证据，不构成生产灾难恢复或外部存储/加密承诺。

### API 认证

1. `ResourceServerConfig` 从 `Authorization: Bearer` 或 `accessToken` cookie 取 token。
2. `JwtDecoder` 使用当前 RSA 公钥验证签名、时间、issuer、audience 和 `type=access`。
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
- access/refresh Cookie 已集中到 `AuthCookieService`，prod Secure 配置有启动期
  fail-closed guard；header/cookie 双凭据、CSRF 和最终 token transport 仍待
  Batch C 原子切换。

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
- remove 和 set-primary 通过 `users.login_methods_revision` 执行用户级乐观 CAS；
  同一用户的组合并发只有一个请求取得变更权，竞争方返回 `409`。

这些约束一部分由 service 检查，一部分由数据库约束保证。顺序行为、并发 provider
绑定、并发 set-primary，以及删除/delete-primary/set-primary 组合并发已有
PostgreSQL 集成测试；Shell E2E 还会发起真实并发 HTTP 请求并验证最终不变量。

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
| `dev` | PostgreSQL | Flyway migrate/validate；Hibernate `validate` |
| `test` | PostgreSQL | Flyway migrate/validate；Hibernate `validate` |
| `prod` | PostgreSQL | Flyway migrate/validate；Hibernate `validate` |

当前问题：

- 演示数据默认关闭；显式启用时只允许 disposable test/demo 数据库并只 upsert 受管账户。
- Flyway 当前为 dev-derived V1 baseline + V2 登录方式约束 + V3 登录方式 revision
  CAS + V4 实体约束与索引对齐 + V5 Web3/SIWE challenge message 绑定；不得修改
  已发布的 V1/V2/V3/V4/V5。
- V2 已对齐登录方式的时区/nullability，并增加 provider/行形状与 primary 唯一约束；
  V3 已保护 remove/set-primary 组合并发；V4 已对齐 users、Web3 nonce、email
  verification 和 token blacklist 的目标 nullability/default/check，并补齐 email 查询
  索引、移除可证明冗余的索引。其余 schema 和数据预检仍归后续 H1.4 切片。
- V5 将 Web3 nonce 与服务端完整 SIWE message 绑定；nonce 生成采用 PostgreSQL
  upsert，验证采用带 message 和有效期条件的原子删除，V5 migration 会失效旧 challenge。
- Spring Session 表由 Flyway V1 管理，框架自动建表关闭。
- `blacksheep_dev` 已通过只读 baseline rehearsal，尚未执行 baseline apply。

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
- schema/entity 变更：同时检查 PostgreSQL migration、三个 profile、baseline guard、
  schema fingerprint、导出脚本和 Testcontainers 集成测试。
- CORS/cookie/callback 变更：检查全部安全链、YAML、前端代理和部署配置。

## 相关历史材料

- [前后端契约](FRONTEND_BACKEND_CONTRACT.md)
- [下一轮加固实施计划](drafts/NEXT_HARDENING_IMPLEMENTATION_PLAN.md)
- [多登录方式设计与实施记录](drafts/README.md#多登录方式)
- [异构资源服务器材料](drafts/README.md#异构资源服务器与微服务)
- [Perplexity 历史架构](Perplexity/01-Architecture-Design.md)
