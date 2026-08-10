# UniAuth 加固阶段最终收尾计划

> 状态：F1-F5 completed；阶段退出检查 pending
> 冻结日期：2026-08-09
> 当前总体进度：约 99.9%
> 目标：只加固、修复和验证现有功能；完成本文五个批次后退出加固阶段
> 上位路线图：[全面加固实施规划](HARDENING_IMPLEMENTATION_PLAN.md)
> 历史执行记录：[下一轮加固实施计划](NEXT_HARDENING_IMPLEMENTATION_PLAN.md)

## 1. 为什么需要最终计划

此前加固工作已经建立 PostgreSQL 16-only、Flyway V1-V8、Testcontainers、Java
集成测试、Shell HTTP/Flyway E2E、Playwright、Python 契约测试和统一门禁，并完成
登录方式并发、Web3 challenge、refresh replay/logout、Cookie、CORS 与 OAuth2
redirect 等多批修复；邮件参考服务已推进到独立 Flyway V5。

旧计划同时保留了大量生产级长期目标，并要求每批结束后继续重新探索。这会把加固
变成没有固定终点的循环。本文取代该执行方式：

1. 剩余加固范围一次性冻结为五个批次，不再新增第六批。
2. 五批完成并通过统一门禁、三轮检查后，加固阶段结束。
3. “加固完成”表示现有工程基线达到本文定义的退出标准，不表示已经获得生产发布、
   容量、多区域、真实 provider 或合规认证证明。
4. 新功能、架构演进和非阻断改进进入正常 backlog，不再借“继续加固”无限延长。
5. 当前已获准连续完成 F2-F5；各批验收后继续下一冻结批次，不创建第六批。

## 2. 进度口径校准

此前报告的 99% 适用于已经完成的基础工程和既定五轮测试/迁移批次，不适用于
`HARDENING_IMPLEMENTATION_PLAN.md` 中全部生产级目标。按本文最终退出范围重新评估：

| 领域 | 当前状态 | 权重内进度 |
|------|----------|------------|
| 数据库、Flyway、测试隔离和恢复基座 | 已建立并反复验证 | 100% |
| Java/Shell/Playwright/Python/CI 基础门禁 | F5 完整 15 阶段组合门禁已通过 | 100% |
| 登录方式、Web3 challenge、token family/replay/logout、CORS/redirect | F3 固定范围已完成 | 100% |
| 邮箱 challenge、canonical identity、可靠投递和枚举防护 | F1 固定范围已完成 | 100% |
| token family、浏览器 transport、CSRF 和身份来源消歧 | F2 固定范围已完成 | 100% |
| OAuth2 显式绑定、provider trust、生产配置和密钥运维 | F5 组合门禁已通过；退出检查待执行 | 100% |

F1 启动前综合进度约 82%。F1、F2、F3 已于 2026-08-09 分别通过完整统一门禁；
F4 已于 2026-08-09 通过定向验收；F5 也已于 2026-08-09 通过完整统一门禁。
当前综合进度约 99.9%，后续只执行阶段退出检查，不能通过增加零碎测试或文档条目虚增。

| 批次 | 状态 | 进度口径 |
|------|------|----------|
| F1 邮箱与身份状态完整性 | 已完成并通过统一门禁 | 82% -> 86% |
| F2 Token family、浏览器 transport 与 CSRF | 已完成并通过统一门禁 | 86% -> 91% |
| F3 OAuth2、Web3 与 canonical API 契约 | 已完成并通过统一门禁 | 91% -> 95% |
| F4 依赖、生产配置、密钥和运维门禁 | 已完成定向验收 | 95% -> 98% |
| F5 最终证据与统一阶段门禁 | 已完成并通过统一门禁 | 98% -> 99% |
| F 阶段退出检查 | 待执行 | 99% -> 100% |

## 3. F1 开始前冻结事实

以下内容记录 2026-08-09 F1 开始前的不可变规划基线，不代表 F1 完成后的当前实现：

- 根 Java `200/200`、邮件参考服务 `148/148`、shared-schema E2E `4/4`、
  HTTP E2E `16/16`、Flyway guard `14/14`、Mock Playwright `28/28`、
  真实邮箱登录浏览器 E2E `1/1`、Python `18/18`、邮件 stub `9/9` 已通过。
- PostgreSQL 16 是唯一受支持数据库；Flyway V1-V5 是唯一 schema owner；
  Hibernate 使用 `validate`，SQLite 已退役。
- 当前 refresh `jti` 已单次消费，logout/blacklist 已接入 Java 资源 API 和
  introspection；尚无 token family/security version。
- 自定义 introspection 当前公开，无客户端鉴权，并接受 query/form/raw body 多种 token
  输入；它不能作为生产实时撤销边界。
- 当前浏览器仍在 JSON/localStorage 中持有 access token；refresh token 仍出现在
  JSON，Resource Server 对冲突的 header/cookie 身份选择 header。
- 邮件服务同步拒绝时不会保存 challenge，但“外部已接受、本地提交失败”和异步最终
  投递失败仍没有 durable 状态机；同一 email/purpose 可有多个 pending challenge。
- 验证码当前以低熵明文写入数据库；注册 challenge metadata 仍可长期保留 password
  hash；邮箱查询没有统一 canonical 规则；forgot-password 会明确泄露账户是否存在。
