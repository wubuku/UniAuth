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
- 默认验证中会被启动或会发送真实邮件的服务。

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

Profile 行为：

| Profile | Hibernate schema 行为 | 用途 |
|---------|-----------------------|------|
| `dev` | `update` | 独立、可丢弃的本地参考数据库 |
| `prod` | `validate` | 只验证外部管理的 schema |

当前组件没有 Flyway migration。生产采用 `validate` 时，部署者必须另外提供并管理
`email_queue` 和 `email_logs` schema。

## 构建和测试

离线行为测试使用 H2 和 mock，不会连接 PostgreSQL 或发送真实邮件：

```bash
cd reference/email-service
mvn test
```

只编译：

```bash
cd reference/email-service
mvn clean compile test-compile
```

## 安全启动

不要复用 UniAuth 数据库，也不要连接共享开发库。先创建独立、明确可丢弃的数据库，
再准备未提交的 `.env`：

```bash
createdb -h 127.0.0.1 -U postgres uniauth_email_demo
cp .env.example .env

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
- 没有 Flyway migration、生产 schema 发布流程或 PostgreSQL 集成测试。
- 自动化测试使用 H2 和 mock，不证明 PostgreSQL 方言、SMTP TLS 或真实收件。
- `/api/email/logs` 先加载全部匹配记录再在内存分页，不适合大数据量。
- 邮件队列和发送日志会保存完整 HTML；验证码清理和数据保留策略尚未实现。

## 与来源版本的调整

复制时没有带入源 `.env`、`target/`、机器专用数据库或字面量密码。仓库内版本另外：

- 使用显式环境变量和显式 profile。
- 默认只监听 loopback。
- 将 PostgreSQL 不支持的 `LONGTEXT` 列声明改为 `TEXT`。
- 修正恢复扫描间隔的分钟换算。
- 让模板列表包含 `email/email-verify`。
- 用本 README 替代来源中的历史测试记录和机器专用运行说明。

修改 HTTP 契约、模板变量或成功语义时，必须同步 UniAuth adapter、
[配置基线](../../docs/CONFIGURATION.md#邮件服务依赖)、
[当前架构](../../docs/ARCHITECTURE.md#邮箱注册密码登录与密码重置) 和
[验证指南](../../docs/VERIFICATION.md)。
