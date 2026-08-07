# UniAuth 配置基线

> 状态：Live
> 核验日期：2026-08-07
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
服务的 HTTP 适配器。已有的独立 email-service 实现不属于本仓库，因此只克隆或部署
UniAuth 不会自动获得真实邮件投递能力。

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
`APP_EMAIL_SERVICE_URL` 也可覆盖同一属性，Shell E2E 使用该形式指向不可达测试地址。

这里的依赖是协议契约，不只是一个 host/port。外部 RESTful 服务必须满足：

| 调用 | UniAuth 的要求 |
|------|----------------|
| `GET /api/email/health` | 返回 2xx JSON，且 `status` 精确为 `UP` |
| `POST /api/email/template` | 接收 `Content-Type: application/json` |
| 模板 | 提供 `email/email-verify` 和 `email/password-reset` |
| 模板变量 | 支持 `username`、`verificationCode`、`expiryMinutes`；请求还会同时发送 `code` |
| 成功响应 | 返回 2xx JSON `success=true`；UniAuth 将其解释为 `QUEUED` |
| 服务鉴权 | 当前客户端不发送 API key、Bearer token 或其他服务鉴权 header |

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
只依赖模板邮件端点。

`success=true` 只表示外部服务接受或入队，不代表 SMTP/供应商已经送达邮件。外部服务
仍需自行负责模板渲染、队列、重试、SMTP/供应商凭据和投递状态。

当前实现还有两个必须显式知晓的限制：

- `app.email.service.timeout: 5000` 已出现在 YAML，但 `RestTemplate` 仍以
  `new RestTemplate()` 创建，该值目前没有绑定到 connect/read timeout。
- 邮件服务不可用、返回失败或后续异步投递失败时，UniAuth 仍可能保存验证码，
  `/api/auth/send-verification-code` 和忘记密码接口仍可能返回发送成功。

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

## 数据初始化

三个 profile 使用同一 PostgreSQL migration 链：

- Flyway location：`classpath:db/migration/postgresql`
- history table：`uniauth_flyway_schema_history`
- 当前版本：V2（V1 baseline + V2 登录方式加固）
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

Flyway 只扫描 `src/main/resources/db/migration/postgresql/`。旧 V1-V4、V6-V8
和四份 SQL init 文件已归档到 `docs/archive/database/legacy-sql/`，不能执行或复制回
runtime classpath。

V1 来自获准的实际 dev PostgreSQL 8 表结构。V2 加固登录方式时区/nullability、
provider/行形状和 primary 唯一性。后续修复使用 V3+；不得改写 V1/V2 checksum。

## Existing-schema baseline

`scripts/flyway-baseline-existing.sh rehearse` 默认只读源库，并在 disposable PostgreSQL
中完成 restore、baseline、fresh migrate、validate 和结构指纹比较。

对真实库执行 `apply` 需要：

- 非生产数据库名保护。
- rehearsal 成功。
- 精确匹配本次结构指纹的 `UNIAUTH_BASELINE_CONFIRM`。
- apply 写入前再次确认源 schema 指纹、V2 数据预检均未变化，且仍不存在 Flyway history table。
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
