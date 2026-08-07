# UniAuth 全面加固实施规划

> 状态：In progress；Phase 0 H0.1-H0.3、PostgreSQL/Flyway H1.1-H1.3、
> 测试基础 Batch A、登录方式约束 Batch B1、删除/primary 并发保护 Batch B2a 与
> 实体约束/索引对齐 Batch B2b 已通过 2026-08-07 完整门禁
> 事实基线：2026-08-07
> 实施范围：只修复、约束和验证现有能力，不增加新的用户功能
> 安全前提：任何会启动 Spring 的验证都必须使用明确隔离、可丢弃的数据库

## 0. 当前实施状态

| 工作项 | 状态 | 当前证据边界 |
|--------|------|--------------|
| H0.1 关闭默认破坏性启动 | Verified | 无默认 profile；演示数据显式 opt-in、disposable 数据库名保护、无全表删除 |
| H0.2 删除危险调试端点 | Verified | controller/caller 已删除；`/api/auth/**` 使用 method/path allowlist 和默认拒绝 |
| H0.3 收紧敏感配置和日志 | Verified | secret fallback/输出已移除；Python JWT/JWKS 收紧；历史 RSA key 退出索引 |
| H1.1 disposable PostgreSQL 测试基座 | Verified | Testcontainers PostgreSQL；自动化测试不读取 `.env` 或共享数据库 |
| H1.2 schema 事实与迁移布局 | Verified | dev-derived 8 表 V1、legacy SQL runtime 外归档、结构指纹 |
| H1.3 可执行 migration 链 | Verified | Flyway fresh/baseline、Hibernate validate、Session JDBC、SQLite 退役 |
| H1.4 Batch B1 | Verified | Flyway V2、登录方式时区/nullability、provider/shape/primary 约束与 bind/set-primary 并发 |
| H1.4 Batch B2a | Verified | Flyway V3、用户级 revision CAS、delete/delete 与 delete/set-primary 组合并发 |
| H1.4 Batch B2b | Verified | Flyway V4、其余目标实体约束/default、email 查询索引、重复索引清理与只读 preflight |
| H1.4 其余工作-H8 | Not implemented | 仍按本文后续 phase 执行，不得从前述门禁推断为已完成 |

本批次自动化和隔离 HTTP 证据见 [验证指南](../VERIFICATION.md)。H1.4-H8 必须分别完成
其工作项和 release gate。下一轮的实际顺序和测试矩阵见
[下一轮加固实施计划](NEXT_HARDENING_IMPLEMENTATION_PLAN.md)。

### 已完成实施批次：H1.1-H1.3

本批建立 PostgreSQL 数据与迁移基础，没有把后续 JWT、邮箱或 Web3 正确性问题伪装成已完成：

| 顺序 | 工作 | 退出条件 |
|------|------|----------|
| 1 | 用 Testcontainers PostgreSQL 替换 SQLite 集成测试基座 | 已完成 |
| 2 | 固化实际 dev schema 基线并归档旧迁移 | 已完成 |
| 3 | 引入 Flyway并切换唯一 schema 所有权 | 已完成 |
| 4 | 退役 SQLite 运行与测试入口 | 已完成 |

`blacksheep_dev` 已完成只读 rehearsal，但尚未执行 baseline apply。创建 Flyway history
仍是独立、需要显式授权和精确 confirmation token 的操作，不属于普通测试门禁。

### 当前实施批次：测试基础、Batch B1、Batch B2a 与 Batch B2b 已完成

测试基础 Batch A、登录方式约束 Batch B1、删除/primary 并发保护 Batch B2a 与
实体约束/索引对齐 Batch B2b 已完成完整门禁。剩余 H1.4、认证行为修复、实际工作包、
退出条件和连续三轮检查规则见 [下一轮加固实施计划](NEXT_HARDENING_IMPLEMENTATION_PLAN.md)。

## 1. 目标与边界

本规划用于把当前 UniAuth 从“能构建、包含多种认证流程的演示型实现”加固为
“启动行为可控、数据演进可重复、安全边界明确、关键流程有自动化证据”的工程基线。

本轮只处理现有能力的正确性、安全性、可测试性和可运维性：

- 阻止默认启动清空数据，隔离演示数据初始化。
- 将 PostgreSQL 设为唯一受支持数据库，建立可执行、可升级、可回滚演练的
  Flyway 迁移与 Testcontainers 测试基线。
- 修复 JWT 密钥、校验、刷新、撤销、登出和内省链路。
- 收敛 SecurityFilterChain、CSRF、CORS、cookie 和 OAuth2 redirect。
- 修复邮箱验证码、注册、密码重置和 Web3/SIWE 的正确性缺口。
- 修复后端、React、Python 示例之间的契约漂移。
- 建立 Java、前端、Python、迁移和安全回归门禁。
- 移除仓库中的私钥、历史 token、数据库快照和环境硬编码风险。

明确不做：

- 不增加新的 OAuth2 provider、MFA、Passkey、多租户、管理后台或新业务页面。
- 不扩展授权模型、计费、审计产品能力或用户画像。
- 不以 UI 重设计、性能优化或大规模架构重写替代安全修复。
- 不直接把现有 `db/migration/V*.sql` 接入 Flyway 后启动应用。
- 不继续维护 SQLite 运行时、schema parity 或 SQLite 测试兼容性。
- 不在缺少发布证据时恢复“生产级”“生产就绪”“全部通过”等声明。

## 2. 实施前事实快照

以下结论来自 2026-08-07 开始实施前对代码、配置、脚本和无启动构建结果的交叉核对。
其中 H0.1-H0.3 对应条目已由第 0 节状态取代；其余条目仍需在所属 phase 实施前重新核验。

| 领域 | 当前事实 | 主要证据 | 风险 |
|------|----------|----------|------|
| 默认启动 | `application.yml` 默认激活 `test` | `src/main/resources/application.yml` | 裸跑应用会连接 PostgreSQL |
| 数据清空 | `dev`、`test` 初始化器都执行两个 `deleteAll()` | `DevEnvironmentInitializer`、`TestEnvironmentInitializer` | 每次启动删除用户和登录方式 |
| 测试数据库 | `test` 曾默认回退到 `blacksheep_dev` 和硬编码弱口令 | 历史 `application-test.yml` | 容易误连共享库且弱凭据进入配置 |
| 迁移 | `db/migration` 中的 V1-V4、V6-V8 没有执行器，V5 缺失 | `pom.xml`、`src/main/resources/db/migration/` | 新增 migration 文件不会生效 |
| 迁移一致性 | 旧 migration 混合 PostgreSQL/SQLite、旧字段和不同 ID 类型 | `V2`、`V3`、`V4`、`V6` | 不能通过添加 Flyway 依赖直接启用 |
| 仓库 PostgreSQL schema | `schema-postgresql.sql` 缺少 `email_verification_codes`，含实体未映射的 Web3 列 | tracked SQL、entity | 不能作为 Flyway 基线 |
| 实际 dev PostgreSQL | 2026-08-07 只读核验确认 8 张 UniAuth 表完整存在，含 email code 与 Spring Session | `blacksheep_dev` schema-only export | 获准作为新 V1 基线的权威输入 |
| 实际 dev 数据不变量 | UUID/user FK/provider/purpose/primary/token type 定向检查均为 0 异常；存在 1 组重复的未使用 email code `(email,purpose)` | 2026-08-07 只读聚合 SQL | 可 baseline；H1.4 上唯一约束前必须显式修复重复 challenge |
| 实际 dev 类型/nullability | `UserLoginMethod.linkedAt/lastUsedAt` 是 `Instant`，实际列仍是无时区 timestamp；多项 entity 非空字段在数据库允许 null | entity、实际 dev PostgreSQL | V1 保留事实，H1.4 必须用 V2+ 显式对齐 |
| 实际 dev 索引 | email code 只有主键索引；users email/username、blacklist jti/expires 存在重复索引 | `pg_indexes`、repository 查询 | V1 保留事实，H1.4 补查询索引并清理冗余 |
| 共享 schema | `blacksheep_dev.public` 同时包含大量非 UniAuth 表，且没有 Flyway history table | `information_schema`、`to_regclass` | Flyway history/clean 必须与其他系统隔离 |
| Flyway 版本 | Spring Boot 3.3.4 管理 Flyway `10.10.0`；当前未声明依赖 | effective POM、`pom.xml` | 应使用 Boot 管理版本和 PostgreSQL database module |
| SQLite | 当前 `dev`、启动脚本和一项 HTTP 集成测试仍依赖 SQLite；schema 不完整 | profile、script、test、schema | 已决定退役，不能先删后测 |
| Session | `prod` 不自动创建 Spring Session 表 | `application-prod.yml` | 部署依赖外部建表但无统一迁移证明 |
| Schema 导出 | 当前脚本要求显式 `POSTGRES_*`、覆盖 8 张表、拒绝部分导出并原子落盘 | `scripts/export-schema-pg.sh`、2026-08-07 实际执行 | 可作为基线采集器，仍需补充结构指纹与 restore 验证 |
| 基线导出证据 | 临时 schema-only 导出为 173 行，SHA-256 为 `a82eb9af8043f6049b8765af945aa2fbac6a76388e4a32e558a04e08b68bb69c` | 2026-08-07 实际导出 | 实施时须重新导出并把受审查版本纳入 V1 |
| Schema 资产边界 | PostgreSQL schema 文件尾部的块注释含 DELETE/UPDATE 运维示例 | `schema-postgresql.sql` | 当前不会执行，但可执行 schema 资产混入运维指令，后续编辑/导出时容易误启用 |
| Schema 管理重叠 | `test` 同时启用 SQL init、Hibernate `update`、Spring Session JDBC init；`dev` 同时启用 SQL init 与 Session init | `application-test.yml`、`application-dev.yml` | 多个机制可能并行创建或修改同一 schema，无法证明唯一演进来源 |
| Schema 工具失败语义 | export 已使用临时文件、严格错误传播和缺表拒绝策略 | `scripts/export-schema-pg.sh`、2026-08-07 实际执行 | H1 需保持该行为并增加恢复闭环 |
| 公共认证边界 | `/api/auth/**` 全部 `permitAll` 且禁用 CSRF | `AuthApiConfig` | 公开与需认证端点混在同一 matcher |
| 调试端点 | check-user、generate-hash、create-test-user、reset-password 可达 | `AuthController` | 用户枚举、明文回显、任意建号/改密 |
| Provider token 诊断 | `/api/validate-*-token` 及 `JwtValidationService` 仍被前端/模板调用 | `ApiAuthController`、frontend、templates | 暴露无核心流程依赖的 provider token/用户信息诊断面 |
| Profile 标注 | `@Profile("dev")` 标在 handler 方法 | `AuthController.resetPassword` | 不能依赖它关闭非 dev 路由 |
| Token 端点 | introspection/validate/test 无客户端认证 | `OAuth2TokenController` | 任意调用者可提交和解析 token |
| OAuth2 redirect | 错误处理接受 state 中任意 `redirect_uri` | `SecurityConfig` | open redirect |
| OAuth2 绑定意图 | 普通登录与绑定共用授权入口，后端仅凭 access cookie 推断绑定 | `SecurityConfig`、`TestPage.tsx` | 已登录用户进行普通 OAuth 登录时可能被静默绑定 |
| OAuth2 前端来源 | 授权请求解析器把未校验 Referer origin 写入 session | `SecurityConfig.authorizationRequestResolver` | 回调目标可能绕过 redirect allowlist |
| OAuth2 token/scopes | X 请求 `offline.access` 等非登录必需 scope，authorized client 生命周期未清理 | YAML、`SecurityConfig` | 长期 provider token 暴露面超过当前功能需要 |
| Provider email | OAuth 建号统一设置 `emailVerified=true`，Google/X 未执行明确可信度策略 | `SecurityConfig`、`UserService` | 未验证/合成 email 可能被误作可信身份属性 |
| Provider 主体标识 | OAuth user-info 的稳定用户 ID 未统一校验非空、类型和长度 | `SecurityConfig`、`UserService` | 畸形 provider 响应可能进入建号/绑定，且并发首次登录的失败语义不可证明 |
| Provider 来源 | 部分逻辑通过 user-info 属性名猜测 Google/GitHub/X | `SecurityConfig`、`ApiAuthController` | 属性重叠或畸形响应可能造成 provider 命名空间混淆 |
| Canonical email schema | `users.email` 为 `NOT NULL UNIQUE`，无邮箱的 OAuth/Web3 账户只能写合成地址 | entity、实际 dev PostgreSQL | 合成标识与已验证邮箱混在同一身份字段，和邮箱信任规则冲突 |
| ORM/schema nullability | `UserLoginMethod.user` 声明 `nullable=true`，实际 dev PostgreSQL 的 `user_id` 是 `NOT NULL` | entity、实际 dev PostgreSQL | ORM 元数据与数据库身份不变量冲突 |
| CORS | YAML 与三个 Java 配置重复 | `application.yml`、`CorsConfig`、`WebConfig`、`WebMvcConfig` | 实际策略依赖覆盖顺序 |
| Cookie | local、OAuth2、refresh、Web3 的 Secure 设置不同 | 多个 controller、`SecurityConfig` | 本地不可用或生产降级，行为不一致 |
| Session cookie 配置 | cookie 属性写在不存在的 `spring.session.cookie.*` 前缀 | `application.yml`、`application-prod.yml`、Boot 3.3.4 metadata | Secure/SameSite 等声明实际可能未生效 |
| JWT 密钥 | 构造器硬编码加载 `rsa-keys.ser` | `JwtTokenService` | `jwt.rsa.key-file` 不能控制实际加载 |
| 私钥材料 | `rsa-keys.ser` 已被 Git 跟踪 | Git index | 私钥应视为已暴露并轮换 |
| 私钥格式/权限 | 私钥以自定义明文二进制写入工作目录，当前文件权限可被同机用户读取 | `JwtTokenService`、文件模式 | 难以审计、轮换和实施最小权限 |
| JWT 配置 | `app.jwt.secret/secret-file` 与 `jwt-secret.key` 不参与当前 RS256 签发 | `application.yml`、`JwtTokenService` | 死配置会造成错误安全假设 |
| Access 校验 | decoder 只建立 RSA 签名解码器 | `ResourceServerConfig`、`JwtTokenService.jwtDecoder` | 未显式校验 issuer、audience、type、撤销 |
| Refresh 校验 | 只检查签名和过期，refresh token 无 audience | `validateRefreshToken`、`generateRefreshToken` | access token 可被误用，缺少轮换/重放防护 |
| Blacklist | entity/repository/schema 存在但未接入 | `TokenBlacklist*` | 登出后已签发 token 继续有效 |
| 登出 | 只清 cookie/session | `AuthController`、`ApiAuthController` | 不撤销 access/refresh token |
| Token 传递 | refresh token 出现在 JSON，前端多处写 localStorage | controller、`useAuth.ts` | XSS 后长期凭据暴露 |
| Token 来源歧义 | ResourceServer 同时支持 header/cookie 且 header 优先，前端会同时发送二者 | `ResourceServerConfig`、`authService.ts` | 两个 token 属于不同用户时产生身份混淆，CSRF 策略也无法可靠判断凭据来源 |
| 邮箱验证码 | 邮件和数据库分别生成随机 code | `EmailVerificationCodeService` | 用户收到的 code 与可验证 code 不同 |
| 发送失败 | 邮件失败/不可用仍保存 code 并返回成功路径 | 邮件 service/controller | 虚假成功和不可达验证码 |
| 邮件交付原子性 | 数据库写入与外部邮件调用无 durable outbox/idempotency | email service/controller | 进程崩溃或提交失败可产生“已发送但不可验证”或重复邮件 |
| 重试 | 代码硬编码 5，预检查不增加 retry | `EmailVerificationCodeService` | 配置失效且可绕过尝试限制 |
| 频控 | 只按 email 统计，查询/写入非原子 | email repository/service | 并发和分布式场景可绕过 |
| 密码重置 | 先发送硬编码 `123456`，再触发第二次发送 | `ForgotPasswordService` | 邮件内容与数据库 code 不一致 |
| 用户枚举 | forgot-password 明确返回未注册邮箱 | `ForgotPasswordController` | 泄露账户存在性 |
| 重置二阶段枚举 | 未注册邮箱不创建 challenge，后续 verify 可区分“无记录”和“错误 code” | forgot/reset service | 即使发送响应统一，攻击者仍可通过第二步判断账户存在性 |
| 注册契约 | `RegisterRequest` 无 Bean Validation，存在两条邮箱注册路径 | DTO、两个 auth controller | 空值、弱密码和响应语义漂移 |
| 邮箱验证码 purpose | entity 与前端类型保留 `LOGIN`，但当前没有受支持的邮箱验证码登录 endpoint，React 只使用 `REGISTRATION`，密码重置走独立流程 | email entity/controller、`authService.ts`、`LoginPage.tsx` | 泛化 purpose 可能误开放未实现的匿名登录路径 |
| 邮箱注册事务 | `verifyCode` 已标记 used，controller 随后再次 `markAsUsed` | `EmailVerificationCodeService`、`EmailAuthController` | 消费与建号边界含混，失败恢复不可证明 |
| 邮箱注册元数据 | JSON 序列化失败返回 null，反序列化失败返回空 map | `EmailVerificationCodeService` | 可创建缺失密码或显示名的账户 |
| 邮箱已有用户 | 匿名邮箱验证可绑定 password hash 为 null 的 LOCAL 方式 | `EmailAuthController.bindEmailLoginMethod` | 产生不可用凭据并混淆账户绑定语义 |
| 邮箱规范化 | email 与 email 型 local username 均按原字符串查询和唯一 | repository、service、schema | 大小写/空白变体可能形成重复身份或流程不一致 |
| 未验证邮箱占位 | 普通用户名注册可把未经所有权验证的 email 写入全局唯一 `users.email` | `AuthController`、`UserService` | 攻击者可抢占他人邮箱并阻断后续可信 OAuth/邮箱注册或恢复 |
| Web3 消息 | 只比对独立 nonce，签名任意客户端 message | `Web3AuthService.verifySignature` | nonce 未绑定 domain/address/URI/chain/expiry |
| Web3 nonce | 获取后再删除，没有原子消费 | `Web3NonceService` | 并发重放窗口 |
| Web3 challenge 覆盖 | 匿名 nonce 请求按 wallet 覆盖唯一记录 | `Web3NonceService.saveNonce` | 攻击者可持续为受害钱包申请 nonce，使其已展示的签名挑战失效 |
| Web3 测试接口 | 公开 DELETE nonce | `Web3AuthController` | 任意人可使他人挑战失效 |
| Web3 响应 | `isNewUser` 在创建后判断，bind 忽略 false | `Web3AuthController` | 成功语义错误 |
| EIP-191 | 前缀使用 Java 字符长度 | `Web3SignatureUtils` | 非 ASCII 消息长度不符合 UTF-8 字节语义 |
| 当前用户 | JWT 用户 provider 固定为 `local` | `ApiAuthController` | OAuth/Web3 登录后身份来源错误 |
| 登录事务 | `UserService.login` 是 read-only，却在其中更新 `last_used_at` | `UserService`、`LoginMethodService` | 成功登录的使用时间可能不持久化 |
| 重复密码校验 | local login 先经 `AuthenticationManager`，随后又调用 `UserService.login` 再次 BCrypt | `AuthController`、`UserService` | 单次请求重复消耗密码哈希 CPU，认证与审计语义可能分叉 |
| 密码哈希 | 固定默认 BCrypt encoder，无算法标识、成本配置或登录后升级 | `WebSecurityConfig`、password write paths | 无法平滑提升哈希强度或识别遗留格式 |
| 登录方式字段约束 | schema 未按 LOCAL/OAuth/Web3 强制必填与互斥字段 | login-method entity/schema | 可写入 null hash、缺 provider ID 或混合两类凭据的不可用记录 |
| Primary 约束 | `(user_id,is_primary)` 只有普通索引，切换逻辑无并发锁 | schema、`LoginMethodService` | 并发下可能出现零个或多个 primary |
| 最后登录方式 | remove 先无锁统计数量，再删除 | `LoginMethodService.removeLoginMethod` | 并发删除可绕过“至少一个登录方式” |
| Web3 建号事务 | `findOrCreateUser` 分别保存 user 与 login method，且不在事务中 | `Web3AuthService` | 中途失败可留下无登录方式用户 |
| 前端类型 | 用户/登录方式 ID 部分声明为 number | `types/index.ts`、`authService.ts` | 后端 UUID string 契约不匹配 |
| 邮箱响应类型 | 前端期望顶层 userId/username，后端返回嵌套 user | 前端 service、`EmailAuthController` | 登录状态构造错误 |
| HttpOnly 误用 | 前端尝试通过 `document.cookie` 读取 token | `useAuth.ts`、`OAuth2CallbackPage.tsx` | 逻辑永远不能读取真正 HttpOnly cookie |
| 浏览器日志 | login/refresh/Web3 回调直接记录响应、error、nonce 或完整 token | frontend source | DevTools/采集 SDK 可能留存密码、token 和身份信息 |
| 诊断前端 | `/resource-test`、token 模拟和旧 Thymeleaf test/debug 内容进入常规路由/静态资产 | React routes、templates、static assets | 生产仍可能暴露内省、token 调试和历史诊断工作流 |
| Python JWT | 算法取自 token header，kid 失败回退首 key | `python-resource-server/app.py` | 算法/密钥选择不应信任 token |
| Python TLS | JWKS 请求 `verify=False`，Flask `debug=True` | Python app | TLS 与调试模式不安全 |
| Python claim | 把 `sub` 当 username | Python app | 当前 `sub` 实际是 UUID |
| Python 授权 | 受保护路由只判断 token 有效并回显 token claims/上游地址 | Python app | 认证成功被当成授权，响应暴露不必要的安全元数据 |
| 自动化 | Maven 通过但没有 Java 测试，ESLint 缺配置 | `src/test`、`frontend/package.json` | 构建成功不能证明行为正确 |
| 认证防滥用 | local login、OAuth 发起、refresh、Web3 nonce/verify 等无统一限流 | controller/security config | 暴力猜测、资源消耗和枚举攻击缺少服务端约束 |
| 认证输入上限 | Web3 message、token/header 和多个 Map/form 参数缺少统一长度限制 | DTO、controller、security filter | 超大输入可在 JWT/SIWE/密码哈希前放大 CPU、内存与日志压力 |
| 外部 HTTP | 自定义 OAuth/email `RestTemplate` 没有实际 connect/read timeout | `SecurityConfig`、email service | 远端卡住可耗尽请求线程，YAML timeout 当前未绑定 |
| 浏览器响应策略 | 未定义 CSP、Referrer-Policy、HSTS 和认证响应 `no-store` | security config/controller | XSS、引用泄露和缓存凭据缺少显式门禁 |
| 启动/代理边界 | start 脚本打印 secret 片段；Nginx 放大 header/body、公开诊断路由并记录 query | scripts、`docker/nginx/nginx.conf` | 凭据日志、资源耗尽和诊断面暴露 |
| 发布健康信号 | 无明确 liveness/readiness 契约，发布计划只写“健康检查” | code/deployment | 无法证明实例已完成迁移、密钥和数据库准备 |

