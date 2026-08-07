# UniAuth Agent Guide

本文件是面向后续编程代理的仓库级长期记忆。它记录当前代码的真实结构、稳定约定和高风险操作边界。
当本文与代码冲突时，以当前代码和配置为准；`README.md`、`docs/Perplexity/`、`docs/drafts/` 中有较多历史方案和过时端口。

## Documentation Map

- `docs/README.md`: 文档体系主入口和生命周期分类。
- `docs/ARCHITECTURE.md`: 当前模块、安全链、身份和 JWT 模型。
- `docs/CONFIGURATION.md`: 端口、profile、数据库、回调、CORS 和密钥基线。
- `docs/DEVELOPMENT.md`: 安全构建、启动前检查和日常工作流。
- `docs/VERIFICATION.md`: 权威交付门槛、验证层级、2026-08-07 基线和测试缺口。
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
- 默认不激活任何 Spring profile；启动者必须显式选择 `dev`、`test` 或 `prod`。
- `test` 使用 PostgreSQL；`dev` 使用 SQLite；`prod` 使用 PostgreSQL。
- `test` PostgreSQL 的 host、port、database、user 和 password 都必须显式提供。
- 外部邮件服务默认地址：`http://localhost:8095`。
- React 生产构建直接写入 `src/main/resources/static/`，该目录是生成物并被 gitignore。
- OAuth2 callback 和 `app.frontend.url` 当前包含部署域名硬编码；本地 OAuth2 流程需要显式覆盖配置。

## Critical Safety Rule

不要在未确认 profile、数据库目标和数据处置前运行应用。

仓库不再提供默认 profile，旧的 `DevEnvironmentInitializer` 和
`TestEnvironmentInitializer` 已删除。演示数据默认关闭，只在 `dev`/`test`、
`app.demo-data.enabled=true`、`app.demo-data.disposable=true` 且数据库名符合
test/demo 安全规则时 upsert 三个受管账户；它不得执行全表删除。

根启动脚本默认选择隔离的 `dev` SQLite 目标；直接运行 Maven 时仍必须显式选择 profile。
`test` profile 的 SQL init/Hibernate 仍会改变 schema，只能指向明确可丢弃的 PostgreSQL。

仓库根目录 `.env`、`jwt-secret.key`、OAuth2 凭据和数据库密码属于敏感信息。不要打印、提交或写入文档。
历史提交中的 `rsa-keys.ser` 包含已暴露的 JWT 私钥材料，不能继续信任或恢复到版本控制。
本地运行生成的同名文件必须保持 ignored；真实环境需要独立轮换和外部密钥管理。

## Security Filter Chains

安全改动必须同时审查四条有序链：

1. `AuthApiConfig`, `@Order(0)`: `/api/auth/**`，按 HTTP method/path 公开 allowlist，
   其余请求 `denyAll`，CSRF 禁用。
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

`JwtTokenService` 构造时读取 `jwt.rsa.key-file`；默认路径是 ignored 的
`.local/uniauth/rsa-keys.ser`。已有密钥无法解析或 POSIX 权限过宽时启动失败，
新生成的本地密钥会收紧为 owner-only。修改密钥格式、路径或轮换逻辑时必须验证
JWKS、旧 token 和 Python 资源服务器；真实环境仍需外部密钥管理。

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
mvn clean compile test-compile
mvn test
cd frontend && npx tsc --noEmit && npm run build && npm run test:e2e
bash -n build-frontend.sh start.sh start-with-frontend.sh scripts/*.sh
python3 -c 'from pathlib import Path; [compile(p.read_text(), str(p), "exec") for root in ("python-resource-server", "scripts") for p in Path(root).glob("*.py")]'
(cd python-resource-server && python3 -m unittest -v test_app.py)
```

已知状态：

- 2026-08-07 Phase 0 门禁执行了 28 个 Java tests、5 个 Python tests 和
  1 个 Mock Playwright test；后续变更必须重新运行，不能继承该结果。
- 前端已包含最小 Playwright Mock harness；`npm run test:e2e` 是触达认证页面时的门禁。
- 前端 lint 当前失败，原因是缺少 ESLint 配置。
- Python 资源服务器已有离线 RSA/JWKS/Flask 测试。
- OAuth2、邮件、Web3、PostgreSQL 脚本属于外部依赖集成测试，运行前必须确认凭据、服务、数据库和副作用。

`start.sh` 可读取显式环境变量或指定的 Google client JSON；
`start-with-frontend.sh` 要求所有 OAuth2 环境变量。两者都经过
`scripts/runtime-guard.sh`，但启动前仍应检查其 profile 和数据库目标。

## Delivery Gate

每次准备交付前必须遵守 `docs/VERIFICATION.md` 的完整验收协议：

- 后端至少通过本任务相关集成测试、`mvn clean compile test-compile` 和完整 Maven 测试。
- 集成测试尽可能覆盖 HTTP、安全链和持久化边界；不得只靠单元测试或代码 review 报告完成。
- 并发修复优先数据库约束、条件更新、乐观锁或 CAS，禁止把悲观锁当默认方案；
  不要为了形式上的“乐观锁”自动增加 JPA `@Version`。
- 触达前端时至少通过 `npx tsc --noEmit`、生产构建和覆盖核心改动的 Mock 浏览器测试。
- 触达 Python 资源服务器时必须通过离线 RSA/JWKS/Flask 测试，真实 JWKS 网络验证只能显式 opt in。
- 需要启动服务时，只能使用显式 profile 和隔离可丢弃数据库；联调不能替代两端各自的自动化验证。
- 不把可自动化的首轮验收转交给用户；“已完成”必须有可复现的测试证据。
- 基础验证全部通过后，才执行固定范围的三轮收敛检查。任何实质问题引发的修改都将
  计数器归零，只有连续三轮无问题、无修改才结束。

## Current Rough Edges

在依赖相关功能前先验证这些已知风险：

- 邮箱验证码发送与持久化分别调用了两次随机码生成，邮件中的 code 可能与数据库中的 code 不同。
- 密码重置邮件模板使用硬编码 `123456`，随后又创建独立随机验证码。
- token blacklist 有 entity/repository/schema，但没有接入 token 验证或登出撤销流程。
- OAuth2、local login、refresh、Web3 的 cookie `Secure` 设置不一致。
- `ApiAuthController` 对 JWT 用户把 provider 默认标成 `local`，不能反映真实主登录方式。
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
- 增加或修改后端行为时必须同步补充相应的集成/行为测试。
- 完成工作后检查 `git status`，不要提交 `.env`、数据库、key、报告、`target/`、`node_modules/` 或静态构建产物。
