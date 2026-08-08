# UniAuth 配置基线

> 状态：Live
> 核验日期：2026-08-08
> 重要：不要在未确认数据库目标和数据可丢弃前启动 Spring 应用。

## 当前默认拓扑

| 服务 | 默认地址/端口 | 权威来源 |
|------|---------------|----------|
| Spring Boot | `http://localhost:8081` | `application.yml` |
| Vite | `http://localhost:5173` | `frontend/vite.config.ts` |
| Python 资源服务器 | `http://localhost:5002` | `python-resource-server/app.py` |
| PostgreSQL | `localhost:5432` | `POSTGRES_*` 环境变量 |
| 邮件服务 | `http://localhost:8095` | `application.yml` |

`8080`、`8082`、`5001` 和历史隧道域名仍散落在旧文档、脚本或部署示例中。
除非文件明确覆盖端口，否则它们不是当前默认值。

## 邮件服务依赖

邮箱地址注册验证和密码重置需要一个独立邮件发送服务。UniAuth 当前没有 SMTP、
JavaMailSender 或邮件供应商 SDK 实现；`RestTemplateEmailServiceImpl` 只是调用外部
服务的 HTTP 适配器。仓库中的
[邮件服务参考实现](../reference/email-service/README.md) 是独立 Maven 组件，不纳入
根构建，也不会由 UniAuth 进程自动启动；部署者仍需单独运行它或提供兼容服务。

这个依赖不是 Spring 进程的启动前置条件，但它是以下用户流程的运行前置条件：

| 流程 | 是否需要邮件服务 |
|------|------------------|
| 普通用户名/密码登录 | 否 |
| 已验证账户的邮箱加密码登录 | 否 |
| 邮箱地址首次注册和所有权验证 | 是 |
| 忘记密码和密码重置 | 是 |
| 邮箱验证码无密码登录 | 当前不支持；没有对应 endpoint |

服务地址由 `app.email.service.url` 控制，`application.yml` 的显式环境变量入口是
`EMAIL_SERVICE_URL`，默认值为 `http://localhost:8095`。Spring 标准环境变量
`APP_EMAIL_SERVICE_URL` 也可覆盖同一属性。根 Shell HTTP E2E 的正常邮箱注册/重置
路径使用该配置指向真实运行的 `reference/email-service`，并直接检查其
`email_queue`；只有为了稳定制造参考实现不会自然返回的 `503/429` 失败响应，脚本
才切换到受控 loopback REST stub。两条路径都通过真实 HTTP client 覆盖接受或失败
语义。
该值必须是带真实 host 的绝对 HTTP/HTTPS URL，禁止 userinfo、query 和 fragment；
允许非空 context path 和尾部斜杠，客户端会在其后追加 `/api/email/*`。
客户端连接和读取超时共用 `app.email.service.timeout`，通过
`EMAIL_SERVICE_TIMEOUT_MS` 设置，默认 `5000` 毫秒，有效范围为 `100..600000`
毫秒。URL 或 timeout 不符合约束时，Spring ApplicationContext 启动失败。
`EMAIL_SERVICE_API_KEY` 非空时，health、模板和简单邮件请求都会发送
一个 `X-Email-Service-Key`；外部服务必须配置相同值，并且只接受恰好一个该
header 且整值精确匹配。缺失、错误或重复同名凭据都必须返回 `401`，不能选择首值
或末值继续处理。该值最长 1024 字符且不能包含 CR/LF；不符合约束时 UniAuth 会在
ApplicationContext 启动阶段失败，而不是等到第一次构造 HTTP header 时才失败。

这里的依赖是协议契约，不只是一个 host/port。外部 RESTful 服务必须满足：

| 调用 | UniAuth 的要求 |
|------|----------------|
| `GET /api/email/health` | 返回 2xx JSON，且 `status` 精确为 `UP` |
| `POST /api/email/template` | 接收 `Content-Type: application/json` |
| 模板 | 提供 `email/email-verify` 和 `email/password-reset` |
| 模板变量 | 支持 `username`、`verificationCode`、`expiryMinutes`；请求还会同时发送 `code` |
| 成功响应 | 返回 2xx JSON `success=true`；UniAuth 将其解释为 `QUEUED` |
| 服务鉴权 | 可选共享密钥 header `X-Email-Service-Key`；配置后只接受恰好一个 header 且整值精确匹配，缺失、错误或重复同名凭据返回 `401`；值最长 1024 字符且禁止 CR/LF |
| 超时 | 调用必须在配置的 connect/read timeout 内完成；UniAuth 客户端不自动重试 |