## 3. 加固不变量

后续实现和评审必须持续满足以下不变量：

1. 未显式选择 profile 和数据库时，应用不得接触共享数据库，更不得删除业务数据。
2. 演示数据只能在显式开关、可丢弃数据库和防误连检查同时成立时写入。
3. 数据库 schema 只能由一个被验证的演进机制负责；Hibernate 不得在共享环境自动改表。
4. 每个 access token 只能作为 access token 使用；每个 refresh token 只能使用一次。
5. JWT 必须验证签名、算法、kid、issuer、audience、type、时间和撤销状态。
6. 浏览器长期凭据不得出现在 JavaScript 可读存储；cookie 认证的状态变更必须有 CSRF 防护。
7. CORS、cookie、redirect allowlist 和 token 时长都只能有一个配置来源。
8. 邮件中发送、数据库中验证的必须是同一个 code；发送失败不得返回成功。
9. 邮箱验证码和 Web3 nonce 都必须一次性、过期受控、并发下原子消费。
10. SIWE 签名必须绑定服务端签发的完整挑战，而不只是客户端提交的 nonce 字段。
11. API 成功响应必须对应真实持久化结果；不能忽略 service 的 false/失败结果。
12. 后端、React 和 Python 对 ID、provider、claim 和 token transport 的解释必须一致。
13. 每个高风险修复与自动化测试同批落地，不把测试集中拖到最后阶段。
14. 任一 release gate 未满足时，不得宣称生产就绪。
15. OAuth2 账户绑定只能由已认证用户显式发起的、服务端保存且单次消费的绑定意图触发；
    access cookie、Referer 或普通登录入口都不能隐式改变账户关联。
16. 每个用户必须恰好有一个 primary 登录方式；数据库约束负责“至多一个”，
    条件更新/CAS 事务优先负责创建、切换和删除后的“至少一个”。只有在所选隔离级别下
    无法证明 CAS 方案维持不变量时，才允许使用范围严格受限的单用户锁。
17. 绑定、增加、删除或切换登录方式等敏感账户操作必须要求最近认证；
    最近认证依据是初次认证时记录且 refresh 时保持不变的 `auth_time`，不能使用新 token 的 `iat` 冒充。
18. email 身份在所有入口使用同一规范化规则，数据库唯一性与查询语义必须一致。
19. user 与其首个 login method 必须原子创建；并发删除也必须保证用户至少保留一个登录方式。
20. OAuth provider 只请求当前登录所需的最小 scope；不需要调用 provider API 时，
    access/refresh token 在回调完成后不得继续保存在 authorized-client/session 存储。
21. provider email 只有在 provider 明确证明已验证时才能标记可信；
    合成 email 不能参与找回、跨 provider 关联或“已验证邮箱”判断。
22. 密码哈希必须带可识别版本并支持逐步升级；未知格式、null 和不满足策略的凭据失败关闭。
23. token、密码、验证码、nonce、签名和含凭据的 HTTP error/response 不得写入服务端或浏览器日志，
    OAuth code/state、PKCE verifier、binding/challenge id、CSRF token 和 session id 也不得记录；
    认证/token 响应必须禁止共享或浏览器缓存。
24. 外部邮件投递必须由可恢复、幂等的持久化状态机协调，不能把数据库事务和网络调用伪装成原子操作。
25. migration 是 schema 的唯一写入者；启用后 SQL init/Hibernate update 必须关闭，
    且版本化 migration 遇到未知漂移必须失败，不能用宽泛 `IF NOT EXISTS` 静默掩盖。
26. PostgreSQL 是唯一受支持数据库；`dev`、`test`、`prod`、集成测试和 migration
    不得保留 SQLite 旁路或双 schema 写入路径。
27. readiness 只有在 migration、数据库、Session 和签名密钥全部可用后才成功；
    备份、日志和代理不得泄露认证中间态或凭据。
28. refresh token reuse 必须撤销整条 token family，包括已经轮换出的后继 token；
    不能只拒绝被重复提交的旧 token。
29. 单个请求只能采用一种 access token transport；header 与 cookie 同时出现必须拒绝，
    不能用优先级静默选择身份。
30. 匿名 challenge 创建不能覆盖或删除其他浏览器仍有效的 Web3 challenge；
    challenge 必须由不可猜测 id 精确关联并受容量限制。
31. 用户禁用、密码/凭据恢复和登录方式删除必须立即使旧应用 token 失效；
    不能只等待 access token 自然过期。
32. 未经所有权验证的 email 不得占用 canonical `users.email`、参与账户冲突判断、
    密码找回或跨 provider 关联；pending email 必须与可信身份字段分离。
33. 密码找回的发送、验证和重置失败链都不得泄露账户或本地凭据是否存在；
    不能只统一第一步响应而让第二步 challenge 状态形成枚举 oracle。
34. canonical `users.email` 只保存已验证邮箱并允许无邮箱账户为 null；
    OAuth/Web3 合成标识只能保存在 provider/login-method 字段，不能伪装成可找回邮箱。
35. 角色/authority 的授予或撤销必须与 token security version 更新和 refresh family
    撤销处于同一事务；旧 token 不得继续携带已经撤销的权限。
36. refresh、绑定或其他账户变更后的 token 重签发必须继承原 `auth_time`；
    只有对当前账户完成明确 reauthentication 才能推进最近认证时间。
37. 匿名邮箱验证只能创建全新账户，不能给已有账户增加或替换登录方式；
    邮箱控制权不等同于当前账户的最近认证，已有账户变更必须走认证后敏感操作流程。
38. 生产流量必须经过唯一受信边缘入口；应用后端不得被公网直连，
    也不得信任客户端自行提供的 Host、Forwarded 或 `X-Forwarded-*` 身份与协议信息。
39. 凭据、权限、登录方式、账户状态、token family 和密钥状态变更必须产生可追溯的持久安全事件；
    审计写入失败不得静默丢失关键状态变更，也不得把凭据或原始身份标识写入事件。
40. 邮件请求的“已接受”“provider 已接收”和“用户可验证”是三个不同状态；
    pending 投递必须有截止时间，验证码有效期从成功投递后开始且仍受总生命周期上限约束。
41. 用户选择的 local username 与系统生成的 OAuth/Web3 canonical username 使用不同命名空间；
    provider email、subject、wallet、display name 和可变 provider username 都不能直接控制
    `users.username`，公开注册也不能抢占系统保留命名空间。
42. 同一浏览器 token pair 的 access/refresh 必须属于同一 user、同一 `sid`/refresh family；
    refresh 或新登录不得用一类 cookie 静默覆盖另一身份。已有有效身份时，同用户重新登录必须
    原子替换旧 family，不同用户登录必须先显式 logout，不能遗留仍有效的孤儿 refresh family。
43. local、OAuth、Web3、refresh 和密码找回解析到现有用户后，必须在任何 `last_used_at` 更新、
    challenge 投递、凭据修改或 token 签发前检查统一账户状态；disabled 用户失败关闭，
    外部响应不得泄露禁用状态，也不能通过其他认证方式绕过。
44. email-shaped local username 必须等于同一账户已经验证的 canonical `users.email`；
    不能只验证任意另一邮箱后占用他人的邮箱字符串作为登录名。无 canonical verified email
    的账户只能增加非 email public username；单独的 email-change/verification 流程未实现前
    不得通过 add-local 顺带写入或替换 canonical email。

## 4. 阶段依赖与发布门禁

```text
Phase 0 立即止损
   |
   v
Phase 1 隔离测试 + Schema/Migration + 最小安全审计基线
   |
   v
Phase 2 JWT 核心（H2.1-H2.4）
   |
   v
Phase 2/3 联合批次（H2.5 token transport + H3 HTTP 安全）
                         |
             +-----------+-----------+
             v                       v
       Phase 4 邮箱/密码          Phase 5 Web3/SIWE
             +-----------+-----------+
                         v
               Phase 6 跨端契约修复
                         |
                         v
               Phase 7 质量与 CI 门禁
                         |
                         v
               Phase 8 运维、密钥与发布演练
```

| Gate | 必须满足的条件 | 未满足时 |
|------|----------------|----------|
| G0 止损 | 默认不清库；危险测试端点不可达；无默认共享库密码 | 不允许启动集成环境 |
| G1 数据 | PostgreSQL 空库 migrate、既有库 baseline/upgrade、回滚恢复、SQLite 退役和最小持久安全审计测试通过 | 不允许合入业务修复 |
| G2 Token/HTTP | JWT 矩阵、刷新重放、撤销、CSRF、CORS、redirect 测试通过 | 不允许外部联调 |
| G3 认证流程 | email/password/Web3 成功与失败路径通过并发和重放测试 | 不允许候选发布 |
| G4 跨端 | React build/lint/test、Python test、契约测试全部通过 | 不允许发布 |
| G5 运维 | 私钥已轮换、配置无弱回退、迁移演练和恢复演练有证据 | 才可评估生产就绪 |

H2.5 与 H3.2 必须作为一个原子变更集设计、实现和回滚。token transport 决定
CSRF 边界，CSRF 方案反过来决定 cookie transport；两者不能以互相等待的方式拆分完成。
H2.1-H2.4 可以先合入经过隔离测试的密钥、validator、session/family、撤销和内省能力，
但在所有登记消费者、H3.1 凭据来源矩阵及 H3.2 CSRF 方案就绪前，不得对共享流量停止旧格式
签发或激活新格式校验。真正的 issuer/validator、旧 kid、cookie 和 CSRF 切换只能在
H2.5/H3.1/H3.2 联合发布中一次完成；不存在按请求自动回退或长期双格式接受阶段。
H2.5/H3.1/H3.2 是技术上必须原子切换的最小集合，不代表可在 H3.3-H3.7 尚未完成时
提前开放共享流量。批次 5 可以先在隔离环境中完成全部实现和测试，实际激活必须是该批次
最后一步，并以 H3.1-H3.7 的完整 G2 证据为前置；激活前 H2.5 只能处于未启用/staged 状态。

## 5. 通用实施规则

每个工作项都按以下顺序执行：

1. 先添加能复现当前缺口的失败测试或检查。
2. 做最小实现修复，不顺带增加功能。
3. 运行该工作项的定向测试和所属 phase 的完整门禁。
4. 更新受影响的 live guide、OpenAPI/DTO 和脚本。
5. 记录数据库、配置、API 或安全契约的兼容性影响。

数据库规则：

- 已发布 migration 一旦进入共享环境不得修改，只能新增 forward-fix migration。
- 破坏性 schema 改动采用 expand -> migrate -> contract，不在单次部署中直接删列。
- 迁移前保留 schema-only 和必要数据备份，记录校验和与恢复命令。
- rollback 优先回滚应用并保留向前兼容 schema；只有演练证明安全时才回滚 DDL。

安全规则：

- 错误响应和日志不得回显密码、token、验证码、nonce、签名或 provider secret。
- 安全回退必须 fail closed；不能因邮件、JWKS、数据库或配置不可用而降级为成功。
- 所有公开 endpoint 都要有明确原因、速率限制和测试，不接受目录级 blanket permit。

## 6. Phase 0：P0 立即止损

### H0.1 关闭默认破坏性启动

> 当前状态：Verified。

**涉及文件**

- `src/main/resources/application.yml`
- `src/main/resources/application-dev.yml`
- `src/main/resources/application-test.yml`
- `DevEnvironmentInitializer.java`
- `TestEnvironmentInitializer.java`
- 新增对应配置绑定和测试文件

**前置条件**

- 不启动当前应用。
- 准备不连接数据库的 context/configuration test。

**实施**

1. 删除 `spring.profiles.active: test`，要求启动者显式选择 profile。
2. 为演示初始化增加默认 false 的显式开关，例如
   `app.demo-data.enabled`，用 `@ConditionalOnProperty` 控制 bean 是否创建。
3. 初始化器不得再执行全表 `deleteAll()`；改为只 upsert/remove 固定命名空间的演示账户。
4. 增加数据库目标保护：只有显式声明 disposable 且数据库名符合测试约束时才允许初始化。
5. `test` 配置移除数据库名、用户名和密码弱回退，缺失变量时启动失败。
6. 将用于自动化测试的 fixture 移到 `src/test`，不依赖 runtime `CommandLineRunner`。

**测试**

- 无 profile、`dev`、`test`、`prod` context 测试。
- 默认开关下 verify initializer bean 不存在且 repository delete 未被调用。
- 显式演示开关只影响命名空间账户，不影响预置普通用户。
- disposable 标志缺失或数据库名不匹配时 fail closed。

**回滚**

- 可临时恢复“显式开关下创建演示账户”，不能恢复默认清库。

**完成定义**

- 裸配置启动不会选择 PostgreSQL。
- 所有 profile 默认不删除任何用户。
- 没有默认数据库密码。

### H0.2 删除或隔离危险调试端点

> 当前状态：Verified。

**涉及文件**

- `AuthController.java`
- `ApiAuthController.java`
- `OAuth2TokenController.java`
- `Web3AuthController.java`
- `JwtValidationService.java`
- `AuthApiConfig.java`
- 前端、Thymeleaf 模板、脚本和文档中对相关 endpoint 的引用

**前置条件**

- 用 `rg` 确认调用方。
- 为 endpoint 可达性建立 MockMvc 安全测试。

**实施**

1. 删除 `/api/auth/check-user`、`generate-hash`、`create-test-user`。
2. 删除无认证 `/api/auth/reset-password`，保留真实验证码密码重置流程。
3. 删除 `/api/auth/web3/nonce/{wallet}` 的 DELETE 测试接口。
4. 删除 `/oauth2/introspect-test` 和通用 `/oauth2/validate` 测试接口。
5. 删除 `/api/validate-google-token`、`/api/validate-github-token`、
   `/api/validate-x-token` 及无其他生产调用方的 `JwtValidationService`，
   同步删除 `authService.ts`、Thymeleaf 测试模板和 live docs 中的入口。
6. 若确有本地诊断需求，只能放入单独 `@Profile("dev")` controller，
   同时要求显式开关和管理员/loopback 限制；默认实现优先删除。
7. 删除将 `@Profile` 放在 handler 方法上的无效安全假设。

**测试**

- 所有移除 endpoint 返回 404。
- 公开 endpoint allowlist 快照测试。
- 非 dev profile 不加载任何诊断 controller。
- 前端与模板构建后不再引用任何 provider-token validation endpoint。

**回滚**

- 只允许恢复为默认关闭、整 bean profile-gated 的诊断工具，不恢复公开路由。

**完成定义**

- 公网调用者无法枚举用户、生成密码哈希、创建测试用户、直接改密或删除 nonce。

### H0.3 立即收紧敏感配置和日志

> 当前状态：Verified。

**涉及文件**

- `.gitignore`
- `application*.yml`
- `JwtTokenService.java`
- `Web3NonceService.java`
- `Web3SignatureUtils.java`
- `SecurityConfig.java`
- email service 实现
- `python-resource-server/*.py`
- root `start*.sh`、`build-frontend.sh` 与 `scripts/*`

**前置条件**

- 不读取或输出 `.env`、私钥和真实 provider secret。

**实施**

1. 仓库级 `.gitignore` 增加 `.env`、私钥、数据库导出、token 调试文件和 Python cache。
2. 删除日志中的 nonce、签名分量/消息哈希、OAuth code/state、PKCE verifier、
   binding/challenge id、CSRF token、session id、完整异常堆栈、token 响应内容、
   完整 email/wallet 和 provider 用户敏感详情；安全事件只记录不可逆摘要或低基数结果码。
3. 生产/测试配置中的 secret 占位符不再作为可运行值；缺失即失败。
4. 立即盘点是否有环境仍使用 tracked `rsa-keys.ser`。如有，先停止其继续签发并执行
   紧急换钥；不能等待 Phase 8。H2.1 建立长期轮换能力，H8.2 完成仓库和历史处置。
5. 启动、导出和测试脚本不得打印 secret、token、验证码或 secret 子串；
   即使“只显示前几位”也视为泄露。

**测试**

- secret pattern 扫描不命中新增内容。
- 日志测试确认认证失败不包含 token/password/code/nonce/signature/message hash、
  OAuth code/state/PKCE、binding/challenge id、CSRF/session 值、完整 email/wallet
  或 provider response。

**回滚**

- 日志级别可以临时提高，但敏感值脱敏不能回滚。

**完成定义**

- G0 止损门禁全部通过。

## 7. Phase 1：数据库迁移、隔离测试与安全审计基线

### H1.1 建立 disposable 测试数据库

> 实施状态：Verified，2026-08-07。

**涉及文件**

- `pom.xml`
- `src/test/java/**`
- `src/test/resources/application-test.yml`
- 可选的 Testcontainers 配置

**前置条件**

- H0 完成。
- 本机/CI 有可用的容器环境；无容器时测试应明确 skip，而不是连接默认共享库。

**实施**

1. 使用 Testcontainers PostgreSQL 为集成测试提供随机、一次性数据库。
2. 引入 Boot 管理的 Testcontainers 依赖，不声明独立版本；抽取可复用的
   PostgreSQL container/service-connection 基座，避免每个测试自行拼接固定连接串。
3. 把现有 `AuthApiSecurityIntegrationTest` 从 `@ActiveProfiles("dev")` + 临时 SQLite
   迁移到 PostgreSQL container，先保持其 HTTP 安全断言不变。
4. 测试 profile 禁止 runtime demo initializer 和外部 OAuth/mail 调用。
5. 为外部邮件和 provider 使用 stub/mock server。
6. Maven 输出必须包含真实 test count。
7. 本地缺少容器时只允许通过显式命令跳过标记清楚的 PostgreSQL 集成组；
   CI 和 release gate 缺少容器能力必须失败，不能以 skip 变绿。
8. 集成测试不得读取仓库 `.env`，不得连接 `blacksheep_dev` 或任何固定数据库。

**测试**

- 并行运行两次测试不会共享数据库。
- 测试完成后容器及其 volume 被清理。
- 未安装容器环境时不会回退到 `blacksheep_dev`。
- 现有 HTTP 安全集成测试在 PostgreSQL 上保持原断言。
- PostgreSQL foreign key、cascade、unique、timestamp/JSONB 和 Spring Session 基础行为可执行。
- CI 强制执行 PostgreSQL 集成组并报告非零数量。

**回滚**

- 可以临时只运行纯单元测试，但不能恢复共享数据库回退。

**完成定义**

- 后续所有数据库行为测试都有 disposable harness。

### H1.2 冻结当前 schema 事实并选择迁移布局

> 实施状态：Verified，2026-08-07。

**涉及文件**

- `schema-postgresql.sql`
- `schema-sqlite.sql`
- `data-postgresql.sql`
- `data-sqlite.sql`
- `src/main/resources/db/migration/V*.sql`
- `scripts/schema-blacksheep_dev-*.sql`
- `scripts/export-schema-pg.sh`
- 新增 baseline provenance/迁移说明

**前置条件**

- H1.1 完成。
- 使用获准的实际 `blacksheep_dev` 做只读 schema 导出；不得读取或复制用户明细。
- 获得其他已部署 PostgreSQL schema 的只读导出；没有其他真实环境时明确记录未知。

**实施**

1. 将 2026-08-07 实际 dev 导出的以下 8 张表定义为 V1 权威输入：
   `users`、`user_login_methods`、`web3_nonces`、`email_verification_codes`、
   `user_authorities`、`token_blacklist`、`spring_session`、
   `spring_session_attributes`。
2. 重新执行 schema-only 导出，记录来源、PostgreSQL server version、导出时间、
   表清单、脚本版本和 SHA-256；导出物不得包含数据、owner、grant 或凭据。
3. 对 entity、实际 dev 导出、tracked PostgreSQL schema、Spring Session 3.3.2
   官方 PostgreSQL DDL 和历史 migration 建立列级矩阵；实际 dev 导出决定 V1，
   差异进入 V2+，不得为了“清理”偷偷改写 baseline。
4. 确认哪些列仍被代码读写，哪些只是历史遗留。当前实际 dev 表中的
   `chain_id`、`web3_nonce`、`wallet_metadata` 未被 entity 完整映射，只作为
   baseline 事实保留，后续通过 expand/contract 处理。
5. 唯一 runtime location 固定为 `classpath:db/migration/postgresql`。
6. 将现有 V1-V4/V6-V8（V5 缺失）及四份手写 SQL init schema/data 作为 legacy
   输入归档到 `docs/archive/database/legacy-sql/` 等 runtime classpath 之外的位置，
   保留 Git 历史和说明，不删除其 provenance，也不让 Flyway 扫描。
7. PostgreSQL 是唯一 canonical schema；`schema-sqlite.sql`、`data-sqlite.sql`、
   SQLite driver/dialect、SQLite 查看脚本和 SQLite 测试入口进入 H1.3 退役清单。