- 公开 email status 会暴露 pending registration，公开 check-code 在错误时不增加
  retry；两者当前可充当邮箱状态和低熵验证码 oracle。
- 非邮箱用户名注册会把未验证 email 写入全局唯一 `users.email`；OAuth/Web3 还会生成
  内部合成地址，当前状态不足以阻止未验证/合成值占用或冒充可投递 verified identity。
- 普通注册、add-local 和密码重置的服务端密码规则不一致；登录参数可从 query 读取，
  当前密码认证也没有多实例一致的失败节流。
- OAuth2 redirect allowlist 已完成，但 callback 仍可仅凭 access cookie 推断绑定，
  provider 来源和可信 email 规则尚未统一。
- add-local、remove、set-primary、OAuth2 bind 和 Web3 bind 当前没有统一 recent-auth
  守卫；凭据集合变化也没有统一递增 token security version 并撤销旧 family。
- Web3 challenge 已绑定完整 SIWE message 并原子消费；首次钱包建号及绑定仍需补齐
  跨请求并发和稳定冲突语义。
- `/test`、`/resource-test` 等诊断 UI 和 token 操作仍进入普通前端构建；生产边缘、
  trusted proxy、Swagger/诊断路由和浏览器安全头尚无完整可执行门禁。
- `npm audit` 当前有 2 个 moderate React Router 公告，修复版本需要主版本升级；
  high/critical 为 0。
- `pip-audit` 对当前 `python-resource-server/requirements.txt` 报告 Flask、
  Flask-CORS、PyJWT、Requests、Cryptography 和 Werkzeug 6 个包中的 46 条已知
  漏洞记录；当前统一门禁未运行 Python 漏洞审计。
- Maven 版本扫描显示多个依赖和插件存在大版本跨度，且项目没有 Maven Enforcer
  最低 Java/Maven 约束；当前统一门禁未运行 Maven 漏洞审计。
- Spring Authorization Server 当前仍使用固定 `auth-client`、`{noop}auth-secret`、
  localhost redirect、password grant 和无 PKCE 配置；它不是当前主要业务 token
  签发路径，也没有生产可用性证据。

依赖“有更新”不等于必须全部升级。最终批次只处理安全、支持周期和可重复构建所需
升级，不在加固阶段顺手迁移无关大版本。

## 4. 范围控制

### 4.1 允许进入当前五批的发现

只有以下发现可以加入正在执行的批次，并且不得创建新批次：

- 可导致共享数据丢失、错误迁移或不可恢复状态。
- 可导致认证/授权绕过、账户错误合并或凭据重放。
- 可泄露密码、token、code、私钥或可直接利用的身份材料。
- 可让统一门禁在关键测试未执行、失败或证据丢失时错误返回成功。
- 当前批实现直接引入的行为回归、迁移错误或测试盲区。

发现上述问题时，修复后重跑当前批受影响门禁。F1-F5 不启动三轮检查计数器；只有
F1-F5 全部完成后的阶段末检查发生实质修改时，才将该统一计数器归零。

### 4.2 必须进入加固后 backlog 的事项

- 新 provider、MFA、Passkey、多租户、管理后台、审计 UI 或新业务页面。
- 把自定义 JWT 与 Spring Authorization Server 全面统一成新产品架构。
- 不为关闭已知漏洞或支持周期所必需的框架/语言大版本迁移。
- 性能优化、容量扩展、多区域、灾备产品化和合规认证。
- 将 Python 示例改造成完整生产资源服务器。
- 历史文档搬迁、UI 重设计、代码风格统一和无行为收益的重构。
- 对 `blacksheep_dev` 执行 baseline/apply；该操作继续需要用户单独授权。

## 5. 五个最终批次

### F1：邮箱与身份状态完整性（82% -> 86%，已完成）

固定范围：

1. 建立唯一 canonical email 入口，统一 trim、大小写和长度规则；注册、邮箱验证、
   登录、密码重置、用户和登录方式查询全部复用。
2. 用 Flyway preflight 和数据库约束保证 canonical `users.email`、email-shaped
   local username 及 challenge 查询语义一致；坏数据只报告，不按查询顺序自动合并。
3. 保留普通 username 和“email 即 username”两种 local 注册输入，但统一到一条状态机：
   两者都必须先验证 canonical email，再在同一事务创建 user 与首个 LOCAL method；
   验证前不得占用 `users.email`。内部 synthetic identity 与 verified contact 使用明确
   不同的状态/字段，合成值不得标记为可投递/verified 或参与 canonical email 唯一归属。
   迁移遇到冲突只输出可操作报告，不自动挑选账户。
4. 所有新密码写入入口共用一个服务端策略和 DTO：普通注册、邮箱验证注册、add-local
   和密码重置必须使用相同长度/控制字符规则、最大输入边界和 BCrypt 参数；已有 hash
   继续可登录，并在成功认证后按目标成本安全 rehash。历史 passwordless LOCAL 记录
   在 migration/preflight 中保持可识别、可报告且不阻断升级，但 F1 完成后的任何注册、
   匿名验证或 add-local 写路径都不得新建无密码 LOCAL；除非以后通过独立、已认证且
   明确受支持的新功能重新定义该凭据类型，否则不能把 schema 兼容形状当作可写业务状态。
