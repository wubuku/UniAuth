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

当前所有端点都没有应用层鉴权。默认配置把服务绑定到 `127.0.0.1`；部署到其他主机时，
必须通过私有网络、网关或其他外部控制限制访问，不能直接暴露到公网。

## 配置

组件不默认激活 profile，也不包含可用数据库或 SMTP 凭据。启动必须显式选择
`dev` 或 `prod` 并提供环境变量。

关键环境变量见 [.env.example](.env.example)：

| 类型 | 变量 |
|------|------|
| 监听 | `EMAIL_SERVICE_BIND_ADDRESS`、`EMAIL_SERVICE_PORT` |
| PostgreSQL | `EMAIL_POSTGRES_HOST`、`EMAIL_POSTGRES_PORT`、`EMAIL_POSTGRES_DATABASE`、`EMAIL_POSTGRES_USER`、`EMAIL_POSTGRES_PASSWORD` |
| SMTP | `SMTP_HOST`、`SMTP_PORT`、`SMTP_USERNAME`、`SMTP_PASSWORD` |
| TLS/SSL | `SMTP_STARTTLS_ENABLE`、`SMTP_STARTTLS_REQUIRED`、`SMTP_SSL_ENABLE` |
| 发件人 | `EMAIL_FROM_ADDRESS`、`EMAIL_FROM_NAME` |
| 队列 | `EMAIL_RATE_LIMIT_PER_MINUTE`、`EMAIL_RETRY_DELAY_MINUTES`、`EMAIL_RECOVERY_SCAN_INTERVAL_MINUTES` |

从来源目录复制的本机 `.env` 使用 Spring 标准变量
`SPRING_MAIL_USERNAME`、`SPRING_MAIL_PASSWORD` 和 `APP_MAIL_FROM_EMAIL`；当前配置
继续兼容这些名称。该文件被 gitignore 且不得提交，但它不包含完整运行配置：
仍必须补充独立邮件数据库的 `EMAIL_POSTGRES_*`、`SMTP_HOST`、`SMTP_PORT` 和适用的
TLS/SSL 设置。不要在文档或日志中打印变量值。

Profile 行为：

| Profile | Hibernate schema 行为 | 用途 |
|---------|-----------------------|------|
| `dev` | `validate` | 独立、可丢弃的本地参考数据库 |
| `prod` | `validate` | 部署环境的独立邮件数据库 |

## 数据库与 Flyway

Flyway 是本组件唯一的 schema owner：

- location：`classpath:db/migration/postgresql`
- history table：`email_service_flyway_schema_history`
- 当前 migration：`V1__create_email_queue_and_logs.sql`
- `baseline-on-migrate=false`
- `clean-disabled=true`
- `validate-on-migrate=true`
- SQL init：`never`
- Hibernate：所有 profile 均为 `ddl-auto=validate`

V1 创建 `email_queue`、`email_logs`、检查约束和查询索引。已发布 migration 不得改写；
后续 schema 变更必须新增 V2+。邮件服务必须使用独立数据库，不得把该 migration
指向 UniAuth、`blacksheep_dev` 或其他共享 schema。

## 构建和测试

快速测试保留 H2 和 mock；组件级 E2E 使用完整 Spring ApplicationContext、随机真实
HTTP 端口、Testcontainers PostgreSQL、Flyway、真实 repository/service/event Bean、
Thymeleaf 和进程内 GreenMail SMTP。它不读取 `.env`，也不会连接真实邮件供应商。

```bash
cd reference/email-service
TESTCONTAINERS_RYUK_DISABLED=true mvn clean compile test-compile
TESTCONTAINERS_RYUK_DISABLED=true mvn test
```

E2E 覆盖：

- V1 migration、独立 history table 和 Hibernate `validate`。
- `GET /api/email/health` 与必需模板列表的真实 HTTP 契约。
- `email/email-verify` 和 `email/password-reset` 从 HTTP 入队到 SMTP 收件的完整链路。
- 未知模板拒绝且不创建队列/日志。
- SMTP 连接失败时写入失败日志并把队列安排为可重试状态。

2026-08-07 当前基线：59 tests，0 failures/errors/skips，其中 5 个为上述组件级 E2E。

测试需要 Docker。若本机下载依赖受限，只把机器代理临时注入当前命令，不要写入
仓库配置、`.mvn/` 或可提交的环境文件。

## 安全启动

不要复用 UniAuth 数据库，也不要连接共享开发库。先创建独立、明确可丢弃的数据库，
再补齐未提交的 `.env`。如果来源 `.env` 已存在，不要用示例文件覆盖其中的凭据；
只合并缺失的变量：

```bash
createdb -h 127.0.0.1 -U postgres uniauth_email_demo
test -f .env || cp .env.example .env

set -a
source .env
set +a

mvn spring-boot:run -Dspring-boot.run.profiles=dev
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
```

## 状态模型

队列状态：

- `PENDING`: 等待事件处理或定时恢复。
- `PROCESSING`: 已由一个 worker 取得。
- `COMPLETED`: SMTP 调用返回成功。
- `FAILED`: 达到最大重试次数。

`email_logs` 会保存收件人、主题、HTML 内容、供应商、错误和耗时。它包含个人信息和
可能敏感的验证码内容，必须限制数据库和日志访问，并定义保留/清理策略。

## 已知限制

- `health` 始终报告进程存活，不探测 SMTP、供应商或真实投递。
- HTTP `success=true` 只表示模板已渲染并写入队列。
- 所有 REST 端点未鉴权，管理和日志端点也不例外。
- 没有 API idempotency key，调用方重试可能创建重复邮件。
- 限流计数保存在单进程内存中，多实例之间不共享。
- 定时恢复每次最多处理 50 条，没有容量或积压恢复证明。
- Flyway V1 和 PostgreSQL E2E 已存在，但没有生产 migration 发布/回滚演练。
- GreenMail E2E 证明本地 SMTP 协议链，不证明供应商鉴权、TLS 策略、退信处理或
  外部真实收件。
- `/api/email/logs` 先加载全部匹配记录再在内存分页，不适合大数据量。
- 邮件队列和发送日志会保存完整 HTML；验证码清理和数据保留策略尚未实现。

## 与来源版本的调整

复制时没有带入 `target/`、机器专用数据库配置或可提交的字面量密码。来源 `.env`
仅作为 ignored、owner-only 的本机文件复制，不进入版本控制。仓库内版本另外：

- 使用显式环境变量和显式 profile。
- 默认只监听 loopback。
- 启用 Flyway V1 作为唯一 schema owner，并让所有 profile 使用 Hibernate `validate`。
- 增加 PostgreSQL/Flyway/HTTP/GreenMail 的完整 ApplicationContext E2E。
- 让异步发送事件在入队事务提交后、独立事务中处理。
- 将 PostgreSQL 不支持的 `LONGTEXT` 列声明改为 `TEXT`。
- 修正恢复扫描间隔的分钟换算。
- 让模板列表包含 `email/email-verify`。
- 用本 README 替代来源中的历史测试记录和机器专用运行说明。

修改 HTTP 契约、模板变量或成功语义时，必须同步 UniAuth adapter、
[配置基线](../../docs/CONFIGURATION.md#邮件服务依赖)、
[当前架构](../../docs/ARCHITECTURE.md#邮箱注册密码登录与密码重置) 和
[验证指南](../../docs/VERIFICATION.md)。
