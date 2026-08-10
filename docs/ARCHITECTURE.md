# UniAuth 当前架构

> 状态：Live
> 核验日期：2026-08-09
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
- 邮箱验证码注册、邮箱加密码登录与密码重置。
- Web3 钱包 nonce、签名验证、登录和绑定。
- 自定义 RS256 access/refresh token、持久 token family 和 refresh generation CAS。
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
3. `TokenIssuanceFacade` 在事务中创建持久 token family/session snapshot，再由
   `JwtTokenService` 从同一快照生成 access token 和 refresh token。
4. 响应统一写入 access/refresh HttpOnly Cookie；refresh token 不进入 JSON，
   access token 仅在显式 `app.auth.transport.expose-access-token=true` 时进入 JSON。
5. 普通生产前端通过 Cookie 和 canonical current-user API 恢复身份，不持久化
   access token。只有显式 diagnostics dev/E2E 模式为异构资源服务器演示把 JSON
   access token 写入 localStorage。

### OAuth2 登录

1. 浏览器访问 `/oauth2/authorization/{google|github|x}`。
2. Spring Security OAuth2 Client 完成 provider 回调。
3. `SecurityConfig.oauth2SuccessHandler` 识别登录或绑定场景。
4. `UserService.getOrCreateOAuthUser` 查找、创建或绑定登录方式。
5. `TokenIssuanceFacade` 创建或替换 token family、写入 HttpOnly Cookie，随后重定向
   到配置的前端地址；前端回调页通过 refresh/current-user API 完成状态恢复。

### 邮箱注册、密码登录与密码重置

1. `EmailAuthController` 规范化邮箱并创建 opaque challenge handle。
2. `EmailVerificationCodeService` 在同一事务中保存 HMAC digest challenge 和
   `email_delivery_outbox`，初始状态为 `PENDING_DELIVERY`。
3. outbox worker 使用稳定 idempotency key 调用外部邮件服务，并查询 delivery
   status 恢复响应丢失窗口；确认接受后 challenge 才进入 `ACTIVE`，终态失败会使其
   不可验证。
4. 用户提交 handle、canonical email、purpose 和验证码后，服务按 retry budget、
   HMAC digest 与状态做 PostgreSQL 条件消费；注册事务随后创建用户及 `LOCAL`
   登录方式并签发 JWT。
5. 已建立账户后的登录走统一 JSON credential/password policy 流程，邮箱只是
   `local_username`，
   不会在每次登录时发送验证码。
6. 密码重置复用同一邮件服务边界和验证码表，purpose 为 `PASSWORD_RESET`。

