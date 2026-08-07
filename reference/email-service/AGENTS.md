# Email Service Reference Agent Guide

本目录是 UniAuth 外部邮件 REST 服务的独立参考实现，不属于根 Maven 工程。

## Safety

- 不提交 `.env`、SMTP 凭据、数据库密码、真实收件地址或邮件内容。
- 不连接 UniAuth 数据库或任何共享数据库；邮件队列必须使用独立数据库。
- 启动时显式选择 `dev` 或 `prod` profile，并显式提供全部数据库和 SMTP 配置。
- 默认测试只能使用 H2 和 mock，不得发送真实邮件。
- 真实 SMTP/供应商测试必须显式 opt in，并使用隔离测试账户。

## Contract

UniAuth 当前依赖：

- `GET /api/email/health`
- `POST /api/email/template`
- `email/email-verify`
- `email/password-reset`
- 2xx JSON `success=true` 表示请求已接受或入队

修改这些端点、字段、模板或成功语义时，同时检查：

- `src/main/java/org/dddml/uniauth/service/email/impl/RestTemplateEmailServiceImpl.java`
- `docs/CONFIGURATION.md`
- `docs/ARCHITECTURE.md`
- `docs/VERIFICATION.md`

## Verification

```bash
cd reference/email-service
mvn test
```

Flyway 是 PostgreSQL schema owner，history table 是
`email_service_flyway_schema_history`；所有 profile 的 Hibernate 都使用 `validate`。
已发布 migration 不得改写。不要把该参考实现描述为生产就绪服务。
