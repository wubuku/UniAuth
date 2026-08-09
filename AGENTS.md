# UniAuth Agent Guide

本文件是面向后续编程代理的仓库级长期记忆。它记录当前代码的真实结构、稳定约定和高风险操作边界。
当本文与代码冲突时，以当前代码和配置为准；`README.md`、`docs/Perplexity/`、`docs/drafts/` 中有较多历史方案和过时端口。

## Documentation Map

- `docs/README.md`: 文档体系主入口和生命周期分类。
- `docs/ARCHITECTURE.md`: 当前模块、安全链、身份和 JWT 模型。
- `docs/CONFIGURATION.md`: 端口、profile、数据库、回调、CORS 和密钥基线。
- `docs/DEVELOPMENT.md`: 安全构建、启动前检查和日常工作流。
- `docs/VERIFICATION.md`: 权威交付门槛、验证层级、2026-08-09 基线和测试缺口。
- `docs/EMAIL_LOGIN_BROWSER_E2E.md`: 真实邮箱注册/登录、前端回跳、跨 origin
  Python API Bearer 访问和独立服务脚本。
- `docs/drafts/README.md`: 既有计划、调查和历史记录索引。
- `docs/drafts/DOCUMENTATION_PLAN.md`: 文档体系建设计划。
- `docs/drafts/NEXT_HARDENING_IMPLEMENTATION_PLAN.md`: 下一轮测试优先实施切片。
- `docs/archive/database/README.md`: 旧 SQL 的历史归档和当前替代路径。
- `reference/email-service/README.md`: 外部邮件 REST 服务的独立参考实现、Flyway 和 E2E。

已有文档保持原路径。新指南链接历史材料，不通过搬迁或复制来“整理”目录。

## Big Picture

UniAuth 是一个单仓库认证系统，包含三个主要运行部分和一个独立参考组件：

- `src/main/java/org/dddml/uniauth/`: Spring Boot 3.3.4 / Java 17 后端。
- `frontend/`: React 18 + TypeScript + Vite SPA。
- `python-resource-server/`: Flask 异构资源服务器示例，通过 JWKS 验证 UniAuth JWT。
- `reference/email-service/`: 外部邮件 REST 服务参考实现；独立 Maven 工程，不纳入根构建。

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
- 前端 lint/build 要求 Node.js `20.19+`、`22.13+` 或 `24+`；CI 使用 Node `20.19`。
- Python 示例与组件 README 当前统一使用：`5002`。
- Python 组件是纯 REST API；资源展示页是 React `/resource-test`。当前跨 origin
  演示读取登录/注册 JSON 中的 access token，写入 localStorage 后构造 Bearer
  header；HttpOnly Cookie 不能替代该跨 host header。该路径仅用于演示，不是生产
  token 存储建议。独立资源前端域需要 OAuth/OIDC Code+PKCE 或 BFF。
- 默认不激活任何 Spring profile；启动者必须显式选择 `dev`、`test` 或 `prod`。
- `dev`、`test`、`prod` 只支持 PostgreSQL 16；当前不维护 PostgreSQL 15 或
  SQLite 兼容路径。
- 三个 profile 的 host、port、database、user 和 password 都必须显式提供。
- Flyway 是唯一 schema owner；当前 runtime migration 链是 PostgreSQL V1 baseline +
  V2 登录方式约束 + V3 登录方式 revision CAS + V4 实体约束与索引对齐 + V5
  Web3/SIWE challenge message 绑定。
- Hibernate 使用 `validate`；SQL init 和 Spring Session 自动建表均关闭。
- 外部邮件服务默认地址：`http://localhost:8095`。
- UniAuth 主应用只实现邮件服务 HTTP 适配器；真实邮箱注册验证和密码重置需要独立
  邮件服务满足当前 HTTP、模板和响应契约。`reference/email-service/` 提供可运行参考，
  但不会由根应用自动启动；普通邮箱加密码登录不发信。客户端 timeout 默认 5 秒，
  可选通过 `X-Email-Service-Key` 使用共享密钥。配置密钥时，兼容服务必须只接受
  恰好一个该 header 且整值精确匹配；缺失、错误或重复同名凭据都必须返回 `401`。
- 参考邮件服务的 `prod` profile 只接受强制 STARTTLS 或 implicit SSL，禁止两者同时
  启用，并要求 SMTP server identity verification。`dev/test` 明文 SMTP 只用于
  loopback GreenMail 等隔离夹具；Shell 与 Spring ApplicationContext 都执行保护。
- 参考邮件服务的 `SMTP_HOST` 必须是无 URI 语法、空白或控制字符的 host/IP token；
  `SMTP_PORT` 必须是 `1..65535`。Shell 和 Java guard 校验同一有效 endpoint，
  PostgreSQL ApplicationContext 断言配置进入真实 `JavaMailSender`。