UniAuth 主应用内的 `RestTemplateEmailServiceImpl` 只是 HTTP 客户端，不直接连接
SMTP 或邮件供应商。外部服务必须提供 health、模板邮件端点、模板和约定的 JSON 响应；
仓库提供一个独立的[邮件服务参考实现](../reference/email-service/README.md)，其 schema
由独立 Flyway V1/V2/V3/V4/V5 管理，并通过真实 HTTP、PostgreSQL、Spring Beans 和本地 SMTP
E2E 验证。数据库默认使用独立 PostgreSQL；显式 `shared-uniauth` 可在获准的空
`public` schema 先启动任一侧，或与完整 UniAuth V1-V8 peer 使用独立 history table
共存。两种启动顺序由共享 advisory lock 串行化，并有真实 ApplicationContext 与
双进程 E2E。邮件组件只创建
`email_queue`、`email_logs`、对应序列/索引/约束和
`email_service_flyway_schema_history`；这些 relation 名称与 UniAuth V1-V8 无冲突。
原始兼容问题是后启动 Flyway 面对非空 `public` schema 且缺少自身 history，而不是
业务表重名。受控兼容路径只在 peer 完整、本侧 relation 不存在且 history 无失败记录
时创建 baseline V0，`baseline-on-migrate` 仍保持 `false`。非 PostgreSQL datasource 会
在 Flyway 前失败。双方 history 同时存在后，后续启动继续重新校验 peer history 和
核心 relation；peer history 必须精确匹配预期成功 SQL 版本，仅可附带 0 或 1 个成功
V0 baseline，失败、重复、未知 versioned 或 repeatable 记录均被拒绝。任一侧 relation
已经出现但对应 peer history 不存在时，也视为半成品布局并失败关闭。邮件服务必须
持续显式选择 `shared-uniauth`。参考服务的所有邮件 API 响应还统一禁止缓存和 MIME
嗅探。客户端使用专用
`RestTemplate`，统一应用 connect/read timeout，并可向
所有邮件服务请求各发送一个 `X-Email-Service-Key`；配置密钥时，兼容服务必须只
接受恰好一个该 header 且整值精确匹配，缺失、错误或重复同名凭据都返回 `401`，
不能选择首值或末值继续处理。类型化配置在 ApplicationContext 启动时拒绝无 host、
非 HTTP/HTTPS、含 userinfo/query/fragment 的 URL 和越界 timeout，也拒绝超过
1024 字符或包含 CR/LF 的 API key。详细契约见
[配置基线](CONFIGURATION.md#邮件服务依赖)。当前 verification purpose 只允许
`REGISTRATION` 和 `PASSWORD_RESET`，没有受支持的邮箱验证码无密码登录 endpoint。

外部 `success=true` 只解释为“已接受/入队”，不证明最终送达。UniAuth 已使用
transactional outbox、稳定 idempotency key 和 delivery status reconciliation
协调本地 challenge 与邮件服务接受状态；同步拒绝、限流、超时、响应丢失、重启和
终态失败均有可恢复或失败关闭语义。该状态机仍不证明真实供应商收件、退信、外部 TLS
或生产容量。
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
V5 进一步固定敏感载荷生命周期：投递前的 `PENDING`/`PROCESSING` 队列仍保留渲染
HTML；进入 `COMPLETED`/`FAILED` 后，HTML 必须替换为 `<redacted/>` 且 metadata
必须为空。`email_logs.email_content` 在所有状态下都必须为空。V5 会先规范化 V4
历史数据再建立约束；收件人、主题、错误文本和重试中队列 HTML 仍属于需要保护的
持久化数据。
参考实现另提供只读、owner-only、同 PostgreSQL major 的 custom backup 工具。该工具
在独立或共享布局下都只导出邮件队列、日志、序列和邮件 Flyway history，不会导出
UniAuth 用户/认证表；disposable 空库恢复后会启动真实 Spring 应用验证 history、
数据、约束和继续写入。这属于组件级恢复证据，不替代共享数据库的整库灾备，也不
构成生产灾难恢复或外部存储/加密承诺。

### API 认证

1. `ResourceServerConfig` 从 `Authorization: Bearer` 或 `accessToken` cookie 取 token。
2. `JwtDecoder` 使用当前 RSA 公钥验证签名、时间、issuer、audience 和 `type=access`。
3. `AuthenticationCredentialResolver` 对重复、空值、不同 token 或不同身份的
   Authorization/Cookie 凭据失败关闭。
4. `TokenValidationService` 继续核对用户 security version、token family、generation
   和撤销状态；`authorities` claim 转换为 Spring Security authority。
5. `/api/user` 和其他 `/api/**` 受资源服务器链保护；带认证 Cookie 的 unsafe 请求
   还必须提交 Session bootstrap 返回的精确单值 CSRF header。

### Python 资源服务器

Python 组件是纯 REST API，不提供资源展示页面；当前资源页面是 React
`/resource-test`。该页面与 UniAuth 登录页面属于同一个前端 origin，但 Python API
可以部署在另一个 origin。

普通生产构建不包含 `/test`、`/resource-test` 或对应诊断 bundle，后端 `prod`
profile 也不在 JSON 中暴露 access token。只有 Vite dev server 显式设置
`VITE_AUTH_DIAGNOSTICS=true`，并配合后端显式
`app.auth.transport.expose-access-token=true` 时，诊断页面才会把 JSON access token
暂存于 localStorage，并在访问 Python API 时构造
`Authorization: Bearer <token>`；请求使用 `credentials: omit`，不会携带 Cookie。
因此该跨 origin 测试依赖显式 diagnostics 模式中的 JSON/localStorage Bearer token，
不是普通生产 transport。HttpOnly Cookie 主要供 UniAuth 自身同站请求使用。

生产部署通常选择以下一种边界：

1. SPA 直接调用跨域 API：SPA 持有 access token，优先只保存在内存；refresh token
   使用 HttpOnly Cookie。
2. BFF：浏览器只持有 HttpOnly session cookie，由同域 BFF 在服务器端持有或交换
   Bearer token 并调用资源 API。

如果资源展示页面本身也位于独立域，则它不能读取 UniAuth 域的 localStorage 或
host-only Cookie；应使用 OAuth/OIDC Authorization Code + PKCE 或 BFF，而不是复制
当前演示 transport。完整拓扑、脚本和浏览器断言见
[邮箱登录跨服务浏览器 E2E](EMAIL_LOGIN_BROWSER_E2E.md)。

Python API 的验证步骤：

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
| 1 | `AuthorizationServerConfig` | JWKS、strict introspection、未支持 AS endpoint | JWKS/introspection 进入自定义边界；authorize/token/revoke deny all |
| 2 | `ResourceServerConfig` | `/api/**` | JWT Resource Server；除认证 API 外默认需要认证 |
| 3 | `SecurityConfig` | 其余请求 | OAuth2 登录、SPA/Web、CSRF 和授权规则 |

高风险边界：

- `/api/auth/**` 不经过 Resource Server 链。该空间内需要身份的接口必须自行验证，
  例如当前 Web3 bind 手工解析 bearer token。
- 四条安全链都显式启用 `CorsConfig` 提供的同一个
  `CorsConfigurationSource`；`CorsProperties` 校验 `app.cors`，MVC 层不再维护
  第二套 allowlist。
- OAuth2 成功、业务错误和 Spring failure handler 共用 `OAuth2RedirectPolicy`。
  `state.redirect_uri` 只能保留配置 allowlist 中的同源 path/query；恶意目标回退到
  主前端 base path 下的登录页，成功与失败回跳都保留配置的 context path；
  `Referer` 不作为 Session redirect 来源。
- access/refresh Cookie 已集中到 `AuthCookieService`，prod Secure 配置有启动期
  fail-closed guard；header/cookie 双凭据消歧、Session CSRF、refresh Cookie-only
  和生产诊断路由隔离已完成。

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
| `sid` | 持久 token family UUID |
| `generation` | 当前 refresh generation |
| `ver` | 用户 token security version |
| `auth_time` | 初始真实认证时间；refresh 不推进 |
| issuer | `${JWT_ISSUER:https://auth.example.com}` |
| audience | `${JWT_AUDIENCE:resource-server}`（access token） |
| `kid` | `${JWT_KID:key-1}` |

access token 默认 1 小时，refresh token 默认 7 天。
同一 pair 从一个 `TokenSessionSnapshot` 签发并共享 `sid`、generation、`ver` 和
`auth_time`。refresh 通过 PostgreSQL CAS 单次推进 generation；replay、logout、
密码重置或凭据变化可以撤销整族未知后继 token。Java Resource Server 和 strict
introspection 会查询持久 session 状态；纯 JWKS 的 Python 示例只能验证签名与 claims，
不能实时感知 PostgreSQL 撤销。

`JwtTokenService` 构造阶段读取 `jwt.rsa.key-file`。默认路径是 ignored 的
`.local/uniauth/rsa-keys.ser`，新生成文件在 POSIX 文件系统上限制为 owner read/write。
该格式仍是本地二进制文件而非生产密钥库；历史提交中的根目录私钥必须视为已暴露。
prod 禁止自动生成或使用工作目录内 key；当前只发布一个 active key/kid，紧急切换
立即使旧 token 失效，不提供双 key 兼容窗口。

## 数据持久化

| Profile | 数据库 | schema 行为 |
|---------|--------|-------------|
| `dev` | PostgreSQL | Flyway migrate/validate；Hibernate `validate` |
| `test` | PostgreSQL | Flyway migrate/validate；Hibernate `validate` |
| `prod` | PostgreSQL | Flyway migrate/validate；Hibernate `validate` |

当前约束：

- 演示数据默认关闭；显式启用时只允许 disposable test/demo 数据库并只 upsert 受管账户。
- Flyway 当前为 dev-derived V1 baseline + V2 登录方式约束 + V3 登录方式 revision
  CAS + V4 实体约束与索引对齐 + V5 Web3/SIWE challenge message 绑定 + V6 邮箱身份/
  challenge/outbox/限流/安全事件加固 + V7 token family/security version/session
  claim 加固 + V8 OAuth2 bind intent/Web3 challenge/canonical API 加固；不得修改
  已发布的 V1/V2/V3/V4/V5/V6/V7/V8。
- V2 已对齐登录方式的时区/nullability，并增加 provider/行形状与 primary 唯一约束；
  V3 已保护 remove/set-primary 组合并发；V4 已对齐 users、Web3 nonce、email
  verification 和 token blacklist 的目标 nullability/default/check，并补齐 email 查询
  索引、移除可证明冗余的索引。
- V5 将 Web3 nonce 与服务端完整 SIWE message 绑定；nonce 生成采用 PostgreSQL
  upsert，验证采用带 message 和有效期条件的原子删除，V5 migration 会失效旧 challenge。
- V6 规范化 contact email 与 email-shaped LOCAL username，分离 synthetic identity，
  退役明文 code/metadata，并增加唯一 active challenge、transactional outbox、
  PostgreSQL 认证限流和 append-only security event。
- V7 增加 `users.token_security_version` 和 `token_families`，固定 family owner、
  generation、`auth_time`、expiry、revoke 状态及查询索引。
- V8 增加 `oauth2_binding_intents`、Web3 challenge handle 与 source/global
  capacity counter，固定显式绑定、一次性消费和有界 challenge 契约。
- Spring Session 表由 Flyway V1 管理，框架自动建表关闭。
- `blacksheep_dev` 已通过只读 baseline rehearsal，尚未执行 baseline apply。

详细启动风险见 [配置基线](CONFIGURATION.md)。

## 代码所有权

| 主题 | 主要代码 |
|------|----------|
| 本地认证 | `AuthController`、`UserService`、`CustomUserDetailsService` |
| OAuth2 登录/绑定 | `SecurityConfig`、`UserService`、`LoginMethodService` |
| CORS 与 OAuth2 回跳边界 | `CorsProperties`、`CorsConfig`、`FrontendProperties`、`OAuth2RedirectPolicy` |
| JWT/session | `JwtTokenService`、`TokenIssuanceFacade`、`TokenSessionTransactionService`、`TokenController` |
| API 认证 | `ResourceServerConfig`、`AuthenticationCredentialResolver`、`TokenValidationService`、`ApiAuthController` |
| CSRF/transport | `CsrfBootstrapController`、`CsrfProtectionFilter`、`AuthCookieService`、`AuthTransportProperties` |
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
- [加固阶段最终收尾计划](drafts/FINAL_HARDENING_EXIT_PLAN.md)
- [历史下一轮加固实施计划](drafts/NEXT_HARDENING_IMPLEMENTATION_PLAN.md)
- [多登录方式设计与实施记录](drafts/README.md#多登录方式)
- [异构资源服务器材料](drafts/README.md#异构资源服务器与微服务)
- [Perplexity 历史架构](Perplexity/01-Architecture-Design.md)
