# F2 Token Session、浏览器 Transport 与 CSRF 实施记录

> 状态：Completed（实现和本轮自动化验收已完成）
> 规划日期：2026-08-09
> 完成日期：2026-08-09
> 总体进度口径：F1 完成后约 86%；F2 完成后约 91%
> 上位计划：[加固阶段最终收尾计划](FINAL_HARDENING_EXIT_PLAN.md#f2token-family浏览器-transport-与-csrf8691)

## 1. 执行规则

0. F2 已明确启动；实施只按本文件冻结范围推进，不扩展为新的加固批次。
1. 本轮只加固现有认证、刷新、登出、introspection 和浏览器 transport，不增加新用户功能。
2. 固定范围以本记录和上位计划的 F2 条目为准；非阻断发现进入加固后 backlog。
3. 不写共享 `blacksheep_dev`，迁移和集成测试只使用 disposable PostgreSQL 16。
4. 不使用悲观锁或机械增加 JPA `@Version`；refresh rotation、整族撤销和用户安全版本使用
   PostgreSQL 条件更新、唯一约束和 CAS。
5. 不丢弃、回滚或 stash 工作区内其他人的修改；提交时使用 `git add -A` 纳入全部非
   ignored、非敏感、非生成修改。
6. F2 只通过针对性测试和完整统一门禁验收，不执行单轮连续三轮无修改检查。唯一一次
   三轮检查在 F1-F5 全部完成并通过阶段统一门禁后执行。

## 2. 已验证现状

截至 F2 开始时：

- UniAuth migration 链为 V1-V6，用户没有 token security version，数据库也没有 refresh
  family/session relation。
- access/refresh 只有独立 `jti`；refresh rotation 通过 `token_blacklist` 单次消费旧
  refresh，但 replay 不能撤销未知后继 token。
- 初始签发分别散落在 `TokenIssuanceFacade`、OAuth2 success handler 和 Web3 controller；
  签名与数据库安全状态没有共同事务边界。
- refresh token 同时写入 HttpOnly Cookie 和 JSON；access token 被普通前端流程写入
  localStorage。
- 资源服务器在 Authorization header 与 access Cookie 冲突时选择 header，重复 header、
  重复同名 Cookie 和同名 Cookie path 歧义没有统一失败关闭。
- `/api/auth/**` 整条安全链关闭 CSRF；现有前端尝试读取可读 `XSRF-TOKEN` Cookie，但该
  机制没有覆盖 refresh/logout 的可执行契约。
- 自定义 introspection 无客户端鉴权，兼容 query、form 和 raw body，并返回超出实时撤销
  所需的身份字段。
- `/test` 和 `/resource-test` 始终进入生产路由和 bundle。
- F1 统一限流尚未接入 refresh 和 introspection。

## 3. 协调切换顺序

### F2.1 PostgreSQL 与 token contract

1. 新增 V7，只做 forward migration：
   - `users.token_security_version`；
   - refresh family/session relation；
   - generation、security version、auth time、expiry 和 revoke 状态约束；
   - user/active/expiry 查询索引。
2. 新 token 必须同时包含并严格验证：
   - `sid`：family id；
   - `generation`：refresh generation；同一 pair 的 access/refresh 相同；
   - `ver`：用户 token security version；
   - `auth_time`：初始认证时间；refresh 原样继承，不能推进。
3. V7 生效后停止签发旧格式；仓库内 validator、测试 fixture 和 Python consumer 同步拒绝
   缺少新 claim 的 legacy token。

### F2.2 唯一事务签发、rotation 与整族撤销

1. 初始 local/email/OAuth/Web3 登录和 refresh 共用一个 session issuance 边界。
2. family 创建、generation CAS、整族撤销、安全版本校验和 security event 在事务内完成；
   事务提交后才签名并向 controller 返回 token。
3. refresh 对 `(sid, user, ver, generation)` 做条件推进；失败后识别 replay 并在同一事务
   撤销 family。并发 refresh 只允许一个 generation 推进成功。
4. logout 只需从任一有效 access/refresh credential 定位 family，即可撤销整族；access
   过期但 refresh 有效时仍必须完成。
5. 密码重置、增加/删除凭据和账户安全状态变化递增用户 security version 并撤销旧 family；
   primary 切换不改变凭据，不递增版本。
6. 新登录遇到同用户有效 Cookie family 时先撤销旧 family；不同用户有效 Cookie 必须要求
   显式 logout。

### F2.3 Cookie、凭据消歧、CSRF 与响应头

1. refresh token 只写 HttpOnly Cookie，不再出现在 JSON。
2. 普通同源浏览器流程不依赖 access-token localStorage；异构 Python bearer 演示只在显式
   dev/demo 诊断模式保留。
3. 统一解析 Authorization 和 Cookie：
   - 重复、空值、畸形值失败；
   - header/cookie 用户或 family 不一致失败；
   - 同名 Cookie 多值失败；
   - Bearer-only 请求不能借 Cookie 改变认证结论。
4. 提供同源 CSRF bootstrap；token 保存在服务端 Session，通过 JSON 返回，不能依赖 sibling
   domain 可注入的可读 Cookie。所有携带认证 Cookie 的 unsafe 请求必须提交精确单值 header。
5. prod auth/session/CSRF Cookie 使用 `__Host-` 名称、`Secure`、`Path=/` 且无 Domain；
   dev/test 保留 loopback HTTP 可运行配置。
6. token、认证状态和用户状态响应统一 no-store/no-cache；prod 增加 CSP、HSTS、
   Referrer-Policy、frame、nosniff 和 Permissions-Policy，local HTTP 不发送 HSTS。

### F2.4 严格 introspection、限流与前端/consumer 切换

1. introspection 只保留一个规范 POST form endpoint，要求恰好一个受管客户端凭据：
   - 禁止 query/raw body；
   - 禁止浏览器认证 Cookie；
   - 拒绝重复 Authorization、重复 token 和额外歧义字段；
   - 响应只返回实时撤销所需最小 claim。
2. refresh 和 introspection 接入 F1 PostgreSQL 限流；limiter 故障在密码学解析前稳定失败。
3. 前端保持 single-flight、Web Locks 和跨标签 logout 不变量，并改为：
   - 先 bootstrap CSRF；
   - 普通 API 使用同源 Cookie；
   - refresh 响应不读取 refresh token；
   - prod 不保留 access token 或诊断路由。
4. Python 离线 validator 严格验证新 claim，并明确 JWKS 离线验证不能感知 family revoke。

## 4. 验收矩阵

### PostgreSQL / Java

- V1-V7 fresh、existing baseline/upgrade、Hibernate `validate` 和精确 schema inventory。
- 初始 family/pair claim 一致性、transaction rollback、generation CAS、并发 refresh、
  replay 整族撤销、logout、过期清理和 security version。
- local/email/OAuth/Web3 签发入口都经过唯一 session service。
- 密码重置、add/remove login method 撤销旧 family；set-primary 不撤销。
- Authorization/Cookie 重复、冲突、空值、过期 access + 有效 refresh 和不同用户 replacement。
- Cookie unsafe 请求的 CSRF 缺失、错误、重复和成功矩阵；Bearer-only 不被 Cookie 混淆。
- introspection client auth、form 单值、Cookie 拒绝、限流和最小响应。
- no-store 与 prod/local 安全 header、Cookie 名称和属性矩阵。

### Shell HTTP/Flyway

- 真实 Cookie jar 执行 CSRF bootstrap、登录、refresh、replay、logout 和 restart。
- refresh JSON 不含 refresh token；新 claims 和数据库 family/generation/version 对齐。
- 双身份、重复 header/Cookie、无/错 CSRF、严格 introspection 和 limiter 故障失败关闭。
- Flyway guard 精确接受 V1-V7，拒绝缺失 V7 relation/constraint/index 和 grouped migration
  失败后的半成品 schema。

### Playwright / Python

- 同页/跨标签 refresh 仍只推进一次，logout 后迟到 continuation 不恢复认证状态。
- 普通登录、注册、Web3 和 OAuth callback 不把 refresh token 写入任何 Web Storage。
- production mode 的 bundle/route snapshot 不包含 `/test`、`/resource-test` 或 token
  篡改工具；dev/demo 保留异构资源演示。
- Python 接受完整新 access claim，拒绝 legacy、refresh、缺少/畸形 sid/generation/ver/
  auth_time，并记录离线撤销限制。

### 本轮退出门槛

1. 本任务相关 Java/PostgreSQL、Shell/Flyway、Playwright 和 Python 测试全部通过。
2. `mvn clean compile test-compile`、完整 Maven、前端 lint/type/build 和统一
   `scripts/verify.sh` 全部通过。
3. 更新 live 文档和本记录，提交并推送全部非 ignored 修改。
4. 不执行 F2 单轮三轮无修改检查；验收通过并提交推送后按最终退出计划进入 F3。

## 5. 实施结果

F2 已按冻结范围完成以下协调切换：

- Flyway V7 增加 `users.token_security_version` 和 `token_families`，双方
  shared-schema peer inventory、schema fingerprint、Java/Shell migration guard 和
  真实双进程启动顺序同步到 V1-V7。
- 新 access/refresh token 使用 `sid`、`generation`、`ver` 和 `auth_time`；Java、
  introspection 与 Python consumer 都拒绝缺少或畸形 session claim 的 legacy token。
- 初始签发与 refresh rotation 共用持久 token-family 事务边界；generation CAS、
  replay 整族撤销、logout、密码重置和登录凭据变化的 security-version 失效均有
  PostgreSQL 集成覆盖。
- refresh token 仅通过 HttpOnly Cookie 传递，不再进入 JSON；普通生产前端不持久化
  access token，跨域 Python Bearer 演示只在显式 Vite/后端 diagnostics 模式可用。
- Authorization/Cookie 重复、空值和冲突身份统一失败关闭；Cookie 认证的 unsafe
  请求使用服务端 Session 中的 CSRF bootstrap token。
- `/oauth2/introspect` 只接受受 Basic client 鉴权的单值 form POST，拒绝 query、
  raw body、认证 Cookie、重复凭据和额外字段，并接入共享限流与最小响应。
- 生产构建不包含 `/test`、`/resource-test` 或诊断 bundle；真实浏览器 E2E 通过
  启动命令临时设置 `VITE_AUTH_DIAGNOSTICS=true`，没有创建 `.env.local`。

## 6. 验收证据

2026-08-09 在同一稳定源码快照上执行：

- `scripts/verify.sh`：12/12 完整通过。
- 根 Maven：219/219，0 failures/errors/skips。
- reference email-service：150/150；runtime 44/44、HTTP 11/11、Flyway 15/15、
  backup/restore 10/10。
- shared-schema process E2E：4/4；HTTP/Flyway/Web3/email E2E：16/16；
  Flyway baseline guard：16/16。
- 前端 lint、typecheck、生产构建通过；Mock Playwright 28/28，生产 Playwright 2/2，
  真实邮箱登录跨服务 Playwright 1/1。
- Python 邮件 stub contract 12/12；Python 资源服务器 20/20。
- 文档相对链接和 `git diff --check` 通过。

本轮按阶段规则不执行连续三轮无修改检查。该检查只在 F1-F5 全部完成并通过阶段统一
门禁后执行一次。