- 参考邮件服务的 `dev`、`test`、`prod` profile 只接受 PostgreSQL datasource；
  H2 不再是测试后端。默认 `EMAIL_DATABASE_LAYOUT=dedicated` 要求邮件专用数据库；
  `shared-uniauth` 是显式 opt-in，可在获准的空 `public` schema 先迁移邮件 V1-V3，
  也可加入完整 UniAuth V1-V5 peer。两种启动顺序都使用独立 history；后启动一侧
  验证完整 peer 后创建自己的 V0 baseline，两侧通过同一 PostgreSQL advisory lock
  串行化首次 baseline/migrate。Java guard 会在 Flyway 前拒绝非 PostgreSQL、未知
  layout、受保护数据库和不完整 peer。存在 peer relation 却缺少 peer history 属于
  半成品布局，必须失败关闭。
- 两侧业务 relation 命名没有冲突：邮件组件只拥有 `email_queue`、`email_logs`、
  对应序列/索引/约束；UniAuth 拥有认证、Session 与
  `uniauth_flyway_schema_history`。共享部署需要兼容的是非空 schema 下的 Flyway
  history 发现语义，不是表重名。
- React 生产构建直接写入 `src/main/resources/static/`，该目录是生成物并被 gitignore。
- OAuth2 callback 和 `app.frontend.url` 当前包含部署域名硬编码；本地 OAuth2 流程需要显式覆盖配置。

## Critical Safety Rule

不要在未确认 profile、数据库目标和数据处置前运行应用。

仓库不再提供默认 profile，旧的 `DevEnvironmentInitializer` 和
`TestEnvironmentInitializer` 已删除。演示数据默认关闭，只在 `dev`/`test`、
`app.demo-data.enabled=true`、`app.demo-data.disposable=true` 且数据库名符合
test/demo 安全规则时 upsert 三个受管账户；它不得执行全表删除。

根启动脚本默认选择 `dev`，但不会提供数据库回退；必须显式设置 `POSTGRES_*`。
`dev` 只接受 dev/test/demo 命名的非生产数据库，`test` 只接受明确可丢弃的
test/demo 数据库。自动化测试必须使用 Testcontainers，不得读取 `.env`。

共享 `blacksheep_dev` 只允许显式只读预检和 rehearsal。普通测试、开发验证和
migration 测试不得连接它；baseline/apply 仍需要用户单独授权和精确 confirmation
token。不要因为本地已有凭据就推断获得了写权限。

Flyway V1 源自 2026-08-07 对 `blacksheep_dev` 的只读 schema 导出。
该库已通过只读 rehearsal，但尚未执行 baseline apply。未经用户显式授权和精确
confirmation token，不得对其创建 `uniauth_flyway_schema_history` 或执行 pending
migrations。apply 前必须重新核对源 schema 指纹、V2 数据预检和 history table，
以及 V4 实体契约预检，不能沿用长时间 rehearsal 开始时的旧状态；apply 后必须与
rehearsal 的 fresh 最新迁移结果一致。

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
- `users.login_methods_revision` 是登录方式集合变更的用户级乐观 CAS token；
  remove/set-primary 竞争只有一个请求取得变更权，失败方返回稳定 `409`。
- `LOCAL` 同时承载用户名/密码和邮箱验证码绑定；后者允许
  `local_password_hash IS NULL`。数据库行形状约束不得假设每个 LOCAL 方式都有密码。

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

认证响应通常双重传递 token：HttpOnly cookie + JSON body。所有签发入口通过
`AuthCookieService` 统一写入 access/refresh Cookie；base/dev/test 使用本地 HTTP
兼容值，`prod` 必须同时保持认证 Cookie 和 Session Cookie 的 `Secure=true`，更高
优先级配置若将任一值覆盖为 false，ApplicationContext 启动失败。前端只把 access
token 放入 localStorage 以支持异构资源服务器测试，并在启动时移除历史
`refreshToken` key；不要把这种演示策略直接描述为生产最佳实践。

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

## Email Service Boundary

`RestTemplateEmailServiceImpl` 是 UniAuth 到外部邮件服务的唯一生产实现。它要求：

- `GET /api/email/health` 返回 JSON `status=UP`。
- `POST /api/email/template` 接收 `to`、`subject`、`templateName`、`variables`
  和 `emailType`，并以 JSON `success=true` 表示已接受或入队。
- 外部服务提供 `email/email-verify` 和 `email/password-reset` 模板，使用
  `username`、`verificationCode` 和 `expiryMinutes`，并兼容同时发送的 `code`。
- `EMAIL_SERVICE_TIMEOUT_MS` 同时约束 connect/read timeout；`EMAIL_SERVICE_API_KEY`
  非空时，所有 health/template/simple 请求各发送一个 `X-Email-Service-Key`；
  外部服务只能接受恰好一个该 header 且整值精确匹配，不能按首值或末值消解重复
  凭据；缺失、错误或重复同名 header 都必须返回 `401`。密钥最长 1024 字符且不能
  包含 CR/LF。