health 响应的最小兼容形状：

```json
{
  "status": "UP"
}
```

模板邮件请求的字段和一个邮箱验证示例：

```json
{
  "to": "user@example.com",
  "subject": "Verify your email",
  "templateName": "email/email-verify",
  "variables": {
    "code": "123456",
    "verificationCode": "123456",
    "username": "user@example.com",
    "expiryMinutes": 10
  },
  "emailType": "VERIFICATION"
}
```

密码重置使用 `templateName=email/password-reset`、`emailType=PASSWORD_RESET`，变量
形状相同。响应至少需要：

```json
{
  "success": true
}
```

外部服务可以额外返回 `queueId`、`message` 等字段，但 UniAuth 当前不会保存或跟踪
这些值。非 2xx、空响应、无法解析的 JSON 或 `success` 不为 `true` 都不会被适配器
视为已接受。`/api/email/simple` 虽然存在于当前适配器接口中，但邮箱注册和密码重置
只依赖模板邮件端点。服务 URL 末尾可以有斜杠，客户端会在拼接路径前归一化。

`success=true` 只表示外部服务接受或入队，不代表 SMTP/供应商已经送达邮件。外部服务
仍需自行负责模板渲染、队列、重试、SMTP/供应商凭据和投递状态。

这些是 UniAuth 到外部 REST 服务的协议要求，并不要求兼容实现必须使用 JavaMail 或
SMTP。若外部服务继续向下游 SMTP/供应商投递，生产部署仍必须提供等价的传输安全：
加密不可静默降级、证书/主机身份必须校验、凭据不得明文跨越不可信网络。仓库参考
实现把这些要求具体化为可执行的 Java/Shell runtime guard。

外部邮件 REST 服务还必须把 `/api/email` 及其子路径视为敏感运维 API：成功、鉴权
失败、参数拒绝和路由错误响应都应设置 `Cache-Control: no-store`、
`Pragma: no-cache` 与 `X-Content-Type-Options: nosniff`。UniAuth 客户端不会读取
这些 header 来判定发送是否成功，但部署不能依赖客户端行为来允许队列、日志或邮件
相关响应进入缓存。

仓库参考实现默认监听 `127.0.0.1:8095`，并有自己的配置和数据库边界：

- 必须使用独立的 `EMAIL_POSTGRES_*` 数据库，不能复用 UniAuth 或共享数据库。
- Flyway location 是 `classpath:db/migration/postgresql`，history table 是
  `email_service_flyway_schema_history`，当前 migration 为 V1 + V2。
- Flyway 缺失 location 和非法 migration 文件命名都必须使启动失败：
  `fail-on-missing-locations=true`、`validate-migration-naming=true`。
- 所有 profile 使用 Hibernate `validate`，SQL init 关闭。
- 这些不是可被部署平台随意覆盖的建议默认值：参考实现的 Java
  `EmailServiceRuntimeGuard` 和 Shell `runtime-guard.sh` 会拒绝 Flyway disable、
  自动 baseline、clean、validation、out-of-order、缺失 location 策略或 migration
  命名校验覆盖，并拒绝 migration location/history/schema、SQL init 和 Hibernate
  schema-generation 覆盖。兼容实现也必须保持 Flyway 为唯一 schema owner。
- loopback 监听时 API key 可选；任何非 loopback 监听都必须设置
  `EMAIL_SERVICE_API_KEY`。设置后所有 `/api/email/**` 端点都要求该 header；参考
  服务同样在启动阶段拒绝超过 1024 字符或包含 CR/LF 的值，并在请求阶段拒绝
  缺失、错误或重复同名 header。反向代理或兼容实现不得通过选择重复凭据中的首值
  或末值绕过该单值要求。
- SMTP 首选 `SMTP_*` 和 `EMAIL_FROM_*` 变量；从来源 `.env` 复制的
  `SPRING_MAIL_USERNAME`、`SPRING_MAIL_PASSWORD`、`APP_MAIL_FROM_EMAIL` 仍兼容。