8. `blacksheep_dev.public` 包含其他系统表，因此 Flyway 使用专用
   `uniauth_flyway_schema_history`，禁止使用 `clean`，migration 只操作明确列出的
   UniAuth 对象。
9. 空库通过 V1 创建 8 张表；已有库只有在 8 张表结构指纹和必要数据不变量完全匹配时，
   才允许显式 baseline 到 version `1`。`baseline-on-migrate` 保持关闭，
   未识别 schema 必须失败。
10. 从可执行 schema 资产移除块注释中的 DELETE/UPDATE/诊断查询等运维示例；
   当前这些语句不会执行，但仍应迁入文档。清理任务进入受测的应用 job/运维命令，
   示例 SQL 只放文档且默认不可执行。
11. 记录每个 legacy migration 的 vendor、前置 schema 和破坏性风险，
   特别标明 V3 的 SQLite integer ID 重建与 PostgreSQL UUID 模型不兼容。

**测试**

- 自动 schema diff 报告能识别缺表、缺列、类型、nullability、default、
  primary/foreign key、unique/check/index 和 enum 字符串差异。
- legacy migrations 不在运行时 location 中。
- schema/migration 可执行目录不含示例用户 UPDATE、清理 DELETE 或未审查 DML。
- V1 内容可追溯到受审查的 dev schema-only 导出，且不包含 8 张表之外的共享对象。
- 不匹配 8 表指纹、缺表、额外未识别 auth 列或已经存在其他 Flyway history 的库
  均被 baseline guard 拒绝。

**回滚**

- 在正式启用 migration 前保持当前 SQL init；不能半启用执行器。

**完成定义**

- 团队知道空库、当前 test 库和可能的 prod 库分别从哪个版本升级。

### H1.3 建立可执行 migration 链

> 实施状态：Verified，2026-08-07；`blacksheep_dev` baseline apply 尚未执行。

**涉及文件**

- `pom.xml`
- `application-dev.yml`
- `application-test.yml`
- `application-prod.yml`
- `scripts/runtime-guard.sh`
- `start.sh`
- `start-with-frontend.sh`
- `src/test/java/**`
- `DemoDataInitializer.java`
- `UserEntity.java`
- `UserLoginMethod.java`
- `src/main/resources/db/migration/postgresql/V1__baseline_uniauth_auth_schema.sql`
- legacy SQL 的 runtime 外归档目录

**前置条件**

- H1.2 schema 矩阵和 baseline 策略通过评审。
- 实际 dev 导出的 V1 候选已在一次性 PostgreSQL 上恢复并通过结构 diff。
- 不直接对 `blacksheep_dev` 创建 history table；先完成 fresh 和 existing-schema rehearsal。

**实施**

1. 在 `pom.xml` 增加 Boot 3.3.4 管理的 `flyway-core` 和
   `flyway-database-postgresql`，不单独覆盖 Flyway `10.10.0` 版本。
2. 新建 PostgreSQL-only runtime location，并用受审查的实际 dev 导出生成
   `V1__baseline_uniauth_auth_schema.sql`。V1 精确复现当前可用结构，不夹带 H1.4
   的约束重塑；所有改进从 V2 开始。
3. Flyway 固定使用 `uniauth_flyway_schema_history`、`baseline-on-migrate=false`、
   `clean-disabled=true`、`validate-on-migrate=true`、`out-of-order=false`。
4. 为已有库提供 guarded baseline 工具：先做 schema-only 备份、8 表结构指纹、
   必要数据不变量和 history 冲突检查，再要求显式确认，把匹配库标记到 version `1`。
   guard 失败不得建议 `repair` 或强制插入 history row。
5. fresh 数据库执行 V1；匹配的已有数据库只执行显式 baseline，不重放 V1。
   后续 V2+ 同时服务 fresh 和已 baseline 的数据库。
6. `dev`、`test`、`prod` 全部切换到 PostgreSQL；交互启动脚本要求显式
   `POSTGRES_*`，`dev` 只接受显式 allowlist 中的 dev/test/demo 目标，
   包括已批准的 `blacksheep_dev`。自动化测试只用 container。
7. 移除 `sqlite-jdbc`、`hibernate-community-dialects`、SQLite datasource/profile
   配置、四份已归档的 SQL init schema/data、`view-db.sh`、`DemoDataInitializer`
   的 SQLite target 分支和 SQLite-specific test setup；历史说明只在 archive/index
   中保留。
8. 所有 profile 设置 `ddl-auto: validate`；schema 修改只来自 Flyway。
9. Flyway 在 JPA、repository、Session 和 HTTP listener 可用前完成 migration；
   migration 失败时应用不得继续启动或报告 readiness。
10. Flyway 启用后所有 profile 关闭 `spring.sql.init` schema/data 写入，
   Hibernate 仅使用 `validate`，并关闭 `spring.session.jdbc.initialize-schema`；
   不允许 SQL init、Hibernate update、Spring Session JDBC init 和 migration
   并行管理同一表。
11. Spring Session 表采用实际 dev 导出且与 Spring Session 3.3.2 官方 PostgreSQL DDL
    交叉验证，切换后用真实 session create/read/delete 证明可用。
12. 多实例部署依赖 Flyway PostgreSQL lock 或独立 migration job；同一版本只允许一个
    migration owner，失败实例不得绕过校验继续启动。
13. `TWITTER`/`X`、timestamp、email nullability、login-method shape、primary 约束等
    结构改进保留到 H1.4 的 V2+，避免 baseline 与 hardening 变更混在同一不可审查脚本。

**测试**

- PostgreSQL 空库 Flyway migrate -> Hibernate validate。
- 把 V1 导出恢复到一次性 PostgreSQL，执行 guarded baseline -> validate。
- 从 baseline clone 执行 V2+ upgrade -> validate；下一轮没有 V2 时也必须证明
  baseline 后应用可启动且 migration info/validate 正常。
- 重复启动不重复改表。
- checksum 被修改、未知 auth 列/约束、缺表、错误 history table 和中途失败时
  启动必须失败；恢复/forward-fix 流程完成演练。
- Spring Session 表可被框架实际读写。
- 启动日志和数据库审计证明只有 migration 写 schema，SQL init、Hibernate update
  和 Spring Session JDBC init 没有旁路。
- repository/HTTP 集成测试确认用户、登录方式、邮箱 code、Web3 nonce 和 token blacklist
  至少完成代表性 insert/read/update/delete，不调用真实 OAuth、邮件或 Web3 外部服务。
- `mvn clean compile test-compile`、完整 `mvn test`、脚本语法检查通过；
  frontend 与 Python 基线验证不得因数据库基础改动回归。

**回滚**

- 应用版本应兼容 expand 阶段 schema；数据库优先 forward-fix。
- migration 已进入共享环境后不得改写 checksum。
- SQLite 删除只能通过回滚整个 H1.1-H1.3 批次恢复；不能让 PostgreSQL 与 SQLite
  两套 schema owner 长期并存。

**完成定义**

- `db/migration` 不再是“看起来存在但不会执行”的目录。
- fresh、existing-schema baseline 和后续 upgrade 三条路径都有自动化证据。
- SQLite 不再是可启动、可测试或文档承诺的受支持路径。

### H1.4 修复 schema 工具与约束

**涉及文件**

- `scripts/export-schema-pg.sh`
- 新 fail-fast restore/validation 工具
- schema/migration 文件
- `UserEntity.java`
- `UserLoginMethod.java`
- 相关 repository/entity

**前置条件**

- H1.3 完成。

**实施**

1. 保持 export 脚本当前的显式环境变量、完整 8 表、临时文件和 fail-fast 行为，
   增加与 Flyway migration/schema history 的一致性检查。
2. 表清单从受版本控制的 canonical inventory 生成或校验，覆盖后续新增的
   security audit event、refresh family、binding intent、email outbox/rate-limit 表，
   不维护静默过期的手写子集。
3. restore 使用 `ON_ERROR_STOP`/事务或等价 fail-fast 选项，只有完整恢复并通过 schema
   校验后才把导出物标记为成功。
4. 为“每用户单一 primary”“每用户每 provider 唯一”“nonce 唯一”
   等不变量增加数据库约束和可验证事务策略：
   PostgreSQL 使用 `WHERE is_primary = true` 的 partial unique index 保证至多一个。
5. primary 创建、切换、删除优先使用带预期状态的条件更新/CAS，并在同一事务内完成；
   清除旧 primary、设置新 primary 任一步失败均整体回滚，禁止先提交为零再补写。
   若并发测试和隔离级别分析证明 CAS 无法维持“至少一个”，才使用范围严格受限的
   单用户行或专用 invariant row 锁；不得锁表或把宽泛悲观锁作为默认路径。
6. 删除登录方式使用带预期计数/primary 状态的条件删除或等价 CAS，在数据库内重新确认
   仍有替代方式并拒绝删除最后一个；若写偏斜在所选隔离级别下仍不可避免，才复用前述
   单用户锁。不能依赖 controller 预检查或一次无锁 `findByUserId`。
7. 上约束前扫描并显式修复零个/多个 primary、零登录方式用户、重复规范化 email
   和同一 `(email,purpose)` 的多条未使用 verification code。修复规则、报告和回滚点
   必须留痕，不按查询返回顺序静默选择；对 transient code 只能按明确的创建时间/
   过期规则保留一条并失效其余记录。
8. email 存储前执行统一 trim/canonicalization；PostgreSQL 通过规范化存储值
   和唯一约束保证大小写语义一致，email 型 local username 使用同一规则。
9. 清理 entity 未映射但 schema 宣称在用的 Web3 列；采用 expand/contract。
10. 为过期 email code、nonce、blacklist 建立明确清理策略和测试。
11. 对非 LOCAL 登录方式要求规范化后的 `provider_user_id` 非空且长度受限；
    数据库保持 `(auth_provider, provider_user_id)` 唯一，应用不能用 null、display name、
    email 或可变 username 代替 provider 稳定主体标识。
12. PostgreSQL 增加 login-method shape constraints：
    LOCAL 必须有规范化 `local_username` 和非空、可识别版本的 `local_password_hash`，
    且不得携带 provider subject；GOOGLE/GITHUB/TWITTER/WEB3 必须有 provider subject，
    且不得携带 local username/hash。`auth_provider`、token type 和 purpose 使用允许值约束，
    迁移期脏数据先报告并显式处置，不用放宽最终约束。
13. 定义单一 username canonicalizer。普通非 email local username 先 trim、Unicode NFKC、
    `Locale.ROOT` 小写，再按明确 ASCII allowlist 和长度约束校验；原展示名放 `display_name`，
    不用大小写变体创建不同登录身份。email 型 local username 继续使用 email canonicalizer。
    email 型 local username 还必须等于所属 user 的 canonical verified email；
    迁移前报告规范化冲突、email 所有权不一致和缺失 canonical email 的记录并人工处置，
    不能静默合并账户或把 local username 反向提升为可信 email。
14. 为无用户选择 username 的 OAuth/Web3 账户生成保留前缀加至少 128-bit CSPRNG 的
    opaque canonical username；公开注册/add-local 拒绝该前缀。生成值不包含 provider、
    email、subject、wallet 或 display name，唯一冲突只允许重新生成。
15. 建立 entity/schema mapping 校验，至少覆盖 `users.email`、`user_login_methods.user_id`、
    provider/local 字段形状、UUID 长度、enum 和时间类型；不能只证明 migration SQL 自洽，
    却让 JPA 注解继续描述另一套约束。
16. 明确时间类型映射：Java `Instant` 对应 PostgreSQL `TIMESTAMP WITH TIME ZONE`，
    `LocalDateTime` 对应 `TIMESTAMP WITHOUT TIME ZONE`。至少把
    `user_login_methods.linked_at/last_used_at` 转为带时区类型，并核对
    `nonce_expires_at`、email code、Web3 nonce、users 与 blacklist；不得照搬旧 V6
    把所有时间列一律改成带时区。
17. 用 V2+ 对齐 entity 声明与数据库 nullability/default，至少覆盖 users 的状态/时间列、
    login method 的 `user_id/is_primary/is_verified/linked_at`、Web3 nonce `created_at`、
    email code 的 `is_used/retry_count` 和 blacklist 的 `token_type/blacklisted_at`。
    先扫描并填补现存 null，再添加约束；不能让 primitive/非空 entity 继续依赖偶然数据。
18. 根据 repository 查询补充 email code 的 `(email,created_at)`、`expires_at` 索引，
    并在清理现有重复记录后增加 `(email,purpose) WHERE is_used=false` 的 partial
    unique index；审计并删除 users email/username、token blacklist jti/expires
    的重复非必要索引。索引变更必须保留唯一约束，并记录写放大与锁时间。

**测试**

- 导出后在空库恢复并通过 migration/validate。
- 故意让一个 `pg_dump` 子命令失败，脚本必须非零退出且不留下“成功”文件。
- PostgreSQL 拒绝同一用户的第二个 primary。
- 并发 bind、set-primary、remove-primary 结束后始终恰好一个 primary，
  违反唯一性时得到可预测业务错误且不留下部分更新。
- 仅有两个登录方式时并发删除二者，最多一个成功且最终仍保留一个 primary。
- `Alice@Example.com`、前后空白和 canonical value 的注册/找回/验证码查询落到同一身份。
- 非 LOCAL 记录缺失/超长 provider subject 时 migration 或写入失败；
  同一 provider subject 的并发首次登录最多创建一个用户和一个登录方式。
- LOCAL null/未知 hash、OAuth/Web3 缺 subject、混合 local/provider 字段和未知 enum 值
  在 PostgreSQL 被拒绝，正常 credential upgrade 仍可通过。
- 普通 username 的大小写、空白和 Unicode compatibility 变体落到同一登录 key；
  非允许字符、保留前缀和迁移期规范化冲突失败关闭。
- OAuth/Web3 生成 username 不泄露 provider subject/wallet/email，不能被普通注册抢占，
  并发创建仍只留下一个 canonical user。
- entity mapping 与 PostgreSQL schema 的关键 nullability、长度和关系约束矩阵一致；
  `users.email=null` 可加载保存，`user_login_methods.user_id=null` 在 ORM 与数据库两层都失败。
- `Instant` 字段写入、读取和时区切换保持同一时刻；`LocalDateTime` 字段不发生隐式时区偏移，
  旧 V6 的 blanket timestamp 转换不会进入新链。
- email code 的发送频控、最新未使用 code、过期清理查询通过 EXPLAIN/行为测试使用合理索引；
  同一 `(email,purpose)` 的第二条未使用 code 被拒绝；删除重复索引后 users/blacklist
  唯一性和 lookup/cleanup 行为不变。

**回滚**

- 新约束上线前先执行数据冲突扫描；有冲突则停止，不自动删除数据。

**完成定义**

- schema 工具、约束和并发不变量具备可重复证据。

### H1.5 建立最小持久安全审计基座

**涉及文件**

- 新 security audit event entity/repository/service
- 新 PostgreSQL migration
- append-only 数据库权限与 retention job
- 应用外密钥操作的受控 audit sink/runbook 模板

**前置条件**

- H1.3 migration 链可执行。
- H1.4 schema inventory、导出和约束策略已稳定。

**实施**

1. 建立仅供内部安全控制使用的最小事件 schema，不扩展用户可见审计产品能力：
   不可猜测 event id、事件类型、结果、发生时间、actor/subject 的内部 ID 或带版本不可逆摘要、
   可信客户端地址摘要、request id、auth method、原因码和必要的变更前后安全版本。
2. 禁止原始 email/username/wallet、token、code、nonce、OAuth state/PKCE verifier、
   binding/challenge id、CSRF/session、签名、私钥、完整 JWK 和 provider 响应进入事件。
3. 提供事务内 append API 和 transactional outbox API；调用方必须显式选择语义。
   凭据、权限、登录方式、账户状态、token family 和应用内密钥状态变更使用同事务 audit row
   或 outbox，审计持久化失败时整个安全变更回滚，不能先提交业务状态再异步补记。
   不可变 audit event 与可领取/确认的 outbox delivery state 使用分离表和权限；
   worker 只能更新 delivery state，不能改写已提交事件正文。
4. 使用 PostgreSQL 实现 append-only 边界：独立 runtime/retention role 和显式 GRANT，
   业务账户只允许插入及必要读取，不允许更新或任意删除。
   到期清理只能由独立最小权限任务或 maintenance path 执行，并按事件类别定义保留期、
   完整性校验和删除证据。
5. 为应用外执行的密钥 publish/切换/吊销建立同一字段语义的受控 audit sink/runbook：
   每个动作必须先取得操作 id，完成后写入结果和公钥指纹；sink 不可用时非紧急操作停止，
   紧急泄露处置可优先停止旧 key，但必须在受控事件记录中保留故障原因和补记闭环，
   不能把普通应用日志当作持久审计。
6. H2-H6 的工作项只增加各自事件生产者，不得各自创建不兼容的审计表、JSON blob 或日志格式；
   H3.5 负责统一外部错误、普通安全日志、高频事件管道并扩展覆盖，不再晚建底层审计基座。

**测试**

- PostgreSQL migration、append、查询、role/GRANT 拒绝、retention role 和保留清理测试。
- 同事务业务变更注入 audit insert 失败时，业务状态不变；事务提交后事件不可更新。
- outbox 重复领取、worker 崩溃和幂等投递不会丢失或重复产生不同语义的事件；
  delivery state 可推进但对应 audit event 正文始终不可变。
- 应用外密钥操作在 sink 正常、不可用和紧急处置三种路径下都有可追溯结果。
- 敏感值扫描确认数据库事件、导出物、普通日志和异常链均不含禁止字段原值。

**回滚**

- 可停止非关键事件生产者，但不能删除已提交事件或让关键安全状态变更绕过事务审计。
- migration 已进入共享环境后只允许 forward-fix；不能回滚为普通可更新业务表。

**完成定义**

- Phase 2 开始前已有可复用、可迁移、失败关闭的持久安全审计接口。
- G1 数据门禁通过。

## 8. Phase 2：JWT、刷新、撤销和密钥

### H2.1 重构密钥加载与轮换

**涉及文件**

- `JwtTokenService.java`
- 新 `JwtProperties`/key provider
- `application*.yml`
- `AuthorizationServerConfig.java`
- `OAuth2TokenController.java`

**前置条件**

- H1 migration 可用于保存撤销状态。
- H1.5 持久安全审计基座完成。
- 已准备新的外部密钥材料和轮换方案。

**实施**

1. 配置先绑定，再由 key provider 加载；构造器不得硬编码文件。
2. prod/test 缺少私钥时 fail fast，不自动生成并写工作目录。
3. dev 临时密钥只能在显式配置下生成，并清楚标记不可跨重启验证旧 token。
4. `kid` 来自密钥元数据，签发和 JWKS 使用同一来源。
5. 轮换时新 key 只负责签名，旧公钥仅在过渡窗口验证；窗口结束后移除。
   正常轮换先发布新公钥并确认 Java/Python/其他资源服务器可取得，再切换签名；
   旧公钥保留时间至少覆盖旧 token 最大 TTL + clock skew，且窗口结束后按证据移除。
   私钥疑似泄露属于紧急轮换：立即停止旧 key 签名并按事件响应决定提前停止验证，
   不为兼容性继续接受攻击者可能签发的 token。紧急吊销必须把旧 `kid` 标为 revoked：
   UniAuth strict validator 与 introspection 立即拒绝该 kid，JWKS 同步移除并缩短/清除缓存。
   已缓存旧公钥的纯离线资源服务器只能在其声明的 key-cache/reconfiguration SLA 内失效；
   需要即时拒绝的资源必须切换受认证 introspection，或部署受控的 revoked-kid denylist
   并确认生效。不能仅凭“JWKS 已删除”宣称所有资源服务器立即完成吊销。
6. 当前 tracked `rsa-keys.ser` 视为已暴露，不得继续作为发布密钥。
7. 淘汰自定义明文私钥 blob，使用可审计的 PKCS#8/PEM/JWK、keystore 或受管密钥服务；
   文件模式至少 owner-only、拒绝符号链接/目录穿越，写入采用原子替换。
8. publish、开始签名、停止签名、停止验证和紧急吊销分别记录持久安全事件；
   事件只包含 kid、公钥指纹、操作者/自动化主体、原因码和时间，不包含私钥、完整 JWK
   或 secret-manager 响应。应用外执行的轮换也必须把同等证据写入受控 runbook/audit sink。

**测试**

- 配置路径确实决定加载文件。
- 缺失、损坏、权限过宽、符号链接、kid 冲突都 fail closed。
- 轮换窗口中新旧 token 的预期验证矩阵。
- 新公钥尚未传播时不开始签名；正常窗口到期后旧 kid 失败，紧急泄露演练中旧 kid
  在 UniAuth/introspection 立即失效；离线资源服务器在声明的 key-cache/reconfiguration
  SLA 内通过 denylist、配置发布或模式切换停止接受，超过 SLA 必须使发布门禁失败。
- key lifecycle 各状态转换都有不可变审计证据，且 secret scan 不命中私钥或完整密钥响应。

**回滚**

- 正常、未泄露轮换可在原评审上限内保留旧公钥验证；已泄露或疑似泄露 key 不得因兼容性
  延长信任窗口。可以回滚应用并要求客户端重新认证，不能恢复旧私钥签名或移除 revoked-kid 状态。

**完成定义**

- 配置、签发和 JWKS 不再出现 key path/kid 漂移。

### H2.2 建立单一严格 token validator

**涉及文件**

- `JwtTokenService.java`
- `ResourceServerConfig.java`
- `OAuth2TokenController.java`
- Web3 bind、OAuth2 binding 中手工解析 token 的代码
- user token security-version 字段与 migration
- Python 与其他已登记资源服务器的最小 claim-cutover validator

**前置条件**

- H2.1 完成。

**实施**

1. 建立复用 validator/parser，禁止各 controller 自行只验签名。
2. access token 强制：RS256、已知 kid、issuer、audience、`type=access`、
   exp、iat、sub/userId、jti、sid、auth_time。
