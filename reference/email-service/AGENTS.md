# Email Service Reference Agent Guide

本目录是 UniAuth 外部邮件 REST 服务的独立参考实现，不属于根 Maven 工程。

## Safety

- 不提交 `.env`、SMTP 凭据、数据库密码、真实收件地址或邮件内容。
- 数据库默认使用独立布局。只有显式设置
  `EMAIL_DATABASE_LAYOUT=shared-uniauth` 时，才允许在获准的 PostgreSQL 16
  `public` schema 中先于 UniAuth 迁移，或与完整 UniAuth V1-V6 peer 共存；两侧使用
  独立 Flyway history table 和同一 advisory lock。目标非空时必须先验证完整 peer；
  双方 history 同时存在后，每次启动都必须重新校验 peer；邮件服务不得在首次
  baseline 后退回默认 `dedicated`。`blacksheep*`、系统库和未获准共享开发库始终拒绝。
- peer history 必须恰好包含当前预期的成功 SQL 版本，另只允许 0 或 1 个成功 V0
  baseline；失败、重复、未知 versioned 或 repeatable 记录必须失败关闭。出现 UniAuth
  relation 却没有 `uniauth_flyway_schema_history` 时视为半成品布局，不得继续启动。
- 邮件侧只创建 `email_queue`、`email_logs`、对应序列/索引/约束和
  `email_service_flyway_schema_history`，与 UniAuth V1-V6 的 relation 名称没有
  冲突。共享部署的原始冲突是后启动 Flyway 面对非空 `public` schema 而缺少自身
  history，不是业务表重名；只能由受控 baseline V0 兼容路径解决。
- 启动时显式选择 `dev` 或 `prod` profile，并显式提供全部数据库和 SMTP 配置。
- 使用 `start.sh` 加载 env 和执行运行保护；它按所选 database layout 校验目标，
  并拒绝权限过宽或符号链接形式的 env 文件。
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
  `fail-on-missing-locations=true`、`validate-migration-naming=true`、
  `baseline-on-migrate=false`、`clean-disabled=true`、`validate-on-migrate=true`、
  `out-of-order=false`，location/history/default schema/schemas 必须固定；SQL init
  必须为 `never`，Hibernate 必须为 `validate`。Java ApplicationContext guard 和
  `scripts/runtime-guard.sh` 都要拒绝环境变量、JVM 属性或部署平台注入的覆盖。
- Flyway V3 固定队列生命周期行形状：终态必须有 `processed_time`，只有 `PENDING`
  可以保留 `next_retry_time`，只有 `FAILED` 可以保留 `error_message`。claim、retry、
  完成和永久失败转换必须同步维护这些字段。V4 增加可空的幂等请求 identity；
  同一 idempotency key 只能对应一个稳定 request fingerprint 和 queue identity。
  不得改写已发布的 V1/V2/V3/V4。
- PostgreSQL 备份使用 `scripts/backup-postgres.sh`：它支持 `dedicated` 和显式
  `shared-uniauth`，但无论哪种布局都只导出 `email_queue`、`email_logs`、对应序列
  和 `email_service_flyway_schema_history`，不得把共享库的 UniAuth 表带入组件备份。
  history 必须精确匹配 SQL V1-V4；shared layout 只额外允许 0 或 1 个 V0 baseline，
  缺失、重复、失败、未知 versioned 或 repeatable migration 都必须失败关闭。
  它不得隐式读取 `.env`，只能读取显式环境变量或显式
  `EMAIL_SERVICE_ENV_FILE`，并拒绝未知布局、缺失邮件 schema、相对/符号链接/
  非 owner-only 输出目录，以及和源 PostgreSQL major 不一致的 `pg_dump`/`pg_restore`。
  archive 与 checksum 必须先在临时文件中完成并验证，再以 `0600` 发布；失败不得留下
  看似成功的最终文件。
- 默认 restore 验证只允许 `scripts/test-backup-restore-rehearsal.sh` 在 disposable
  PostgreSQL 空库中执行；不得把自动恢复接入普通启动或对现有共享/生产库执行覆盖。
- `dev`、`test`、`prod` 的 datasource URL 都必须是 `jdbc:postgresql:`，且数据库
  必须符合所选 layout 和 profile 命名规则；H2 只允许作为负向 guard 输入，不是测试后端。
  Java guard 必须在 Flyway 前拒绝任何非 PostgreSQL URL，并保留 Spring Context
  启动失败测试。
- 所有 `/api/email` 及其子路径的响应都必须设置 `Cache-Control: no-store`、
  `Pragma: no-cache` 和 `X-Content-Type-Options: nosniff`，包括成功、鉴权失败、
  参数拒绝和路由错误；该策略不改变 JSON body 契约。
- 纯 service/config 单测可使用 mock 做快速反馈；所有 JPA repository 测试必须使用
  disposable PostgreSQL、Flyway 和 Hibernate `validate`，组件级 E2E 继续使用完整
  Spring ApplicationContext、真实 repository/service/event Bean 和进程内 GreenMail；
  不得连接真实 SMTP 或发送真实邮件。
- 真实 SMTP/供应商测试必须显式 opt in，并使用隔离测试账户。

## Contract

UniAuth 当前依赖：

- `GET /api/email/health`
- `POST /api/email/template`
- `GET /api/email/delivery/status?idempotencyKey=...`
- `email/email-verify`
- `email/password-reset`
- 2xx JSON `success=true` 表示请求已接受或入队
- UniAuth challenge 投递请求携带稳定 `idempotencyKey`；重复相同请求返回同一
  queue identity，不同 payload 复用同一 key 返回稳定冲突
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

Flyway 是邮件组件对象的 PostgreSQL schema owner，history table 是
`email_service_flyway_schema_history`；所有 profile 的 Hibernate 都使用 `validate`。
已发布 migration 不得改写；新增 schema 变更使用 V4+。Java/Shell guard 必须在迁移
前拒绝 schema-owner 配置覆盖；checksum drift 测试必须
证明失败启动不会自动改写 history，只有显式恢复后才能重新通过验证。E2E 必须经过
真实 HTTP、Flyway/PostgreSQL、真实 Spring Beans、Thymeleaf、异步事件和
GreenMail SMTP；统一门禁还必须完成 owner-only 原子备份和 disposable 空库恢复
rehearsal 10/10，并证明 shared-uniauth 备份不包含 UniAuth 表。不要把该参考实现
描述为生产就绪服务。
根项目 `scripts/test-http-e2e.sh` 的正常邮箱注册/重置路径会启动本服务真实 JAR 和
独立 PostgreSQL，并检查 `email_queue`；根脚本仅在需要稳定制造 `503/429` 失败映射
时切换到受控 stub。修改根邮件边界文档或脚本时，必须保持这一区分清晰。
