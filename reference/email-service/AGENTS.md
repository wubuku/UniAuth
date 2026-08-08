# Email Service Reference Agent Guide

本目录是 UniAuth 外部邮件 REST 服务的独立参考实现，不属于根 Maven 工程。

## Safety

- 不提交 `.env`、SMTP 凭据、数据库密码、真实收件地址或邮件内容。
- 不连接 UniAuth 数据库或任何共享数据库；邮件队列必须使用独立数据库。
- 启动时显式选择 `dev` 或 `prod` profile，并显式提供全部数据库和 SMTP 配置。
- 使用 `start.sh` 加载 env 和执行运行保护；它要求独立邮件数据库，并拒绝权限过宽
  或符号链接形式的 env 文件。
- 非 loopback 监听必须设置 `EMAIL_SERVICE_API_KEY`；UniAuth 侧配置相同值。密钥
  最长 1024 字符且不能包含 CR/LF。配置后只接受恰好一个
  `X-Email-Service-Key` 且整值精确匹配；缺失、错误或重复同名 header 均返回
  `401`，不得选择首值或末值继续处理。
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
  因未实际投递而释放。reservation 必须绑定取得 slot 时的窗口 generation 且
  幂等释放；旧窗口迟到释放不得扣减新窗口额度，配置临时关闭不得阻止释放旧额度。
- 配置、实体、事件和请求 DTO 不得通过自动 `toString()` 暴露 API key、收件人、
  验证码或 HTML。
- Flyway 是唯一 schema owner：`spring.flyway.enabled=true`、
  `baseline-on-migrate=false`、`clean-disabled=true`、`validate-on-migrate=true`、
  `out-of-order=false`，location/history/default schema/schemas 必须固定；SQL init
  必须为 `never`，Hibernate 必须为 `validate`。Java ApplicationContext guard 和
  `scripts/runtime-guard.sh` 都要拒绝环境变量、JVM 属性或部署平台注入的覆盖。
- 所有 `/api/email` 及其子路径的响应都必须设置 `Cache-Control: no-store`、
  `Pragma: no-cache` 和 `X-Content-Type-Options: nosniff`，包括成功、鉴权失败、
  参数拒绝和路由错误；该策略不改变 JSON body 契约。
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
- 配置 API key 时，`X-Email-Service-Key` 必须恰好出现一次且整值精确匹配
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
共享 `target/` 的结果。验证期间源文件发生变化时必须重跑。根统一门槛传入
仓库外的 `EMAIL_SERVICE_VERIFICATION_ARTIFACTS_DIR`，邮件入口必须回传实际
Surefire XML 和非伪造的退出状态；artifact 写入失败必须令验证失败。

Flyway 是 PostgreSQL schema owner，history table 是
`email_service_flyway_schema_history`；所有 profile 的 Hibernate 都使用 `validate`。
已发布 migration 不得改写；新增 schema 变更使用 V3+。Java/Shell guard 必须在迁移
前拒绝 schema-owner 配置覆盖；checksum drift 测试必须
证明失败启动不会自动改写 history，只有显式恢复后才能重新通过验证。E2E 必须经过
真实 HTTP、Flyway/PostgreSQL、真实 Spring Beans、Thymeleaf、异步事件和
 GreenMail SMTP。不要把该参考实现描述为生产就绪服务。
根项目 `scripts/test-http-e2e.sh` 的正常邮箱注册/重置路径会启动本服务真实 JAR 和
独立 PostgreSQL，并检查 `email_queue`；根脚本仅在需要稳定制造 `503/429` 失败映射
时切换到受控 stub。修改根邮件边界文档或脚本时，必须保持这一区分清晰。