3. refresh token 强制：同样声明并要求 `type=refresh`；签发时加入 audience。
   两类 token 都要求 `sub == userId`、非空且受限的 `sid`、`auth_time <= iat < exp`，
   同一次签发的 access/refresh 必须共享 userId、sid 和 security version；时间值为预期数值类型，
   只允许配置中的小幅 clock skew，并拒绝超过配置最大 access/refresh 生命周期的 token。
   `username`、authorities 和可选 verified email 也必须满足固定类型/长度 schema。
   `jti` 与新建 family 的 `sid` 使用至少 128-bit CSPRNG 和固定 canonical 格式生成，
   不接受客户端值、可预测计数器或从 userId/时间派生。
4. 资源服务器 decoder 增加 issuer、audience、type、blacklist 和 browser-family validator。
   对带 `sid` 的 access token，UniAuth 必须确认 family 仍 active；等价实现可以在 family
   撤销事务中把该 family 所有未过期 access jti 原子物化到 blacklist，但不能只撤销 refresh
   记录后让同 family 的 access token 继续有效。离线资源服务器仍按 H2.4 的撤销模型处理。
5. 手工认证 endpoint 使用 Spring `Authentication`/`Jwt` principal，
   不从任意 header token 直接提取 userId。
6. token 错误只返回稳定错误码，不回显 parser 内部异常。
7. 为用户增加单调递增的 token security version，access/refresh token 都携带该版本；
   UniAuth strict validator 和 introspection 每次校验用户存在、enabled 与当前版本。
   用户禁用、密码重置/修改、账户恢复、登录方式删除和角色/authority 变更在同一事务中
   递增版本并撤销 refresh family，使已签发 access token 立即失效；
   离线资源服务器仍遵守 H2.4 的 TTL/introspection 边界。
8. `auth_time` 表示策略认可的最近一次真实凭据验证时间，不是 callback/token 签发时间：
   local 登录使用成功密码验证时间，Web3 使用新 challenge 的成功签名时间；
   邮箱注册只在一次性注册 challenge 成功消费、用户与首个 LOCAL 凭据原子创建并提交后，
   才把该 challenge 的验证时间作为初始 `auth_time`。密码重置、单纯验证邮箱所有权和
   匿名 challenge 创建都不是新登录，不能推进现有会话的 `auth_time`。
   OIDC 使用已验证的 provider `auth_time`/`max_age` 语义。`select_account`、`consent`
   或复用已有 provider session 不能自行视为 fresh authentication。
   无法证明 fresh auth 的 OAuth provider 只签发普通访问 token，并把 `auth_time` 保持为
   旧值或明确的 non-recent sentinel，使敏感操作失败；相应 provider step-up 未实现前
   不开放依赖最近认证的账户变更。refresh rotation 必须原样继承，不能把 refresh 时刻
   写成新的最近认证时间。OAuth/Web3/add-local 绑定、登录方式删除、security-version
   变化后的 token 重签发也继承当前会话原 `auth_time`；只有经过单独 reauthentication
   endpoint/flow 对当前账户凭据验证成功后才能推进。
9. 旧 token 不具备可可信补造的 sid/auth_time/security version，refresh 还缺 audience，
   因此不得在 strict validator 中填默认值继续接受。H2.2 负责盘点所有 Java/Python/其他
   资源服务器，并交付可在联合切换时启用的新 schema validator；在隔离 fixture/canary 中
   证明 strict 模式拒绝 legacy token。共享流量继续使用旧格式期间，新 validator 只能以
   未激活代码或不参与认证决策的观测模式预部署，不能按单请求自动回退或把两套结果择优接受。
   停止旧格式签发、启用新 issuer/validator、吊销旧 kid 和清理 cookie 归入 H2.5/H3.1/H3.2
   联合切换；任一登记消费者尚未就绪都阻断该切换。

**测试**

- 正常、过期、未来 iat、错误 issuer/audience/type/kid/alg、缺 jti/sid/auth_time、
  `sub != userId`、`auth_time > iat`、`exp <= iat`、超最大 TTL、错误 claim 类型和
  clock-skew 边界的参数化矩阵。
- refresh token 不能访问资源 API，access token 不能刷新或 bind。
- 多轮 refresh 后 `auth_time` 不变，敏感操作不会因 refresh 获得新的认证时效。
- 邮箱注册只有在 code 消费与账户/首个 LOCAL 凭据事务提交后才取得初始 `auth_time`；
  事务回滚、密码重置或匿名邮箱验证都不能刷新已有会话的最近认证时间。
- OAuth/Web3/add-local 绑定或其他账户变更后，新 token 的 `auth_time` 仍与变更前一致；
  仅显式 reauthentication 成功才产生更晚时间。
- OIDC `auth_time`/`max_age` 成功与过期矩阵；仅有 select-account/consent 或无可信
  provider auth time 时，普通登录可用但 add/remove/bind/set-primary 等敏感操作失败。
- 用户禁用或 security version 递增后，旧 access/refresh token 在 UniAuth 与 introspection
  立即失效；旧版本 token 不能靠 refresh 获得新版本。
- 撤销 `ROLE_ADMIN` 或其他 authority 后，旧 access token 不能继续通过对应授权；
  新授予权限也只能出现在变更后的新版本 token 中。
- 用当前代码签发的 legacy access/refresh fixtures 验证：所有登记消费者的 strict 模式
  均拒绝缺 sid/auth_time/security version 或 refresh audience 的 token；预部署期间
  strict 观测不得改变共享流量的认证结果，也不得形成自动 fallback。

**回滚**

- 联合切换前可撤回尚未激活的 validator 代码；联合切换后按 H2.5 回滚规则处理，
  不能补造 sid/auth_time/security version、恢复双格式接受或关闭 issuer/audience/type 验证。

**完成定义**

- 所有 token 接受点已有同一 strict 验证策略和通过的 legacy-rejection fixture；
  消费者清单、配置和切换证据齐全，但本工作项不单独改变共享流量 token 格式。

### H2.3 Refresh rotation、replay detection 与 blacklist

**涉及文件**

- `TokenRefreshService.java`
- `TokenController.java`
- `TokenBlacklistEntity/Repository`
- 新 token revocation service
- 新统一 `TokenSessionService`/pair issuance context
- local、OAuth、email、Web3 登录 controller/success handler
- migration/schema
- `frontend/src/services/authService.ts`

**前置条件**

- H2.2 完成。

**实施**

1. 每次 refresh 在一个事务中消费旧 refresh jti、写入撤销记录并签发新 token pair。
2. 同一个 refresh token 第二次使用必须失败，并触发可观测的 replay 事件。
3. 刷新前验证用户存在且 enabled；禁用用户不能继续刷新。
4. blacklist 查询接入 access/refresh 验证。
5. 明确过期撤销记录的清理任务，不影响仍有效 token。
6. 将持久化模型扩展为最小 refresh-session/family 状态，记录 family id、当前 generation、
   refresh jti/父子关系、每代 access jti、状态和过期时间；family id 作为 token 的 `sid`，
   同一 pair 和后续 rotation 始终保持同一 sid；数据库约束保证 family sid、refresh jti、
   access jti 全局唯一，并保证同一 family 的 generation 唯一且只消费一次。
   任何标识符碰撞都使整个 issuance/rotation 事务失败并重新开始，不把重复标识 token 返回给客户端。
7. 建立唯一 `TokenSessionService`：local、OAuth、email、Web3 初始登录和 refresh rotation
   都提交一个不可变 issuance context，包含 userId、canonical username、verified email、
   authorities、security version、auth_time、sid/family 与 token TTL；access/refresh 从同一快照签发。
   controller/success handler 不得分别直接调用 access/refresh signer，遗留 `generateToken` 和
   合成 email helper 删除或收为不可达内部实现。服务通过条件更新/CAS 与版本校验确认 user enabled，
   持久化 family/generation/audit 与 pair claim 一致后才允许响应返回；事务失败时已生成 token
   不得离开进程。
8. 密码修改、账户禁用等安全状态变化必须撤销该用户现有 refresh family，
   并按 H2.2 递增 token security version，立即拒绝仍有效的旧 access token。
9. 任一已消费 refresh token 被再次提交时，在同一安全事务中撤销整条 family 及所有
   已签发后继，并使该 family 所有仍有效 access token 在 UniAuth/introspection 立即失效；
   不能只标记旧 refresh jti 后让攻击者先取得的 successor 或 access token 继续使用。
10. 在启用严格 reuse 撤销前，前端 401 interceptor 必须先实现全局 single-flight refresh，
   所有失败请求等待同一个 Promise，避免正常并发请求重复提交同一 refresh token
   并自触发 family 撤销。
11. refresh rotation 是不可自动重试的非幂等操作；前端 HTTP client、反向代理和服务网格
    均不得在超时/连接中断后重放同一 refresh 请求。若新 token 响应丢失，客户端再次提交
    旧 token 时按 reuse 撤销整族、清理 cookie 并要求重新认证，不通过存储/回显原始
    refresh token 来伪造幂等恢复。
12. single-flight 必须覆盖同源多标签页/窗口，而不只是单个 JavaScript runtime：
    使用经测试的 Web Locks/SharedWorker/Service Worker 等 origin-wide 互斥并通过
    BroadcastChannel 通知认证状态变化；不支持可靠协调时停止自动并发 refresh，
    不能以易失、无 fencing 的 localStorage flag 假装互斥。
13. refresh 请求若同时携带 access cookie，必须验证其签名和结构，并要求 userId/sid 与
    refresh cookie 一致；access 已过期时只允许使用“验签成功但忽略 exp”的受限解析结果做
    pair 一致性比较，不能把它当作有效 access principal。主体或 sid 不一致时拒绝刷新、
    清除浏览器认证 cookie 并记录不含凭据的安全事件，不能让 refresh 覆盖另一身份。
14. H2.3 在隔离数据库和 fixture 中完成新 family/pair issuer 与 rotation；可以先合入，
    但不得在共享登录/refresh 流量激活。所有入口切换到该 service、停止 legacy token 签发
    和开始使用新 cookie transport，必须等待 H2.5/H3.1/H3.2 联合发布。

**测试**

- 正常轮换、重复使用、两个并发 refresh、禁用用户、已撤销 token。
- local、OAuth、email、Web3 初始登录和 refresh 都只能经过统一 session service；
  每个 pair 的 userId/sid/security version/auth_time/TTL 来自同一 snapshot，
  架构测试确认 controller/success handler 不再直接调用分离 signer。
- 固定随机源制造 sid/access jti/refresh jti 碰撞时数据库拒绝重复值，事务不产生可用 token；
  正常样本满足格式、熵来源和全局唯一约束。
- 事务失败时不能出现旧 token 未撤销却签发新 token 的部分成功。
- 模拟攻击者先刷新、合法客户端后提交旧 token：reuse 检测后攻击者取得的 successor
  和该 family 后续 refresh/access token 在 UniAuth 与 introspection 全部失效。
- 多个并发 401 只产生一次 refresh 请求；等待者复用结果，失败时统一结束且不形成重试风暴。
- 两个及以上同源标签页同时收到 401 时只有一个提交旧 refresh token；其他标签页等待 cookie/
  主体更新并重新请求，不触发 reuse。锁持有标签页崩溃、超时和浏览器不支持协调能力时
  有界恢复或要求重新认证，不形成永久锁和重复 refresh。
- access/refresh cookie 的相同用户同 sid、相同用户不同 sid、不同用户、access 过期、
  access 畸形和单独 refresh 矩阵；只有合法 pair 或 refresh-only 请求可按策略轮换，
  不一致 pair 不会覆盖当前身份。
- 模拟数据库已提交但 refresh 响应丢失：客户端/代理不自动重试；人工再次提交旧 token
  会撤销 successor 和整族、清理本地状态并进入重新认证，不继续静默循环。

**回滚**

- 可以停止签发新 token，但不能删除仍在有效期内的撤销记录。

**完成定义**

- 新 family/pair issuer 在隔离环境中保证 refresh 单次使用，发现 reuse 后整条 family 失效，
  不留下攻击者可继续使用的后继；共享流量激活留给联合切换。

### H2.4 统一登出、JWKS 与内省

**涉及文件**

- `AuthController.java`
- `ApiAuthController.java`
- `OAuth2TokenController.java`
- `AuthorizationServerConfig.java`
- Python resource server

**前置条件**

- H2.3 完成。

**实施**

1. 合并重复 logout 路由和实现。
2. logout 从已验证 principal/cookie 提取 jti/sid，撤销 access 与 refresh family，再清 session/cookie。
   access 已过期但 refresh 仍有效时也必须允许安全登出，并验证两个 token 的 userId 一致。
   两者都存在时还必须验证 sid 一致；不一致时分别按可验证凭据执行有界撤销并返回稳定的
   主体冲突/撤销未完成结果，不能把其中一个静默当作当前身份。
   revocation/family 持久化失败时仍尽力清理本浏览器 cookie/session，但返回稳定 503/
   `REVOCATION_INCOMPLETE`，记录不含凭据的安全事件，不能以 200 宣称 token 已全局失效。
3. introspection 只接受 POST form，要求受管客户端认证，不接受 query/body 手工猜测。
4. introspection 使用同一 strict validator 和 blacklist，返回真实 type/scope/active。
   调用客户端必须被授权服务该 token 的 audience/resource；错误 audience 的受管客户端
   也不能探查 token。响应只返回该资源服务器必需的 allowlist claims，不默认泄露 email、
   全部 authorities 或内部 security version。
5. JWKS 保持公开但只暴露公钥，增加合理 cache header。
6. 禁用当前未完成且无人使用的 Authorization Server authorize/token/revoke 配置，
   移除 `{noop}auth-secret` 和 password grant 假象；保留当前实际使用的
   OAuth2 Client 登录、自定义 JWT、JWKS 和受保护内省。
7. 明确异构资源服务器的撤销模型：需要即时撤销时调用受认证 introspection；
   纯离线 JWKS 验证只能接受 access token 剩余 TTL。密钥泄露时还必须遵守 H2.1 的
   revoked-kid 与 key-cache SLA，不能把普通 JWKS cache refresh 当作即时吊销。
8. introspection client credential 从 secret manager/环境加载、可轮换且不进入仓库或日志；
   优先采用适合部署环境的强客户端认证，明文默认 secret 和共享万能客户端禁止使用。

**测试**

- logout 后 UniAuth API 立即拒绝 token。
- logout、reuse 或同用户重新登录撤销 family 后，该 family 已签发且尚未过期的 access token
  在 UniAuth/introspection 立即失败；纯离线 Python 行为符合声明的 TTL/SLA。
- 紧急 revoked kid 在 UniAuth/introspection 立即失败；纯离线客户端按 H2.1 声明的
  key-cache/reconfiguration SLA 失效，测试证据不得用 JWKS 源端删除时间代替客户端生效时间。
- 注入 blacklist/family 存储失败时 cookie/session 被清理，但响应明确撤销未完成；
  恢复后可重试，不会把未持久化撤销报告为成功。
- Python 的 introspection 模式拒绝已撤销 token。
- 未认证 introspection 401；JWKS 仍可公开读取。
- 已认证但未授权该 audience 的客户端被拒绝，合法客户端只得到 allowlist claims；
  client credential 轮换窗口和旧 credential 失效行为可验证。
- 当前 OAuth2 Client 登录路由不受关闭不完整 Authorization Server 影响。

**回滚**

- 未泄露 key 只能在 H2.1 的正常轮换上限内保留验证窗口；revoked/泄露 kid 的拒绝状态
  不随应用回滚而撤销。不能恢复未认证 introspection、明文 client secret 或仅靠 JWKS
  源端重新发布旧 key 来绕过资源服务器失效策略。

**完成定义**

- G2 中 token 撤销相关门禁通过。

### H2.5 收敛 token transport

**涉及文件**

- 所有签发 token 的 controller/success handler
- 新统一 cookie writer
- 前端 auth service/hook
- OpenAPI 和 live docs

**前置条件**

- H2.1-H2.4 的代码、隔离测试、消费者 inventory 和发布证据完成，但尚未对共享流量激活。
- H3.1 的 filter-chain/唯一凭据来源矩阵已实现并通过测试。
- 与 H3.1 的生产激活及 H3.2 作为同一个设计、实现和回滚批次，不单独宣告完成。
- H3.3-H3.7 可以依赖 staged 的 H2.5 代码完成实现和隔离测试；第 11 步的共享流量激活
  必须等待这些工作项全部达到 G2，不把最小原子集合误当成完整发布门禁。

**实施**

1. 建立统一 cookie factory，从配置读取 Secure、HttpOnly、SameSite、Path、Max-Age。
2. 浏览器 refresh token 只放 HttpOnly cookie，不出现在 JSON body。
3. 生产浏览器模式不把 access/refresh token 写 localStorage。
4. 为当前异构资源服务器演示保留的 JavaScript bearer 模式必须 dev-only、
   显式启用并在 UI/文档中标为风险模式。
5. token 时长只从配置生成响应，不硬编码 3600/604800。
6. 所有签发、刷新、内省和认证状态响应设置 `Cache-Control: no-store`、
   `Pragma: no-cache`，不得被共享缓存保存。
7. 生产 access、refresh 和 session cookie 使用满足平台约束的 `__Host-` 名称：
   `Secure`、`Path=/`、无 `Domain`；本地 HTTP 使用明确分离的 dev 名称，避免把弱属性带入生产。
8. access token header 与 cookie 同时出现时拒绝请求，不按“header 优先”静默选择；
   browser cookie 模式和异构资源服务器 bearer 模式必须能由凭据来源明确区分。
9. cookie 清除复用同一 factory，名称、Path、Domain/Secure/SameSite 与设置时一致；
   迁移时显式清除旧 `accessToken`、`refreshToken` 和 `JSESSIONID` 名称。
10. local/OAuth/email/Web3 登录完成前检查现有 access/refresh cookie pair：
    同一用户的有效旧 family 及其未过期 access token 必须在新 pair 提交前原子撤销/替换；
    不同用户的有效凭据使登录
    失败并要求先 logout；无效或过期 cookie 先按统一 factory 清除。不能只覆盖 cookie 值，
    把旧 refresh family 留在服务端继续有效。
11. 在 H3.1-H3.7 全部通过 G2 后，作为批次 5 的最后动作执行唯一一次协调切换：
    先确认所有登记消费者已部署 strict validator、H3.1/H3.2 和新 cookie writer 可同时激活，
    再停止 legacy 签发并启用 H2.3 的 family/pair issuer，激活 strict validator，
    按 H2.1 吊销 tracked/旧 signing kid，清理旧 cookie 并强制重新认证。
    切换后缺 required claim、旧 kid 或旧 cookie 名的 token 全部失败；不能等待旧 refresh TTL、
    按请求 fallback 或长期双格式共存。任一步无法完成时不得开放共享流量。

**测试**

- local/OAuth2/email/Web3/refresh 的 cookie 属性快照一致。
- 生产配置响应不含 refresh token，前端不写 localStorage。
- dev 互操作模式默认关闭且有独立测试。
- login/refresh/introspection 响应与重定向链的缓存 header 符合策略。
- 生产 `Set-Cookie` 满足 `__Host-` 规则，伪造的 domain cookie 不能覆盖认证身份；
  logout 能同时清除当前和迁移期旧 cookie。
- 同请求携带相同或不同的 header/cookie access token 都失败关闭，不发生跨用户身份选择。
- 已有同用户 token pair 时重新登录只留下一个有效 family，旧 access 也立即失效；
  已有不同用户 access 或 refresh
  时 local/OAuth/email/Web3 登录都不能静默切换，显式 logout 后才能建立新身份。
- 联合切换演练覆盖 Java、Python、introspection 和所有登记消费者：新 pair 可用，
  legacy access/refresh、旧 kid 和旧 cookie 均被拒绝；部分消费者未切换时发布门禁失败。

**回滚**

- 可以在 dev 显式开启互操作模式；prod 不回滚到 refresh token JSON/localStorage。
- 联合切换后即使回滚业务代码，也必须保留 strict required claims、旧 kid 吊销、
  legacy token 拒绝和旧 cookie 清理；兼容性问题通过重新认证或停止流量处理，
  不恢复双格式接受。

**完成定义**

- 浏览器长期凭据不可被 JavaScript 读取；新 family/token schema 已在所有登记消费者激活，
  legacy token/kid/cookie 已协调失效。

## 9. Phase 3：SecurityFilterChain、CSRF、CORS 与 OAuth2 redirect

### H3.1 建立 endpoint 权限矩阵并重构过滤链

**涉及文件**

- `AuthApiConfig.java`
- `AuthorizationServerConfig.java`
- `ResourceServerConfig.java`
- `SecurityConfig.java`
- 所有 controller mapping

**前置条件**

- H0 危险 endpoint 已移除。
- 先生成 endpoint inventory。

**实施**

1. 按 endpoint 而不是 `/api/auth/**` 整目录定义 public/authenticated/admin。
2. Web3 bind 和 login-method 管理只接受已验证 access principal。
   logout 使用 H2.4 的独立撤销规则：可由有效 access、有效 refresh cookie 或两者共同发起；
   access 已过期但 refresh 仍有效时仍允许撤销 refresh family。两者同时可验证时必须属于
   同一用户；任何凭据无效、存储故障或主体不一致都不能阻止本地 cookie/session 清理，
   但全局撤销未完成时不得返回成功语义。
3. 将 OAuth2 Client 登录、API resource server、公开认证 endpoint 的职责分开。
4. 删除重复或永远匹配不到的 matcher。
5. 对每条 chain 记录 order、securityMatcher、CSRF 和 session policy；
   `/api/**` resource chain 使用 `SessionCreationPolicy.STATELESS`、关闭 request cache，
   不从 HttpSession 恢复 API 身份。
6. 对未知 `/api/**` 默认拒绝，不以宽泛 authenticated 掩盖误路由。
7. 只保留一个 canonical 当前用户 endpoint（`/api/user`），删除或明确弃用
   当前落入公开链的 `/api/auth/user`。