5. 登录只接受一种规范 body 编码和严格单值字段，拒绝 query password、重复参数、
   超长输入和不支持 content type；错误响应、日志和 tracing 不得回显用户名或密码。
   一次请求只能由统一 credential service 执行一次密码哈希校验；成功后在明确写事务中
   重新确认账户 enabled 状态，更新 `last_used_at`、写入安全事件并把同一认证结果交给
   单一 token issuance facade，不能由 controller/service 再次 BCrypt 或在只读事务中
   写状态。F1 可先由 facade 适配当前严格 issuer；F2 在不改变调用方认证决策的前提下
   原子替换为持久 family/session issuance。
6. 建立多实例一致、共享原子存储的统一认证限流基础设施；F1 先接入密码登录、注册、
   验证码发送/验证和密码重置，F2/F3 接入 refresh、introspection、OAuth 发起/失败、
   Web3 nonce/verify 和敏感登录方式写操作。key 组合 endpoint、可信来源和 canonical
   identity 的不可逆摘要，使用通用外部错误和可恢复 cooldown，不永久锁定账户，也不
   让伪造用户名无限制造持久状态。共享 limiter 故障时高风险入口在昂贵计算/外部调用前
   稳定 `503`，logout 仍必须执行本地清理和可完成的撤销。
7. 把 challenge 改为明确的 delivery/usage 状态，保证同一 canonical
   `(email,purpose)` 最多一个 active challenge。
8. 使用 UniAuth 自有 transactional outbox 或等价持久状态机协调 challenge 与邮件
   服务调用：本地提交、外部接受、最终失败和重试均可恢复、幂等且可测试。
9. 扩展 UniAuth 与邮件服务的内部契约：每次请求携带稳定 idempotency key，邮件服务
   对重复请求返回同一 queue/delivery identity，并提供受鉴权的最小状态查询或等价
   回执；不能靠“再次发送一封相同邮件”猜测上次是否成功。
10. challenge 只有在邮件请求被接受后才能 ACTIVE；reconciler 必须能把“provider 已
   接受但 UniAuth 尚未更新”的窗口恢复为 ACTIVE。最终 delivery 失败必须使其不可用。
   待发送/待确认状态必须有处理截止时间和总生命周期上限；验证码有效期按明确的接受/
   投递语义开始计算，但任何重试或恢复都不能把总生命周期无限延长。
11. 频控和 cooldown 使用 PostgreSQL 条件写入/CAS 或唯一 reservation，不能继续依赖
   “先 count 再 insert”；不使用悲观锁或机械增加 JPA `@Version`。
12. 数据库不得保存可直接使用的低熵验证码。使用带独立服务端密钥的 HMAC/peppered
   digest 和常量时间比较，记录 key id 以支持短期轮换；禁止使用可离线穷举的无密钥
   快速 hash，原明文列必须通过安全 migration/preflight 退役。
13. 公开 `email/status/{email}` 和 `check-verification-code` 不得继续作为账户状态或
   验证码 oracle。保持现有注册 UX 时使用不可猜测、短期且绑定 purpose/email 的
   challenge handle，或只保留唯一原子 verify 路径；每次错误尝试都必须进入同一
   PostgreSQL retry/CAS 和统一限流规则，不能存在不计数的只读试码接口。
14. challenge 不保存 password、password hash 或任意 JSON 凭据 metadata。最终 verify
   使用 send 返回的 opaque challenge handle 与客户端内存中的注册 DTO；pending 数据只
   保存最小类型化状态和明确保留期，过期/失败清理不得长期保留身份材料。
15. forgot-password 第一阶段和后续错误 code 阶段使用统一外部语义和有界 decoy，
   不能泄露账户、enabled 状态或本地登录方式是否存在。
16. 合并 `/register` 验码分支、`/verify-email` 和重复 controller/service 建号逻辑，
   只保留一个验证码消费 + user/login-method 创建事务和一个 canonical auth response；
   兼容 endpoint 只能调用同一 operation，不能保留两套写路径。
17. 匿名邮箱验证不得给任何既有 LOCAL/OAuth/Web3 用户绑定登录方式、覆盖密码或签发
   该账户 token；已有账户增加 LOCAL 必须先以现有方式登录并通过 F3 recent-auth，
   未实现独立恢复流程前保持关闭。
18. 删除未实现的 `LOGIN` verification purpose 及其前端/schema 允许值；migration
   遇到遗留活动记录时先报告并使其失效，不能转换成 REGISTRATION/PASSWORD_RESET。
19. 保持现有注册、验证和重置用户流程，不增加邮箱验证码登录或新页面。
20. 建立最小 append-only security event 结构，只记录稳定事件类型、内部 ID、
    request id、结果和原因码；不保存原始 email、code、password hash 或 token。
    已提交事件不能由业务角色更新或任意删除；关键状态变更与事件写入在同一事务内，
    事件写入失败时业务状态必须回滚。

验证：

- fresh/upgrade migration、坏数据 preflight、唯一 active challenge、audit 不可变性、
  outbox 幂等和 accepted-before-activation 恢复。