- 本机 `.env` 被忽略且不得提交；它不替代显式数据库、SMTP host/port 和 TLS 配置。
- `SMTP_HOST` 只接受最长 255 字符、无 URI 路径/userinfo/query/fragment、无空白或
  控制字符的 host/IP token；不要填写 `smtp://...` URL。`SMTP_PORT` 必须是
  `1..65535` 的十进制整数。Shell 入口和直接 JAR 的 Java guard 校验相同规则。
- SMTP 默认使用强制 STARTTLS：
  `SMTP_STARTTLS_ENABLE=true`、`SMTP_STARTTLS_REQUIRED=true`、
  `SMTP_SSL_ENABLE=false`、`SMTP_SSL_CHECK_SERVER_IDENTITY=true`。如果供应商要求
  implicit SSL，必须把两个 STARTTLS 变量都设为 `false` 并把 `SMTP_SSL_ENABLE`
  设为 `true`；两种模式不能同时启用。
- `prod` 拒绝明文 SMTP、可降级的 optional STARTTLS 和
  `SMTP_SSL_CHECK_SERVER_IDENTITY=false`。`dev/test` 仅为 loopback GreenMail 等
  隔离夹具允许显式明文配置；证书或主机名错误不能通过关闭身份校验绕过。
- `reference/email-service/start.sh` 会拒绝非邮件专用数据库名、非 disposable 的
  dev 数据库、权限过宽或符号链接形式的 env 文件、未受保护的非 loopback 暴露和
  非法 SMTP endpoint/transport 组合。直接运行 JAR 时 Java guard 会执行同一组
  核心校验。
- `EMAIL_RECOVERY_SCAN_INTERVAL_MINUTES` 有效范围为 `1..10080`；恢复任务只有在
  `app.mail.enabled`、`app.mail.queue.enabled` 和 `app.mail.recovery.enabled`
  同时为 true 时才会处理 pending/stuck 队列。
- 参考实现把 PostgreSQL 队列视为不受信任的持久化边界：最终 SMTP 投递前会重新
  校验 recipient、subject、HTML 上限和自定义 header token。历史数据、手工 SQL
  或异常写入不能绕过 HTTP 入队校验直接进入 MIME header。
- 拒绝非法队列载荷时，`email_logs` 只保留 queue id、通用错误和安全占位字段；
  合法内部 `sendMethod` 可用于定位来源，非法值统一记录为 `UNKNOWN`。这保证失败
  审计本身不会因字段长度或 header injection 值而回滚现有 retry 状态机。
- event 与 recovery 共用单进程限流器。取得 slot 后如果 PostgreSQL claim 返回 false
  或抛异常，必须释放 reservation；delivery 返回 `SKIPPED` 也释放。进入 delivery
  bean 后则按一次真实投递尝试计数，后续失败或异常不会归还本分钟 slot。reservation
  带有取得额度时的窗口 generation 且释放幂等；旧窗口迟到释放不能扣减新窗口计数，
  临时关闭限流也不能阻止释放此前已计数的 reservation。
- API key 配置对象、持久化实体、队列事件和 HTTP 请求 DTO 不生成包含 API key、
  收件人、验证码或 HTML 的自动 `toString()`；日志和异常仍需遵守同一脱敏边界。

完整启动、Flyway 和验证说明见
[邮件服务参考实现 README](../reference/email-service/README.md)。

当前实现还有几个必须显式知晓的限制：

- 邮件服务同步返回失败、限流、非法邮箱，或发生超时、网络异常、空结果时，UniAuth
  不保存 challenge，并让注册发送或密码重置发送请求失败关闭。
- 外部服务已经返回接受后，如果 UniAuth 本地 challenge 保存事务失败，邮件可能已经
  入队，但收件人拿到的验证码无法使用；当前没有 outbox 或补偿协议关闭这个窗口。
- 外部服务后续异步投递失败不会自动撤销已保存的 challenge，UniAuth 也不保存
  `queueId` 或跟踪最终 delivery 状态。
- 参考服务的队列恢复是至少一次语义；SMTP 已接受后若数据库提交失败、进程崩溃或
  stuck 记录被 recovery worker 重新领取，可能重复发送同一 queue id 的邮件。

因此，生产启用邮箱流程前不能只检查 UniAuth 接口返回值；必须另外验证外部服务
可达、模板存在、SMTP/供应商凭据有效，并完成一条显式 opt-in 的真实收件测试。

