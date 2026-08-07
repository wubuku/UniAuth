# UniAuth Agent Guide

本文件是面向后续编程代理的仓库级长期记忆。它记录当前代码的真实结构、稳定约定和高风险操作边界。
当本文与代码冲突时，以当前代码和配置为准；`README.md`、`docs/Perplexity/`、`docs/drafts/` 中有较多历史方案和过时端口。

## Documentation Map

- `docs/README.md`: 文档体系主入口和生命周期分类。
- `docs/ARCHITECTURE.md`: 当前模块、安全链、身份和 JWT 模型。
- `docs/CONFIGURATION.md`: 端口、profile、数据库、回调、CORS 和密钥基线。
- `docs/DEVELOPMENT.md`: 安全构建、启动前检查和日常工作流。
- `docs/VERIFICATION.md`: 验证层级、2026-08-07 基线和测试缺口。
- `docs/drafts/README.md`: 既有计划、调查和历史记录索引。
- `docs/drafts/DOCUMENTATION_PLAN.md`: 文档体系建设计划。

已有文档保持原路径。新指南链接历史材料，不通过搬迁或复制来“整理”目录。

## Big Picture

UniAuth 是一个单仓库认证系统，包含三个可运行部分：

- `src/main/java/org/dddml/uniauth/`: Spring Boot 3.3.4 / Java 17 后端。
- `frontend/`: React 18 + TypeScript + Vite SPA。
- `python-resource-server/`: Flask 异构资源服务器示例，通过 JWKS 验证 UniAuth JWT。

后端同时承担几种角色：

- 本地用户名/密码、邮箱验证码、Web3 钱包认证。
- Google、GitHub、X 的 OAuth2 Client。
- 自定义 RS256 JWT 的签发方和资源服务器。
- 暴露 JWKS 与 token introspection 接口。
- 包含部分 Spring Authorization Server 配置，但主要业务 token 由自定义 controller/service 签发；不要假设标准 Authorization Server 流程已完整接通。
- 构建后的 React 静态资源由 Spring Boot 提供。

## Canonical Runtime Facts

- 后端默认端口：`8081`。
- Vite 开发端口：`5173`，`/api` 和 `/oauth2` 代理到 `http://localhost:8081`。
- Python 示例与组件 README 当前统一使用：`5002`。
- 默认 Spring profile：`test`。
- `test` 使用 PostgreSQL；`dev` 使用 SQLite；`prod` 使用 PostgreSQL。
- 默认 `test` PostgreSQL 连接回退值位于 `application-test.yml`，不要假设它是隔离数据库。
- 外部邮件服务默认地址：`http://localhost:8095`。
- React 生产构建直接写入 `src/main/resources/static/`，该目录是生成物并被 gitignore。
- OAuth2 callback 和 `app.frontend.url` 当前包含部署域名硬编码；本地 OAuth2 流程需要显式覆盖配置。

## Critical Safety Rule

不要在未确认数据库目标和数据可丢弃前运行应用。

`DevEnvironmentInitializer` 和 `TestEnvironmentInitializer` 都会在每次启动时：

1. 删除全部 `user_login_methods`。
2. 删除全部 `users`。
3. 重建 `testlocal`、`testsso`、`testboth` 三个演示账户，密码为 `password123`（有本地方式的账户）。

因此裸跑 `mvn spring-boot:run` 会激活默认 `test` profile，并可能清空默认或环境变量指向的 PostgreSQL 用户数据。
构建和测试优先使用不会启动应用的命令。确需启动时，先明确 profile、数据库、端口和数据处置。

仓库根目录 `.env`、`jwt-secret.key`、OAuth2 凭据和数据库密码属于敏感信息。不要打印、提交或写入文档。
`rsa-keys.ser` 当前已被 Git 跟踪，包含 JWT 私钥材料；不要无意轮换、覆盖或公开它。

## Security Filter Chains

安全改动必须同时审查四条有序链：

