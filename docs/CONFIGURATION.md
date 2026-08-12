# UniAuth 配置基线

> 状态：Live
> 核验日期：2026-08-10
> 重要：不要在未确认数据库目标和数据可丢弃前启动 Spring 应用。

## 当前默认拓扑

| 服务 | 默认地址/端口 | 权威来源 |
|------|---------------|----------|
| Spring Boot | `http://localhost:8081` | `application.yml` |
| Vite | `http://localhost:5173` | `frontend/vite.config.ts` |
| Python 资源服务器 | `http://localhost:5002` | `python-resource-server/app.py` |
| PostgreSQL | `localhost:5432` | `POSTGRES_*` 环境变量；自动化固定 16.13 |
| 邮件服务 | `http://localhost:8095` | `application.yml` |

`8080`、`8082`、`5001` 和历史隧道域名仍散落在旧文档、脚本或部署示例中。
除非文件明确覆盖端口，否则它们不是当前默认值。

## OAuth provider HTTP 边界

OAuth authorization-code token 交换、Spring 标准 provider user-info 请求以及
GitHub/X 的补充 profile 请求都使用 `app.oauth2.http` 下的显式超时：

| 属性 | 环境变量 | 默认值 | 有效范围 |
|------|----------|--------|----------|
| `connect-timeout-ms` | `OAUTH2_HTTP_CONNECT_TIMEOUT_MS` | `5000` | `100..60000` |
| `read-timeout-ms` | `OAUTH2_HTTP_READ_TIMEOUT_MS` | `10000` | `100..60000` |

token endpoint 使用 OAuth2 form/JSON 专用转换器和错误处理器，但与 user-info client
共享上述 timeout 边界。超时配置不合法时 ApplicationContext 启动失败；客户端不对
authorization code、provider token 或 user-info 请求执行盲重试。生产部署仍必须
使用 provider 的 HTTPS endpoint 和 JVM 信任链校验，测试只通过 loopback 慢响应及
合成成功响应验证连接、读取和解析契约。

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
`EMAIL_SERVICE_API_KEY` 非空时，UniAuth 对 health、模板和 delivery status 请求都会
发送一个 `X-Email-Service-Key`；外部服务必须配置相同值，并且只接受恰好一个该
header 且整值精确匹配。缺失、错误或重复同名凭据都必须返回 `401`，不能选择首值
或末值继续处理。该值最长 1024 字符且不能包含 CR/LF；不符合约束时 UniAuth 会在
ApplicationContext 启动阶段失败，而不是等到第一次构造 HTTP header 时才失败。

这里的依赖是协议契约，不只是一个 host/port。外部 RESTful 服务必须满足：