- `EMAIL_SERVICE_URL` 必须是带 host 的绝对 HTTP/HTTPS URL，禁止 userinfo、query
  和 fragment；允许 context path 和尾部斜杠。timeout 范围是 `100..600000ms`。

真实邮箱注册验证和密码重置依赖该服务及其下游 SMTP/供应商配置。普通
`POST /api/auth/login` 的邮箱加密码登录不调用邮件服务；虽然 enum 和前端类型仍有
`LOGIN` purpose，当前没有受支持的邮箱验证码登录 endpoint。

只有外部邮件服务同步返回 `SUCCESS`/`QUEUED` 时 UniAuth 才保存 challenge；拒绝、
限流、非法邮箱、超时、网络异常和空结果都失败关闭。正确验证码只允许通过按已选
challenge id 和 code 的 PostgreSQL 条件更新消费一次；controller 不得再按
email/purpose 二次标记，否则可能误消费验证期间新建的 challenge。该流程仍不是生产
可靠状态机：
外部服务已接受后，如果本地 challenge 事务失败，用户可能收到不可用验证码；异步
delivery 失败也不会自动撤销 challenge。Java 测试使用完整 ApplicationContext、
  PostgreSQL 和真实业务 Bean，只 mock 最外层 `EmailService`；独立 client 集成测试和
  Shell E2E 使用真实 `RestTemplate`。根 HTTP E2E 的正常邮箱流程启动
  `reference/email-service` 的真实 JAR、独立 PostgreSQL 和真实 HTTP 端口，直接断言
  模板进入其 `email_queue`；只有参考实现不会自然产生的 `503/429` 失败映射场景才
  切换到受控 loopback stub。这些测试不证明真实供应商送达。`reference/email-service`
  的默认 E2E 通过真实 HTTP、Flyway/PostgreSQL、Spring Beans、Thymeleaf、异步队列和
  GreenMail 验证兼容实现。

参考邮件服务的 schema 由其自己的 Flyway V1/V2/V3 管理，history table 是
`email_service_flyway_schema_history`；所有 profile 使用 Hibernate `validate`，
SQL init 关闭。默认使用独立 PostgreSQL；显式 `shared-uniauth` 允许在获准的空
`public` schema 先启动任一侧，或与完整 UniAuth V1-V5 peer 共存，且不得连接
`blacksheep*` 或其他未获准共享库。后启动一侧只有在对端 history/核心 relation
完整、本侧 relation 不存在且对端没有失败 migration 时，才在共享 advisory lock
内创建 baseline V0；全局
`baseline-on-migrate` 仍固定为 `false`。H2 或其他 datasource 即使在 `test` profile
也会在 Flyway 前失败。修改该组件时：

```bash
cd reference/email-service
scripts/verify.sh
```

默认测试使用 disposable PostgreSQL、进程内 GreenMail 和无投递副作用的 Shell
进程门禁，不读取 `.env`，也不发送真实邮件。`start.sh` 还会拒绝不安全 env 文件、
不符合所选 layout 的数据库和无 API key 的非 loopback 暴露。真实 SMTP/供应商验证
仍须显式 opt in。恢复任务只有在邮件总开关、队列和 recovery 都启用时才处理存量；配置、
实体、事件和请求 DTO 不得通过自动 `toString()` 泄露 API key、收件人、验证码或
HTML。最终 SMTP 投递不得只信任 HTTP 入队校验；从 PostgreSQL 读取的 recipient、
subject、HTML 和自定义 header token 必须在构造 MIME 前重新校验。
Flyway V3 约束队列生命周期行形状：终态必须有 `processed_time`，只有 `PENDING`
可以保留 `next_retry_time`，只有 `FAILED` 可以保留 `error_message`；claim、retry、
完成和永久失败转换必须维护同一规则。
邮件 PostgreSQL 组件备份使用
`reference/email-service/scripts/backup-postgres.sh`：它只读取显式配置，支持
`dedicated` 和显式 `shared-uniauth`，但只导出邮件队列、日志、对应序列和邮件
Flyway history，不包含 UniAuth 用户或认证表。脚本拒绝未知布局、缺失邮件 schema、
非精确 V1-V3 migration 链、不安全目录和 PostgreSQL client/server major 不一致，
以临时文件校验后发布 `0600` archive/checksum。共享库的整库灾备仍由仓库外经过
授权的数据库运维流程负责；组件
备份不能替代整库备份。默认 restore 自动化只在 disposable 空库 rehearsal 中执行，
不得覆盖现有共享或生产数据库。
非法队列载荷的失败审计只保留 queue id、通用错误和安全占位字段，不复制恶意
recipient、subject、HTML 或 header token；非法 `sendMethod` 必须降级为
`UNKNOWN`，不能让 `email_logs` 写入失败并回滚 retry。
event 和 recovery 共用的单进程限流 slot 在 claim 返回 false 或抛异常时必须释放；
一旦进入 delivery bean 就按一次投递尝试计数，即使后续失败或抛异常也不归还，
只有 `SKIPPED` 表示未发生投递并释放 slot。每次取得 slot 都返回绑定当前窗口
generation 的幂等 reservation；旧窗口的迟到释放不得扣减新窗口额度，临时关闭
限流也不得阻止释放原窗口的 reservation。