## Spring Profiles

`application.yml` 不设置 `spring.profiles.active`。直接运行 Maven 时必须显式选择
`dev`、`test` 或 `prod`；根启动脚本默认选择 `dev`，但不会提供数据库回退。

| Profile | 数据库 | 启动时 SQL/Hibernate | 数据风险 |
|---------|--------|----------------------|----------|
| `dev` | 必须显式提供 PostgreSQL 五项连接变量 | Flyway；SQL init never；`ddl-auto: validate` | 只接受 dev/test/demo 命名目标 |
| `test` | 必须显式提供 PostgreSQL 五项连接变量 | Flyway；SQL init never；`ddl-auto: validate` | 只接受 disposable test/demo 目标 |
| `prod` | 必须显式提供 PostgreSQL 五项连接变量 | Flyway；SQL init never；`ddl-auto: validate` | 目标和备份由部署流程确认 |

自动化 Java 测试通过 Testcontainers 动态注入连接，不读取仓库 `.env`。

## 认证与 Session Cookie

access/refresh Cookie 由 `AuthCookieService` 统一写入和清除：

- `HttpOnly=true`
- `Path=/`
- `SameSite=Lax`
- `Max-Age` 分别来自 `jwt.expires.access-token` 和
  `jwt.expires.refresh-token`
- `app.auth.cookie.secure` 在 base/dev/test 默认为 `false`，`prod` 为 `true`

Spring Session Cookie 使用 Boot 3.3.4 实际绑定的
`server.servlet.session.cookie.*`，而不是无效的 `spring.session.cookie.*`。
base 配置固定 `JSESSIONID`、`HttpOnly=true`、`Path=/`、`SameSite=Lax`；
`application-prod.yml` 额外设置 `Secure=true`。

`prod` profile 有启动期 fail-closed guard。即使环境变量或命令行等高优先级配置把
`app.auth.cookie.secure` 或 `server.servlet.session.cookie.secure` 覆盖为
`false`，ApplicationContext 也会拒绝启动。该保护不替代 TLS 终止、可信代理和
`Forwarded` header 配置核验。

## 数据初始化

三个 profile 使用同一 PostgreSQL migration 链：

- Flyway location：`classpath:db/migration/postgresql`
- history table：`uniauth_flyway_schema_history`
- 当前版本：V5（V1 baseline + V2 登录方式约束 + V3 登录方式 revision CAS +
  V4 实体约束与索引对齐 + V5 Web3/SIWE challenge message 绑定）
- `baseline-on-migrate=false`
- `clean-disabled=true`
- SQL init：`never`
- Hibernate：`validate`
- Spring Session JDBC init：`never`

Spring Session 两张表已进入 V1，不再由框架或部署脚本旁路创建。

### 演示数据

旧的 profile 初始化器已删除。`DemoDataInitializer` 只有在以下条件同时成立时才加载：

- profile 是 `dev` 或 `test`；
- `app.demo-data.enabled=true`；
- `app.demo-data.disposable=true`；
- JDBC 数据库名符合 test/demo 安全规则。

初始化器只 upsert `testlocal`、`testsso`、`testboth` 三个受管账户，不执行 `deleteAll()`。

### Migration 目录

Flyway 只扫描 `src/main/resources/db/migration/postgresql/`。历史 V1-V4、V6-V8
和四份 SQL init 文件已归档到 `docs/archive/database/legacy-sql/`，不能执行或复制回
runtime classpath。

V1 来自获准的实际 dev PostgreSQL 8 表结构。V2 加固登录方式时区/nullability、
provider/行形状和 primary 唯一性。V3 增加非负的用户级
`login_methods_revision`，供登录方式删除和 primary 切换使用乐观 CAS。V4 对齐
users、Web3 nonce、email verification 和 token blacklist 的目标约束/default，
增加 email repository 索引并删除有等价覆盖的重复索引。

V5 将 `web3_nonces.message` 设为必填，并在写入时保存服务端签发的完整 SIWE message。
迁移会删除 V1-V4 期间无法安全重建的旧未消费 nonce；此表只保存短期 challenge，
失效旧 challenge 不改变用户、登录方式或已完成认证数据。生成 nonce 使用 PostgreSQL
原子 upsert，验证使用 nonce、message 和有效期条件删除，只有一条并发请求可以消费。
后续结构修复从 V6 开始；不得改写 V1/V2/V3/V4/V5 checksum。