- PostgreSQL ApplicationContext 覆盖并发发送、提交前后故障、重复 worker、最终失败、
  audit insert failure、原子消费、metadata 清理、canonical 变体、密码策略/rehash、
  query/duplicate credential 拒绝、节流、匿名既有账户绑定拒绝、单一注册事务、
  未实现 purpose、公开 oracle 和枚举矩阵。
- Shell 使用真实 UniAuth + 参考邮件服务 + 双 PostgreSQL 覆盖跨进程恢复和重启。
- Playwright 覆盖现有注册/重置成功与统一失败语义；不新增用户流程。

### F2：Token family、浏览器 transport 与 CSRF（86% -> 91%）

固定范围：

1. 增加最小 refresh family/session 持久模型、用户 token security version 和
   `sid`/generation/`auth_time` 契约；同一次签发的 access/refresh 必须属于同一
   user、family、version，rotation 原样继承 `auth_time`，不能把 refresh 当成最近认证。
   replay、logout、密码重置、凭据集合变化和账户禁用可撤销整族未知后继 token。
   local、email、OAuth、Web3 初始认证和 refresh 必须共用唯一事务化 issuance service，
   从同一份 enabled user/security-version/authority 快照生成 pair；family、generation、
   撤销和安全事件提交成功前，任何已签名 token 都不得离开进程。
   local 的初始 `auth_time` 来自本次成功密码校验，Web3 来自本次原子消费的签名 challenge，
   新邮箱账户来自完成建号事务的一次性注册 challenge。密码重置、单纯邮箱所有权验证、
   token refresh 和凭据集合修改都不能推进既有会话的时间。OAuth 只有在受验证的 provider
   `auth_time`/`max_age` 语义足以证明 fresh authentication 时才建立 recent 时间；否则
   仍可按现有普通登录策略签发非 recent 会话，但敏感操作必须失败。
2. replay detection 必须在同一事务内撤销 family；并发 refresh 仍只允许一个成功。
3. 浏览器主流程切换为 HttpOnly refresh Cookie；refresh token 不再进入 JSON。
4. 主应用浏览器认证明确唯一凭据来源。header/cookie 同时存在且值不同时失败关闭，
   不再静默选择身份；重复 Authorization header、重复同名认证 Cookie、空值和不同
   Cookie path 造成的同名歧义也必须拒绝，不能按首值或末值继续。
5. access token localStorage 只允许保留在显式 dev/demo 异构资源服务器演示路径；
   prod 构建和普通认证页面不得依赖该存储。
6. 对 cookie 认证的 refresh、logout 和其他状态变更建立可执行 CSRF 规则；Bearer-only
   API 不能借认证 Cookie 绕过或混淆规则。生产 access/refresh/session/CSRF Cookie
   使用满足平台约束的 `__Host-` 名称、`Secure`、`Path=/`、无 `Domain`，CSRF 值通过
   受控 bootstrap 返回而不是依赖可被 sibling domain 注入的可读 Cookie。
7. 所有含 token、认证状态或用户状态的响应统一 `Cache-Control: no-store` 和
   `Pragma: no-cache`；生产明确 CSP、HSTS、Referrer-Policy、frame、
   X-Content-Type-Options 与 Permissions-Policy，本地 HTTP 不发送污染开发域的 HSTS。
8. 前端 single-flight、Web Locks 和跨标签页 logout 继续满足当前已验证不变量。
9. Python 离线模式继续严格校验 token；需要实时撤销的边界只能使用受鉴权的
   introspection，不伪装成纯 JWKS 可感知 PostgreSQL family 状态。
10. 自定义 introspection 改为严格服务到服务 endpoint：只接受一种规范 POST form
    token 输入和恰好一个受管客户端凭据，拒绝 query/raw body、浏览器认证 Cookie、
    重复 header/参数和未授权调用；响应最小化并纳入限流、no-store 和安全事件。
11. 新登录遇到现有 Cookie pair 时执行明确替换：同用户旧 family 在新 pair 提交前
    原子撤销；不同用户的有效凭据必须先显式 logout，不能只覆盖 Cookie 留下孤儿 family。
    refresh 同时收到 access Cookie 时必须验证 user/sid 一致；logout 在 access 过期但
    refresh 有效时仍能撤销 family。
12. 凭据新增/删除、密码变更和账户恢复递增 security version、撤销旧 family；认证后
    账户操作只有在安全状态提交后才可签发继承原 `auth_time` 的 replacement family，
    失败时不能恢复旧 family。账户禁用及 role/authority 授予或撤销也必须在同一安全
    事务中递增 version 并撤销旧 family；单纯 primary 切换不改变凭据，可不替换 family。
13. 生产构建和路由不包含 `/test`、token 篡改、资源诊断等开发工具；跨域 Python
    bearer 演示只在显式 dev/demo 模式可达，生产 URL、bundle 和路由快照均不可达。
14. refresh 和 introspection 接入 F1 统一限流；单账户多来源、单来源多账户、并发突发、
    多实例竞争、limiter 故障和过期清理都必须有稳定行为，随机 token/kid 不能形成
    无界密码学或 JWKS/introspection 资源消耗。