## Database Reality

PostgreSQL 是唯一受支持数据库。Flyway 配置：

- location: `classpath:db/migration/postgresql`
- history: `uniauth_flyway_schema_history`
- `fail-on-missing-locations=true`
- `baseline-on-migrate=false`
- `baseline-version=0`
- `clean-disabled=true`
- `validate-migration-naming=true`
- `validate-on-migrate=true`
- `out-of-order=false`

UniAuth 的自定义 migration strategy 会拒绝上述 schema owner 配置被外部覆盖，
也会拒绝 `spring.flyway.enabled=false`；Shell 启动保护执行相同检查。共享
`public` schema 中一旦存在邮件服务 history，双方每次启动都重新校验对端 history
和核心 relation；peer history 必须恰好包含当前预期的成功 SQL 版本，另只允许
0 或 1 个成功 V0 baseline，不接受失败、重复、未知 versioned 或 repeatable 记录。
存在 peer relation 却没有 peer history 同样失败关闭。邮件服务还必须持续显式选择
`shared-uniauth`，不能只在首次 baseline 时 opt in。

V1 精确复现获准的 8 张 dev auth/session 表；V2 对齐登录方式时区/nullability，
增加 provider/行形状约束和每用户至多一个 primary 的唯一索引；V3 增加用户级
`login_methods_revision`，用于登录方式集合变更的乐观 CAS；V4 对齐 users、Web3
nonce、email verification 和 token blacklist 的既有实体约束，补齐 email repository
索引并移除有等价唯一/规范索引覆盖的重复索引；V5 将 Web3 nonce 绑定到服务端签发
的完整 SIWE message，并通过 PostgreSQL 条件删除完成一次性消费。V5 发布时会失效
所有旧的未消费 Web3 challenge；后续结构修复必须新增 V6+，不得修改已经发布或
baseline 的 V1/V2/V3/V4/V5 checksum。

修改 entity/schema 时至少核对：

- `src/main/resources/db/migration/postgresql/`
- 三个 profile 的 Flyway/`ddl-auto`/SQL init/Session init 行为
- `scripts/export-schema-pg.sh` 的表清单
- fresh migrate、existing-schema baseline、Hibernate validate 和 Session round-trip 测试
- 生产/开发库的 preflight、备份、forward-fix 和显式 apply 流程

历史 V1-V4、V6-V8 及旧 PostgreSQL/SQLite init SQL 已原样归档到
`docs/archive/database/legacy-sql/`，不得恢复到 runtime classpath。

## Frontend Workflow

编辑 `frontend/src/**`，不要手改 `src/main/resources/static/**`。

```bash
cd frontend
npm run build
```

Vite 会清空并重建 Spring Boot 静态资源目录。当前构建成功，但主 JS chunk 超过 500 kB。

前端已提供与当前 React/TypeScript 工具链匹配的 ESLint 配置。修改前端时至少执行：

```bash
cd frontend
npm run lint
npx tsc --noEmit
npm run build
npm run test:e2e
```

登录方式 id 的前端类型已统一为 UUID string。跨端修改时仍要核对真实 JSON，
并同步 service、types、页面、Playwright 和 Shell contract。
`GET /api/user` 的 wire 字段是 `userId`、`userName`、`userEmail`；Playwright route
必须按真实响应形状提供当前用户。跨页面认证测试要断言导航和 `checkAuth()` 完成后的
稳定状态，不能依赖登录/验证响应写入 localStorage 后被当前用户接口覆盖前的瞬时值。

## Verification Commands

快速分层验证：

```bash
mvn clean compile test-compile
mvn test
cd frontend && npm run lint && npx tsc --noEmit && npm run build && npm run test:e2e
bash -n build-frontend.sh start.sh start-with-frontend.sh scripts/*.sh \
  scripts/email-login-e2e/*.sh \
  reference/email-service/start.sh reference/email-service/scripts/*.sh
python3 -c 'from pathlib import Path; [compile(p.read_text(), str(p), "exec") for root in ("python-resource-server", "scripts") for p in Path(root).glob("*.py")]'
(cd python-resource-server && python3 -m unittest -v test_app.py)
PYTHON_BIN=python3 scripts/test-email-login-browser-e2e.sh
```

完整仓库门禁会启动 disposable PostgreSQL、真实 Spring 应用和 Mock 浏览器，不读取
`.env`，也不接触共享开发库；前端依赖先通过无宽松参数的 `npm ci` 干净安装：

```bash
PYTHON_BIN=python3 scripts/verify.sh
```

当前验证基线（2026-08-09 工作树；每次后续变更仍须重跑）：