8. session 只保存 OAuth2 state、PKCE verifier 和 binding intent correlation；
   应用 API 只信任严格校验的 access JWT。OAuth callback 签发 JWT 后清理临时认证状态，
   不把 provider principal 留作另一套长期应用身份；local login 不持久化 SecurityContext
   到 session。logout 同时清理 JWT、临时 session 和 provider state。
   Web3/email challenge 使用各自的持久化 challenge id/短期关联机制，不创建应用 HttpSession
   或把 challenge correlation 当作认证身份。
9. BearerTokenResolver 返回并审计唯一凭据来源；header/cookie 双凭据、重复同名 cookie、
   多个/逗号折叠 Authorization header、非规范 Bearer、空 token，以及 session principal
   与 API token principal 不一致时均失败关闭，不依赖代理/容器取值或合并顺序。

**测试**

- 参数化 endpoint matrix：匿名、普通用户、管理员、refresh token、错误 token。
- refresh token 不能访问 bind、login-method 或普通资源 API，但 refresh-only logout 可以撤销
  family；access/refresh 主体不一致时本地状态仍被清理且返回稳定撤销失败结果。
- 验证请求只进入预期 filter chain。
- header/cookie/session 组合矩阵不会把一个浏览器中的不同用户身份静默拼接。
- 多个 Authorization header、折叠双 Bearer、重复 access cookie、大小写/空白畸形 scheme
  在 JWT 解析前稳定失败。
- 已存在 OAuth session 不能绕过 `/api/**` JWT；local login 不创建长期认证 session，
  callback 完成后旧 provider principal 不再能访问应用 API。

**回滚**

- 权限问题只能按单 endpoint 修正，不能恢复 blanket permit。

**完成定义**

- 每个 endpoint 的公开理由和认证方式可从测试直接看到。

### H3.2 修复 cookie 认证的 CSRF

**涉及文件**

- security config
- 统一 cookie writer
- `frontend/src/services/authService.ts`
- 可能的 CSRF bootstrap endpoint

**前置条件**

- H2.1-H2.4 已达到预部署门槛但尚未对共享流量激活，cookie/browser 认证模型已形成评审决策。
- 与 H2.5 作为同一个原子变更集实现，不以互相等待作为前置。

**实施**

1. 对 cookie 可自动携带或会建立/切换浏览器身份的 local login、register finalization、
   email registration verify、Web3 challenge issuance/verify、refresh、logout、bind 和登录方式修改
   启用 CSRF；匿名不等于可免除 login-CSRF，攻击者不能把受害浏览器登录进攻击者账户。
2. 仅对纯 Bearer header 且不读取认证 cookie 的 stateless endpoint 禁用 CSRF。
3. 使用一个 `CookieCsrfTokenRepository` 和固定 header 名；生产 CSRF cookie 使用
   `__Host-` 名称、`Secure`、`HttpOnly`、`Path=/`、无 `Domain`，
   避免 sibling-domain cookie 注入；JavaScript 不再读取该 cookie。header token 必须与
   当前浏览器上下文自动携带的 HttpOnly CSRF cookie 匹配，不能把仅有 header 值视为有效。
   CSRF header 和 cookie 都必须恰好一个；重复、逗号折叠或容器解析结果不唯一时失败关闭。
4. 提供 `no-store` 的 CSRF bootstrap endpoint/response header，前端从响应取得 token
   并只保存在内存，由 axios interceptor 发送；不依赖跨域读取 `document.cookie`，
   不写 localStorage。
   明确 Spring 6 BREACH masking/`CsrfTokenRequestHandler` 语义，bootstrap 返回值与请求
   header 校验使用同一 raw/masked 约定，不能在升级后静默失效。
5. 校验 SameSite 策略与真实 OAuth2 callback、跨域部署拓扑兼容。
6. 将 session cookie 属性移到 Boot 3.3.4 实际绑定的
   `server.servlet.session.cookie.*`；删除无效 `spring.session.cookie.*`，
   通过真实 `Set-Cookie` 断言而不是只测 properties 对象。
7. 明确 Spring Session auto-configuration 与 `@EnableSpringHttpSession` 的唯一责任方；
   OAuth2 认证前后按 Spring 策略旋转 session id，callback 完成后清理临时 session。
   local/JWT 登录不得把手工设置的 `SecurityContextHolder` 保存为长期 session，
   并清除浏览器中与目标 JWT 用户不一致的旧 OAuth session。
8. CSRF 决策使用 H2.5/H3.1 已确认的凭据来源；只要请求含认证 cookie 就不能因另有
   Authorization header 而进入 bearer-only 豁免。
9. 登录成功、OAuth callback、logout、session id/应用身份切换时清除并轮换 CSRF token；
   旧身份或预认证阶段取得的 token 不能继续授权状态变更。前端在这些边界重新 bootstrap，
   失败时清空内存 token 并停止自动重试。
10. refresh 或身份切换导致 CSRF token 轮换时，通过 H2.3 的同源协调通道通知其他标签页
    丢弃旧内存 token；各标签页在下一次状态变更前重新 bootstrap，不并发复用失效 token。

**测试**

- 带 cookie 无 CSRF 的状态变更 403。
- 跨站提交攻击者自己的 local 凭据、email code 或 Web3 challenge/signature 不能让受害浏览器
  建立或切换到攻击者身份；匿名 challenge/verify 仍可在取得同源 bootstrap token 后正常完成。
- 正确 token 与当前 HttpOnly CSRF cookie/context 配对时成功；错误 token、其他浏览器上下文
  的 header token 与当前 cookie 组合、缺失、重复/折叠 CSRF header 或重复 CSRF cookie 均失败。
- Bearer-only 请求不被无关 CSRF 阻断。
- 同时携带 bearer header 和认证 cookie 的状态变更不能借 header 绕过 CSRF。
- 生产 CSRF cookie 满足 `__Host-` 规则；跨 origin 前端通过 bootstrap 响应而不是
  `document.cookie` 获取 token，恶意 domain cookie 不能造成验证通过。
- CSRF cookie 为 HttpOnly；Spring 6 raw/masked token 测试均按选定 handler 通过。
- OAuth state session 在选定 SameSite 下能完成 callback，错误地使用 Strict 时测试应失败。
- OAuth 登录前后 session id 按策略旋转并在 callback 后清理；local 登录不创建长期认证 session。
- 临时 session cookie 的 Secure/HttpOnly/SameSite/Path 实际生效。
- 登录/登出/身份切换前后的旧 CSRF token 失败，新 bootstrap token 成功；失败不会形成
  bootstrap/login/refresh 的循环重试。
- 旧 refresh-only 身份、access/refresh 不同 user/sid 和同用户重新登录的 CSRF/token-pair
  矩阵符合 H2.3/H2.5：不同身份不切换，同身份替换后旧 family 与旧 CSRF token 都失效。
- 一个标签页 refresh/登录/登出后，其他标签页的旧 CSRF token 失败并能有界重新 bootstrap，
  不重复提交 refresh 或状态变更。

**回滚**

- 可临时将特定 endpoint 改为 Bearer-only；不能全局关闭 cookie CSRF。

**完成定义**

- cookie 认证不再依赖 SameSite 作为唯一 CSRF 防线。

### H3.3 CORS 单一来源

**涉及文件**

- `application*.yml`
- `CorsConfig.java`
- `WebConfig.java`
- `WebMvcConfig.java`
- security chains

**前置条件**

- 明确本地、测试、生产允许的 origin 列表。

**实施**

1. 保留一个 `CorsConfigurationSource`，从 validated properties 读取。
2. 删除另外两个/三个重复 MVC CORS 实现。
3. credentials=true 时禁止 wildcard origin。
4. origin、methods、headers、exposed headers 按最小集合配置。
5. 生产不包含 localhost 和历史隧道域名；测试域名由环境变量显式提供。
6. 若 CSRF bootstrap 通过响应 header 传 token，只暴露该固定 header；
   不为便利暴露任意认证或调试 header。

**测试**

- allowed/disallowed origin 的 preflight 与实际请求。
- 不同 profile 的 origin 快照。
- credentials、Authorization header 和 CSRF header 的组合。

**回滚**

- 通过配置增加单个 origin；不恢复多处硬编码。

**完成定义**

- CORS 行为能由一个配置对象解释。

### H3.4 OAuth2 state、显式绑定意图与 redirect allowlist

**涉及文件**

- `SecurityConfig.java`
- login-method controller/service 与短期 binding-intent store
- `application*.yml`
- `frontend/src/services/authService.ts`
- `frontend/src/pages/TestPage.tsx`
- 前端 callback 逻辑

**前置条件**

- 确认本地和部署 callback/landing URL。
- H3.1 已建立可信 access principal，H3.2 已提供状态变更 CSRF 防护。

**实施**

1. 不从未验证 state JSON 接受任意 redirect URI。
2. 使用 Spring 管理的 state/session 或服务端保存的短期 request context。
3. 普通 OAuth 登录和账户绑定使用不同的服务端入口与 intent purpose；
   `TestPage.tsx` 先通过带 CSRF 的认证 API 创建绑定意图，再进入 provider 授权，
   不再直接复用普通 `getLoginUrl()`。
4. 绑定意图只在已验证 access principal 下创建，绑定 target user 取自 principal，
   不接受客户端 userId；记录 intent id、purpose、provider、target user、redirect、
   state correlation、创建时的 auth_time/token security version、createdAt、expiresAt，
   并进行完整性保护。intent id 使用至少 128-bit CSPRNG 和固定 canonical 格式生成，
   不接受客户端提供、可预测计数器或从 user/provider/时间派生的值；碰撞时事务失败并重新生成。
5. 绑定属于敏感账户操作：要求最近一次认证时间在配置窗口内；超时则要求重新认证，
   不能仅凭仍有效的长期 cookie 创建 intent；时间依据 H2.2 的 `auth_time`，
   不能依据 access token `iat`。intent 的 expiresAt 不得晚于 `auth_time + recent-auth window`，
   即使通用 OAuth state TTL 更长也不能扩大敏感操作窗口。
6. callback 只有在 state、session/request context、provider 和单次绑定意图全部匹配时
   才调用 bind；意图成功或失败后均失效，过期、重复或跨 session 使用必须失败关闭。
   在 claim intent 和提交绑定前重新读取 target user，验证 enabled、当前 security version
   与 intent 快照一致，且 auth_time 在 callback 时仍处于 recent-auth 窗口；
   期间发生密码重置、登录方式变化、账户禁用或其他版本递增时 intent 立即失效。
   binding intent 在进入 provider 身份绑定前以独立原子状态转换从 ISSUED claim 为 CONSUMED；
   后续绑定事务失败也不恢复 intent，客户端必须重新创建，避免回滚后重复 callback。
7. 普通登录即使携带有效 access 或 refresh cookie 也绝不进入绑定分支；
   已有活动 access/refresh family 且未携带显式 binding intent 的新 OAuth 登录按 H2.5
   执行同用户原子替换或不同用户拒绝，提示先退出或使用绑定入口；
   不创建关联，也不静默切换账户或遗留旧 family。
8. success/failure redirect 只能选择配置 allowlist 中的绝对 URI 或安全相对路径。
   Referer 不能作为信任来源；若仅用于选择前端落点，也必须通过同一个 allowlist resolver。
9. 对 scheme、host、port、path 做精确比较，拒绝 userinfo、编码绕过和 scheme-relative URL。
10. 登录/绑定的成功和失败处理器共享同一 redirect resolver；消费 state、PKCE verifier
    和 binding intent 后清理临时 session，不保留 provider principal 作为应用 API 身份。
11. OAuth scope 收敛到建立身份所需最小集合；删除 X 的 `offline.access`、
    tweet/like/follows 等当前未使用 scope。回调完成后删除不再需要的 authorized client，
    success/failure/logout 都不能遗留 provider access/refresh token。
12. 建立 provider email trust policy：
    Google 必须校验 `email_verified`；GitHub 只使用 emails API 返回的 verified primary email；
    X 或无 email provider 不写 canonical `users.email`，仅在 provider/login-method 字段保存
    不可信显示标识并保持 `emailVerified=false`。
    未验证/合成标识不参与 password reset、email binding 或跨 provider 冲突判断。
13. 自定义 GitHub/X user-info HTTP client 使用配置驱动的 connect/read timeout、
    响应大小限制和最小重试；认证请求取消或超时后清理临时 OAuth state。
14. 保留并验证当前 OAuth2 Client PKCE；每个授权请求使用新的高熵 verifier，
    callback 缺失/错误 verifier 必须失败，不能因使用 confidential client 而关闭。
15. provider email 只作为经过信任策略判定的属性或冲突提示，绝不能单独触发账户合并；
    跨 provider 关联仍必须经过第 3-6 步的显式 binding intent。
16. 为每个 provider 明确定义稳定主体标识来源：Google 使用 OIDC `sub`，GitHub/X 使用
    provider 返回的不可变用户 ID；回调先校验非空、预期标量类型、规范化和长度上限，
    缺失或畸形时失败关闭。首次登录的“查找或创建 user + login method”使用单一事务，
    由 H1.4 的 provider 唯一约束裁决并发，不能以 email、display name 或 username
    作为 subject 回退，也不能留下无登录方式用户。
    已有 provider subject 解析到用户后，在更新 provider metadata/last-used 或签发 token 前
    检查 enabled；disabled 用户走与其他认证失败一致的稳定结果，不创建新 user 影子记录。
17. provider 只能取自服务端已保存的 authorization request 和
    `OAuth2AuthenticationToken` client registration id，再经单一映射转换为 domain enum；
    不能根据 user-info 是否含 `sub`、`login`、`username` 猜测，也不接受客户端 provider 参数。
    未知或上下文不一致的 registration 必须在建号/绑定前失败关闭。
18. Google OIDC 保留 Spring Security 的 ID token 校验链，验证固定 issuer、audience/`azp`、
    nonce、签名和时间声明；自定义 success handler/user service 不得直接信任未验证 claims。
    多 provider 共用 callback 时以保存的 authorization request、state、issuer/registration
    共同防止 mix-up，来自错误 token endpoint/issuer 的响应在建号前失败关闭。
19. 普通 OAuth 登录中，新 provider subject 的 trusted verified email 若已被另一用户占用，
    必须失败关闭：不自动合并、不创建 email=null 的影子账户、不签发 token。
    客户端只得到不含目标用户信息的稳定冲突码；用户需先以已有账户登录并创建显式
    binding intent，再绑定该 provider。trusted email 未占用时才可原子写入 canonical email。
20. 已绑定 provider subject 后续返回不同 email 时，不自动覆盖 canonical email、
    合并账户或改变 password-recovery 地址；只可更新明确标为非权威的 provider metadata。
    独立的认证后 email-change/重新验证流程未实现前，canonical email 变更保持关闭。
21. OAuth 首次建号按 H1.4 生成系统保留命名空间的 opaque canonical username；
    不使用 trusted email、provider subject/display username 或客户端字段作为 `users.username`，
    provider 可变名称只进入受限 metadata/display 字段。

**测试**

- 正常本地/部署 redirect。
- `https://evil.example`、`//evil`、userinfo、双重编码、CRLF 等恶意 state。
- state 缺失、过期、重复使用。
- 未登录创建 binding intent、无/错 CSRF、过期 reauthentication、篡改 target user。
- intent 创建时 fresh 但 callback 时 freshness 已过期、security version 已变化或用户已 disabled
  时不绑定；intent TTL 不能超过 recent-auth 剩余时间。
- 普通登录携带 access、refresh-only 或不一致 token pair 都不绑定；只有匹配 intent 的
  provider callback 可以绑定，不同身份不能静默切换。
- binding intent 的猜测/碰撞、跨 session、错 provider、重复 callback 和并发消费全部失败。
- 恶意 Referer 不能进入 session redirect context，allowlist 内 Referer 只影响落点不影响 purpose。
- scope 快照不包含未使用权限；回调成功/失败/登出后 authorized-client store 无 provider token。
- verified/unverified/missing provider email 矩阵，不可信 email 不能进入找回或绑定流程。
- provider user-info 超时、超大响应和 5xx 不泄露 token，且不会留下可复用 state。
- 授权请求包含 S256 PKCE，缺失、篡改、跨 session 或重复使用 verifier 的 callback 失败。
- 即使两个 provider 返回同一 verified email，也不会在没有 binding intent 时自动合并账户。
- provider subject 缺失、类型错误、空白或超长时不建号/绑定；
  同一新 subject 的两个并发首次登录最多一个成功建号，失败事务不留下孤立 user。
- 已绑定 provider subject 对应 disabled 用户时不更新 last-used/provider metadata、不签发 token，
  外部响应不暴露账户状态；不能因相同 email 或并发 callback 创建第二个影子用户。
- user-info 同时含 `sub`/`login`/`username`、客户端伪造 provider 或 registration/state
  provider 不一致时都不能改变服务端确定的 provider 命名空间。
- OIDC nonce 缺失/重放、错误 issuer/audience/azp/签名/时间，以及 GitHub/X/Google
  authorization response/provider mix-up 均失败且不创建 session、user 或 login method。
- 新 provider subject 的 verified email 已被占用时不建号、不登录、不泄露目标账户；
  只有认证已有账户后的 binding intent 能完成关联，并发 login/bind 仍保持唯一所有者。
- 已绑定 provider 的 email 缺失、从 A 改为 B 或可信度下降时，canonical email 与恢复地址
  不自动变化，也不会触发跨账户关联。
- OAuth 首次建号的 canonical username 不泄露或包含 provider subject/email/display name，
  普通注册无法抢占保留命名空间，并发首次登录仍只有一个 user。

**回滚**

- 可关闭 OAuth2 绑定并回滚到固定配置前端 URL；
  不回滚到 cookie 推断绑定、客户端任意 redirect 或未校验 Referer。

**完成定义**

- open redirect 与隐式绑定测试矩阵全部失败关闭，绑定意图可审计且只消费一次。

### H3.5 统一认证错误、安全日志并扩展审计覆盖

**涉及文件**

- `GlobalExceptionHandler.java`
- 各 controller catch block
- logging 配置
- H1.5 security audit service/outbox 与事件生产者
- 高频安全事件管道、指标和告警配置

**前置条件**

- endpoint matrix 稳定。
- H1.5 持久安全审计基座完成。

**实施**

1. 统一 400/401/403/404/409/429/500 错误码和 JSON shape。
2. 不在 5xx 响应中返回 `e.getMessage()`。
3. 用结构化日志替换 `System.out/err` 和 `printStackTrace`。
4. 认证失败只记录必要上下文，不记录凭据。
5. 复用 H1.5 的单一事件 schema 和写入接口，禁止 controller/service 自建不兼容日志事件；
   将 key lifecycle、refresh reuse/family 撤销、凭据重置、账户启停、authority 变更、
   登录方式增删/切换等 H2-H6 生产者纳入覆盖矩阵，缺失任一关键状态事件都使门禁失败。
6. 对关键状态变更继续使用 H1.5 的同事务 audit row 或 transactional outbox；
   本工作项不得以统一日志改造为理由弱化“audit 持久化失败则业务状态回滚”的语义。
7. 登录失败、限流拒绝等高频事件可进入有界异步安全事件管道，但必须同时保留聚合指标；
   管道不可用时执行明确的容量保护和告警策略，不得阻塞到耗尽认证线程，
   也不得把原始请求降级写入普通日志。
8. 按事件类别配置保留期、访问角色、导出、完整性校验和到期删除；
   安全事件 append-only，业务代码无更新/删除权限，清理由独立最小权限任务执行。

**测试**

- 错误响应契约快照。
- 日志敏感值断言。
- 安全状态变更与 audit/outbox 原子性故障注入；audit 写入失败时业务状态不变。
- 高并发认证失败、事件管道满/不可用、保留清理和未授权审计读取测试。
- 同一敏感值在日志、指标、audit row 和异常链中均不可恢复或直接搜索到。

**回滚**

- 可提高内部日志详细度，但外部错误 shape 和脱敏不回滚。
- 可暂时关闭非关键高频明细事件并保留聚合指标；
  关键安全状态变更的持久审计和原子性不能回滚。

**完成定义**

- 认证错误响应、日志脱敏和关键安全状态变更的持久审计矩阵通过，
  为 H3.6 完成 G2 HTTP 门禁提供稳定错误语义。

### H3.6 认证接口防暴力与防滥用

**涉及文件**

- local login/register controller
- OAuth2 authorization initiation/failure handler
- refresh、introspection、email、Web3 和 login-method endpoint
- 新统一 rate-limit properties/service/store
- trusted-proxy 配置与安全事件日志

**前置条件**

- H3.1 endpoint 权限矩阵稳定。
- 明确单实例开发与多实例部署的共享状态方案。

**实施**

1. 建立统一策略，并先为 local login、register、OAuth2 发起/失败、refresh、
   introspection 和敏感 login-method 写操作定义独立限额；
   email send/verify 与 Web3 nonce/verify 分别在 H4.2、H5.2 接入同一机制。
2. 限流 key 至少组合 endpoint、可信客户端 IP 和规范化账户标识的不可逆摘要；
   不把原始 email/username/wallet 写入指标 key 或高基数日志。
3. 生产多实例使用共享、原子存储；进程内 limiter 只能用于显式单实例 dev。
4. 只在配置的 trusted proxies 后接受转发客户端地址，拒绝伪造
   `X-Forwarded-For` 绕过或污染他人限额。显式配置 Spring forwarded-header 策略和受信代理网段；
   边缘代理必须覆盖而不是追加客户端提交的 Forwarded/`X-Forwarded-*`，
   非受信来源的这些 header 一律忽略。
5. 失败响应使用稳定 429 与配置驱动 `Retry-After`，不因“用户不存在”使用不同配额或错误。
   local login 对不存在、禁用、错误密码使用统一外部错误和有界、可比较的认证成本；
   保留框架 dummy password hash 或等价机制，不能以提前返回形成明显时间 oracle。
6. 采用有界退避/滑动窗口，避免可被攻击者利用的永久账户锁定；
   成功认证只重置对应维度，不清除 IP/全局异常信号。