15. token family、登出、密码重置和账户状态变更写入 F1 的最小安全事件接口。
16. 新 claims/family issuer、所有 Java/Python/introspection validator、新 Cookie 名称和
    CSRF 规则先在隔离环境共同就绪，再执行一次协调切换演练：停止 legacy token 签发，
    激活 strict validator，清理旧 Cookie，并拒绝 legacy token/kid fixture。切换后不
    允许按请求回退、长期双格式接受或为兼容恢复 refresh JSON/localStorage；失败时停止
    流量、要求重新认证并使用 forward-fix，不能回滚到旧不安全 transport。F2 完成只
    证明仓库内格式与消费者切换闭环；真实生产候选仍被 F4 的外部密钥、历史 key revoke
    和 prod 配置门禁阻断，不能在 F4 前宣布可发布。

验证：

- PostgreSQL 并发 rotation/replay/family revoke/rollback 和过期清理。
- 四条 SecurityFilterChain 的 Cookie/Bearer/CSRF/冲突身份矩阵。
- Shell 覆盖真实 Cookie jar、无 CSRF、错误 CSRF、双身份、logout、受鉴权
  introspection、限流/故障语义和 restart。
- Playwright 覆盖同页/跨标签并发、迟到 refresh、prod 禁止长期凭据存储和诊断路由。
- Python 覆盖新 claims、旧 token 兼容边界和实时撤销限制。

实施结果（2026-08-10 当前候选）：

- Flyway V7、token family/security version、session claims、rotation/replay/logout、
  Cookie/CSRF、strict introspection、限流和生产诊断路由隔离已完成。
- 根 Maven 219/219、邮件 Maven 154/154、shared-schema 4/4、HTTP/Flyway 16/16、
  Mock Playwright 28/28、生产 Playwright 2/2、真实浏览器 1/1、Python 12/12 +
  20/20 已通过。
- F2 与 post-F1 邮件 V5 的合并树已重新执行完整 `scripts/verify.sh` 并 12/12
  通过，没有继承合并前结果。按阶段规则不执行 F2 单轮三次无修改检查，下一步进入
  F3。

### F3：OAuth2、Web3 与 canonical API 契约（91% -> 95%）

固定范围：

1. OAuth2 普通登录和绑定使用两个明确入口；绑定入口先通过统一 recent-auth 守卫，
   再创建服务端保存、绑定当前用户、
   provider registration id、浏览器 session/授权请求 state，一次性且短期有效的
   intent，不能跨 provider、跨用户或跨授权请求复用。
2. callback 必须原子消费 intent；普通登录不能因浏览器已有 access Cookie 自动绑定。
3. provider 只由 Spring registration id/OIDC registration 决定，不再通过 user-info
   属性名猜测；稳定 subject 必须非空、类型和长度合法。
4. Google、GitHub、X 的 email trust 规则显式化；未验证或合成 email 必须写入 F1
   定义的非 verified 状态，不得被当作密码重置、可投递 contact 或自动账户合并依据。
5. provider subject 首次登录、重复 callback、不同用户绑定和并发创建使用数据库
   唯一约束/CAS，返回稳定业务错误，不泄露数据库细节；OAuth/Web3 新用户使用系统
   保留的 opaque username，不从 provider subject、wallet、email 或 display name 派生。
   subject/wallet 命中既有用户后，必须在更新 provider metadata、`last_used_at`、消费
   敏感绑定状态或签发 token 前重新确认用户存在、enabled 且 security version 符合
   当前上下文；disabled 或状态已变化的账户失败关闭且不得留下部分更新。
6. 收敛非登录必需 provider scopes 和 authorized-client 生命周期，减少长期 provider
   token 暴露面。
7. `/api/user` 和统一用户 DTO 从数据库 primary login method 返回真实 provider，
   不再把所有 JWT 用户标成 local。
8. Web3 首次建号与绑定使用完整事务和唯一冲突映射，不能留下无登录方式用户或通过
   `findAll()` 扫描判定绑定；删除公开 wallet-binding status oracle。
9. 保持现有完整 SIWE message 绑定和原子消费语义，但删除匿名 nonce 请求按 wallet
   覆盖其他浏览器未完成 challenge 的行为；使用不可猜测 challenge handle 精确关联，
   对单 wallet、单来源和全局 active challenge 设置有界上限、过期清理和统一限流。
   chainId 要么由服务端固定/allowlist 验证，要么从请求移除，不得静默忽略。使用
   标准库/标准向量覆盖 EIP-191 UTF-8 byte length、ASCII、中文、emoji、v/r/s 和
   错误地址，不增加链或钱包功能。
10. add-local、remove、set-primary、OAuth2 bind 和 Web3 bind 共用 recent-auth 守卫；
    add/remove/bind 按 F2 递增 security version 并替换 family，refresh 不能延长窗口。
    email-shaped add-local username 只能等于当前 verified canonical email，不能顺带认领
    或修改 email；set-primary 只改变展示主身份，不无理由撤销会话。
11. 用 typed DTO/form binder 和固定长度/单值规则替换认证写路径的裸 Map；OAuth user-info、
    Web3 message/signature、username/email 和错误响应在昂贵解析前执行边界检查。