- 当前根统一门禁：Maven 151 tests、shared-schema process E2E 4/4、
  HTTP 15/15、Flyway baseline guard 14/14、Mock Playwright 27/27、
  真实邮箱登录浏览器 E2E 1/1、Python 资源服务器 18/18、邮件 REST stub
  contract 9/9；前端严格 `npm ci`、audit、lint、typecheck、build、文档链接和
  patch hygiene 均通过。
- 当前邮件参考服务：148 tests，0 failures/errors/skips；Shell runtime 43/43、
  HTTP 11/11、Flyway guard 15/15、backup/restore rehearsal 10/10。
- 以下 2026-08-08 条目保留为加固增量历史，不替代上述当前基线。
- 邮件参考服务初始纳入基线：94 tests，0 failures/errors/skips；另有 runtime guard 15/15、
  Shell HTTP 8/8 和 Flyway guard 8/8。
- 2026-08-08 SMTP transport 加固增量：邮件参考服务 101 tests，
  Java runtime guard 17 tests，Shell runtime guard 21/21；HTTP 8/8 和 Flyway
  guard 8/8 保持通过。
- 2026-08-08 SMTP endpoint 加固增量：邮件参考服务 108 tests，
  Java runtime guard 24 tests，Shell runtime guard 27/27；HTTP 8/8 和 Flyway
  guard 8/8 保持通过。
- 2026-08-08 持久化队列投递边界加固增量：邮件参考服务 110 tests，
  16 个 PostgreSQL/GreenMail ApplicationContext E2E；runtime 27/27、HTTP 8/8
  和 Flyway guard 8/8 保持通过。
- 2026-08-08 限流 reservation 异常路径加固增量：邮件参考服务 116 tests，
  18 个 PostgreSQL/GreenMail ApplicationContext E2E；runtime 27/27、HTTP 8/8
  和 Flyway guard 8/8 保持通过。
- 2026-08-08 限流 reservation 窗口 ownership 与附加 E2E 加固增量：邮件参考服务
  124 tests，20 个 PostgreSQL/GreenMail ApplicationContext E2E；runtime 27/27、
  HTTP 9/9、Flyway guard 9/9。HTTP E2E 断言 queue detail 不返回 HTML/metadata，
  且当前验证码夹具值不出现在响应中；Flyway guard 断言 checksum drift 失败关闭、
  漂移 checksum 保持、数据不变且可显式恢复。
- 2026-08-08 敏感邮件 API 响应加固增量：邮件参考服务 127 tests，21 个
  PostgreSQL/GreenMail ApplicationContext E2E；runtime 27/27、HTTP 10/10、Flyway
  guard 10/10。成功、401、400、404、500 和 matrix 参数路径均固定返回
  no-store/no-cache/nosniff 安全 header，不改变 JSON body 或状态码语义。
- 2026-08-08 邮件 API 鉴权 header 单值加固增量：邮件参考服务 129 tests，22 个
  PostgreSQL/GreenMail ApplicationContext E2E；runtime 27/27、HTTP 10/10、Flyway
  guard 11/11。配置 API key 时只接受恰好一个精确匹配的 header，重复正确值、
  正确/错误和错误/正确组合均返回 `401`；Python 邮件 stub contract 8/8。
- 2026-08-08 邮件参考服务 Flyway schema-owner 覆盖保护增量：邮件服务 131 tests、
  其中 22 个 PostgreSQL/GreenMail ApplicationContext E2E、26 个 Java runtime
  guard tests；Shell runtime 37/37、HTTP 11/11、Flyway guard 12/12。测试夹具使用
  真实 disposable PostgreSQL + Flyway + Hibernate `validate`；Java/Shell guard
  均拒绝 Flyway disable/baseline/clean/validation/out-of-order、location/history/
  schema、SQL init 和 Hibernate schema-generation 覆盖。
- 2026-08-08 邮件参考服务 Flyway discovery/naming fail-closed 增量：固定
  `fail-on-missing-locations=true` 和 `validate-migration-naming=true`；Java/Shell
  guard、真实 ApplicationContext 与 Flyway baseline guard 均拒绝将其覆盖为
  `false`。邮件服务完整门禁为 Maven 131/131、Shell runtime 39/39、HTTP 11/11、
  Flyway guard 14/14。
- 2026-08-08 邮件参考服务 PostgreSQL repository fixture 加固增量：移除 H2 测试
  依赖；两个 JPA repository 测试使用 disposable PostgreSQL + Flyway +
  Hibernate `validate`，直接覆盖 retry bound 和 queue foreign key 约束。邮件服务
  完整门禁为 Maven 133/133、Shell runtime 39/39、HTTP 11/11、Flyway guard 14/14。
