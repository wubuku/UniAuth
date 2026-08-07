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
| PostgreSQL | `localhost:5432` | `application-test.yml` 和环境变量 |
| 邮件服务 | `http://localhost:8095` | `application.yml` |

`8080`、`8082`、`5001` 和历史隧道域名仍散落在旧文档、脚本或部署示例中。
除非文件明确覆盖端口，否则它们不是当前默认值。

## Spring Profiles

`application.yml` 当前硬编码：

```yaml
spring:
  profiles:
    active: test
```

因此裸跑 `mvn spring-boot:run` 会激活 `test`。

| Profile | 数据库 | 启动时 SQL/Hibernate | 数据风险 |
|---------|--------|----------------------|----------|
| `dev` | `jdbc:sqlite:./dev-database.db` | SQL init always；`ddl-auto: none` | 初始化器删除全部用户/登录方式 |
| `test` | PostgreSQL，默认库 `blacksheep_dev` | SQL init always；`ddl-auto: update` | 初始化器删除全部用户/登录方式 |
| `prod` | 环境变量指定 PostgreSQL | SQL init never；`ddl-auto: validate` | 不清空用户，但依赖外部 schema |

`test` 不是 Maven 自动化测试专用的隔离内存环境。它默认连接真实 PostgreSQL，
且回退密码在配置中明文存在。

## 数据初始化

### dev

- `schema-sqlite.sql`
- `data-sqlite.sql`
- `DevEnvironmentInitializer`

### test

- `schema-postgresql.sql`
- `TestEnvironmentInitializer`
- Hibernate `ddl-auto: update`

### prod

- 不自动执行 SQL init。
- Hibernate 只验证 schema。
- Spring Session 表必须由部署流程创建。

### Migration 目录

`src/main/resources/db/migration/V*.sql` 当前是未接线的迁移材料。`pom.xml`
没有 Flyway 或 Liquibase 依赖，所以新增文件不会自动执行。

数据库变更必须明确选择真实迁移机制，不能只提交新的 `V*.sql`。

## SQLite 与 PostgreSQL 差异

PostgreSQL schema 已包含：

- `web3_nonces`
- `email_verification_codes`
- Web3 相关登录方式列
- Spring Session 表

SQLite schema 当前缺少其中多项。fresh `dev` 不能被视为完整功能环境，
直到 schema 与实体对齐并有测试覆盖。

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
| key file 配置 | `jwt.rsa.key-file: rsa-keys.ser` |
| 实际构造加载 | 硬编码 `rsa-keys.ser` |
| issuer | `https://auth.example.com` |
| audience | `resource-server` |
| key id | `key-1` |

敏感文件和变量：

- `.env`
- `jwt-secret.key`
- `rsa-keys.ser`
- OAuth2 client secret
- PostgreSQL password

`.env`、数据库和 `jwt-secret.key` 被忽略；`rsa-keys.ser` 当前已被 Git 跟踪。
不要打印、复制到文档或无意覆盖密钥。

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
- 认证服务器和 JWKS URL 硬编码为历史隧道域名。
- HTTPS 请求禁用了证书验证。
- issuer/audience 硬编码。

组件 README 中的 `5001` 是历史值。后续应改为环境变量驱动，并恢复 TLS 验证。

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
