# UniAuth Email Service Reference

> 状态：Reference，非生产就绪组件。
> 来源：`Blacksheep-API/src/email-service`，复制自仓库提交
> `77bb89e8a607504bd3f39e6e646c0948fb54270f`。
> 纳入日期：2026-08-07。

本目录提供 UniAuth 所依赖邮件 REST 接口的可运行参考实现。它使用 Spring Boot、
Thymeleaf、PostgreSQL 和 `JavaMailSender`，把模板渲染后的邮件写入数据库队列，
再通过异步事件和定时恢复任务发送。

它的用途是：

- 固化 UniAuth 与外部邮件服务之间的 HTTP 和模板契约。
- 提供本地集成、接口测试和替换其他邮件供应商时的参考。
- 展示队列、重试、限流和 SMTP 发送的基本结构。

它不是：

- UniAuth 根 Maven 工程的一部分。
- 经过生产安全、容量、多实例或灾难恢复验证的邮件平台。
- 会由 UniAuth 根应用自动启动的内嵌模块。
- 默认验证中会连接真实 SMTP 或向外部收件人发送邮件的服务。

## UniAuth 依赖关系

UniAuth 的 `RestTemplateEmailServiceImpl` 默认访问
`http://localhost:8095`。邮箱地址首次注册和密码重置需要本服务或一个兼容实现；
已经建立账户后的邮箱加密码登录不需要调用邮件服务。

UniAuth 侧还有两个运行参数：

- `EMAIL_SERVICE_TIMEOUT_MS`：connect/read timeout，默认 `5000` 毫秒，有效范围
  `100..600000` 毫秒。
- `EMAIL_SERVICE_API_KEY`：可选共享密钥；非空时所有请求携带
  一个 `X-Email-Service-Key`，本服务必须配置相同值；最长 1024 字符且不能包含
  CR/LF。配置后服务只接受恰好一个该 header 且整值精确匹配，缺失、错误或重复
  同名凭据均返回 `401`。

`EMAIL_SERVICE_URL` 必须是带 host 的绝对 HTTP/HTTPS URL，禁止 userinfo、query
和 fragment。允许 context path 和尾部斜杠；UniAuth 会归一化尾斜杠后追加
`/api/email/*`。这些约束在 UniAuth ApplicationContext 启动时校验。

UniAuth 只依赖以下最小契约：

| 方法和路径 | 要求 |
|------------|------|
| `GET /api/email/health` | 返回 2xx JSON，`status` 精确为 `UP` |
| `POST /api/email/template` | 接收模板邮件 JSON，请求成功时返回 2xx JSON `success=true` |

邮箱验证请求示例：

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

密码重置使用：

- `templateName`: `email/password-reset`
- `emailType`: `PASSWORD_RESET`
- 相同的 `username`、`verificationCode`、`expiryMinutes` 变量

兼容成功响应的最小形状：

```json
{
  "success": true
}
```

当前实现还会返回 `queueId` 和 `message`。UniAuth 不保存 `queueId`，并且把
`success=true` 解释为“已接受/入队”，不是“邮件已送达”。

客户端不自动重试邮件服务请求。非 2xx、超时、空响应、不可解析 JSON 或
`success != true` 都映射为失败。UniAuth 只有在本接口同步接受后才保存验证码
challenge；拒绝、限流和网络失败不会留下可验证记录。该顺序仍存在一个明确窗口：
本服务已经接受或入队后，如果 UniAuth 本地 challenge 事务失败，收件人可能收到
无法使用的验证码；后续异步投递失败也不会自动撤销 challenge。

### 与 UniAuth 根项目的跨进程验证

根目录 `scripts/test-http-e2e.sh` 会将本参考实现打包为真实 JAR，启动独立的
PostgreSQL 和 loopback HTTP 进程，再启动 UniAuth。邮箱注册和密码重置的正常路径
通过生产 `RestTemplateEmailServiceImpl` 调用本服务的 `/api/email/template`，测试
随后查询本服务的 `email_queue`，确认 `VERIFICATION` 和 `PASSWORD_RESET` 模板已被
渲染并持久化。该路径不是 Python stub 模拟。

参考实现不会为了测试而伪造 UniAuth 需要的 `503`/`429` 供应商失败响应，所以根
E2E 在正常路径完成后重启 UniAuth，显式切换到受控 loopback stub 验证失败映射和
“失败不保存 challenge”约束。参考服务自身的 `scripts/test-http-e2e.sh` 仍独立
验证其真实 HTTP、Flyway/PostgreSQL、API key、队列和重启持久化边界；默认不启用
SMTP，GreenMail 的最终投递边界由 Spring ApplicationContext E2E 覆盖。