- 2026-08-08 邮件参考服务 PostgreSQL-only runtime guard 收敛增量：所有 profile
  在 Flyway 前拒绝 H2 和其他非 PostgreSQL JDBC URL；27 个直接 Java guard tests
  与 1 个真实 Spring ApplicationContext 启动失败测试共同固定该要求。邮件服务
  Maven 135/135、Shell runtime 39/39、HTTP 11/11、Flyway guard 14/14 已随本批
  完整统一门禁通过。
- 2026-08-08 邮件参考服务队列生命周期状态加固增量：Flyway V3 规范化历史
  `processed_time`、`next_retry_time` 和 `error_message`，并增加 PostgreSQL
  `chk_email_queue_lifecycle_state`；claim/retry/完成/永久失败维护同一行形状，不改变
  REST、SMTP 或最大重试语义。邮件服务 Maven 138/138、Shell runtime 39/39、
  HTTP 11/11、Flyway guard 15/15。
- 2026-08-08 邮件参考服务 backup/restore 运维加固增量：只读 custom backup、
  owner-only 原子 archive/checksum、同 major `pg_dump`/`pg_restore` guard 和
  disposable 空库 restore rehearsal 10/10 已完成；邮件组件 Maven 138/138、
  runtime 39/39、HTTP 11/11、Flyway 15/15 和 backup/restore 10/10 已通过，本批
  组合工作树的完整根统一门禁也已通过。
- 2026-08-08 shared-schema 共存加固增量：默认 dedicated 不变；显式
  shared-uniauth 使用独立 Flyway history、受控 baseline V0 和共同 advisory lock。
  根 Java 140/140、shared-schema 双进程 E2E 4/4；邮件组件 Maven 148/148、
  Shell runtime 43/43、HTTP 11/11、Flyway 15/15、backup/restore 10/10。
- 2026-08-08 refresh replay/logout 持久撤销增量：当前格式 access/refresh token
  使用统一严格校验，refresh `jti` 在签发新 token 的同一事务中单次消费，两个 logout
  路由持久撤销当前 token；Resource Server/introspection/Web3/OAuth2 绑定共用
  blacklist 和 disabled-user 结论。前端同 runtime single-flight 与 Web Locks 覆盖
  同页面/同源标签页，Python 同步要求 `jti`。随后发现并修复 Bearer auth scheme
  大小写解析漂移，Resource Server/logout/Web3 bind 已复用同一提取器，定向
  PostgreSQL/JWT 测试 17/17 通过。前端 refresh 结果只在 Web Lock 内持久化，
  锁外调用方不再重复写回 token；跨标签页 logout 后迟到 refresh continuation
  不能恢复认证状态。修复后的完整根 Maven 为 151/151，统一门禁通过。
- Shell HTTP E2E：15/15；正常邮箱流程使用真实参考服务，失败映射场景使用受控 stub。
- Flyway baseline guard：14/14。
- Mock Playwright：27/27；真实邮箱登录浏览器 E2E：1/1。
- Python 资源服务器：18/18；邮件 REST stub contract：9/9。
- 前端 ESLint、TypeScript 和生产构建通过。
- 每个未提交批次仍必须在完整门禁后重新执行连续三轮无修改检查；无问题轮次只记录在
  当次工作报告，不为留痕修改仓库文件。
- 前端 lockfile 已通过严格 `npm ci`；已显式升级受影响的 Axios、Ethers、
  React Router、Vite 和相关传递依赖，`npm audit --audit-level=high` 通过。
- npm audit 仍报告 2 个 React Router moderate advisories。当前 SPA 只使用
  `BrowserRouter/Routes`，导航 pathname 均为固定同源值；OAuth 错误仅作为
  `encodeURIComponent` 编码后的 `/login` query 参数，不成为导航目标。不使用 RSC、
  SSR data router 或 `deserializeErrors`；继续禁止让外部输入决定 `Link`、
  `Navigate` 或 `useNavigate` 的目标 URL，并在可用的无重叠修复版本出现后升级。
- Flyway：fresh migration、existing-schema baseline integration、checksum/failure recovery、
  guard failure matrix 和 `blacksheep_dev` 只读 rehearsal 已通过。
- `flyway-baseline-existing.sh` 的临时 Flyway 配置必须使用以 `XXXXXX` 结尾的
  portable `mktemp` 模板；macOS 不会替换带 `.conf` 后缀的模板。baseline guard
  会断言一次 rehearsal 的 5 个配置路径互不相同且均被删除，并已通过两套并行
  `12/12` guard 验证。
- `scripts/verify.sh` 是本地统一验证入口；`.github/workflows/verification.yml` 在 CI
  中执行同一入口。根入口会复制当前全部非忽略源码到进程专属临时 Git 快照后执行
  11 个阶段，避免并行 `mvn clean`、`npm ci` 或前端构建共享 `target/`、
  `node_modules/` 和静态生成物；原工作区源码在验证期间变化时失败关闭。需要保留
  Surefire 报告和 Playwright trace 时，将绝对且位于仓库外的
  `VERIFICATION_ARTIFACTS_DIR` 传给入口；CI 已固定上传该目录，不能改回原工作区
  `frontend/test-results`。根门槛必须断言邮件子门槛的 `exit_code=0` 和实际
  Surefire XML 已回传；artifact 写入失败必须令门槛失败。`SIGINT`/`SIGTERM`
  分别记录为 `130`/`143`；成功证据写入后若最终输出失败，也必须用真实非零状态
  覆写，不能生成伪成功状态。
