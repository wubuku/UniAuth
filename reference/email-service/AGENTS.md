# Email Service Reference Agent Guide

本目录是 UniAuth 外部邮件 REST 服务的独立参考实现，不属于根 Maven 工程。

## Safety

- 不提交 `.env`、SMTP 凭据、数据库密码、真实收件地址或邮件内容。
- 不连接 UniAuth 数据库或任何共享数据库；邮件队列必须使用独立数据库。
- 启动时显式选择 `dev` 或 `prod` profile，并显式提供全部数据库和 SMTP 配置。
- 使用 `start.sh` 加载 env 和执行运行保护；它要求独立邮件数据库，并拒绝权限过宽
  或符号链接形式的 env 文件。
- 非 loopback 监听必须设置 `EMAIL_SERVICE_API_KEY`；UniAuth 侧配置相同值。密钥
  最长 1024 字符且不能包含 CR/LF。
- SMTP transport 必须通过 Java 与 Shell 双重 guard：`STARTTLS_REQUIRED=true`
  不能脱离 `STARTTLS_ENABLE=true`，implicit SSL 不能与 STARTTLS 同时启用；`prod`
  只能使用强制 STARTTLS 或 implicit SSL，并保持 server identity verification 开启。
  `dev/test` 才允许为本地 GreenMail 等隔离夹具显式关闭传输加密。
- `SMTP_HOST` 必须是最长 255 字符、无 URI 语法、空白或控制字符的 host/IP token；
  `SMTP_PORT` 必须是 `1..65535`。Shell 和 Java guard 必须保持一致。
- `EMAIL_RECOVERY_SCAN_INTERVAL_MINUTES` 和 stuck timeout 必须在 `1..10080`；
  邮件总开关或队列关闭时，恢复任务不得发送存量。
- PostgreSQL 队列不是可信输入；最终投递必须重新校验 recipient、subject、HTML
  上限和自定义 MIME header token，不能只依赖 HTTP DTO/service 入队校验。
- 拒绝非法队列载荷时，投递日志只保留 queue id、通用错误和安全占位字段；非法
  `sendMethod` 必须记录为 `UNKNOWN`，不得让审计写入失败并回滚 retry。
- event/recovery 取得限流 slot 后，如果 claim 返回 false 或抛异常，必须释放；
  一旦调用 delivery bean 就按一次投递尝试计数，失败或异常不归还，`SKIPPED`
  因未实际投递而释放。
- 配置、实体、事件和请求 DTO 不得通过自动 `toString()` 暴露 API key、收件人、
  验证码或 HTML。
- 默认测试可使用 H2/mock 做快速反馈，并使用 disposable PostgreSQL 和进程内
  GreenMail 做完整 ApplicationContext E2E；不得连接真实 SMTP 或发送真实邮件。
- 真实 SMTP/供应商测试必须显式 opt in，并使用隔离测试账户。

## Contract

UniAuth 当前依赖：

- `GET /api/email/health`
- `POST /api/email/template`
- `email/email-verify`
- `email/password-reset`
- 2xx JSON `success=true` 表示请求已接受或入队
- UniAuth base URL 必须是带 host、无 userinfo/query/fragment 的绝对 HTTP/HTTPS
  地址；允许 context path 和尾斜杠，timeout 范围为 `100..600000ms`

修改这些端点、字段、模板或成功语义时，同时检查：

- `src/main/java/org/dddml/uniauth/service/email/impl/RestTemplateEmailServiceImpl.java`
- `docs/CONFIGURATION.md`
- `docs/ARCHITECTURE.md`
- `docs/VERIFICATION.md`

## Verification

```bash
cd reference/email-service
scripts/verify.sh
```

该入口在进程专属临时源码快照中执行全部 Maven 和 E2E 阶段；不要绕过它直接依赖
共享 `target/` 的结果。验证期间源文件发生变化时必须重跑。

Flyway 是 PostgreSQL schema owner，history table 是
`email_service_flyway_schema_history`；所有 profile 的 Hibernate 都使用 `validate`。
已发布 migration 不得改写；新增 schema 变更使用 V3+。E2E 必须经过真实 HTTP、
Flyway/PostgreSQL、真实 Spring Beans、Thymeleaf、异步事件和 GreenMail SMTP。
不要把该参考实现描述为生产就绪服务。