12. OAuth2 authorization 发起/失败、Web3 nonce/verify 和敏感 login-method 写操作
    接入 F1 统一限流；伪造 forwarded header 不能改变限流身份，失败路径不泄露账户、
    provider 或 wallet 绑定状态。
13. OAuth2/Web3 绑定、primary 变化和冲突结果写入最小安全事件接口。

验证：

- Mock OAuth2/OIDC ApplicationContext 覆盖 login/bind intent、重放、过期、跨用户、
  provider 属性畸形、可信 email 和并发首次 callback。
- PostgreSQL 覆盖 OAuth/Web3 首次建号、唯一冲突、事务回滚和最终登录方式不变量。
- Shell/Playwright 覆盖普通登录不绑定、显式绑定只消费一次、recent-auth、
  status oracle 关闭、限流/故障语义、`/api/user` provider 和生产诊断路由不可达。
- 不调用真实 provider；真实 provider 验证仍是发布环境 opt-in。

实施结果（2026-08-09）：

- Flyway V8、显式 OAuth2 login/bind 分离、一次性 binding intent、provider profile
  信任规则、opaque Web3 challenge handle、source/global capacity CAS、recent-auth、
  typed login-method DTO 和真实 primary provider 已完成。
- F3 定向 PostgreSQL/Java 测试 63/63；migration/shared-schema 定向测试根项目 15/15、
  邮件 peer guard 8/8；shared-schema process E2E 4/4、HTTP/Flyway/Web3/email
  E2E 16/16、相关 Playwright 15/15 和显式 OAuth bind 1/1 通过。
- 完整统一门禁重新执行并 12/12 通过：根 Maven 222/222、邮件 Maven 154/154、
  Mock Playwright 29/29、生产 Playwright 2/2、真实浏览器 1/1、Python
  12/12 + 20/20，文档链接和 patch hygiene 通过。
- F3 未执行单批三轮无修改检查；按阶段规则进入 F4。

### F4：依赖、生产配置、密钥和运维门禁（95% -> 98%）

固定范围：

1. 把 Python 的人类维护输入与带 hash 的精确解析锁文件分离，CI 使用
   `--require-hashes` 安装并审计同一锁文件；升级依赖直到 `pip-audit` 对认证关键直接
   依赖及其解析出的运行时依赖报告零个未豁免已知漏洞。因为 `pip-audit` 本身不提供
   可靠 severity 过滤，不能只写“high/critical”；任何临时豁免都必须列明 advisory、
   固定版本、不可达证据、回归测试、责任人和到期日，并由统一门禁检查到期。
   本地和 CI 的统一入口都必须在仓库外创建隔离 Python 环境，从同一 hash lock 安装后
   再运行测试与审计；不得复用开发机全局 site-packages 或只比较 `requirements.txt`。
2. 根应用和 `reference/email-service` 两个 Maven 工程都增加可重复的漏洞审计；
   固定工具版本、CVSS 阻断阈值、报告和带到期日的 suppression，网络失败、数据库更新
   失败或报告缺失必须失败关闭。
3. 两个 Maven 工程增加 Maven Enforcer，固定最低 Java/Maven、禁止重复/冲突依赖
   等可重复构建规则；选择仍受支持且兼容 Java 17 的 Spring Boot 维护线，不在本批
   跳到 Java 21/Boot 4。
4. npm high/critical 继续阻断；2 个 React Router moderate 公告必须通过兼容升级
   清零，或以有期限、列明不可达代码路径和回归测试的显式例外退出，不能静默忽略。
5. 安全相关 patch/minor 依赖升级逐项验证；不把“全部升级到最新版”作为目标。
6. GitHub Actions 使用精确 commit SHA 或等价受控策略；PostgreSQL 测试镜像固定
   PostgreSQL 16 的明确 patch/digest 更新策略。
7. `prod` 必须显式提供 Web3 domain/URI、JWT issuer/audience/kid、验证码 HMAC key、
   OAuth2 callback、frontend/CORS 和所有启用 provider/client 的凭据与 redirect；
   placeholder、`{noop}` secret、localhost redirect 和弱/重复 key id 失败关闭。
8. 明确 Spring Authorization Server 是关闭的非业务路径还是受支持路径。默认关闭时
   不能暴露伪可用 client/endpoint；显式启用时必须移除 password grant 和固定
   `auth-client`/secret，按 client 类型要求 PKCE 或安全 client authentication，并
   对 grant、scope、redirect、secret 编码和生命周期建立配置与集成测试。
9. `prod` 禁止生成或使用仓库工作目录中的本地 RSA 私钥；建立外部密钥加载、权限和
   rollover：新旧 kid 在有界兼容窗口内按策略同时验证/发布，窗口结束后拒绝 retired
   kid；自定义 JWT、自定义 JWKS endpoint、所有 verifier 和可选 Authorization Server
   `JWKSource` 必须使用一致 key set。publish、开始/停止签名、retire 和紧急 revoke
   使用 F1 的最小安全事件语义留下不含私钥或完整 JWK 的持久证据；历史已暴露 key
   直接走 revoke，不进入正常兼容窗口。
10. 增加最小 liveness/readiness：只有数据库、Flyway、密钥和必要配置准备完成才
    ready；详细失败原因不得公开。