- 邮件参考服务的统一入口会复制当前非忽略源码到进程专属临时目录后执行全部 Maven
  和 E2E 阶段，避免并行 `mvn clean` 共享 `target/`；验证期间源文件变化会失败关闭。
- 后续变更必须重新运行受影响门禁，不能继承该结果。
- Python 资源服务器已有离线 RSA/JWKS/Flask 测试。
- 真实 OAuth2、真实邮件和共享数据库写操作仍属于显式 opt-in 验证。

当前增量状态（2026-08-08）：

- 邮箱 challenge 投递接受与原子消费加固已通过完整统一门禁：外部同步拒绝/限流/异常
  不保存 challenge，注册 purpose 受限，配置驱动动态响应，正确验证码条件更新原子
  消费，controller 不再二次按 email/purpose 标记，错误重试使用 CAS；根 Java
  120 tests、HTTP 15/15、Flyway 13/13、
  Mock Playwright 20/20、Python 资源服务器 14/14 和邮件 stub 6/6 通过。
- 持久化队列投递边界加固已通过完整邮件组件门禁：邮件参考服务 110 tests、
  16 个 PostgreSQL/GreenMail ApplicationContext E2E、Java runtime guard 24 tests、
  Shell runtime 27/27、HTTP/Flyway 各 8/8；根 Java 98 tests、HTTP 14/14、
  Flyway 12/12、Mock Playwright 19/19、Python 14/14 和前端 lint/type/build 通过。
- 限流 reservation 异常路径加固的邮件组件门禁已通过：116 tests、
  18 个 PostgreSQL/GreenMail ApplicationContext E2E、Java runtime guard 24 tests、
  Shell runtime 27/27、HTTP/Flyway 各 8/8；根 Java 98 tests、HTTP 14/14、
  Flyway 12/12、Mock Playwright 19/19、Python 14/14 和前端 lint/type/build 通过。
- 限流 reservation 窗口 ownership 与附加 E2E 的邮件组件门禁已通过：124 tests、
  20 个 PostgreSQL/GreenMail ApplicationContext E2E、Java runtime guard 24 tests、
  Shell runtime 27/27、HTTP/Flyway 各 9/9；当前组合工作树的根统一门禁也已通过：
  Java 98 tests、HTTP 14/14、Flyway 12/12、Mock Playwright 19/19、Python 14/14，
  前端 lint/type/build、文档链接和 patch hygiene 通过。
- 敏感邮件 API 响应加固的邮件组件门禁已通过：127 tests、21 个
  PostgreSQL/GreenMail ApplicationContext E2E、Java runtime guard 24 tests、
  Shell runtime 27/27、HTTP/Flyway 各 10/10；Python 邮件 stub contract 7/7。
- 邮件 API 鉴权 header 单值加固的完整门禁已通过：邮件服务 129 tests、22 个
  PostgreSQL/GreenMail ApplicationContext E2E、Java runtime guard 24 tests、
  Shell runtime 27/27、HTTP 10/10、Flyway guard 11/11；根统一门禁同时通过
  Java 127、HTTP 15/15、Flyway 13/13、Mock Playwright 21/21、Python 资源
  服务器 16/16、邮件 stub contract 8/8，以及前端 lint/type/build。
- 认证 Cookie 与浏览器 refresh 存储预备切片已通过完整门禁：local、邮箱、
  Web3、OAuth2 和 refresh 复用统一 Cookie writer；prod Secure 配置不能被覆盖为
  false；Session Cookie 使用 Boot 3.3.4 的有效配置前缀；前端不再持久化 refresh
  token；Python 资源服务器拒绝 refresh 或缺少 type 的 token。
- root Flyway baseline guard 临时配置并发隔离修复已通过两套并行 `12/12` 定向
  验证，并随当前组合工作树通过完整根统一门禁。

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

- token blacklist 已接入当前格式 access/refresh 校验、refresh 单次消费、两个 logout
  路由和 introspection；当前 replay 只拒绝已消费 `jti`，尚无 token family/security
  version，不能撤销未知后继 token。Python 离线资源服务器也无法感知 PostgreSQL
  blacklist，只能依赖 access token 剩余 TTL。
- refresh token 仍出现在 JSON；access token 仍为演示目的写入 localStorage，
  header/cookie token 来源也可能冲突。前端已用 runtime single-flight 和支持浏览器的
  Web Locks 避免正常同页面/同源标签页重复 refresh，并保证跨标签页 logout 不会被
  迟到 refresh continuation 写回；彻底收敛 transport 仍属于 Batch C 原子切换。