7. H4.2 的 email 频控复用同一基础设施，并保留 challenge 数据库原子约束作为第二道防线。
8. 记录不含凭据的安全事件和指标，并设置容量上限与过期清理。
   生产共享 limiter 不可用时，local login/register、OAuth 发起、refresh、introspection、
   email/Web3 send/verify 和敏感 login-method 写操作在昂贵计算/外部调用前返回稳定 503，
   不静默 fail open；logout 不因 limiter 故障被阻断，仍执行 H2.4 的本地清理与撤销语义。
   已认证低成本只读请求是否继续服务需逐 endpoint 明确，不使用全局模糊降级。
9. 用 typed DTO/form binder 替换认证写路径的裸 `Map`，为 username、email、password、
   verification code、wallet、SIWE message/signature、OAuth state、Authorization header、
   cookie token 和 request body 设置配置/协议驱动的应用层上限；在 JWT/SIWE parser、
   password hash、数据库查询和外部 HTTP 前拒绝超限输入。JSON、form 和 query 中重复的
   安全字段名必须在绑定前拒绝，不能依赖 first-wins/last-wins 或代理折叠顺序。
10. 应用与 Nginx 的 header/body/cookie 上限形成一致矩阵；即使绕过代理直连 backend，
    超限请求也只能得到稳定 400/413/431，不进入全局 500 或记录原始内容。

**测试**

- 单 IP 多账户、单账户多 IP、并发突发和多实例竞争都不能突破配置限额。
- 用户不存在、禁用和错误密码的 local login 外部响应、限流行为与统计时延分布不可区分；
  forgot-password 的同类断言由 H4.4 完成。
- 伪造 forwarded headers 不改变客户端身份；可信代理链按配置解析。
- 通过受信代理和测试环境允许的后端直连分别验证协议、Host、客户端地址和限流 key；
  直连请求不能用伪造 forwarded header 获得 HTTPS/受信来源语义。
- limiter 存储故障、计数溢出和过期清理有确定行为。
- limiter 故障时高风险入口在昂贵工作前稳定 503，logout 仍可清理并报告撤销结果，
  已认证只读 endpoint 只按权限矩阵中明确的策略处理。
- 超长 bearer/cookie token、重复/过多 cookie、超长 username/password/code/SIWE message、
  大 body 和畸形编码在昂贵 parser/hash/service 调用前被拒绝，且日志不包含原始值。

**回滚**

- 可临时降低共享 limiter 的依赖范围并收紧入口；
  不能完全移除 local login、验证码和 Web3 verify 的服务端防滥用。

**完成定义**

- G2 范围内可匿名触发认证计算、外部调用或凭据猜测的入口都有可执行滥用测试与可观测限额；
  email 与 Web3 扩展项作为 G3 的显式未完成清单进入 H4/H5。

### H3.7 浏览器安全头、引用与缓存策略

**涉及文件**

- security config
- auth/token/controller response policy
- Nginx
- React entry/callback 页面

**前置条件**

- staged、尚未开放共享流量的 H2.5 token transport 与 H3.4 redirect 路径稳定。
- 盘点 React、Swagger、Thymeleaf 和静态资源实际加载来源。

**实施**

1. 通过 Spring Security/Nginx 的单一责任划分设置并测试
   CSP、`Referrer-Policy`、`X-Content-Type-Options`、frame policy 和 `Permissions-Policy`。
2. 生产 HTTPS 启用 HSTS；本地 HTTP 不发送会污染开发域名的 HSTS。
3. CSP 先基于实际资源清单收敛，禁止 `unsafe-eval`；
   若暂需 inline style/script 兼容，记录 nonce/hash 或带截止日期的最小例外。
4. OAuth callback、login、refresh、introspection、错误响应和任何含用户状态的页面统一 `no-store`；
   邮箱/SIWE challenge issuance 与 verify 响应也必须 `no-store`；
   指纹化静态资产才允许长期 immutable cache。
5. redirect/error query 只包含稳定错误码，不包含 provider exception、token、email 或内部细节；
   Referrer 不得把 OAuth code/state/error 传播给第三方资源。
6. 明确 Spring 与 Nginx 谁写每个 header，避免重复且冲突的 CSP/HSTS。

**测试**

- MockMvc 与代理后 smoke test 断言各类响应的最终 header。
- OAuth callback 加载第三方资源时不会发送 code/state Referer。
- CSP 阻止注入脚本、frame 嵌入和未批准连接，同时 React/OAuth 正常工作。
- HTTP/HTTPS、dev/prod 的 HSTS 与 cache matrix。

**回滚**

- CSP 可短期降为 report-only 以定位兼容问题；
  认证响应 `no-store`、Referrer 保护和生产 HSTS 不回滚。

**完成定义**

- 浏览器侧认证边界有可执行 header/cache 证据，G2 全部通过。

## 10. Phase 4：邮箱注册与密码重置

### H4.1 单一验证码与可靠发送语义

**涉及文件**

- `EmailVerificationCodeService.java`
- `EmailService` 及实现
- `EmailVerificationCode`/repository
- 新 email challenge properties、digest/encryption key provider
- `application*.yml`
- H1.5 security audit service
- migration

**前置条件**

- H1 migration 完成。
- 外部邮件服务可被 stub。

**实施**

1. 每次请求只生成一次 code，同一值用于持久化验证和邮件变量。
2. 数据库不存明文短码；使用版本化、外部管理的服务端密钥 HMAC 等抗离线枚举方案保存摘要。
   摘要至少绑定 challenge id、canonical email、purpose 和 code，验证使用常量时间比较；
   不能只存可跨记录互换的 `HMAC(code)`，密钥 id/轮换窗口需可审计。
   通过 validated properties 和专用 key provider 加载 digest/encryption key；配置只保存
   secret reference 与 key id，不保存密钥值。prod/test 缺失、重复 key id、未知算法或
   无法读取密钥时 fail closed；不得复用 JWT signing key，也不得自动生成后写入工作目录。
   publish、activate、retire 和紧急吊销使用 H1.5 的持久安全审计语义。
3. 使用 transactional outbox 或等价 durable state machine：
   先提交高熵、不可猜测的 pending challenge id 与幂等 delivery id，再由独立 worker 发送并标记
   `PENDING`/`SENDING`/`SENT`/`FAILED`/`EXPIRED`；不使用进程内 after-commit
   作为唯一调度保证。该状态只描述投递，不兼任 challenge 消费状态。
   challenge 另有 `INACTIVE`/`ACTIVE`/`CONSUMED`/`EXPIRED`（或等价）状态；
   verify 只接受投递为 SENT 且 challenge 为 ACTIVE 的记录，不能跨数据库事务直接假设
   网络调用、投递确认和 challenge 激活原子完成。
4. 若 HMAC 存储 code，投递 worker 所需明文只能短期加密存放或由受控密钥派生；
   邮件服务确认接收后立即清除，不把明文 code 留在普通 challenge 表。
5. email provider 请求携带幂等键；进程在“provider 已接收、状态未落库”窗口崩溃后，
   重试不得重复发送不同 code，也不得永久卡在虚假 pending。
6. API 只在 challenge/outbox 已持久化后返回稳定的“请求已接受/处理中”和 opaque challenge id，
   不得声称“邮件已发送”；真实与 decoy 请求返回相同 response shape，challenge id 不进入 URL、
   access log 或普通应用日志。
   数据库提交失败返回稳定不可用错误。provider 的 QUEUED/DELIVERED 才能推进 SENT，
   FAILED、INVALID_EMAIL 和超过截止时间不得伪装成投递成功。provider durable acceptance
   确认后，在同一数据库事务中把 delivery 标记为 SENT、清除明文 code material 并把
   challenge 激活；事务未提交时，即使 provider 已接收也不能验证，worker 必须依赖幂等键
   对账并完成状态修复。
7. email HTTP client 使用实际绑定的 connect/read timeout；非幂等发送不自动盲重试。
8. 不在日志记录 code、完整收件地址或外部响应正文。
9. 新 code 原子地替代同 email+purpose 的旧未用 code。
10. 区分 `deliveryDeadline`、`sentAt`、`verificationExpiresAt` 和绝对 `purgeAt`：
    worker 只在 delivery deadline 前按 provider 语义对暂时性失败做有上限、带抖动重试；
    验证有效期从首次确认 SENT 时开始，但不得超过 createdAt 起算的绝对总生命周期。
    到期或终态后销毁加密 code material，保留最小状态至 retention 到期后清理。
11. resend 使用新的 challenge/delivery id，并原子使旧 challenge 不可验证；
    旧 outbox 若已进入 provider 的不可取消队列，后续到达的旧 code 仍必须失败。
12. 所有 verify/register/reset 请求都携带 challenge id，并按该 id 使用带预期状态/版本的
    条件更新或 CAS 精确取得处理权；只有条件 DML 无法在所选隔离级别维持不变量时，
    才允许使用范围严格受限的单 challenge 行锁。
    不再按 `email + purpose` 查询“最新一条”。purpose 由具体 endpoint 在服务端固定，
    challenge 中的 canonical email 与客户端字段必须一致；错 id、跨 purpose、跨 email、
    旧 resend id 和猜测 id 均失败关闭且不影响其他 challenge。

**测试**

- 邮件捕获值能通过验证。
- 发送失败、超时、数据库失败、worker 重启和补偿失败。
- 在 commit 前、commit 后发送前、provider 接收后状态更新前分别模拟崩溃。
- pending 超过 delivery deadline、邮件延迟到达、临近截止确认 SENT、worker 重复领取、
  暂时性/永久性 provider 错误和 retry exhaustion。
- delivery 已 SENT 但 challenge 未 ACTIVE、challenge 已 ACTIVE 但 delivery 非 SENT 的
  人为不一致记录均失败关闭；provider 接收后激活事务失败可通过同一 delivery id 对账恢复。
- resend 后旧 code 失效，新 code 可用。
- 同一 email 并行 challenge、错/猜测 challenge id、跨 email/purpose 交换和 resend 新旧 id
  只影响精确目标记录，不会消费或验证其他 challenge。
- 相同 code 在不同 email/purpose/challenge 下摘要不同；交换数据库摘要、错误 key id、
  常量时间比较和 HMAC key 轮换窗口均有测试。
- 缺失/损坏 key、重复 key id、未知算法和 secret provider 不可用时请求失败关闭；
  正常轮换只在声明窗口内验证上一版本，紧急吊销后旧 key 立即拒绝，审计与 secret scan 通过。
- API 的 accepted 响应不被描述或记录为 delivered；outbox 提交失败不会返回 accepted。

**回滚**

- 只能回到仍受支持、版本化且使用外部密钥的上一摘要实现，并在有限窗口内按 key/algorithm id
  验证已签发 challenge；不能恢复明文 code、无密钥/可离线枚举 hash、双生成或失败仍成功。

**完成定义**

- 用户收到的 code 与唯一可消费 challenge 一致，排队、投递、验证和清理状态均可恢复且可审计。

### H4.2 重试、频控和并发

**涉及文件**

- email service/repository
- controller
- migration/constraints
- rate-limit 配置
- `frontend/src/services/authService.ts`

**前置条件**

- H4.1 完成。

**实施**

1. retry 使用 `max-retry-attempts` 配置，不硬编码 5。
2. 删除或改造 `check-verification-code`，不得存在不消耗尝试次数的 oracle。
3. verify 使用带版本/锁的原子更新；并发只有一个成功消费。
4. resend cooldown 查询必须按 createdAt desc，且 purpose 隔离。
5. 频控至少组合 email、purpose、客户端 IP，写入与检查原子化。
6. 429 返回准确、配置驱动的 Retry-After。
7. 公共客户端不能通过 request body 任意选择 `REGISTRATION` 或 `PASSWORD_RESET` purpose；
   purpose 由注册发送/验证和密码找回的具体 endpoint 在服务端固定。
   删除未实现的 `LOGIN` enum、前端联合类型和 schema 允许值，不在本轮加固中新增无密码邮箱登录；
   migration 先报告遗留 `LOGIN` 记录，将未终态记录失效并按 retention 处置后再收紧约束。
8. 删除未被产品流程使用的 `/api/auth/email/status/{email}`，
   或将其改造成不会泄露 pending registration 状态的认证后接口。

**测试**

- N 次失败后锁定；按 challenge id 的并发猜测不会突破上限，也不会增加其他记录的 retry。
- 并发 send 只有一个 challenge 生效。
- purpose 之间不互相污染。
- 客户端提交任意 purpose 或遗留 `LOGIN` 均不能创建、激活、验证 challenge 或签发 token；
  migration 遇到无法解释的遗留记录时失败并输出报告，不静默改成其他 purpose。

**回滚**

- 可以调大限额；不能恢复无消耗预检查。

**完成定义**

- 验证码不能通过预检查、并发、resend 或未实现 purpose 绕过限制。

### H4.3 合并邮箱注册路径与契约

**涉及文件**

- `AuthController.java`
- `EmailAuthController.java`
- `LoginMethodController.java`
- `UserService.java`
- `LoginMethodService.java`
- `RegisterRequest.java`
- frontend login/auth service

**前置条件**

- H4.2 完成。

**实施**

1. 保留一条 local 注册 state machine，删除重复 verify-and-register 路径。
   按当前 React/API 契约，所有 local 注册都必须提供并先验证 email：
   普通 username + 独立 email 与“email 即 username”只是同一状态机的两种输入，
   都不得在 challenge 成功前创建 user 或 LOCAL login method。
2. 最终 verify 请求携带 send 阶段返回的 challenge id 和仍在前端内存中的注册字段；
   不把 password hash 塞入验证码 metadata。
3. 删除注册 challenge 中的 password/displayName metadata；迁移窗口内若仍读取旧 metadata，
   JSON 序列化/反序列化失败或必需字段缺失必须终止流程，不能返回 null/空 map 继续建号。
4. `RegisterRequest` 增加 username/email/password/displayName 的 Bean Validation；
   本轮 email 为必填且必须通过该注册 challenge 验证。未来若需要无邮箱 local 账户，
   必须作为单独契约评审，不在本加固计划中顺带开放。
5. 密码策略与 password reset、add-local-login 使用同一配置和 validator。
6. 校验 user/email/local username 唯一性并保证不部分创建。普通非 email 用户名是公开登录标识，
   不作为私密账户属性：注册时允许用稳定 `USERNAME_UNAVAILABLE` 表达冲突，
   但不提供批量 availability endpoint，且该响应与提交次数受 H3.6 同一限流保护；
   登录、找回和其他认证流程仍不得据此泄露更多账户状态。email-shaped username 不是
   “公开 username”例外，必须按 email canonicalizer 处理；其 local-username 冲突或
   canonical email 已被占用时不得返回 `USERNAME_UNAVAILABLE`、可区分的 409/错误文案，
   而应使用与新 email 相同的外部响应和有界 decoy 状态，后续错误 code 也不可区分。
   email-shaped username 只有在规范化后等于本次 challenge 验证的 canonical email 时才允许；
   不能验证邮箱 A 后把邮箱 B 注册为 local username。
7. 将成功验证码的校验、CAS 单次消费与 user/login method 创建放进一个 service 事务；
   controller 不再二次调用 `markAsUsed()`，事务内只有一个条件 consume 操作，后续写入失败时
   全部回滚；不得在密码哈希计算期间持有数据库锁。
8. 统一成功响应为 canonical auth response。
9. 已存在 OAuth2 用户邮箱的处理必须显式定义：
   匿名注册/verify 不得给任何已有用户绑定 LOCAL 登录方式、覆盖密码或签发该账户 token，
   即使邮箱本身已验证也不例外。OAuth-only 用户增加 LOCAL 必须先以现有方式登录，
   再走 H6.2 最近认证保护的 add-local 流程；已有 LOCAL 用户走 password reset。
   未实现单独、审计完备的账户恢复流程前，该能力保持关闭。
10. send、verify、register、forgot/reset 和 OAuth email 比对全部先调用同一 email canonicalizer；
    DTO validation 在规范化前后均限制长度，避免空白绕过和超长规范化结果。
11. 普通用户名注册不得把未经验证的 email 直接写入 canonical `users.email`：
    注册开始阶段只把规范化 email 与必要关联保存在隔离的 pending challenge 中；
    验证成功后再与 user 和首个 LOCAL login method 在同一事务中占用 canonical email。
    迁移时审计现有 `emailVerified=false` 记录，不能让其继续阻断可信 provider/email 身份，
    也不能在无所有权证据时自动标记为已验证。
12. 普通注册和 add-local 共用 H1.4 username canonicalizer，并拒绝系统保留前缀；
    `users.username`、`local_username` 和登录查询对普通 local 账户使用同一 canonical key。
13. `LoginMethodController`/`LoginMethodService` 保留唯一的认证后 add-local 写入口，
    controller 不直接编码密码或拼装凭据；该入口与注册共享密码策略和 username/email
    canonicalizer，密码编码暂时只允许发生在该 service 写边界内。H4.5 随后在不新增 endpoint
    或第二条写路径的前提下，把该边界与注册/reset 一并替换为统一 credential service。
    H4.3 先消除重复入口、匿名绑定和不一致校验，H6.2 再补齐最近认证、并发登录方式约束、
    security version 与 replacement family 事务。

**测试**

- 普通 username + 独立 email、email 即 username、重复邮箱/用户名、空值、弱密码；
  两种 local 注册输入都必须先完成同一 challenge，验证前数据库没有 user/LOCAL method。
- 重复 public username 只返回稳定 `USERNAME_UNAVAILABLE`，不暴露 user id、email、
  provider、enabled、登录方式或其他账户状态，且批量探测受限流约束。
- email-shaped username 的已占用/未占用、local-username 冲突和 canonical email 冲突
  不能通过 `USERNAME_UNAVAILABLE`、状态码、challenge shape 或时序区分。
- email-shaped username 与已验证 email 不一致时失败且不占用任一身份；大小写/空白规范化后
  相等时可按同一 canonical value 注册，不能用验证邮箱 A 的 challenge 注册邮箱 B 登录名。
- username 大小写/空白/NFKC 变体、非法字符和系统保留前缀的注册/add-local 矩阵；
  规范化后冲突不能创建第二个用户或登录方式。
- 并发两次 verify 只创建一个用户。
- 错/旧/跨 purpose challenge id、challenge email 与注册 email 不一致时不建号，
  也不消费其他注册 challenge。
- metadata 序列化/反序列化失败、旧 metadata 缺 password 时失败关闭。
- 验证码只消费一次；user 或 login-method 写入失败后 code 与业务写入共同回滚。
- 已有 OAuth-only 用户的匿名 email verify 被拒绝绑定，认证后 add-local 才能创建
  非空 password hash 且可登录的 LOCAL 方式。
- 已有 LOCAL 用户不会被注册流程改密；已有 OAuth-only 用户也不会因匿名 email verify
  获得新的 LOCAL 方式或该账户 token。
- 中途写入失败不留下无登录方式用户、空密码 LOCAL 方式或已消费但未建号的 code。
- email 大小写/首尾空白变体不能重复注册，也不会使验证码或密码重置查找失配。
- 普通用户名注册提交他人邮箱不能占用 canonical email、触发找回或阻断该邮箱后续可信注册；
  pending email 验证与最终占用并发时只有一个所有者成功。
- 新/已占用 canonical email 的注册发送响应、错误 code 响应和时序不可区分；
  已占用 email 的 decoy 流程不能修改账户或签发登录 token。

**回滚**

- 旧 endpoint 可短期返回迁移错误说明；不能同时保留两套写路径。

**完成定义**

- 邮箱注册只有一个可测试状态机和一个响应契约。

### H4.4 修复 forgot-password

**涉及文件**

- `ForgotPasswordService.java`
- `ForgotPasswordController.java`
- user/login-method repository
- H2 token security-version、revocation 与 `TokenSessionService`
- H1.5 security audit service
- frontend modal/service

**前置条件**

- H4.1-H4.3 完成。

**实施**

1. 删除硬编码 `123456` 和额外的第二次发送。
2. 已注册/未注册邮箱在发送、验证码失败和重置失败阶段都返回相同外部响应类别、
   时延范围和状态码；外部不得区分 NOT_FOUND、EXPIRED、错误次数或本地登录方式缺失。
3. 先按规范化后的 canonical verified `users.email` 查找用户，再在该用户下查找唯一 LOCAL
   登录方式；不得继续用 `findByLocalUsername(email)` 假设 local username 必然等于邮箱。
   只有两者同时存在且 user enabled 时才能收到邮件并最终改密；因此“非邮箱 local username + 已验证邮箱”
   的账户可找回，而 OAuth/Web3-only 用户不能借邮箱验证码新增 LOCAL 凭据。
   对未知、disabled、无 verified canonical email 或无 LOCAL 方式的请求使用有界、限流、不可用于改密的
   decoy/opaque 状态或等价方案，使后续错误 code 路径与真实 challenge 不可区分。
   decoy 不得触发外部邮件，也不能无限增长。
   send 对真实与 decoy 请求都返回同 shape 的 opaque challenge id；reset 必须携带该 id，
   不能退回按 email 查询最新 challenge。
4. 成功 code 通过条件更新/CAS 取得单次消费权，并与密码 hash 更新、token security version
   递增和该用户现有 refresh session/family 撤销在一个明确事务中完成；任一步失败全部回滚，
   不能出现密码已改但旧 token 仍有效，或 code 已消费但密码未改。
5. 前端不再显示“该邮箱未注册”分支。
6. 前后端密码长度统一为 8-128 或统一配置值。
7. 内部仍记录不含原始 email/code 的细分安全事件，运维可诊断但客户端只得到稳定错误码。

**测试**

- 注册/未注册邮箱在 send 后立即比较、send 后提交同一错误 code、过期后提交和达到限额后
  的响应/时延均不可区分，不能通过两阶段调用恢复账户存在性。
- 正确/错误/过期/重放 code。
- 真实/decoy challenge id、错/猜测 id、跨 email/purpose 交换和 resend 旧 id 的响应不可区分，
  且错误请求不消费其他 challenge。