11. 在 disposable PostgreSQL 执行 fresh、upgrade、backup/restore、F1 开始前冻结并
   记录的 immutable pre-F1 commit SHA/构建产物对 forward-compatible schema 的启动/
   回退演练、forward-fix 和 key rotation rehearsal；当前仓库没有可依赖的 release tag，
   不能在实施时临时把任意 HEAD 称为“前一受支持版本”。不伪造 Flyway down migration，
   不写共享开发库。
12. 统一门禁必须在 Maven/Python/npm 审计未执行、网络失败、豁免过期或报告缺失时
    失败关闭，并保存可核验的供应链证据。
13. 移除未被 RS256 实现使用的 dead secret 配置和误导说明；所有 OAuth user-info、
    email、JWKS/introspection 等出站 HTTP client 使用配置绑定的 connect/read timeout、
    TLS 校验、有限重试和代理策略，黑洞/慢响应不能耗尽请求线程。
14. 固定 trusted proxy/forwarded-header 边界：生产后端绑定 loopback/private
    interface，只信任配置代理覆盖后的 Host/Forwarded/X-Forwarded-*；非受信来源不能
    改变 redirect、Secure Cookie、客户端地址、限流 key 或审计来源。Swagger、开发
    路由和文件系统代理在 prod 默认关闭或认证。
15. 生产代理与应用共享 header/body/cookie 上限和脱敏 access-log 规则；认证 query/body、
    OAuth code/state、token 和 Cookie 不进入日志。CSP/HSTS 等 header 的 owner 在
    Spring/边缘之间唯一化，避免冲突覆盖。
16. 仓库和候选构建执行 secret/private-key/full-token scan；历史已暴露 signing key
    按紧急撤销处理，不进入正常 rollover 兼容窗口。数据库/Session/key metadata 的
    backup 具备加密、访问控制、保留、销毁和隔离恢复后的旧凭据失效步骤。

验证：

- 依赖审计的通过、失败、例外过期和报告丢失矩阵。
- Java 17、CI Node/Python 版本和本地支持版本矩阵。
- prod ApplicationContext 配置失败矩阵、readiness 和密钥权限/轮换。
- trusted proxy/直连、出站黑洞、header/body 上限、诊断路由和 secret scan。
- disposable PostgreSQL 完整迁移、恢复与回滚演练。

### F5：最终证据与统一阶段门禁（98% -> 99%）

固定范围：

1. 冻结 F1-F4 后的 API、schema、配置、测试数量和已知限制。
2. 运行完整 `PYTHON_BIN=python3 scripts/verify.sh`，并确认所有新增 Maven/Python/npm
   供应链阶段真实执行、证据可读取且没有静默 skip。
3. 对根应用、邮件参考服务、Shell/Flyway、前端/Playwright、Python、文档链接和
   patch hygiene 执行完整硬门槛。
4. 更新 `README.md`、`AGENTS.md` 和 live guides，记录 F1-F5 实施完成及阶段级
   三轮检查的固定范围。
5. 提交工作区全部非忽略、非敏感、非生成修改并推送。
6. F5 验收完成后不在本批执行三轮检查；进入下面独立的阶段退出检查。

实施结果（2026-08-09）：

- 完整 `PYTHON_BIN=python3 scripts/verify.sh` 15/15 通过，以
  `PASS: complete repository verification gate` 结束，并保存仓库外成功证据。
- 根 Maven 246/246、邮件 Maven 154/154；shared-schema 4/4、主 HTTP
  E2E 17/17、主 Flyway guard 16/16、认证备份恢复 6/6、邮件 runtime 44/44、
  邮件 HTTP 11/11、邮件 Flyway guard 15/15、邮件备份恢复 10/10 全部通过。
- 前端 lint/typecheck/build、Mock Playwright 29/29、生产 Playwright 2/2、
  真实邮箱登录浏览器 E2E 1/1、Python 资源服务器 20/20 和邮件 stub 12/12 通过。
- 根/邮件 OWASP 报告分别包含 94/54 项 dependency evidence；npm audit 为
  0 vulnerabilities。Python audit 仅保留 3 个精确、到期日为 2026-10-01 UTC 的
  `cryptography 48.0.1` 例外，门禁验证其 advisory、版本、owner、理由和不可达证据。
- 敏感扫描覆盖 670 个源码与候选构建文件，0 findings、0 sensitive-scan exceptions；
  57 个 Markdown 文件相对链接和 `git diff --check` 通过。
- OAuth authorization-code token endpoint 与 provider user-info/profile 请求共用
  有界 connect/read timeout；定向测试覆盖有效 token 响应解析和慢响应超时。
- F5 不执行单批三轮检查；当前进入唯一一次 F 阶段退出检查。

### F 阶段退出检查（99% -> 100%）

1. 仅在 F1-F5 全部完成并分别通过验收后开始。
2. 对 F1-F5 的整体实现、测试、配置、迁移和文档执行连续三轮固定范围、无修改检查。
   任一轮实质修改后计数归零，重跑受影响门槛，再从第 1 轮开始。
3. 检查通过后将本计划标记 Completed，将旧执行计划标记 Historical，并提交推送。
4. 宣布“加固阶段结束”，后续需求使用普通 feature/fix/maintenance 计划；不得自动
   创建新的 hardening batch。

最终声明必须写成：