## Existing-schema baseline

`scripts/flyway-baseline-existing.sh rehearse` 默认只读源库，并在 disposable PostgreSQL
中完成 restore、baseline、fresh migrate、validate 和结构指纹比较。

对真实库执行 `apply` 需要：

- 非生产数据库名保护。
- rehearsal 成功。
- 精确匹配本次结构指纹的 `UNIAUTH_BASELINE_CONFIRM`。
- apply 写入前再次确认源 schema 指纹、V2 登录方式数据预检、V4 实体契约预检均未
  变化，且仍不存在 Flyway history table。
- apply 创建 baseline history、执行 pending migrations，并确认最终结构与 rehearsal
  中的 fresh 最新迁移结果一致。
- 如果 baseline 创建后 pending migration 失败，脚本只会在受管 schema 未变化且
  history 精确为本次 baseline-only 状态时删除不完整 history；其他状态保留现场并
  要求人工恢复，避免把部分迁移伪装成未接管。
- 用户对该次 apply 的显式授权。

`blacksheep_dev` 当前只完成 rehearsal，尚未创建 Flyway history。

## OAuth2 与前端地址

当前 `application.yml` 包含部署环境硬编码：

- `app.frontend.url`
- Google/GitHub/X redirect URI
- Web3 domain
- CORS allowed origins

`application-test.yml` 还对不同 provider 使用了不同部署域名。

本地 OAuth2 流程必须显式覆盖这些值，并确保 provider 控制台注册的 callback
与 `/oauth2/callback` 一致。不要把仓库内历史域名当作可复用默认值。

## CORS

CORS 来源目前由多处共同定义：

- `application.yml` 的 `app.cors`
- `CorsConfig`
- `WebConfig`
- `WebMvcConfig`
- Python `app.py`

这些列表已经发生漂移。修改 origin、method、header 或 credentials 时必须同时审查，
后续加固应收敛为单一配置来源。

## JWT 与密钥

| 配置 | 当前值/行为 |
|------|-------------|
| 算法 | RS256 |
| key file 配置 | `${JWT_RSA_KEY_FILE:.local/uniauth/rsa-keys.ser}` |
| 实际构造加载 | 构造阶段读取 `jwt.rsa.key-file` |
| issuer | `https://auth.example.com` |
| audience | `resource-server` |
| key id | `key-1` |

敏感文件和变量：

- `.env`
- `jwt-secret.key`
- `.local/uniauth/rsa-keys.ser`
- OAuth2 client secret
- PostgreSQL password

`.env`、数据库、`jwt-secret.key` 和本地 RSA key 被忽略。历史提交中的根目录
`rsa-keys.ser` 已暴露，不能继续用于真实环境；生产部署必须显式配置外部 key 路径并轮换。

## 前端构建

Vite：

- 开发端口 `5173`。
- `/api`、`/oauth2` 代理到 `http://localhost:8081`。
- build 输出到 `../src/main/resources/static`。
- build 会清空并重建输出目录。

只编辑 `frontend/src/**`，不要手工修改静态构建产物。

## Python 资源服务器

当前代码：

- 监听 `5002`。
- `AUTH_SERVER_URL` 默认 `http://localhost:8081`，可用 `JWKS_URL` 覆盖 JWKS。
- HTTPS 使用 `requests` 默认的证书验证。
- `JWT_ISSUER`、`JWT_AUDIENCE`、`RESOURCE_SERVER_PORT` 和 CORS origins 均可由环境变量覆盖。
- JWT 只接受 RS256，并要求精确匹配 `kid`。

详细运行和离线测试命令见组件 README。

## 配置优先级

判断当前事实时按以下顺序：

1. 实际代码和 Spring/Vite/Flask 配置。
2. 显式环境变量或启动参数。
3. 本页和其他 live guide。
4. 组件 README。
5. `docs/drafts/`、`docs/Perplexity/` 和历史验证记录。

## 更新触发条件

下列改动必须同步更新本页：

- 默认端口或代理。
- profile 默认值或数据库 URL。
- SQL init、Hibernate、迁移工具。
- OAuth2 callback、前端地址或 CORS。
- JWT issuer、audience、kid、密钥来源或 token 时长。
- 邮件服务和 Python 资源服务器配置方式。