- local username 等于邮箱、local username 不等于邮箱但 canonical email 已验证、
  canonical email 未验证/null、disabled、OAuth/Web3-only 账户的查找与 decoy 矩阵；
  disabled 用户不收可用 code、不改密、不递增安全版本且响应不可区分。
- reset 后旧密码失败、新密码成功、旧 refresh token 失败。
- 在 code 消费、hash 写入、security version 更新和 family 撤销各点注入失败，
  数据库均恢复到可再次安全重试的完整旧状态，不留下部分成功。

**回滚**

- 可以关闭 forgot-password 流程；不能恢复账户枚举或硬编码 code。

**完成定义**

- forgot-password 的发送、枚举防护、重置、重放和 token 撤销矩阵通过；
  Phase 4 的最终凭据门禁由 H4.5 完成。

### H4.5 密码哈希版本、成本与升级

**涉及文件**

- `WebSecurityConfig.java`
- password properties/validator
- `CustomUserDetailsService.java`
- 所有 password 创建/修改 service
- migration/credential tests

**前置条件**

- H4.3 已删除重复注册写路径，并将认证后 add-local 收敛为唯一 controller/service 入口；
  H4.5 在这些已明确的入口下完成统一 credential service 和 hash 格式切换。
- H4.4 reset 与 H2.3 token family 撤销已完成。

**实施**

1. 使用带 `{id}` 的 `DelegatingPasswordEncoder` 或等价版本化格式；
   新密码采用评审后的 Argon2id 或按部署基准确定成本的 BCrypt，不再依赖无说明默认值。
2. 为内存/CPU 参数设置经过基准测试的上下限，prod 配置越界或缺失时 fail fast。
3. 保留只读 legacy verifier；成功登录后在同一安全事务中检测并升级旧 hash，
   不要求一次性明文迁移。
4. register/email registration、add-local、reset 和未来 password change 只调用一个 credential service；
   null、空值、未知 `{id}`、损坏或过长输入统一失败关闭。
5. 密码字符与 UTF-8 byte 长度、Unicode 规范化和所选算法限制必须有明确一致规则，
   前后端只共享用户输入策略，真正 hash 校验以服务端为准。
6. 密码重置或 hash 升级失败不得部分修改凭据；reset 成功沿用 H2.3 撤销 token family。

**测试**

- 当前 legacy BCrypt fixture、新格式 fixture、未知 id、损坏 hash、null hash。
- 成功登录自动升级一次，失败登录不改 hash；并发登录升级结果稳定。
- 不同成本参数、长 Unicode 密码、算法边界和配置越界。
- 所有 password write path 生成同一当前格式。

**回滚**

- 可保留 legacy verifier 更长时间；不能恢复无版本默认 encoder 或生成新的弱 hash。

**完成定义**

- G3 的 email/password 凭据矩阵通过，所有新 hash 可识别、可升级并有成本证据。

## 11. Phase 5：Web3/SIWE

### H5.1 服务端拥有完整 SIWE challenge

**涉及文件**

- `Web3AuthService.java`
- `Web3NonceService.java`
- `Web3Nonce` entity/repository
- migration
- `Web3LoginRequest`、`Web3NonceResponse`
- frontend Web3 service/component

**前置条件**

- H1 migration 与 H3 endpoint security 完成。
- 选择维护中的 EIP-4361 parser/validator；记录版本和兼容性。

**实施**

1. 服务端为每次请求生成不可猜测 challenge id，并生成要签名的确切 EIP-4361 message bytes；
   持久化 challenge id、nonce、wallet、domain、URI、chainId、issuedAt、expiresAt、
   message/challenge hash 和必要的 request correlation。
   correlation 不使用应用 HttpSession；若需要浏览器绑定，使用独立、短期、`__Host-`、
   HttpOnly 的随机关联 cookie 或等价无身份机制，并允许同一浏览器有界并行 challenge。
2. verify 先对客户端提交的原始签名字节做边界校验，并要求其 hash 与持久化 message hash
   常量时间匹配，再用选定 parser 解析并逐字段比较；未由服务端生成并持久化的 statement、
   request-id、resources、not-before 或其他可选/重复字段一律拒绝，不能只比较 nonce 等子集。
3. wallet address、domain、URI、version、chain、nonce、issued/expiry 任一不符即失败。
   domain/URI/version 由服务端配置决定；chainId 必须来自服务端允许列表或固定值，
   客户端请求 unsupported chain 时在 challenge 创建前拒绝，不能“客户端选什么就签什么”。
4. 不接受客户端单独提交的 nonce 作为真实性来源。
5. 规范化 address，但保留签名校验所需的原始语义。
6. nonce 响应返回 challenge id；verify 以 challenge id 精确读取记录，不按 wallet
   “取最新一条”，并限制单 wallet、单客户端和全局未完成 challenge 数量。
7. challenge issuance 使用 POST body，不用有副作用的 GET/path 参数；响应设置 `no-store`，
   避免浏览器预取、共享缓存或访问日志把挑战上下文当普通可缓存资源。
8. issuance 在创建可用于认证的 challenge 前按规范化 wallet 查询现有 WEB3 login method；
   若解析到 disabled 用户，不更新用户或创建可登录 challenge，而是返回与 enabled/新 wallet
   相同 shape、长度和时延范围的有界 decoy challenge。decoy 可按正常协议被签名和单次消费，
   但 verify 只能返回稳定认证失败，不能创建影子 user、更新 last-used 或签发 token；
   decoy 数量、TTL 和清理由 H3.6/H5.2 同样限制。

**测试**

- 正常签名。
- message bytes、address、domain、URI、chain、nonce、issuedAt、expiry 各自篡改；
  添加/重复/重排 statement、request-id、resources、not-before 等字段也失败。
- supported/unsupported chain issuance 与 verify 矩阵，客户端不能通过自选 chain 扩大策略。
- 不同环境 domain/URI 配置。
- challenge id 猜测、错 wallet、错 request/browser correlation 和跨客户端交换。
- enabled、disabled 和未绑定 wallet 的 issuance 响应不可区分；disabled wallet 的正确签名
  只能消费 decoy 并得到稳定认证失败，不更新用户、不创建影子账户或 token。
- Web3 issuance/verify 不创建或读取应用 `JSESSIONID`，现有 OAuth session 也不能替代
  challenge id/correlation。
- GET issuance 返回 404/405；POST 响应不可缓存，前端只使用服务端返回的 message/challenge id。

**回滚**

- 可暂时关闭 Web3 登录；不能恢复任意 message 验签。

**完成定义**

- 签名绑定完整服务端 challenge。

### H5.2 原子消费与重放防护

**涉及文件**

- Web3 nonce repository/service
- migration/constraint

**前置条件**

- H5.1 完成。

**实施**

1. 优先使用单条条件 `UPDATE`/`DELETE` 或 compare-and-set 原子取得并消费 challenge；
   只有在隔离级别分析和并发测试证明条件 DML 无法维持不变量时，才允许使用范围严格
   受限的行锁。
2. `verify` 在签名解析前通过原子状态转换取得消费权；受影响行数必须恰好为一，
   并且不得在密码学计算期间持有数据库锁或长事务。
3. 过期记录清理不能在 readOnly transaction 中执行写操作。
4. 新 challenge 不覆盖其他客户端仍有效的 challenge；通过每 wallet/client 有界数量、
   最短创建间隔、过期清理和 H3.6 统一限流控制资源，不允许匿名请求使受害者已有挑战失效。
5. verify 通过原子状态转换先取得一次性消费权；成功或失败都会消费 challenge，
   客户端需重新申请。不可猜测 challenge id、request/browser correlation 和统一限流
   防止第三方提交失败请求来作废他人挑战。
   该 claim/consume 使用独立、可持久化的短事务，在签名解析和身份写入前完成；
   后续验证、进程崩溃或业务事务失败都不得把 challenge 恢复为可用。

**测试**

- 同一签名串行重放失败。
- 两个并发 verify 只有一个成功。
- 过期、并行签发、清理与 verify 并发。
- 错误签名消费对应 challenge，第二次提交即使签名改正确也失败；错误 challenge id
  不影响任何其他记录。
- 攻击者持续为同一 wallet 申请 challenge 不会使受害者已签发 challenge 失效，
  超出有界容量时使用稳定限流结果且不删除其他客户端记录。

**回滚**

- 可收紧为每次失败即作废；不能允许多次成功消费。

**完成定义**

- nonce 一次性语义在数据库并发下成立。

### H5.3 修复 Web3 endpoint 与响应语义

**涉及文件**

- `Web3AuthController.java`
- `Web3AuthService.java`
- `Web3AuthResponse.java`
- security config
- H2 `TokenSessionService`、统一 cookie writer 与 token security-version service
- recent-auth/sensitive-operation guard 与 H1.5 security audit service
- frontend Web3 调用

**前置条件**

- H2 strict access token validator、H5.2 完成。

**实施**

1. bind 使用认证 principal，不手工解析任意 Bearer token。
2. `bindWalletToUser=false` 返回 409/400，不再返回成功。
3. 在 find/create 前记录是否存在，正确计算 `isNewUser`。
4. chainId 必须被验证和使用，或从请求 DTO 删除，不能静默忽略。
5. 所有 token/cookie 使用统一 transport。
6. 删除当前无前端调用方的公开 `/api/auth/web3/status/{walletAddress}`；
   不保留钱包绑定枚举 endpoint。
7. 建立单一 `verify-and-login` / `verify-and-bind` service operation：
   先按 H5.2 在独立短事务中永久 claim/consume challenge，再验证签名；
   身份查找、user + 首个 login method 原子创建或绑定、security version/family 更新
   在后续明确业务事务中完成。controller 不再串联多个可部分成功的 service 调用，
   token 只在业务事务成功提交后签发/返回。
   已有 wallet 解析到用户后先检查 enabled；disabled 用户不更新 last-login/last-used、
   不签发 token，也不通过同一 wallet 创建第二个 user。
8. Web3 bind 与其他新增登录方式操作一样要求 H2.2 `auth_time` 仍在最近认证窗口内。
9. Web3 无可信邮箱时 `users.email` 保持 null；`users.username` 使用 H1.4 的系统保留 opaque 值。
   wallet 只作为 WEB3 provider subject，截断显示标识如确有 UI 需要只能放 metadata/display，
   不生成 `@web3.local` 或从 wallet 派生可被普通注册抢占的 canonical username。

**测试**

- 正常登录、新/旧用户标志。
- wallet 已绑定、用户已有 wallet、用户不存在、错误 token type。
- 已绑定 wallet 对应 disabled 用户时返回稳定认证失败，不更新使用时间、不签发 token、
  不创建影子 user。
- bind 持久化失败不返回 200。
- user 保存后 login method 保存失败时整体回滚，不留下零登录方式用户。
- challenge claim 后签名失败、进程崩溃或 user/login-method 事务失败都保持已消费；
  重试同一 challenge 失败，申请新 challenge 后可恢复流程。
- 过期 `auth_time` 即使 access token 仍有效也不能 bind；refresh 后仍然过期。
- 新 Web3 用户的 canonical email 为 null，token/API 不把合成地址声明为可信 email。
- Web3 canonical username 不包含 wallet，公开注册不能抢占；status endpoint 返回 404。

**回滚**

- 可关闭 bind endpoint；不能忽略失败结果。

**完成定义**

- API 成功状态与数据库结果一致。

### H5.4 修复 EIP-191 字节长度

**涉及文件**

- `Web3SignatureUtils.java`
- Web3 test vectors

**前置条件**

- H5.1 选定 SIWE library/签名实现。

**实施**

1. EIP-191 prefix 使用 UTF-8 bytes 长度。
2. 优先调用被验证库的 personal-sign 实现，减少手写恢复逻辑。
3. 使用标准向量和 ethers 生成向量交叉验证。

**测试**

- ASCII、中文、emoji 和组合 Unicode 消息。
- v 值、签名长度、错误 r/s、错误 address。

**回滚**

- 可禁用非 ASCII challenge 模板；不能保留错误字符长度计算。

**完成定义**

- Web3 phase 全部篡改、过期和重放测试通过。

## 12. Phase 6：后端、React 与 Python 契约

### H6.1 建立 canonical API DTO

**涉及文件**

- auth/user/login-method/email/Web3 response DTO
- controller
- OpenAPI
- `frontend/package.json`、Vite-compatible test config
- `frontend/src/types/index.ts`
- `frontend/src/services/authService.ts`
- `frontend/src/pages/TestPage.tsx`
- frontend contract fixtures/tests

**前置条件**

- H2-H5 响应语义稳定。

**实施**

1. 消除 controller 中散落的 `Map<String,Object>` 核心响应。
2. 定义 canonical `UserResponse`、`AuthResponse`、`LoginMethodResponse`、
   `TokenRefreshResponse` 和统一错误 DTO。
3. ID 全部为 UUID string。
   `email` 为可选字段，只有 canonical verified email 才能出现在 auth/user DTO 和 token claim；
   无邮箱账户不得由前端或 Python 推导合成邮箱。
4. provider 边界固定为：持久化/domain enum 使用 `TWITTER`，
   Spring registration id 与 public API 使用 `x`；映射只发生在一个边界组件。
5. email verify 的 user 信息只采用一个嵌套位置。
6. `TestPage.tsx` 的 `LoginMethod.id`、删除中 ID 和设置 primary 中 ID 全部改为 UUID string，
   不保留页面私有的 number 影子类型。
7. 生成或手工维护的 TypeScript 类型必须由契约测试校验。当前前端没有 test runner，
   因此本工作项同步建立与现有 Vite/TypeScript 版本兼容的最小测试配置、fixture 和可运行命令；
   H7.2 负责补齐覆盖并把该命令固化为 CI 门禁，不能把 H6 所需的首次可执行验证推迟到 Phase 7。

**测试**

- controller JSON snapshot/schema test。
- 前端 fixtures 能被 TypeScript 类型接受。
- X/TWITTER 映射测试。

**回滚**

- 可以增加临时兼容字段，但需有删除日期；不能让同一字段出现两种类型。

**完成定义**

- 后端和前端共享一个可执行契约。

### H6.2 修复 provider、primary 与身份事务语义

**涉及文件**

- `ApiAuthController.java`
- `LoginMethodController.java`
- OAuth/Web3 binding controller 或 success handler
- `UserService.java`
- `LoginMethodService.java`
- H2 `TokenSessionService`、统一 cookie writer 与 token security-version service
- 统一 recent-auth/sensitive-operation guard 与 H4.5 credential service
- login method repository/migration
- `frontend/src/pages/TestPage.tsx`
- frontend user rendering
- Python response

**前置条件**

- H6.1 完成。

**实施**

1. `/api/user` 从数据库 primary login method 获取 provider，不固定 `local`。
2. `sub` 始终表示用户 UUID，`username` 始终表示 canonical account username：
   local 首次注册使用规范化 local username，OAuth/Web3 使用系统生成 opaque 值；
   provider username、email、wallet 和后续 add-local login name 不得改写该 claim 的含义。
3. primary 登录方式必须只有一个；没有或多个时检测并修复数据。
4. login-method ID 前端改为 string。
5. Python 展示 `username` claim，ID 使用 `sub/userId`。
6. `UserService.login()` 不得在 read-only 事务中调用写操作；
   成功认证与 `last_used_at` 更新使用明确的写事务，失败认证不改变时间。
   local login 只允许一个密码认证决策：由 `AuthenticationManager`/统一 credential service
   完成一次 hash 校验，再以已认证 principal 执行审计和签发；不得在 controller/service
   中对同一请求重复调用 `passwordEncoder.matches`。
7. set-primary、删除 primary 和首次创建登录方式复用 H1.4 的条件更新/CAS 事务策略，
   以及仅在经证明必要时使用的单用户锁后备；
   repository 不再以 `Optional` 掩盖多个 primary 的脏数据。
8. add-local、remove、set-primary、OAuth2 bind 和 Web3 bind 统一使用敏感操作守卫，
   校验最近 `auth_time`；不能由 refresh 延长该窗口。
   add-local 的 email-shaped username 还必须等于当前 user 的 canonical verified email；
   user.email 为 null、未验证或不同值时只允许非 email public username，不能在该操作中
   顺带认领、写入或替换 canonical email。
9. 删除登录方式、修改密码或发生账户恢复时递增 H2.2 token security version，
   并撤销现有 refresh family；新增登录方式也视为凭据集合变化并执行同样处理。
   对 add-local、OAuth/Web3 bind 和认证后 remove 等当前用户操作，安全状态与审计事务提交后
   才能为当前浏览器签发一个使用新 security version、新 sid 且继承原 `auth_time` 的替换 family；
   旧 family 在签发前已经失效。签名、cookie 写入或响应失败不得恢复旧 family，
   客户端必须重新认证。密码重置、账户恢复、管理员禁用/权限撤销不自动给目标用户签发替换 token。
   仅切换 primary 不改变有效凭据，不撤销或替换会话，但仍要求最近认证并记录安全事件。

**测试**

- local、Google、GitHub、X、Web3 用户的 `/api/user`。
- primary 切换与解绑后的 provider。
- 成功 local/OAuth 登录持久化 `last_used_at`，密码/签名失败时保持不变。
- disabled 用户通过 local/OAuth/Web3/refresh 均不能取得 token，且 last-used/last-login 不变；
  各入口外部错误不泄露禁用状态。
- 单次 local login 只执行一次密码 hash 校验；禁用、错误密码和 hash 升级路径仍使用同一
  认证结果，不会因第二套校验逻辑产生不同响应或重复 CPU 成本。
- 并发 set-primary/remove/bind 后数据库恰好一个 primary，事务失败完整回滚。
- 并发删除不同登录方式不能删空；过期最近认证不能 add/remove/set-primary/bind。
- add-local 使用 email-shaped username 时，只有与当前 verified canonical email 规范化后相等
  才能成功；null/未验证/不同 email 均失败且不改变 user 或 login method。
- 删除登录方式后旧 refresh token 不能继续恢复会话；账户恢复后的旧会话处理与策略一致。
- add/remove/bind 后旧 access/refresh token 立即失效；成功响应只携带新 version/new sid 且
  `auth_time` 不变的 replacement pair。模拟 replacement 签名、cookie 或响应失败时旧 family
  仍保持撤销，重新认证后才能恢复；密码重置/管理员状态变更不会意外签发目标用户 token。
- 单纯 set-primary 后当前会话按既定策略继续有效，不产生不必要的新 family。
- `TestPage.tsx` 以 UUID fixture 完成获取、删除和设置 primary 的类型/契约测试。
- Python JSON 契约。

**回滚**

- provider 可暂时返回 `unknown`，不能错误标为 local。

**完成定义**

- 身份字段不因认证方式不同而改变含义。

### H6.3 清理前端 token/state 流程

**涉及文件**

- `useAuth.ts`
- `authService.ts`
- `OAuth2CallbackPage.tsx`
- `LoginPage.tsx`
- `ResourceTestPage.tsx`
- frontend auth/callback/contract tests

**前置条件**

- H2.5/H3.2 完成。

**实施**

1. 删除从 `document.cookie` 读取 HttpOnly token 的逻辑。
2. 删除 refresh token localStorage 读写。
3. logout 只删除本应用 namespaced keys，不调用 `localStorage.clear()`。
4. axios 401 refresh 只允许一个 in-flight 请求，避免 refresh storm。
5. 修复 wrapped Error 与页面读取 `err.response` 的矛盾。
6. email verify 使用后端 nested user，不自行拼错误 user。
7. dev-only resource test 不记录完整 token。
8. 删除生产前端中记录 auth response、Axios error/request config、cookie 内容、
   wallet nonce/signature、完整 token 和用户 PII 的 `console.*`；
   诊断日志经集中 redaction，并在 production build 中关闭。
9. 将真实账户管理 UI 与 token/resource/OAuth 诊断控件拆开；生产路由表和 bundle 不包含
   `/resource-test`、token 过期模拟、provider-token validation、手工 introspection/JWKS
   控件或旧 Thymeleaf `test/debug` 页面。dev-only 代码用构建入口/条件导入隔离，
   不能只隐藏导航、按钮或依赖运行时 CSS。
10. `auth_user`/当前主体不再作为长期 localStorage 身份来源；页面启动和身份切换后以
    `/api/user` 的受认证响应重建内存状态。任何可选持久缓存只能含非敏感展示数据、
    有短 TTL，并且不能决定路由授权、角色、provider、recent-auth 或是否允许敏感操作。

**测试**

- 登录、刷新、多个并发 401、登出、OAuth callback。
- unrelated localStorage key 在登出后保留。
- 伪造/陈旧 `auth_user`、provider 或 role localStorage 值不会显示为已认证或解锁敏感操作；
  后端 401/403 后内存主体和可选缓存被清理。
- 生产 build 中 dev token mode 不可达。
- 登录失败 error 对象、refresh/OAuth/Web3 成功响应和浏览器控制台均不含凭据。
- 生产直接访问诊断 URL 返回 404/受控跳转，路由快照和 bundle string scan 不含已移除
  诊断 endpoint/说明；账户管理、登录和 OAuth callback 仍正常。

**回滚**

- 可以关闭自动 refresh；不能恢复 refresh token localStorage。

**完成定义**

- 前端认证状态与 HttpOnly 设计一致。

### H6.4 加固 Python 资源服务器

**涉及文件**

- `python-resource-server/app.py`
- `requirements.txt`
- Python tests/README
- `debug_token.py`、`test_token.py`

**前置条件**

- H2 strict claims/JWKS/introspection 契约完成。

**实施**

1. AUTH_SERVER_URL、JWKS_URL、issuer、audience、port 从环境读取并校验。
2. 固定允许算法为 RS256，不读取 header 决定 algorithms。
3. kid 必须精确命中；不回退第一把 key。
4. 恢复 TLS 验证，只有显式 local HTTP 模式例外。
5. 复用 H2.2 claim schema，校验 `type=access`、issuer、audience、exp、iat、sub/userId、
   jti、sid、auth_time、security version、username/authorities 类型和配置最大 TTL；
   强制 `sub == userId`、`auth_time <= iat < exp`，并拒绝 legacy 缺失字段 token。
   offline 模式只能验证 token 中 security version/family 声明的格式，不能声称已查询当前值；
   需要当前 enabled/version/family/revocation 状态的 endpoint 必须使用受认证 introspection。