> UniAuth 已完成仓库级工程加固基线；当前自动化、已知限制和发布前环境验收见
> `docs/VERIFICATION.md`。该声明不等同于生产发布、容量、真实 provider、灾备或合规
> 认证完成。

## 6. 每批固定硬门槛

每批至少运行与改动对应的定向测试，并在提交前运行统一入口：

```bash
PYTHON_BIN=python3 scripts/verify.sh
```

涉及 migration、备份、密钥或 prod 配置时，增加本文对应的 disposable rehearsal。
不得连接共享开发库，不调用真实 OAuth/SMTP，不运行高成本外部服务。

### 6.1 Migration、发布与回滚规则

1. 已提交的 Flyway migration 不修改 checksum；所有修复只新增 forward migration。
2. 普通 schema 变化采用 expand -> migrate/backfill -> contract，并用 fresh、V5/上一批
   upgrade、重复启动和坏数据 preflight 证明。contract 前必须验证上一批应用对扩展后
   schema 的兼容性，或明确把该点标成经演练的 no-return cutover。
3. F1 的明文验证码/凭据 metadata 退役、F2 的 strict token/Cookie/CSRF 激活、F4 的
   已暴露 key revoke 属于安全 no-return cutover：完成后不得通过部署旧应用重新产生或
   接受不安全状态。回滚手段是停止流量、清理客户端状态、重新认证、恢复未泄露备份中的
   非凭据数据和部署 forward-fix，不是 Flyway down migration 或恢复旧密钥/旧 token。
4. F1 开始前记录当前可验证 commit SHA、依赖锁和构建产物摘要，作为唯一 pre-F1
   compatibility fixture；每批记录自己的候选 SHA、migration 版本、schema 指纹和
   rehearsal 结果，避免用移动的 branch name 充当回滚证据。
5. 所有 rehearsal 只使用 disposable PostgreSQL 16 和脱敏 fixture；`blacksheep_dev`
   继续只允许已授权的只读检查，不属于自动化发布或回滚测试目标。
6. root 或邮件组件新增 migration 时，同批更新双方 shared-schema peer-history inventory、
   Java/Shell runtime guard、Flyway baseline guard、HTTP migration 断言和受影响的
   backup/restore inventory；任何一处仍只接受旧 V1-V7/V1-V5 链都必须使门禁失败，
   不能通过放宽为“存在 history 即接受”来绕过精确版本与 relation 校验。

F1-F5 每批通过固定范围验收后进入下一冻结步骤，不分别执行三轮无修改检查。五批
全部完成并通过统一阶段硬门槛后，独立的整体实现检查必须遵守：

```text
counter = 0
while counter < 3:
    检查 F1-F5 整体实现、测试、配置、迁移和文档
    if 发现实质问题:
        修复
        重跑受影响门槛
        counter = 0
    else:
        在会话中输出时间、范围、发现、处理和结果
        counter += 1
```

无问题轮次不修改仓库文档。行号和纯格式细节不触发重置；正确性、安全性、可执行性、
覆盖缺口、错误声明和门禁伪成功会触发重置。

## 7. 原计划退出条件

以下是原 F1-F5 整体计划的退出条件，现作为阶段完成状态核对参考；它不扩展冻结范围，
也不阻止统一阶段退出检查通过后结束加固：

1. F1-F5 全部完成，没有新增第六批。
2. 现有功能的关键成功、失败、并发、重放、持久化和恢复路径有自动化证据。
3. PostgreSQL/Flyway 仍是唯一 schema 写入者，测试不连接共享开发库。
4. 浏览器长期 refresh 凭据不可被 JavaScript 读取，Cookie/Bearer/CSRF 边界无歧义。
5. 邮件 challenge、投递、消费、失败和清理状态可恢复且不形成账户枚举。
6. OAuth2/Web3 登录与绑定意图明确，provider identity 不依赖属性猜测。
7. Maven、npm、Python 供应链门禁真实运行；高/严重风险清零或有严格、限时例外。
8. prod 配置、密钥、readiness、migration、backup/restore 和 rollback 演练通过。
9. 完整统一门禁通过，随后连续三轮无问题、无修改检查通过。
10. live 文档与当前实现一致，提交不含 secret、数据库数据、私钥、报告或生成物。

## 8. 加固后 backlog

以下事项在退出后按正常优先级处理，不影响本文完成：

- Spring Authorization Server 与自定义 JWT 的产品级统一。
- MFA、Passkey、新 provider、多租户和管理后台。
- 审计查询 UI、SIEM、合规报表和长期 retention 产品化。
- Python 资源服务器生产化、细粒度授权和独立发布。
- 全量框架大版本升级、Java 21/Boot 4 迁移。
- 容量、压测、多区域、自动灾备和真实 provider/SMTP 发布验收。
- `blacksheep_dev` baseline apply。

## 相关文档

- [全面加固实施规划](HARDENING_IMPLEMENTATION_PLAN.md)
- [历史下一轮实施计划](NEXT_HARDENING_IMPLEMENTATION_PLAN.md)
- [验证指南](../VERIFICATION.md)
- [配置基线](../CONFIGURATION.md)
- [当前架构](../ARCHITECTURE.md)
- [文档导航](../README.md)