1. `AuthApiConfig`, `@Order(0)`: `/api/auth/**`，全部 `permitAll`，CSRF 禁用。
2. `AuthorizationServerConfig`, `@Order(1)`: 指定的 `/oauth2/*` Authorization Server 端点。
3. `ResourceServerConfig`, `@Order(2)`: `/api/**`，Bearer JWT 来自 `Authorization` header 或 `accessToken` cookie。
4. `SecurityConfig`, `@Order(3)`: OAuth2 登录、SPA/Web 路由、其余授权规则。

`/api/auth/**` 不经过资源服务器链。需要认证的子接口必须像 Web3 bind 一样自行验证凭据，或调整 matcher 设计。

CORS 目前同时存在于 `CorsConfig`、`WebConfig`、`WebMvcConfig` 和 YAML。修改来源、header 或 method 时必须检查全部实现，避免只改一处。

## Identity And Token Invariants

- `users.id` 是 36 字符 UUID string。
- 一个用户可绑定多个 `user_login_methods`。
- 登录方式枚举是 `LOCAL`, `GOOGLE`, `GITHUB`, `TWITTER`, `WEB3`。
- Spring registration id 使用 `x`，业务枚举仍使用 `TWITTER`；OAuth2 成功处理器负责映射。
- 同一用户同一 provider 只能有一个登录方式。
- 本地用户名全局唯一；provider + provider user id 全局唯一。
- 不能删除用户最后一个登录方式。
- 每个用户预期只有一个 primary 登录方式。

JWT 由 `JwtTokenService` 使用 RS256 签发：

- `sub`: 用户 UUID，不是用户名。
- `userId`: 用户 UUID。
- `username`: 实际用户名。
- `email`, `authorities`, `type`, `jti` 为自定义 claims。
- access token 默认 1 小时；refresh token 默认 7 天。
- issuer 默认 `https://auth.example.com`。
- audience 默认 `resource-server`。
- `kid` 默认 `key-1`。
- 读取用户名时先读 `username` claim，再回退 `sub` 兼容旧 token。

认证响应通常双重传递 token：HttpOnly cookie + JSON body。前端把 access token 放入 localStorage 以支持异构资源服务器测试。
不要把这种演示策略直接描述为生产最佳实践。

`jwt.rsa.key-file` 目前不能完整控制启动时加载的密钥：`JwtTokenService` 构造函数硬编码先读取 `rsa-keys.ser`。
修改密钥路径或轮换逻辑时必须先修正初始化顺序并验证 JWKS、旧 token 和 Python 资源服务器。

## Main Flows And Ownership

- 本地注册/登录/登出：`AuthController`, `UserService`, `CustomUserDetailsService`。
- 当前用户 canonical API：`GET /api/user`，由 `ApiAuthController` 提供并由资源服务器链保护。
- Token 生成/解析：`JwtTokenService`。
- Token 刷新：`TokenController`, `TokenRefreshService`。
- JWKS/introspection：`OAuth2TokenController`。
- OAuth2 登录与绑定：`SecurityConfig.oauth2SuccessHandler`, `UserService.getOrCreateOAuthUser`。
- 登录方式管理：`LoginMethodController`, `LoginMethodService`。
- 邮箱验证码：`EmailAuthController`, `EmailVerificationCodeService`。
- 密码重置：`ForgotPasswordController`, `ForgotPasswordService`。
- Web3/SIWE：`Web3AuthController`, `Web3AuthService`, `Web3NonceService`, `Web3SignatureUtils`。
- 前端 API 边界：`frontend/src/services/authService.ts`。
- 前端认证状态：`frontend/src/hooks/useAuth.ts`。
- SPA 路由：`frontend/src/App.tsx` 和后端 `SpaController`。

## Database Reality

启动时实际使用 Spring SQL init 和 Hibernate 配置：

- `dev`: `schema-sqlite.sql` + `data-sqlite.sql`, `ddl-auto: none`。
- `test`: `schema-postgresql.sql`, `ddl-auto: update`。
- `prod`: SQL init 关闭，`ddl-auto: validate`，需要外部预置 schema。

`src/main/resources/db/migration/V*.sql` 看起来像 Flyway migration，但 `pom.xml` 没有 Flyway 依赖，当前不会自动执行。
不要只新增 migration 文件就认为数据库已更新。

修改 entity/schema 时至少核对：