6. `debug=False`、`use_reloader=False`；生产由 WSGI server 启动。
7. 移除仓库内完整 token 样例，测试使用运行时 fixture。
8. 需要即时撤销的路径使用受认证 introspection；离线模式明确 TTL 限制。
9. 该服务只接受 Authorization bearer，不启用 credentialed CORS；allowed origins、methods
   和 headers 从校验后的配置读取，不包含历史隧道域名或 wildcard。
10. token/JWKS 失败只返回稳定错误码，未认证响应和 `/health` 不暴露 issuer URL、
    上游地址、解析异常或 token claims。
11. JWKS 使用有界、线程安全的按 `kid` 缓存并服从配置/cache header 的最大 TTL；
    未知 `kid` 最多触发一次带冷却和 single-flight 的受控刷新，随机 `kid` 不能形成上游请求风暴。
    轮换时能及时取得新 key；上游暂时失败时只在明确的短 stale window 内使用已验证的
    last-known-good 公钥，窗口结束后失败关闭。stale key 不得覆盖本地 revoked-kid denylist，
    cache + stale 上限不得超过 H2.1 声明的 key-cache/reconfiguration SLA；
    紧急事件要求即时拒绝时切换 introspection 或先部署 denylist。
    JWKS 响应大小、key 数量和 key 参数受限。
12. 为每个 Python endpoint 定义显式 audience + role/scope 授权矩阵；token 验证成功不自动
    等于有权访问所有资源，缺失/错误 authority 返回稳定 403，未知路由默认拒绝。
13. 业务响应只暴露所需 principal 字段，不回显完整/挑选的 token claims、issuer、
    audience、iat/exp、JWKS/auth server URL 或内部调试结构。
14. offline JWKS 与 authenticated introspection 模式在启动时显式选择并校验配置，
    请求处理中不得因 introspection 超时、5xx 或 client auth 失败静默降级为离线接受。
    即时撤销模式对受保护请求失败关闭；active introspection 正向结果不缓存或只按明确
    撤销 SLA 短暂缓存，revoked/错误响应不得被错误复用。

**测试**

- 本地 JWKS fixture：正确、错误 alg/kid/issuer/audience/type/expiry、缺失/畸形
  sub/userId/jti/sid/auth_time/security version、`sub != userId`、超最大 TTL 和 legacy token。
- TLS 错误不能静默跳过。
- introspection active/revoked。
- `/api/protected` username/ID 契约。
- endpoint role/scope 允许与拒绝矩阵，已认证但权限不足返回 403。
- CORS 不发送 `Access-Control-Allow-Credentials: true`，恶意 origin 被拒绝。
- malformed token、JWKS 故障和 health 响应不泄露内部地址或异常细节。
- 换钥后未知合法 `kid` 触发一次刷新并成功；大量随机 `kid`、并发 cache miss 和 JWKS
  超大/过多 key 不会放大上游请求或耗尽内存，stale window 到期后故障会失败关闭。
- 已缓存公钥对应的 `kid` 被紧急吊销后，denylist/introspection 立即覆盖 cache 与 stale；
  离线模式也不得超过声明 SLA 后继续接受该 key。
- 成功业务响应不包含 token 时间/issuer/audience、上游 URL 或完整 claims。
- introspection 超时、5xx、client credential 失效时不回退 JWKS；撤销 token 在声明的
  SLA 内失效，缓存不会让 active 结果越过该边界。

**回滚**

- 可关闭 Python 示例；不能恢复 `verify=False`、alg 信任或 kid fallback。

**完成定义**

- G4 的跨语言 token 验证门禁通过。

### H6.5 校准脚本和 live docs

**涉及文件**

- `scripts/*.sh`
- `scripts/*.py`
- root `start.sh`、`start-with-frontend.sh`、`build-frontend.sh`
- root/component README
- `docs/ARCHITECTURE.md`
- `docs/CONFIGURATION.md`
- `docs/DEVELOPMENT.md`
- `docs/VERIFICATION.md`

**前置条件**

- API 与配置稳定。

**实施**

1. 脚本使用 canonical endpoint、环境变量和隔离数据库检查。
2. 会触发发送邮件、写数据库、生成报告的脚本默认 dry-run 或要求显式确认。
3. 文档只记录实际验证的命令和结果。
4. 历史文档保持原路径，只更新状态提示或链接。
5. PostgreSQL export/restore/baseline/诊断脚本只操作 canonical inventory，
   对数据库目标和对象名使用 allowlist/安全 quoting，不重新引入 SQLite 查看脚本，
   也不继续读取已移除的 `users.auth_provider`。
6. start 脚本使用结构化 JSON/YAML parser 或纯环境变量，不用 grep 提取 secret，
   不打印 client id/secret 片段，并显式传入安全 profile。

**测试**

- shell/Python 语法。
- 脚本在 mock server/disposable DB 下执行。
- 文档相对链接。
- export 子命令失败、危险数据库目标、缺 profile 和恶意表名都 fail closed。

**回滚**

- 脚本可标记不可用；不能回退到硬编码域名、端口和密码。

**完成定义**

- 活跃脚本和 live docs 与代码契约一致。

## 13. Phase 7：自动化质量门禁

测试基础设施在 Phase 1 建立，每个 phase 必须同步增加测试。本阶段负责把这些测试
固化为不可绕过的仓库门禁，而不是第一次补测试。

### H7.1 Java 测试门禁

**涉及文件**

- `pom.xml`
- `src/test/java/**`
- `src/test/resources/**`

**前置条件**

- H1 disposable harness 已稳定。
- H2-H6 各 phase 的定向测试已随实现落地。

**实施**

1. 覆盖 service unit、repository integration、MockMvc security、migration、
   concurrency 和完整 auth flow。
2. 配置 Maven 在没有测试时失败。
3. 按测试类型分组，外部集成默认使用 stub，真实 provider 测试显式 opt-in。
4. 增加覆盖率报告；初期门槛按关键包风险设定，不以追求数字替代断言质量。

**测试**

- `mvn clean test` 显示非零 test count。
- 关键失败测试无法通过跳过 profile 或共享数据库回退绕过。

**回滚**

- 可隔离 flaky 外部测试；核心安全、迁移和并发测试不能 quarantine。

**完成定义**

- Java 构建成功代表实际执行了关键测试。

### H7.2 前端 lint 与测试

**涉及文件**

- ESLint config
- `frontend/package.json`
- `frontend/src/**/*.test.ts(x)`

**前置条件**

- H6 canonical API DTO 和 token transport 已稳定。
- H6.1/H6.3 已提供可运行的前端 contract/auth test runner 与基础用例。

**实施**

1. 添加与当前 ESLint 8 / TypeScript / React 插件兼容的配置。
2. 修复现有 lint，不把大量规则全局关闭。
3. 补齐 H6 已建立的 Vite-compatible 测试配置与 auth service、hook、callback、契约 fixtures
   覆盖，并统一为稳定的 `npm test`（或评审后的等价）命令；本阶段负责门禁化，
   不把首次可执行测试能力留到所有跨端修复之后。
4. build、lint、test 都使用 CI lockfile 安装。

**测试**

- `npm run build`
- `npm run lint`
- `npm test` 或确定后的等价命令

**回滚**

- 规则可分阶段提升；不能保留“有 lint script 但永远失败”的状态。

**完成定义**

- 前端三项门禁都通过。

### H7.3 Python 测试与静态检查

**涉及文件**

- Python app/tests/requirements
- lint/type/security scan 配置

**前置条件**

- H6.4 的 JWT/JWKS/introspection 契约已稳定。

**实施**

1. 使用 pytest 覆盖 JWT/JWKS/introspection 和 Flask endpoint。
2. 增加格式、lint 和依赖漏洞扫描。
3. 测试不访问历史外部域名。

**测试**

- pytest 全量测试。
- Python lint/type/security scan。
- 测试期间网络访问被阻断或显式 mock。

**回滚**

- 可暂时关闭真实网络集成测试；离线 JWT 安全测试不能跳过。

**完成定义**

- Python 测试完全离线可重复，真实网络测试显式 opt-in。

### H7.4 CI 与供应链门禁

**涉及文件**

- 新 CI workflow
- Maven/npm/pip lock 或可重复安装配置
- dependency scanning 配置

**前置条件**

- H7.1-H7.3 的本地命令已稳定且可重复。

**实施**

1. CI 执行文档链接、secret scan、Java、frontend、Python、shell 和 migration。
2. 检查依赖漏洞、许可证和 lockfile 漂移。
3. 构建产物不提交；CI 从源代码重建。
4. branch protection 要求核心门禁通过。
5. PostgreSQL Testcontainers 集成组在 CI/release workflow 中强制执行；
   容器运行时不可用、测试组被 skip 或实际 test count 为零都必须使 job 失败。

**测试**

- 从干净 clone 执行完整 workflow。
- 故意引入失败测试、断链和 secret fixture，确认对应 job fail closed。
- 故意禁用容器运行时或跳过 PostgreSQL 集成组，确认 CI 不会以成功状态结束。

**回滚**

- 扫描器服务不可用时可明确标记 infrastructure failure；
  不能把安全失败改成成功。

**完成定义**

- G4 质量门禁通过且可在干净环境复现。

## 14. Phase 8：运维、密钥、配置与发布演练

### H8.1 外部化运行配置

**涉及文件**

- `application*.yml`
- Vite config/env example
- Python config
- start scripts、Nginx

**前置条件**

- H3/H6 稳定。

**实施**

1. 端口默认保持 Spring 8081、Vite 5173、Python 5002。
2. frontend URL、OAuth callback、CORS origin、Web3 domain/URI、
   issuer/audience、邮件服务 URL 全部环境化。
3. `vite.config.ts` 不再用 `define` 覆盖真实 `import.meta.env`。
4. 配置启动校验区分 local/test/prod，prod 禁止 placeholder 和 localhost。
5. start scripts 显式要求 profile 和数据库确认。
6. 删除未被 RS256 实现读取的 `app.jwt.secret`、`app.jwt.secret-file`
   和 `jwt-secret.key` 相关说明，避免继续保留死配置。
7. 为 OAuth user-info、email、JWKS/introspection 等所有出站 HTTP client
   绑定 connect/read timeout、TLS、代理和有限重试策略；启动测试证明 YAML 值实际进入 client。
8. 校正 Spring Session 属性命名并移除不生效配置；session timeout、cookie 和 JDBC cleanup
   使用 Boot 3.3.4 支持的前缀，运行时通过 actuator/config test 与 `Set-Cookie` 交叉验证。
9. Nginx 生产配置移除 H2 console/Vite 文件系统等开发路由，Swagger 默认关闭或认证；
   header/body/timeouts 按 endpoint 设置最小上限，不用 64 KiB header 和 100 MiB body 掩盖问题。
10. 生产只暴露一个受信边缘入口：后端绑定 loopback/private interface，
    firewall/security group 只允许受信代理访问，公网直连 backend 端口必须不可达。
    TLS 在受信边缘终止；边缘到 backend 使用 mTLS/TLS，或在有书面威胁模型和网络隔离证据时
    使用私有明文 hop，不能默认假设容器网络等于可信网络。
11. 边缘代理覆盖客户端提供的 Host、Forwarded、`X-Forwarded-For`、`X-Forwarded-Host`
    和 `X-Forwarded-Proto`，只转发规范化值；Spring 只信任该代理链。
    OAuth redirect、Secure cookie 和外部 URL 使用批准的 canonical public origin，
    不直接由未验证 Host/header 拼接。
12. auth 路由 access log 不记录 query/body，OAuth code/state 和 token 不能进入代理日志。
13. API 路由不无条件设置 WebSocket upgrade；配置 request id 并在 app/proxy 日志中脱敏关联。

**测试**

- 各 profile 配置绑定测试。
- placeholder/历史域名/弱默认扫描。
- Nginx 到 backend/frontend 的 smoke test。
- 从公网/非受信网络直连 backend 失败；从受信代理访问成功。
- 伪造 Host、Forwarded 与 `X-Forwarded-*` 不能改变 redirect origin、Secure cookie、
  客户端地址、限流 key 或审计来源；代理到 backend 的 TLS/mTLS 或网络隔离证据可复现。
- 出站 HTTP 黑洞/慢响应测试在配置 timeout 内结束，不耗尽请求线程。
- 超大 header/body、伪造 forwarded header、公开诊断路由和含敏感 query 的请求被拒绝或脱敏。

**回滚**

- 可使用固定的已批准部署配置文件；不能回退代码硬编码。

**完成定义**

- 同一构建产物可由环境配置部署，不需改源代码。

### H8.2 私钥、token 和导出物处置

**涉及文件**

- `rsa-keys.ser`
- `.gitignore`
- `python-resource-server/debug_token.py`
- `scripts/schema-blacksheep_dev-*.sql`
- secret management/runbook

**前置条件**

- 新密钥和 H2.1 轮换能力已准备，H2.5 联合切换已启用外部管理的新 signing key，
  并已将 tracked/旧 kid 置为拒绝状态。
- 确认数据库导出是否含敏感 schema/comment。

**实施**

1. 盘点并核验所有环境都已使用 H0.3/H2.5 部署的外部管理新 signing key，新的 kid 与
   tracked/旧 key 明确区分；发现遗漏环境时阻断发布并立即执行紧急换钥，不把实际轮换推迟到
   Phase 8，也不为“最终整理”无理由再创建另一把未纳入 H2.1 生命周期的新 key。
2. 将 `rsa-keys.ser` 从 Git tracking 移除并加入 ignore，迁移到 H2.1 选定的标准格式/密钥服务；
   私钥文件权限、所有者、挂载只读性和备份访问均纳入 runbook。
3. `rsa-keys.ser` 对应旧私钥视为已泄露，不适用正常轮换的旧 token TTL 兼容窗口：
   若 H0.3 盘点发现仍有环境使用，立即停止旧 key 签名并启动紧急吊销；
   H2.5 联合切换后 UniAuth/introspection 必须持续拒绝该 kid，离线消费者也必须在 H2.1
   声明的 key-cache/reconfiguration SLA 内通过 denylist、配置发布或模式切换停止接受。
   为协调发布所需的短窗口只能在共享流量关闭，或拒绝机制已对所有消费者生效时存在，
   不能继续信任该 key 签发的 token。
4. 删除完整历史 token 样例，改为运行时输入/测试 fixture。
5. 数据库 schema 导出移出源码或脱敏后仅保留必要结构参考。
6. 仓库级 secret scan 确认没有 `.env`、OAuth secret、私钥和密码。
7. 是否清理 Git 历史由单独运维决策执行，不在普通提交中擅自重写历史。

**测试**

- 新 token 使用新 kid；tracked 旧 kid 在 UniAuth/introspection 立即失败，
  离线消费者在声明 SLA 内失败，不存在等待旧 token TTL 的兼容窗口。
- 干净 clone 不包含可签名私钥。
- secret scan 通过。

**回滚**

- 对未泄露的普通轮换可在 H2.1 评审上限内延长旧公钥窗口；
  本工作项处置的 tracked/已暴露私钥不得延期信任。兼容性问题通过重新认证、
  introspection/denylist 或应用回滚处理，绝不恢复旧私钥签名或旧 kid 验证。

**完成定义**

- 仓库不再分发签名私钥或完整 token。

### H8.3 生产迁移与恢复演练

**涉及文件**

- migration
- 部署/runbook 文档
- backup/restore 脚本
- liveness/readiness endpoint 与部署探针

**前置条件**

- G0-G4 全部通过。
- 使用生产结构副本，不使用真实敏感数据。

**实施**

1. 演练备份、migration、应用升级、健康检查和流量切换。
2. 演练应用回滚与 forward-fix migration。
3. 验证 Session、JWT key、blacklist、email code、Web3 nonce 的时序兼容。
4. 记录总耗时、锁表时间、失败点和恢复时间。
5. liveness 只证明进程可工作；readiness 在 migration 完成、数据库/Session 可读写、
   signing key 可用且必要配置通过后才成功，不探测外部 provider 实时可用性造成级联摘流。
6. 健康端点只暴露最小状态，详细原因需认证；Nginx/编排探针使用独立最小权限路径。
7. 备份加密、访问控制、保留和销毁策略覆盖数据库、Session 与 key metadata；
   恢复到隔离环境后清除/失效旧 session、验证码、nonce 和过期撤销记录。
8. migration job 与应用实例并发启动、超时、锁等待和部分 rollout 都有演练。

**测试**

- 候选版本在生产结构副本执行完整 smoke 和安全回归。
- 备份可实际恢复，不只检查文件存在。
- migration 未完成、数据库不可写或 signing key 缺失时 readiness 失败；liveness 仍能区分死锁/崩溃。
- 未认证健康响应不包含数据库地址、异常、版本或密钥信息。

**回滚**

- 按演练 runbook 执行；未演练步骤不得用于真实发布。

**完成定义**

- G5 有可复现证据。

### H8.4 最终文档与声明校准

**涉及文件**

- `README.md`
- `AGENTS.md`
- `docs/*.md`
- component README
- historical indexes

**前置条件**

- 所有 gate 有日期、命令、环境和实际 test count。

**实施**

1. 更新 live guide 的最终配置、风险和验证命令。
2. 只在证据支持时修改状态；否则继续写“加固中”。
3. 历史文档不移动，继续通过 lifecycle banner 和索引说明。
4. 发布声明必须链接验证证据和已知剩余风险。

**测试**

- project-docs 相对链接检查。
- 活跃文档旧端口、placeholder、历史域名和完成声明扫描。
- 人工抽查所有“已验证”命令的原始证据。

**回滚**

- 状态可保持“加固中”；不能回滚为无证据生产声明。

**完成定义**

- 文档与候选版本一致，无断链、无旧默认、无无证据完成声明。

## 15. 建议实施批次

为降低审查和回滚复杂度，建议按以下批次提交：

| 批次 | 工作项 | 预估 | 合入条件 |
|------|--------|------|----------|
| 1（已完成） | H0.1-H0.3 | - | G0 |
| 2（已完成） | H1.1-H1.3 | - | PostgreSQL Testcontainers + dev-derived V1 + Flyway + SQLite 退役 |
| 3（已完成） | 现有功能测试基础扩充 | - | Java/Shell/Playwright/Python P0 覆盖矩阵 |
| 4（进行中） | H1.4-H1.5 | 4-7 工程日 | B1 已完成；剩余 schema hardening + persistent audit + G1 |
| 5 | H2.1-H2.4 | 5-8 工程日 | strict JWT + rotation/replay + revocation 预部署，不激活共享流量 |
| 6 | H2.5-H3.7 | 6-10 工程日 | H2.5/H3.1/H3.2 原子切换 + G2 |
| 7 | H4.1-H4.5 | 4-8 工程日 | email/password matrix |
| 8 | H5.1-H5.4 | 3-6 工程日 | SIWE tamper/replay matrix |
| 9 | H6.1-H6.5 | 3-6 工程日 | cross-client 行为与契约测试通过 |
| 10 | H7.1-H7.4 | 2-5 工程日 | G4 + CI gates |
| 11 | H8.1-H8.4 | 2-5 工程日 | G5 rehearsal |

预估只用于排序和资源安排，不是完成承诺。数据库现状、真实部署数量、
OAuth provider 凭据和邮件服务可用性可能改变工期。

## 16. 每批交付证据

每个批次至少提交：

- 变更说明与受影响契约。
- 定向测试、完整测试和实际 test count。
- 数据库目标、profile 和外部依赖说明。
- migration 的 fresh/baseline/upgrade 结果，若该批涉及 schema。
- 安全失败路径证据。
- rollback 或 forward-fix 步骤。
- 更新后的 live guide 和脚本。
- 未解决风险，不使用“全部完成”掩盖剩余问题。

已完成的 H1.1-H1.3 已提交以下证据：

- 受审查的 8 表 schema-only 基线、来源元数据和 SHA-256；不得提交真实数据。
- legacy migration/runtime schema 的归档清单和 Flyway location 扫描证明。
- 空库 V1 migrate、existing-schema baseline、重复启动、checksum/drift 失败测试。
- Spring Session JDBC 真实 create/read/delete 和核心 repository/HTTP PostgreSQL 集成测试。
- `mvn clean compile test-compile`、完整 Maven tests、前端类型/构建/Mock Playwright、
  Python 离线测试及 shell 语法检查结果。
- 连续三轮固定范围、无修改的实现检查记录；任一轮修改后计数归零。

下一轮测试基础扩充必须提交：

- 现有 endpoint/flow 的覆盖矩阵与 P0 缺口清单。
- OAuth2 mock、JWT 失败矩阵、并发登录方式、email/Web3 tamper/replay 集成测试。
- 扩展后的 Shell HTTP E2E 与 baseline guard 负向测试。
- Web3 wallet、refresh/logout、OAuth callback 和登录方式管理 Playwright。
- ESLint 配置和统一验证入口。
- 基础门禁通过后的连续三轮无修改检查记录。

## 17. 规划完成条件

本规划文档本身完成，不代表代码加固完成。代码实施只有同时满足以下条件才能结束：

1. G0-G5 全部通过。
2. Java、frontend、Python、migration、security 测试均有非零执行证据。
3. 没有默认破坏性启动、弱凭据回退或 tracked 私钥。
4. access/refresh、cookie/CSRF、email code、Web3 nonce 的并发与重放测试通过。
5. 空库安装、旧库升级、应用回滚和备份恢复已演练。
6. live docs 与当前实现一致。
7. 已知剩余风险明确记录，并经发布决策接受。

## 相关文档

- [文档导航](../README.md)
- [当前架构](../ARCHITECTURE.md)
- [配置基线](../CONFIGURATION.md)
- [开发指南](../DEVELOPMENT.md)
- [验证指南](../VERIFICATION.md)
- [文档体系建设计划](DOCUMENTATION_PLAN.md)
- [草稿与历史索引](README.md)