| 调用 | UniAuth 的要求 |
|------|----------------|
| `GET /api/email/health` | 返回 2xx JSON，且 `status` 精确为 `UP` |
| `POST /api/email/template` | 接收 `Content-Type: application/json` 和稳定 `idempotencyKey` |
| `GET /api/email/delivery/status?idempotencyKey=...` | 返回同一幂等请求的最小 queue/delivery 状态 |
| 模板 | 提供 `email/email-verify` 和 `email/password-reset` |
| 模板变量 | 支持 `username`、`verificationCode`、`expiryMinutes`；请求还会同时发送 `code` |
| 成功响应 | 返回 2xx JSON `success=true` 和稳定 `queueId`；UniAuth 将其解释为已接受/入队 |
| 幂等冲突 | 相同 key 与相同渲染请求返回同一 queue identity；相同 key 对应不同请求返回 `409` |
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
  "emailType": "VERIFICATION",
  "idempotencyKey": "email-challenge:opaque-handle"
}
```

密码重置使用 `templateName=email/password-reset`、`emailType=PASSWORD_RESET`，变量
形状相同。响应至少需要：

```json
{
  "success": true
}
```

外部服务可以额外返回 `message` 等字段。UniAuth 会把稳定 `queueId` 保存为 provider
delivery identity，并在响应丢失或进程重启后通过 delivery status 查询恢复确认状态。
非 2xx、空响应、无法解析的 JSON 或 `success` 不为 `true` 都不会被适配器视为已接受。
`/api/email/simple` 虽然存在于当前适配器接口中，但邮箱注册和密码重置只依赖模板邮件
端点。服务 URL 末尾可以有斜杠，客户端会在拼接路径前归一化。

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

- `EMAIL_DATABASE_LAYOUT` 默认 `dedicated`，要求 `EMAIL_POSTGRES_*` 指向邮件专用
  PostgreSQL 16 数据库。只有显式设置 `shared-uniauth` 时才允许复用获准的 UniAuth
  数据库：目标 `public` schema 可以为空，由任一侧先迁移；若已存在 peer，则必须是
  完整且 history 精确的 UniAuth V1-V8 或邮件 V1-V5。两侧使用独立 Flyway history
  table 和 advisory-lock 串行化。`blacksheep*`、系统库、未知 layout、H2 和其他
  非 PostgreSQL datasource 均在 Flyway 前拒绝。
- 邮件侧业务 relation 是 `email_queue`、`email_logs` 及其序列/索引/约束，与
  UniAuth V1-V8 的表、序列和索引名称没有冲突。不能据此直接移除迁移保护：后启动
  Flyway 仍会遇到“schema 非空但缺少自身 history”的启动冲突。
- Flyway location 是 `classpath:db/migration/postgresql`，history table 是
  `email_service_flyway_schema_history`，当前 migration 为 V1 + V2 + V3 + V4 + V5。
- UniAuth history 是 `uniauth_flyway_schema_history`。共享布局中，后启动一侧只有在
  对端核心 relation 和 history 精确匹配、本侧 managed relation 不存在时，才在双方
  共用的 PostgreSQL advisory lock 内创建 baseline V0 并继续 migrate。peer history
  必须恰好包含当前预期的成功 SQL 版本，另只允许 0 或 1 个成功 V0 baseline；
  失败、重复、未知 versioned 或 repeatable 记录均被拒绝。存在 peer relation 却
  没有 peer history 时视为半成品布局并失败关闭。`baseline-on-migrate=false`、
  `baseline-version=0` 均为不可覆盖的运行契约，不允许对任意非空 schema 自动
  baseline。
- V3 规范化历史队列元数据并约束状态行形状：终态必须有 `processed_time`，只有
  `PENDING` 可以保留 `next_retry_time`，只有 `FAILED` 可以保留
  `error_message`。该要求是参考实现的数据库契约，不是外部 REST 协议的表结构要求。
- V4 增加幂等 delivery identity。V5 清空历史 `email_logs.email_content`，并将历史
  `COMPLETED`/`FAILED` 队列的 HTML 替换为 `<redacted/>`、清空 metadata；数据库约束
  阻止后续日志保存 HTML 或终态队列保留实际载荷。`PENDING`/`PROCESSING` 必须继续
  保留渲染 HTML，供真实投递和重试使用。
- Flyway 缺失 location 和非法 migration 文件命名都必须使启动失败：
  `fail-on-missing-locations=true`、`validate-migration-naming=true`。
- 所有 profile 使用 Hibernate `validate`，SQL init 关闭。
- 这些不是可被部署平台随意覆盖的建议默认值：参考实现的 Java
  `EmailServiceRuntimeGuard` 和 Shell `runtime-guard.sh` 会拒绝 Flyway disable、
  自动 baseline、clean、validation、out-of-order、缺失 location 策略或 migration
  命名校验覆盖，并拒绝 migration location/history/schema、SQL init 和 Hibernate
  schema-generation 覆盖；Java guard 还会在 Flyway 前拒绝非 PostgreSQL JDBC URL。
  兼容实现也必须保持 Flyway 为唯一 schema owner。
- UniAuth 根应用同样把 `spring.flyway.enabled=true` 作为不可覆盖契约；自定义
  migration strategy 和 `scripts/runtime-guard.sh` 都会拒绝关闭 Flyway，避免绕过
  migration、peer 校验和 Hibernate `validate` 前置条件。
- loopback 监听时 API key 可选；任何非 loopback 监听都必须设置
  `EMAIL_SERVICE_API_KEY`。设置后所有 `/api/email/**` 端点都要求该 header；参考
  服务同样在启动阶段拒绝超过 1024 字符或包含 CR/LF 的值，并在请求阶段拒绝
  缺失、错误或重复同名 header。反向代理或兼容实现不得通过选择重复凭据中的首值
  或末值绕过该单值要求。
- SMTP 首选 `SMTP_*` 和 `EMAIL_FROM_*` 变量；从来源 `.env` 复制的
  `SPRING_MAIL_USERNAME`、`SPRING_MAIL_PASSWORD`、`APP_MAIL_FROM_EMAIL` 仍兼容。
- 本机 `.env` 被忽略且不得提交；它不替代显式数据库、SMTP host/port 和 TLS 配置。
- 参考实现的 `scripts/backup-postgres.sh` 不隐式读取 `.env`；它要求显式 profile、
  `EMAIL_POSTGRES_*` 和绝对 `EMAIL_BACKUP_DIR`，或显式 owner-only env 文件。
  输出目录不得是符号链接或向 group/other 开放，archive/checksum 为 `0600`。
- `backup-postgres.sh` 支持 `dedicated` 和显式 `shared-uniauth`，但固定只导出
  `email_queue`、`email_logs`、对应序列和
  `email_service_flyway_schema_history`。共享库中的 UniAuth 用户/认证表不会进入
  组件 archive；history 必须精确匹配当前 V1-V5 链，共享布局另允许至多一个 V0
  baseline，未知或额外 migration 会失败关闭。共享数据库的整库备份与恢复仍必须
  由仓库外、经授权的运维流程负责。archive 仍可能包含收件人、主题、错误文本和
  待投递/重试队列的 HTML 或验证码；V5 只保证投递日志 HTML 为空、终态队列载荷脱敏。
- `pg_dump` 和 `pg_restore` major 必须与源 PostgreSQL major 一致；可通过
  `EMAIL_PG_DUMP_BIN`、`EMAIL_PG_RESTORE_BIN` 选择正确客户端。默认 restore
  自动化只在 disposable 空库中执行，不覆盖现有数据库。
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
- `reference/email-service/start.sh` 会按 `EMAIL_DATABASE_LAYOUT` 拒绝不匹配的
  数据库目标，并拒绝非 disposable 的 dev 数据库、权限过宽或符号链接形式的 env
  文件、未受保护的非 loopback 暴露和非法 SMTP endpoint/transport 组合。直接运行
  JAR 时 Java guard 会执行同一组核心校验。
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
  不会激活 challenge，并让注册发送或密码重置发送请求失败关闭。
- UniAuth 使用 transactional outbox、稳定 idempotency key 和 delivery status
  reconciliation 关闭“外部已接受但本地未确认”的响应丢失/重启窗口；provider
  `queueId` 会保存为 delivery identity。
- 外部服务后续异步投递进入终态失败时，reconciler 会使 challenge 不可验证；但该
  结论仍依赖兼容邮件服务准确提供 delivery status。
- 参考服务只在 template 请求提供 idempotency key 时执行去重；UniAuth challenge 总是
  提供稳定 key，但其他不带 key 的 template 调用以及 simple/batch 端点仍可能重复入队。
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
- 当前版本：V8（V1 baseline + V2 登录方式约束 + V3 登录方式 revision CAS +
  V4 实体约束与索引对齐 + V5 Web3/SIWE challenge message 绑定 +
  V6 邮箱身份/challenge/outbox/限流/安全事件加固 +
  V7 token family/security version/session claim 加固 +
  V8 OAuth2 bind intent/Web3 challenge/canonical API 加固）
- `fail-on-missing-locations=true`
- `baseline-on-migrate=false`
- `baseline-version=0`
- `clean-disabled=true`
- `validate-migration-naming=true`
- `validate-on-migrate=true`
- `out-of-order=false`
- SQL init：`never`
- Hibernate：`validate`
- Spring Session JDBC init：`never`

Spring Session 两张表已进入 V1，不再由框架或部署脚本旁路创建。
UniAuth 的 migration strategy 会在执行 migration 前拒绝上述关键 Flyway 配置被
高优先级配置覆盖。共享 schema 中一旦存在邮件服务 history，后续启动会重新核对其
V1-V5 history 和核心 relation。

### 演示数据

旧的 profile 初始化器已删除。`DemoDataInitializer` 只有在以下条件同时成立时才加载：

- profile 是 `dev` 或 `test`；
- `app.demo-data.enabled=true`；
- `app.demo-data.disposable=true`；
- JDBC 数据库名符合 test/demo 安全规则。

初始化器只 upsert `testlocal`、`testsso`、`testboth` 三个受管账户，不执行 `deleteAll()`。

### 初始化管理员账号

系统默认不会创建固定的 `admin` 账号，也不会内置可猜测的默认密码。需要在系统
初始化时提供一个可用的用户名/密码管理员账号时，必须显式设置：

| 配置 | 环境变量 | 默认值 | 说明 |
|------|----------|--------|------|
| `app.bootstrap-admin.enabled` | `APP_BOOTSTRAP_ADMIN_ENABLED` | `false` | 显式启用初始化 |
| `app.bootstrap-admin.username` | `APP_BOOTSTRAP_ADMIN_USERNAME` | 空 | 本地登录用户名；邮箱形式必须与 email 相同 |
| `app.bootstrap-admin.email` | `APP_BOOTSTRAP_ADMIN_EMAIL` | 空 | 管理员邮箱 |
| `app.bootstrap-admin.password` | `APP_BOOTSTRAP_ADMIN_PASSWORD` | 空 | 必须通过当前密码策略 |
| `app.bootstrap-admin.display-name` | `APP_BOOTSTRAP_ADMIN_DISPLAY_NAME` | `Administrator` | 显示名称 |

初始化器只在账号不存在时创建 `ROLE_USER + ROLE_ADMIN` 的 `LOCAL` 登录方式；
重复启动不会覆盖现有密码。已存在但身份、管理员权限或本地凭据不完整时启动
失败关闭，避免静默接管错误账号。初始化密码应在首次登录后通过前端“修改密码”
或 `PUT /api/user/password` 更换为组织要求的强密码。

登录后修改密码要求当前 JWT 的 `auth_time` 在 recent-auth 窗口内，并验证当前密码、
新密码策略和确认字段。成功后递增用户 token security version、撤销所有 token family、
清理认证 Cookie，因此旧 access/refresh token 不能继续使用；用户必须用新密码重新登录。

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

V6 增加 `email_identity_type`、canonical contact/LOCAL username 约束，退役
`email_verification_codes` 的明文 code、JSON metadata 和旧 `is_used` 状态，改用
HMAC digest、delivery/usage 状态和唯一 active challenge；同时新增
`email_delivery_outbox`、`auth_rate_limits` 和 append-only `security_events`。
V6 迁移会失效 pre-F1 旧 challenge，不能回滚旧应用继续写明文状态。

V7 增加 `users.token_security_version` 和 `token_families`，固定 family owner、
generation、`auth_time`、expiry、revoke 状态和查询索引；新 token 的 `sid`、
`generation`、`ver`、`auth_time` 与该持久状态共同验证。

V8 增加 `oauth2_binding_intents`、Web3 challenge handle 和 source/global capacity
counter，并固定显式 OAuth2 绑定、精确 challenge 消费与唯一冲突语义。

后续结构修复从 V9 开始；不得改写 V1/V2/V3/V4/V5/V6/V7/V8 checksum。

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

Google、GitHub 和 X 共用同一个 provider callback 配置：

| 环境变量 | 当前行为 |
|----------|----------|
| `OAUTH2_CALLBACK_URI` | provider 控制台注册的后端 callback；`dev`/`test` 默认 `http://localhost:8081/oauth2/callback`，`prod` 必填 |
| `APP_FRONTEND_URL` | OAuth2 成功回跳和错误回退的主前端地址；`dev`/`test` 默认 `http://localhost:5173`，`prod` 必填 |
| `APP_FRONTEND_ALLOWED_REDIRECT_ORIGINS` | 可选、逗号分隔的额外精确 HTTP(S) origin；仅用于允许 OAuth2 错误回跳保留受信 origin 上的 path/query |

`APP_FRONTEND_URL` 必须是带 host、无 userinfo/query/fragment/控制字符的绝对
HTTP(S) URL，可以包含部署 context path。成功回跳使用该 base URL，错误回退使用
该 base path 下的 `/login`；两条路径都不会丢弃配置的 context path。额外 redirect
origin 必须是无 path/query/fragment 的精确 origin。OAuth2 成功处理器、业务错误
处理器和 Spring failure handler 共用 `OAuth2RedirectPolicy`；跨 origin、userinfo、
错误 scheme、空/越界 port 或控制字符形式的 `state.redirect_uri` 会回退到上述登录页。
授权请求不再把 `Referer` 保存为 Session 中的前端来源。

`app.web3.domain`、JWT issuer/audience/kid 与 provider callback 是独立部署配置，
prod 必须全部显式提供。仓库不再注册 Spring Authorization Server 内存 client；
未支持的 authorize/token/revoke endpoint deny all。

## CORS

UniAuth 后端只有一个 CORS 解释路径：

- `CorsProperties` 绑定并校验 `app.cors`。
- `CorsConfig` 创建唯一的 `CorsConfigurationSource`。
- 四条有序 Spring Security filter chain 都显式启用该 source。
- `WebConfig` 不定义 CORS，旧 `WebMvcConfig` 已删除。

`CORS_ALLOWED_ORIGINS` 是逗号分隔的精确 HTTP(S) origin 列表。`dev`/`test`
默认只允许 `http://localhost:5173`；`prod` 必须显式提供。带凭据时禁止 wildcard，
origin 不得包含 userinfo、path、query、fragment 或控制字符。methods、headers、
exposed headers、credentials 和 max-age 的基线仍位于 `application.yml` 的
`app.cors`。

Python 资源服务器是独立 bearer-only 服务，在 `python-resource-server/app.py`
中维护自己的 CORS 配置；它不是 UniAuth Spring 应用的第二个 CORS 来源。

## JWT 与密钥

| 配置 | 当前值/行为 |
|------|-------------|
| 算法 | RS256 |
| key file 配置 | `${JWT_RSA_KEY_FILE:.local/uniauth/rsa-keys.ser}` |
| 实际构造加载 | 构造阶段读取 `jwt.rsa.key-file` |
| issuer | `${JWT_ISSUER:https://auth.example.com}` |
| audience | `${JWT_AUDIENCE:resource-server}` |
| key id | `${JWT_KID:key-1}` |

敏感文件和变量：

- `.env`
- `jwt-secret.key`
- `.local/uniauth/rsa-keys.ser`
- OAuth2 client secret
- PostgreSQL password

`.env`、数据库、`jwt-secret.key` 和本地 RSA key 被忽略。历史提交中的根目录
`rsa-keys.ser` 已暴露，不能继续用于真实环境；生产部署必须显式配置仓库外绝对 key
路径、关闭自动生成并轮换。当前只支持一个 active key/kid，紧急切换会立即拒绝旧
token 并要求重新认证；不支持双 key 无感 rollover。

## 生产配置与 HTTP 边界

`prod` profile 的启动 guard 要求：

- frontend、CORS、邮件服务、JWT issuer 和 provider callback 使用非本地 HTTPS；
  Web3 使用非保留 host。
- JWT audience/kid、introspection client、验证码 HMAC key id 和 provider client
  使用非 placeholder 标识。
- 数据库、限流、introspection、邮件服务和验证码 HMAC secret 至少 32 字符且互不相同；
  provider client secret 至少 12 字符。
- `JWT_RSA_KEY_FILE` 是工作目录外的绝对路径，文件已存在且为 owner-only；
  `jwt.rsa.generate-if-missing=false`。
- diagnostics/access-token JSON 暴露、Swagger/OpenAPI 均关闭。
- `server.forward-headers-strategy=none`、header 上限 `16KB`、form/swallow 上限
  `1MB`。

应用不信任客户端提交的 `Forwarded`、`X-Forwarded-*` 或 `X-Real-IP`。反向代理必须
清除外部同名 header，并通过受控网络连接后端；canonical redirect 和 Secure Cookie
由显式生产配置决定。相关部署/恢复流程见[运维基线](OPERATIONS.md)。

公开健康探针是 `/actuator/health/liveness` 和
`/actuator/health/readiness`。readiness 包含数据库、Flyway 和 signing key/kid，
但响应不公开组件、JDBC URL、异常或 key 信息。

## 前端构建

Vite：

- 开发端口 `5173`。
- `/api`、`/oauth2` 默认代理到 `http://localhost:8081`，可通过
  `VITE_DEV_PROXY_TARGET` 覆盖。
- dev proxy 将请求 `Origin` 重写为实际 backend origin，使动态 Vite 端口保持同源
  代理语义；不要以接受任意随机 origin 的方式放宽生产 CORS。
- `VITE_AUTH_DIAGNOSTICS=true` 只在 Vite dev server 中启用 `/test`、
  `/resource-test` 和对应诊断 bundle；生产构建始终排除它们。
- `VITE_RESOURCE_SERVER_URL` 控制 diagnostics `/resource-test` 调用的 Python API，
  默认 `http://localhost:5002`。
- build 输出到 `../src/main/resources/static`。
- build 会清空并重建输出目录。

只编辑 `frontend/src/**`，不要手工修改静态构建产物。

## Python 资源服务器

当前代码：

- 监听 `5002`。
- 只提供 REST API；资源展示页面是 React `/resource-test`，不由 Flask 提供。
- `AUTH_SERVER_URL` 默认 `http://localhost:8081`，可用 `JWKS_URL` 覆盖 JWKS。
- HTTPS 使用 `requests` 默认的证书验证。
- `JWT_ISSUER`、`JWT_AUDIENCE`、`RESOURCE_SERVER_PORT` 和 CORS origins 均可由环境变量覆盖。
- JWT 只接受 RS256，并要求精确匹配 `kid`。

显式 diagnostics 跨 origin 演示依赖 dev/test 后端在登录/注册 JSON 中返回的 access
token。只有同时启用 `VITE_AUTH_DIAGNOSTICS=true` 时，前端才把它写入 localStorage
并构造 Bearer header；HttpOnly access-token Cookie 不能被 JavaScript 读取，也不会在
不同 host 的 Python API 上替代该 header。普通生产构建不包含诊断路由/bundle，后端
`prod` 也固定 `app.auth.transport.expose-access-token=false`。该 diagnostics
localStorage 路径会扩大 XSS 风险，不是生产 transport。生产 SPA 应优先把 access
token 仅保存在内存并使用 HttpOnly refresh cookie；或采用 BFF，使浏览器只持有
HttpOnly session cookie。

详细运行和离线测试命令见组件 README；真实跨服务邮箱登录验证见
[邮箱登录浏览器 E2E](EMAIL_LOGIN_BROWSER_E2E.md)。

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