- `schema-postgresql.sql`
- `schema-sqlite.sql`
- 相关 profile 的 `ddl-auto` / SQL init 行为
- `scripts/export-schema-pg.sh` 的表清单
- 生产数据库的真实迁移方式

当前 SQLite schema 明显落后于实体和 PostgreSQL 功能：缺少 `web3_nonces`、`email_verification_codes`，且 `user_login_methods` 缺少部分映射列。
不要把 fresh `dev` profile 当作完整功能环境，除非先修复 schema。

Spring Session 使用 JDBC。生产环境不会自动建 session 表。

## Frontend Workflow

编辑 `frontend/src/**`，不要手改 `src/main/resources/static/**`。

```bash
cd frontend
npm run build
```

Vite 会清空并重建 Spring Boot 静态资源目录。当前构建成功，但主 JS chunk 超过 500 kB。

`npm run lint` 当前不可用，因为仓库没有 ESLint 配置文件；不要报告 lint 通过。

前端类型中仍有历史漂移，例如登录方式 id 在后端是 UUID string，但部分 service 方法参数声明为 `number`。跨端修改时核对真实 JSON，不要只信 TypeScript 现状。

## Verification Commands

不会启动 Spring 应用的基础验证：

```bash
mvn clean test
cd frontend && npm run build
bash -n build-frontend.sh start.sh start-with-frontend.sh scripts/*.sh
python3 -c 'from pathlib import Path; [compile(p.read_text(), str(p), "exec") for root in ("python-resource-server", "scripts") for p in Path(root).glob("*.py")]'
```

已知状态：

- `mvn clean test` 当前成功，但 `src/test` 没有测试源码，不能视为行为测试。
- 前端 build 当前成功。
- 前端 lint 当前失败，原因是缺少 ESLint 配置。
- Shell 与 Python 语法检查当前成功。
- OAuth2、邮件、Web3、PostgreSQL 脚本属于外部依赖集成测试，运行前必须确认凭据、服务、数据库和副作用。

`start.sh` 会查找仓库外的 Google client-secret JSON；`start-with-frontend.sh` 要求所有 OAuth2 环境变量。
它们不是比显式 Maven/Vite 命令更可靠的默认入口，使用前先读脚本。

## Current Rough Edges

在依赖相关功能前先验证这些已知风险：

- 邮箱验证码发送与持久化分别调用了两次随机码生成，邮件中的 code 可能与数据库中的 code 不同。
- 密码重置邮件模板使用硬编码 `123456`，随后又创建独立随机验证码。
- token blacklist 有 entity/repository/schema，但没有接入 token 验证或登出撤销流程。
- OAuth2、local login、refresh、Web3 的 cookie `Secure` 设置不一致。
- `ApiAuthController` 对 JWT 用户把 provider 默认标成 `local`，不能反映真实主登录方式。
- Python 资源服务器仍把 JWT `sub` 当作 username 展示，而当前 `sub` 实际是 UUID。
- Python 资源服务器把认证服务器地址硬编码为外部隧道域名。
- Web3 登录响应中的 `isNewUser` 和 bind 返回值处理需要重新核验。
- live 文档、Nginx 和测试脚本已统一到后端 `8081`、Python `5002`；
  历史材料仍保留旧端口，并在顶部标注其生命周期。

这些条目是工作提示，不代替针对当前任务的代码阅读和测试。

## Change Discipline

- 保持改动紧贴请求，不顺手重写历史文档或大规模清理认证架构。
- 安全、token、cookie、OAuth2 callback、CORS 和 schema 改动具有跨模块影响，必须同时检查后端、前端和 Python 示例。
- API 响应变更要同步 `frontend/src/services/authService.ts`、类型、调用页面和相关脚本。
- JWT claim 变更要同步 ResourceServer、OAuth2 introspection、Python 示例和文档。
- provider 命名变更必须处理 `x` 与 `TWITTER` 的映射。
- 不要把 `docs/drafts/` 中的规划代码当成已实现事实。
- 增加后端行为时优先补真实测试；当前测试空白是主要风险。
- 完成工作后检查 `git status`，不要提交 `.env`、数据库、key、报告、`target/`、`node_modules/` 或静态构建产物。