## 结构

```text
HTTP request
  -> EmailController
  -> Thymeleaf template rendering
  -> email_queue row
  -> Spring event / scheduled recovery
  -> JavaMailSender
  -> SMTP or compatible provider
  -> email_logs row
```

主要代码：

| 路径 | 责任 |
|------|------|
| `controller/EmailController.java` | REST API |
| `service/EmailService.java` | 模板渲染和 JavaMailSender 调用 |
| `service/EmailQueueService.java` | 持久化队列和事件发布 |
| `event/EmailEventListener.java` | 异步即时发送和进程内限流 |
| `service/EmailProcessorService.java` | 定时恢复 pending/stuck 邮件 |
| `entity/EmailQueue.java` | 队列状态 |
| `entity/EmailLog.java` | 每次发送结果 |
| `templates/email/` | 欢迎、邮箱验证、密码重置模板 |

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/email/health` | 进程存活响应，不检查 SMTP 实际可用性 |
| `POST` | `/api/email/template` | 渲染模板并入队 |
| `POST` | `/api/email/simple` | 直接提交 HTML 并入队 |
| `POST` | `/api/email/batch` | 循环提交一批简单邮件 |
| `POST` | `/api/email/validate` | 语法级邮箱地址检查 |
| `GET` | `/api/email/templates` | 返回参考模板列表 |
| `GET` | `/api/email/queue/stats` | 队列状态统计 |
| `GET` | `/api/email/queue/{id}` | 队列详情 |
| `GET` | `/api/email/logs` | 发送日志列表 |

`EMAIL_SERVICE_API_KEY` 非空时，所有 `/api/email/**` 端点都要求
`X-Email-Service-Key` 恰好出现一次且整值精确匹配；缺失、不匹配或重复同名
header 都返回 `401`，包括重复的两个正确值以及正确/错误混合值。实现不得选择首值
或末值继续处理。默认配置把服务绑定到 `127.0.0.1`，loopback 下密钥可选；任何
非 loopback 绑定都必须配置密钥，否则 ApplicationContext 启动失败。超过 1024
字符或包含 CR/LF 的密钥也会在双方 ApplicationContext/运行保护阶段被拒绝，避免
将无效值传入 HTTP header。该共享密钥只是最小服务鉴权，部署仍应使用私有网络、
TLS、入口访问控制和独立密钥管理，不能直接暴露到公网。

所有 `/api/email` 及其子路径的响应（包括 2xx、4xx 和 5xx）都设置：

- `Cache-Control: no-store`，防止队列、日志和验证码相关响应被中间缓存保留。
- `Pragma: no-cache`，兼容仍检查该旧式缓存控制字段的客户端或代理。
- `X-Content-Type-Options: nosniff`，避免浏览器对 JSON 响应执行 MIME 嗅探。

这些是参考服务的响应安全基线，不是 UniAuth 当前客户端用来判定成功的 JSON 字段；
替换实现也不应依赖客户端忽略响应 header 而缓存或暴露邮件运维数据。

请求边界：

- 收件人最长 255 字符并通过邮箱语法检查。
- 主题最长 500 字符，拒绝 CR/LF header injection。
- `/simple` 请求 HTML 和模板渲染后的最终 HTML 最长 1,000,000 字符。
- 模板只允许本目录提供的三个固定名称，variables 最多 50 项。
- batch 最多 100 封；日志 page size 最大 100。
- 对外错误响应不包含内部异常或 SMTP/数据库细节。

## 配置

组件不默认激活 profile，也不包含可用数据库或 SMTP 凭据。启动必须显式选择
`dev` 或 `prod` 并提供环境变量。

关键环境变量见 [.env.example](.env.example)：

| 类型 | 变量 |
|------|------|
| 监听/鉴权 | `EMAIL_SERVICE_BIND_ADDRESS`、`EMAIL_SERVICE_PORT`、`EMAIL_SERVICE_API_KEY` |
| PostgreSQL | `EMAIL_POSTGRES_HOST`、`EMAIL_POSTGRES_PORT`、`EMAIL_POSTGRES_DATABASE`、`EMAIL_POSTGRES_USER`、`EMAIL_POSTGRES_PASSWORD` |
| SMTP | `SMTP_HOST`、`SMTP_PORT`、`SMTP_USERNAME`、`SMTP_PASSWORD` |
| TLS/SSL | `SMTP_STARTTLS_ENABLE`、`SMTP_STARTTLS_REQUIRED`、`SMTP_SSL_ENABLE`、`SMTP_SSL_CHECK_SERVER_IDENTITY` |
| 发件人 | `EMAIL_FROM_ADDRESS`、`EMAIL_FROM_NAME` |
| 队列 | `EMAIL_QUEUE_EVENT_DRIVEN`、`EMAIL_MAX_RETRY_ATTEMPTS`、`EMAIL_RETRY_DELAY_MINUTES` |
| 限流/恢复 | `EMAIL_RATE_LIMIT_ENABLED`、`EMAIL_RATE_LIMIT_PER_MINUTE`、`EMAIL_RECOVERY_ENABLED`、`EMAIL_RECOVERY_SCAN_INTERVAL_MINUTES`、`EMAIL_STUCK_TIMEOUT_MINUTES` |

`EMAIL_MAX_RETRY_ATTEMPTS` 是保留的兼容名称；当前值写入 `max_retries`，表示首次
发送失败后允许的最大重试次数，因此总投递尝试次数最多为该值加 1。
`EMAIL_RECOVERY_SCAN_INTERVAL_MINUTES` 和 `EMAIL_STUCK_TIMEOUT_MINUTES` 的有效
范围都是 `1..10080`。恢复 worker 只有在 `app.mail.enabled`、
`app.mail.queue.enabled` 和 `app.mail.recovery.enabled` 同时为 true 时才处理
pending/stuck 队列；关闭邮件或队列不会继续发送已有积压。

event 和 recovery 共用单进程内存限流器。每次先预留 slot，再以 PostgreSQL 条件
更新 claim 队列；claim 返回 false 或抛异常时释放 reservation。claim 成功后，一旦
调用 delivery bean 就按一次投递尝试计数，即使 SMTP/数据库后续失败或抛异常也不
归还本分钟 slot；delivery 返回 `SKIPPED` 表示未发生投递，会释放该 slot。

`SMTP_HOST` 只填写裸 host/IP token，不填写 `smtp://` URL。它最长 255 字符，不能
包含空白、控制字符、路径、userinfo、query 或 fragment。`SMTP_PORT` 必须是
`1..65535` 的十进制整数。Shell 入口和 Spring ApplicationContext 中的 Java guard
使用同一规则；它们只验证配置形状，不执行 DNS 或真实网络探测。

SMTP 传输模式必须使用下列组合之一：

| 场景 | `STARTTLS_ENABLE` | `STARTTLS_REQUIRED` | `SSL_ENABLE` | `SSL_CHECK_SERVER_IDENTITY` |
|------|-------------------|---------------------|--------------|-----------------------------|
| 生产强制 STARTTLS | `true` | `true` | `false` | `true` |
| 生产 implicit SSL | `false` | `false` | `true` | `true` |
| 隔离的 dev/test 明文 SMTP | `false` | `false` | `false` | `true` |

配置默认使用强制 STARTTLS，并默认启用 SMTP server identity verification。运行保护
拒绝以下组合：

- `SMTP_STARTTLS_REQUIRED=true` 但 `SMTP_STARTTLS_ENABLE=false`。
- 同时启用 `SMTP_STARTTLS_ENABLE` 和 `SMTP_SSL_ENABLE`。
- `prod` 使用明文或非强制 STARTTLS。
- `prod` 设置 `SMTP_SSL_CHECK_SERVER_IDENTITY=false`。
- 上述布尔环境变量使用 `true`/`false` 以外的值。

明文组合仅用于 loopback GreenMail 或等价的隔离测试夹具，不能作为部署配置。
如果供应商要求 implicit SSL，必须同时显式关闭两个 STARTTLS 变量。不要通过关闭
server identity verification 绕过证书或主机名错误；应修复 SMTP host、证书链或
信任库。

从来源目录复制的本机 `.env` 使用 Spring 标准变量
`SPRING_MAIL_USERNAME`、`SPRING_MAIL_PASSWORD` 和 `APP_MAIL_FROM_EMAIL`；当前配置
继续兼容这些名称。该文件被 gitignore 且不得提交，但它不包含完整运行配置：
仍必须补充独立邮件数据库的 `EMAIL_POSTGRES_*`、`SMTP_HOST`、`SMTP_PORT` 和适用的
TLS/SSL 设置。不要在文档或日志中打印变量值。

Profile 行为：

| Profile | Hibernate schema 行为 | SMTP 传输要求 | 用途 |
|---------|-----------------------|----------------|------|
| `dev` | `validate` | 允许显式明文，仅用于隔离本地 SMTP | 独立、可丢弃的本地参考数据库 |
| `prod` | `validate` | 强制 STARTTLS 或 implicit SSL；必须校验 server identity | 部署环境的独立邮件数据库 |

## 数据库与 Flyway

Flyway 是本组件唯一的 schema owner：

- datasource：所有 profile 只接受独立的 `jdbc:postgresql:` URL；H2 不受支持
- location：`classpath:db/migration/postgresql`
- history table：`email_service_flyway_schema_history`
- 当前 migration：V1 建表 + V2 队列完整性、日志外键和恢复查询索引
- `fail-on-missing-locations=true`
- `baseline-on-migrate=false`
- `clean-disabled=true`
- `validate-migration-naming=true`
- `validate-on-migrate=true`
- SQL init：`never`
- Hibernate：所有 profile 均为 `ddl-auto=validate`

这些值不是“推荐默认值”，而是运行契约。Spring `ApplicationContext` 中的
`EmailServiceRuntimeGuard` 和 `scripts/runtime-guard.sh` 都会拒绝外部配置覆盖：
Flyway 必须启用、缺失 migration location 必须失败、migration 命名必须校验、禁止
自动 baseline、禁止 clean、启用 migrate validation、禁止 out-of-order，并固定
migration location、history table、schema；SQL init 必须为 `never`，Hibernate schema
generation 必须为 `validate`。因此，环境变量、JVM 系统属性或部署平台注入的同名
覆盖不能把 schema owner 切换给 Hibernate、SQL init 或另一套 Flyway 配置。该拒绝
矩阵由 Java guard 测试、Shell runtime guard、ApplicationContext PostgreSQL 测试、
HTTP E2E 和 Flyway baseline guard 共同覆盖。

`EmailServiceRuntimeGuard` 会在 Flyway 前拒绝 H2 和其他非 PostgreSQL JDBC URL，
包括 `test` profile。H2 只在负向测试中作为配置字符串出现，不需要驱动，也不承担
repository、migration 或 ApplicationContext E2E 后端职责。

V1 创建 `email_queue`、`email_logs`、基础检查约束和查询索引。V2 增加
`retry_count <= max_retries`、日志到队列的 `ON DELETE SET NULL` 外键，以及恢复和
状态分页索引。已发布 migration 不得改写；后续 schema 变更必须新增 V3+。邮件服务
必须使用独立数据库，不得把该 migration
指向 UniAuth、`blacksheep_dev` 或其他共享 schema。

## 构建和测试

纯 service/config 单测可使用 mock 做快速反馈；所有 JPA repository 测试和组件级 E2E
使用 disposable Testcontainers PostgreSQL、Flyway 和 Hibernate `validate`。组件级
E2E 还使用完整 Spring ApplicationContext、随机真实 HTTP 端口、真实
repository/service/event Bean、Thymeleaf 和进程内 GreenMail SMTP。它不读取 `.env`，
也不会连接真实邮件供应商。

```bash
cd reference/email-service
scripts/verify.sh
```

统一入口执行：

- clean compile/test-compile 和完整 Maven 测试。
- runtime guard 配置拒绝矩阵。
- 真实 JAR + HTTP + disposable PostgreSQL Shell E2E。
- dirty schema、V2 坏数据和 forward-fix Flyway guard。
- 先把所有非忽略源码复制到进程专属临时目录，所有构建和 E2E 都在该快照内执行；
  并行运行不会共享 `target/`，原源码在验证期间变化则失败关闭并要求重跑。
- 根统一门槛通过仓库外的 `EMAIL_SERVICE_VERIFICATION_ARTIFACTS_DIR` 收集本快照的
  Surefire XML 和退出状态，并在邮件阶段返回后立即检查；artifact 写失败、缺少
  报告或非零子状态都必须失败关闭。

ApplicationContext/PostgreSQL/SMTP 覆盖：

- fresh V1→V2、V1→V2 数据保留、独立 history table 和 Hibernate `validate`。
- `GET /api/email/health` 与必需模板列表的真实 HTTP 契约。
- API key 配置后的单值精确匹配：缺失、错误、重复正确值和正确/错误混合 header
  均返回 `401`，单个正确 header 继续通过。
- 所有邮件 API 响应在成功、API key 拒绝和 MVC 路由错误下的
  `no-store`/`no-cache`/`nosniff` 安全 header。
- `email/email-verify` 和 `email/password-reset` 从 HTTP 入队到 SMTP 收件的完整链路。
- API key、输入/header injection、batch 和数据库分页边界。
- 未知模板拒绝且不创建队列/日志。
- SMTP 连接失败、配置化重试、原子 claim 和 stuck `PROCESSING` 恢复。
- Java/Shell runtime guard 的 STARTTLS、implicit SSL、生产加密和 server identity
  拒绝矩阵；真实 `JavaMailSender` Bean 保留身份校验属性。
- Java/Shell runtime guard 的 SMTP host/port 拒绝矩阵；PostgreSQL
  ApplicationContext 确认真实 `JavaMailSender` 使用预期 host/port。
- 最终投递把 PostgreSQL 队列行作为不受信任输入，重新校验 recipient、subject、
  HTML 上限以及 `X-Email-Type`、`X-Send-Method` token；异常行不进入 SMTP。
- 异常行的投递日志只保留 queue id、通用错误和安全占位字段；合法内部
  `sendMethod` 可保留，非法值记录为 `UNKNOWN`，不会因审计字段约束回滚 retry。
- event/recovery 在 PostgreSQL claim 返回 false 或抛异常时释放真实限流 Bean 的
  reservation；一旦进入 delivery 则保留已消费 slot，`SKIPPED` 才释放。
- 恢复候选按 priority 降序处理；关闭邮件总开关或队列后不投递存量队列。
- event 与 recovery 并发 claim 同一 PostgreSQL 队列记录时只允许一个投递者成功，
  最终只有一条成功日志和一封 SMTP 邮件。
- API key 配置、实体、事件和请求 DTO 的对象字符串不包含 API key、收件人、
  验证码或 HTML。
- V2 不兼容数据拒绝、外键删除行为和非空 schema 不自动 baseline。
- migration checksum 失配时失败关闭，并保留已有 migration history 和业务数据。

2026-08-08 当前组合基线：

- 本组件 Maven：135 tests，0 failures/errors/skips；其中 22 个
  PostgreSQL/GreenMail ApplicationContext E2E、27 个 Java runtime guard tests、
  1 个 PostgreSQL-only Spring Context 启动 guard test，以及 2 个 PostgreSQL
  repository constraint tests。
- 本组件 Shell runtime 39/39、HTTP/PostgreSQL E2E 11/11、Flyway guard 14/14。
- UniAuth 根项目：Java 131 tests、HTTP 15/15、Flyway 13/13、
  Mock Playwright 21/21、Python 资源服务器 16/16、邮件 REST stub contract 8/8；
  本轮功能性门禁已通过；完整根统一门禁的源码快照检查和连续三轮无修改检查仍是
  提交前门槛。
- 根 Shell HTTP E2E 的正常邮箱路径使用本参考服务真实 JAR、真实 HTTP 和独立
  PostgreSQL，直接断言模板进入 `email_queue`；脚本随后只为 `503/429` 失败映射
  切换到受控 loopback REST stub，仍通过真实 UniAuth
  `RestTemplateEmailServiceImpl` 验证失败不保存 challenge。
- 默认门禁仍不连接真实 SMTP/供应商，也不证明最终收件、退信、外部 TLS 或
  “外部已接受后 UniAuth 本地事务失败”窗口。

2026-08-08 Flyway schema-owner 覆盖保护增量：

- `EmailServiceApplicationTests` 改为真实 Testcontainers PostgreSQL + Flyway +
  Hibernate `validate`，测试夹具显式固定完整 schema-owner 配置。
- Java `EmailServiceRuntimeGuard` 和 Shell guard 拒绝禁用 Flyway、自动 baseline、
  clean、校验或 out-of-order，拒绝 migration location/history/schema、SQL init 和
  Hibernate schema generation 覆盖。
- 完整邮件服务门禁通过：Maven 131 tests、Java runtime guard 26/26、
  Shell runtime 39/39、HTTP/PostgreSQL E2E 11/11、Flyway baseline guard 14/14。

2026-08-08 Flyway migration discovery/naming fail-closed 增量：

- 固定 `spring.flyway.fail-on-missing-locations=true` 和
  `spring.flyway.validate-migration-naming=true`；Java/Shell guard 拒绝将任一值
  外部覆盖为 `false`。
- 真实 ApplicationContext 断言两项安全值；Flyway baseline guard 在迁移前验证两种
  危险覆盖均失败关闭，且不创建 history/table。
- 完整邮件服务门禁通过：Maven 131 tests、Java runtime guard 26/26、
  Shell runtime 39/39、HTTP/PostgreSQL E2E 11/11、Flyway baseline guard 14/14。

2026-08-08 PostgreSQL repository fixture 加固增量：

- 移除仅用于测试的 H2 依赖；`EmailQueueRepositoryTest` 和
  `EmailLogRepositoryTest` 均使用 disposable Testcontainers PostgreSQL、Flyway
  V1/V2 和 Hibernate `validate`，不再使用 `create-drop` 绕过真实 schema。
- 新增 retry bound check constraint 和 `email_logs.queue_id` 外键的真实 PostgreSQL
  约束断言；Flyway migration fixture 同步固定
  `fail-on-missing-locations=true` 与 `validate-migration-naming=true`。
- 完整邮件服务门禁通过：Maven 133 tests、Shell runtime 39/39、
  HTTP/PostgreSQL E2E 11/11、Flyway baseline guard 14/14。

2026-08-08 PostgreSQL-only runtime guard 收敛增量：

- `EmailServiceRuntimeGuard` 对 `dev`、`test`、`prod` 统一拒绝 H2 和其他
  非 PostgreSQL datasource，不再为 `test` profile 保留 H2 例外。
- 直接 Java guard 测试固定拒绝语义；独立 Spring `ApplicationContextRunner`
  装配真实 configuration properties 和 guard Bean，证明 H2 覆盖在 Flyway 前令
  Context 启动失败。
- 定向 Maven 通过 135/135，其中 Java runtime guard 27/27，另有 1 个
  PostgreSQL-only Spring Context 启动 guard test；完整 Shell runtime 39/39、
  HTTP/PostgreSQL E2E 11/11 和 Flyway guard 14/14 已随根统一门禁通过。

2026-08-07 初始纳入基线：

- Maven：94 tests，0 failures/errors/skips。
- 其中 14 个完整 ApplicationContext E2E、10 个 Java runtime guard tests、
  6 个独立 Flyway migration tests、6 个 context-path/matrix-parameter API key
  filter tests。
- Shell runtime guard：15/15。
- Shell HTTP/PostgreSQL E2E：8/8。
- Shell Flyway guard：8/8。

2026-08-08 SMTP endpoint 配置加固增量：

- Maven：108 tests，0 failures/errors/skips。
- 其中 14 个完整 ApplicationContext E2E、24 个 Java runtime guard tests。
- 该早期切片当时由 H2 与 PostgreSQL/GreenMail ApplicationContext 确认有效
  host/port 进入真实 `JavaMailSender` Bean；当前 H2 测试后端已移除。
- Shell runtime guard：27/27。
- Shell HTTP/PostgreSQL E2E：8/8。
- Shell Flyway guard：8/8。
- 根统一门禁：Java 98 tests、HTTP 14/14、Flyway 12/12、Mock Playwright 19/19、
  Python 14/14，前端 lint/type/build 和文档检查通过。

2026-08-08 持久化队列投递边界加固增量：

- Maven：110 tests，0 failures/errors/skips。
- 其中 16 个 PostgreSQL/GreenMail ApplicationContext E2E、24 个 Java runtime
  guard tests。
- CR/LF subject、超过 1,000,000 字符的 HTML、CR/LF `emailType` 和超长注入型
  `sendMethod` 都在 SMTP 前失败关闭，并沿用现有 retry 状态机。
- `NULL` 或 blank 的历史 `emailType` 均按 `GENERAL` 成功投递。
- 拒绝记录不会复制恶意载荷；非法 `sendMethod` 安全降级为 `UNKNOWN`。
- Shell HTTP E2E 同步覆盖 `emailType` header injection 拒绝。
- Shell runtime guard：27/27。
- Shell HTTP/PostgreSQL E2E：8/8。
- Shell Flyway guard：8/8。

2026-08-08 限流 reservation 异常路径加固增量：

- Maven：116 tests，0 failures/errors/skips。
- 其中 18 个 PostgreSQL/GreenMail ApplicationContext E2E、24 个 Java runtime
  guard tests。
- event 与 recovery 的 claim 异常使用真实 `EmailRateLimiter`、真实队列 Bean 和
  PostgreSQL 验证：队列保持 `PENDING`，不写日志、不进入 SMTP，slot 可立即复用。
- 单元行为测试同时固定 delivery 已开始后异常仍消费配额，避免把未知 SMTP 结果
  误当成未尝试。
- Shell runtime guard：27/27。
- Shell HTTP/PostgreSQL E2E：8/8。
- Shell Flyway guard：8/8。

2026-08-08 限流 reservation 窗口 ownership 与附加 E2E 加固增量：

- Maven：124 tests，0 failures/errors/skips。
- 其中 20 个 PostgreSQL/GreenMail ApplicationContext E2E、24 个 Java runtime
  guard tests。
- 每个受限 acquisition 返回绑定窗口 generation 的幂等 reservation；旧窗口迟到
  释放不会扣减新窗口额度，限流配置临时关闭也不会阻止释放原 reservation。
- event/recovery 的真实 Spring Bean E2E 同时覆盖窗口滚动期间 claim 返回 false，
  确认新窗口额度保持占用、队列不投递且不写日志。
- Shell HTTP E2E 断言 queue detail 不返回渲染 HTML/metadata，且当前夹具的验证码
  值不出现在响应中；该 endpoint 仍返回 subject，这不是任意敏感值的通用脱敏保证。
- Java PostgreSQL migration 测试和 Shell Flyway guard 注入 V1 checksum drift，
  断言启动失败关闭、业务数据和 history 行数不变、漂移 checksum 不被自动改写；
  显式恢复原 checksum 后可正常启动。
- Shell runtime guard：27/27。
- Shell HTTP/PostgreSQL E2E：9/9。
- Shell Flyway guard：9/9。

2026-08-08 敏感邮件 API 响应加固增量：

- Maven：127 tests，0 failures/errors/skips。
- 其中 21 个 PostgreSQL/GreenMail ApplicationContext E2E、24 个 Java runtime
  guard tests。
- 成功、API key 拒绝、参数拒绝、路由错误和内部失败的真实 Spring HTTP 响应均固定
  `Cache-Control: no-store`、`Pragma: no-cache` 与
  `X-Content-Type-Options: nosniff`；context path 和 matrix 参数路径使用同一 matcher。
- Shell runtime guard：27/27。
- Shell HTTP/PostgreSQL E2E：10/10。
- Shell Flyway guard：10/10。

2026-08-08 邮件 API 鉴权 header 单值加固增量：

- Maven：129 tests，0 failures/errors/skips。
- 其中 22 个 PostgreSQL/GreenMail ApplicationContext E2E、24 个 Java runtime
  guard tests。
- 配置 API key 后只接受恰好一个精确匹配的 `X-Email-Service-Key`；重复正确值、
  正确/错误和错误/正确组合在真实 Tomcat HTTP、Shell curl 和 Python stub 中均
  返回 `401`，单个正确 header 的成功语义保持不变。
- Shell runtime guard：27/27。
- Shell HTTP/PostgreSQL E2E：10/10。
- Shell Flyway guard：11/11。
- Python 邮件 REST stub contract：8/8。

2026-08-08 SMTP transport 加固增量：

- Maven：101 tests，0 failures/errors/skips。
- 其中 14 个完整 ApplicationContext E2E、17 个 Java runtime guard tests、
  6 个独立 Flyway migration tests、6 个 context-path/matrix-parameter API key
  filter tests。
- 该早期切片当时由 H2 与 PostgreSQL ApplicationContext 确认
  `mail.smtp.ssl.checkserveridentity=true` 进入真实 `JavaMailSender` Bean。
- Shell runtime guard：21/21。
- Shell HTTP/PostgreSQL E2E：8/8。
- Shell Flyway guard：8/8。

测试需要 Docker。若本机下载依赖受限，只把机器代理临时注入当前命令，不要写入
仓库配置、`.mvn/` 或可提交的环境文件。

## 安全启动

不要复用 UniAuth 数据库，也不要连接共享开发库。先创建独立、明确可丢弃的数据库，
再补齐未提交的 `.env`。如果来源 `.env` 已存在，不要用示例文件覆盖其中的凭据；
只合并缺失的变量。`start.sh` 要求 env 文件是普通文件且 group/other 无权限，
`dev` 数据库名必须包含 `email`/`mail` 和 `dev`/`test`/`demo`/`local` 标记。
生产启动还会拒绝 SMTP 明文、可降级 STARTTLS、TLS 模式冲突和关闭 server identity
verification；所有 profile 都会拒绝非法 SMTP host/port：

```bash
createdb -h 127.0.0.1 -U postgres uniauth_email_demo
test -f .env || cp .env.example .env
chmod 600 .env
./start.sh
```

启动后可做无邮件副作用的存活检查：

```bash
curl -fsS http://127.0.0.1:8095/api/email/health
```

真实模板邮件会产生 SMTP/供应商副作用，只能使用隔离测试账户显式执行。不要把真实
发送加入默认仓库门禁。

UniAuth 通过以下配置指向该服务：

```bash
EMAIL_SERVICE_URL=http://127.0.0.1:8095
EMAIL_SERVICE_TIMEOUT_MS=5000
EMAIL_SERVICE_API_KEY=
```

如果组件配置了 `EMAIL_SERVICE_API_KEY`，UniAuth 必须使用完全相同的值；该值最长
1024 字符且不能包含 CR/LF。
若 URL 带 context path，例如 `http://127.0.0.1:8095/mail`，服务必须在该前缀下
暴露 `/api/email/*`。

## 状态模型

队列状态：

- `PENDING`: 等待事件处理或定时恢复。
- `PROCESSING`: 已由一个 worker 取得。
- `COMPLETED`: SMTP 调用返回成功。
- `FAILED`: 达到最大重试次数。

`email_logs` 会保存收件人、主题、HTML 内容、供应商、错误和耗时。它包含个人信息和
可能敏感的验证码内容，必须限制数据库和日志访问，并定义保留/清理策略。

异步执行器饱和或关闭时不会把已经提交的入队请求反向变成 HTTP 失败；对应事件会被
放弃即时执行，由持久队列的 recovery 扫描继续处理。该降级依赖 recovery 保持启用。

## 已知限制

- `health` 始终报告进程存活，不探测 SMTP、供应商或真实投递。
- HTTP `success=true` 只表示模板已渲染并写入队列。
- API key 是全服务共享密钥，没有身份分级、轮换协议或端点级权限。
- 没有 API idempotency key，调用方重试可能创建重复邮件。
- 投递语义是至少一次而不是恰好一次：SMTP 已接受后若数据库提交失败、进程崩溃或
  stuck 记录被 recovery worker 重新领取，可能再次发送同一 `X-Queue-ID` 邮件。
- 限流计数保存在单进程内存中，多实例之间不共享。
- 定时恢复每次最多处理 50 条，没有容量或积压恢复证明。
- Flyway V1/V2 和失败关闭测试已存在，但没有生产发布、备份或灾难恢复演练。
- GreenMail E2E 证明本地 SMTP 协议链，不证明供应商鉴权、TLS 策略、退信处理或
  外部真实收件。
- 邮件队列和发送日志会保存完整 HTML；验证码清理和数据保留策略尚未实现。

## 与来源版本的调整

复制时没有带入 `target/`、机器专用数据库配置或可提交的字面量密码。来源 `.env`
仅作为 ignored、owner-only 的本机文件复制，不进入版本控制。仓库内版本另外：

- 使用显式环境变量和显式 profile。
- 默认只监听 loopback。
- 启用 Flyway V1/V2 作为唯一 schema owner，并让所有 profile 使用 Hibernate `validate`。
- 增加 PostgreSQL/Flyway/HTTP/GreenMail 的完整 ApplicationContext E2E。
- 增加可选 API key、运行保护、真实进程 HTTP E2E 和 Flyway fail-closed guard。
- 增加输入、header injection、batch、分页和错误信息边界。
- 避免配置、实体、事件和请求 DTO 的自动对象字符串暴露 API key 或邮件内容。
- 将队列 claim/delivery 拆为独立事务 Bean，原子恢复 stuck `PROCESSING` 记录。
- 在 event/recovery claim 异常和竞争失败时可靠释放未消费的限流 reservation。
- 邮件或队列关闭时停止恢复扫描投递，恢复候选按 priority 优先处理。
- 让异步发送事件在入队事务提交后、独立事务中处理。
- 将 PostgreSQL 不支持的 `LONGTEXT` 列声明改为 `TEXT`。
- 修正恢复扫描间隔的分钟换算。
- 让模板列表包含 `email/email-verify`。
- 用本 README 替代来源中的历史测试记录和机器专用运行说明。

修改 HTTP 契约、模板变量或成功语义时，必须同步 UniAuth adapter、
[配置基线](../../docs/CONFIGURATION.md#邮件服务依赖)、
[当前架构](../../docs/ARCHITECTURE.md#邮箱注册密码登录与密码重置) 和
[验证指南](../../docs/VERIFICATION.md)。