- CORS 有 YAML 和多个 Java 配置来源；OAuth2 redirect/Referer 缺少统一 allowlist。
- `ApiAuthController` 对 JWT 用户把 provider 默认标成 `local`，不能反映真实主登录方式。
- 邮件同步接受失败和 challenge 消费并发已经失败关闭/原子化；外部接受后本地事务
  失败、异步 delivery 失败、单一 pending challenge、canonical email 和可靠
  outbox/补偿状态机仍未解决。
- Web3 V5 已严格绑定服务端保存的完整 SIWE message；nonce 生成使用 PostgreSQL
  原子 upsert，验证使用带 message/有效期条件的原子消费，旧 challenge 在迁移时失效。
- 登录方式并发 bind、set-primary、delete/delete 和 delete/set-primary 已由数据库
  约束、用户级 revision CAS 和稳定 `409` 冲突映射加固；PostgreSQL 集成测试与
  Shell HTTP E2E 持续断言最终至少一个登录方式且恰好一个 primary。
- live 端口已统一到后端 `8081`、Python `5002`；部署域名仍需外部化。

这些条目是工作提示，不代替针对当前任务的代码阅读和测试。

## Change Discipline

- 多步骤任务持续使用 plan 工具；关键提醒必须写入本文件或任务实施文档。
- 每次状态汇报都给出诚实的粗略完成百分比；发现遗漏或风险时允许回退，但必须说明
  当前固定范围和下一步如何继续收敛。
- 剩余既有功能加固按约五个中等规模批次收敛；每轮规划应有足以推动总体进度约
  `2%` 到 `3%` 的固定范围。百分比是诚实的粗略评估，不是通过增加零碎测试数量
  自动获得的积分；发现新风险时允许回退，但每轮都必须明显减少最终剩余风险。
- 加固工作采用持续循环：固定范围规划 -> PostgreSQL 集成测试与夹具 ->
  Shell/Flyway E2E -> Playwright/Python/质量工具 -> 完整硬门槛 -> 连续三轮无修改
  检查 -> 文档更新与提交推送。每轮推送后立即重新充分探索并规划下一轮，不把单个
  batch 完成当作停止点；只有用户明确要求暂停，或全面复查确认已无任何有意义的
  加固工作时才能停止。
- 当前总原则是先全面加固已有功能，不增加新功能。
- 实施前先建立集成测试、Shell E2E 和 Playwright 保护；测试必须覆盖本次修改。
- 基础门禁通过后执行连续三轮无修改检查；任何实质修改都将计数归零。
- 并发修复优先数据库约束、条件更新和 CAS，不默认使用悲观锁，也不机械引入 `@Version`。
- 未经用户允许，不运行真实高成本外部调用；真实 OAuth/mail 也不是默认门禁。
- 外部依赖下载遇到网络阻断时，可使用用户提供的本机 `http_proxy`、`https_proxy`
  和 `all_proxy` 临时注入当前命令；不要把机器专用代理地址写入仓库配置、`.npmrc`
  或可提交的环境文件。
- 工作区可能有其他开发者并行修改。绝不能丢弃、覆盖或回滚不是自己创建的改动，
  也绝不能使用 `git stash` 干扰共享工作区。
- 若其他人的修改导致编译或测试夹具暂时阻塞，只做解除验证阻塞所必需的最小测试
  适配；业务接口未稳定前不要追着中间状态修改测试，更不能擅自改写其业务实现。
- 保持改动紧贴请求，不顺手重写历史文档或大规模清理认证架构。
- 安全、token、cookie、OAuth2 callback、CORS 和 schema 改动具有跨模块影响，必须同时检查后端、前端和 Python 示例。
- API 响应变更要同步 `frontend/src/services/authService.ts`、类型、调用页面和相关脚本。
- JWT claim 变更要同步 ResourceServer、OAuth2 introspection、Python 示例和文档。
- provider 命名变更必须处理 `x` 与 `TWITTER` 的映射。
- 新增或修改外部 REST 依赖时，不能只记录 URL/端口；必须在 live 文档中说明责任边界、
  必需 endpoint、鉴权、请求/响应 schema、成功语义、超时/重试和自动化验证边界。
- 修改参考邮件服务 SMTP 配置时，必须同步核对 JavaMail 属性、Java/Shell runtime
  guard、ApplicationContext/GreenMail 测试、`.env.example` 和 live 配置/运维文档。
- 不要把 `docs/drafts/` 中的规划代码当成已实现事实。
- 增加或修改后端行为时必须同步补充相应的集成/行为测试。
- 完成工作后检查 `git status`，不要提交 `.env`、数据库、key、报告、`target/`、`node_modules/` 或静态构建产物。
- 用户要求提交时使用 `git add -A` 纳入工作区全部非忽略修改，包括其他开发者已完成
  且可提交的修改；只排除 ignored、敏感或生成物，不得选择性遗漏、stash 或回滚。
