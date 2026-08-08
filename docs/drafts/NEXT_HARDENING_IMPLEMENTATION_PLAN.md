# UniAuth 下一轮加固实施计划

> 状态：Batch A、Batch B1、Batch B2a、Batch B2b、邮件服务边界、邮箱 challenge
> 投递接受/原子消费、邮件 API 敏感响应、API key 单值鉴权和 Batch C 认证 Cookie/
> 浏览器 refresh 存储预备切片已完成；Batch C 原子切换待下一轮冻结
> 事实基线：2026-08-07；邮件 SMTP、持久化投递和限流异常路径增量：2026-08-08
> 范围：只加固、修复和验证现有功能，不增加新的用户功能
> 前置成果：PostgreSQL-only、Flyway V1 baseline + V2 + V3 + V4、Testcontainers、
> Java/Shell/Playwright/Python 与邮件参考服务基础门禁

## 1. 目标

下一轮先把现有功能放到足够强的自动化保护下，再实施数据库约束、token、
HTTP 安全、邮箱和 Web3 正确性修复。顺序不可倒置：

1. 扩充集成测试、Shell E2E 和 Playwright，形成现有功能覆盖矩阵。
2. 通过 PostgreSQL V5+ migration 继续加固身份数据不变量，并建立 G1 要求的最小持久
   安全审计基座。
3. 收敛 JWT、refresh、blacklist、logout、cookie、CSRF、CORS 和 OAuth2 redirect。
4. 修复邮箱验证码、密码重置和 Web3/SIWE 的并发、重放与失败语义。
5. 每批均通过固定门禁和连续三轮无修改检查后再进入下一批。

本计划是 [全面加固实施规划](HARDENING_IMPLEMENTATION_PLAN.md) 的下一轮执行切片。
总路线图继续保存完整背景，本文件负责实际顺序、测试清单和退出条件。

## 2. 当前已验证基线

| 项目 | 当前事实 |
|------|----------|
| 数据库 | `dev`、`test`、`prod` 只支持显式 PostgreSQL |
| Schema owner | Flyway，runtime location 为 `db/migration/postgresql` |
| 当前 migration | V1 baseline + V2 登录方式约束 + V3 登录方式 revision CAS + V4 实体约束/索引对齐 |
| Flyway history | `uniauth_flyway_schema_history` |
| ORM/初始化 | Hibernate `validate`；SQL init 和 Spring Session 自动建表关闭 |
| Java | `mvn clean compile test-compile` 和 127 tests 已通过 |
| 邮件参考服务 | 129 tests；22 个完整 ApplicationContext E2E；Java runtime guard 24 tests；Shell runtime 27/27、HTTP 10/10、Flyway guard 11/11 |
| HTTP E2E | `scripts/test-http-e2e.sh` 15/15 已通过 |
| Flyway guard | `scripts/test-flyway-baseline-guard.sh` 13/13 已通过 |
| 前端 | 严格 `npm ci`、high/critical audit、ESLint、TypeScript、生产构建、21 个 Mock Playwright tests 已通过 |
| Python | 16 个离线 JWT/JWKS/Flask tests 和 8 个邮件 REST stub contract tests 已通过 |
| 统一入口 | `scripts/verify.sh` 本地通过；CI 使用同一入口 |
| 既有库演练 | `blacksheep_dev` 只读 rehearsal 已通过 |
| Schema fingerprint | `12c67edaba1ca20833c0db634226b2cd3d9c07549cc8c9a390a5ff2df5eadebe` |

重要边界：

- `blacksheep_dev` 尚未执行 Flyway baseline apply。
- rehearsal 只读源库，所有恢复、baseline 和 fresh migration 写入均发生在一次性容器。
- 未经用户显式授权，不创建开发库 history table，不运行 apply。
- 不调用真实 Google/GitHub/X、真实邮件服务或任何高成本外部模型。

## 3. 当前覆盖与缺口

### 3.1 已有后端覆盖

- fresh PostgreSQL 执行 Flyway V1→V4，Hibernate validate。
- 既有 V1 schema baseline 后执行 V2/V3/V4 并启动应用。
- Spring Session JDBC create/read/delete。
- 本地注册、登录、错误密码、refresh、access/refresh type confusion。
- `/api/user`、登录方式查询、设置 primary、删除、添加本地方式。
- 登录方式并发 bind/set-primary、并发删除，以及删除与 set-primary 的组合竞争。
- 邮箱注册、真实持久化验证码、密码重置。
- Web3 本地签名登录、nonce replay、钱包绑定。
- `/api/auth/**` allowlist、未知/已删除路由 fail closed。
- JWT key 文件权限、损坏文件和重载。

### 3.2 已有 Shell E2E 覆盖

- 真实 `start.sh` 启动。
- disposable PostgreSQL 和 Flyway history。
- 本地登录、JWT、refresh、Web3、邮箱、logout cookie、最终数据库不变量。
- 真实并发登录方式 mutation 的 `200/409` 和 CAS revision 结果。

### 3.3 已有 Playwright 覆盖

- 本地登录成功/失败。
- 邮箱注册和验证码 UI。
- 忘记密码。
- OAuth2 callback 成功/失败。
- 登录方式 UUID 操作。
- 登录方式 `409` 并发冲突保持可见且不错误修改列表。
- Python 资源服务器页面 Mock。
- 浏览器无钱包时的 Web3 失败提示。

### 3.4 P0 自动化目标

下表同时包含两类自动化：

- 当前已有且应继续工作的行为：在 Batch A 中补成通过型集成/E2E 特征测试。
- 当前明确缺失的安全与并发语义：Batch A 先固化夹具、输入矩阵和预期契约，
  在所属 Batch B/C/D 中先运行失败测试，再实施修复并转绿。

不得把当前不安全行为写成长期正确断言，也不得用 `disabled`、条件跳过或弱化断言让
门禁表面通过。

| 领域 | 必须纳入自动化的测试 |
|------|----------|
| OAuth2 | provider 主体解析、登录/绑定分支、错误回调、redirect allowlist、未知 provider |
| JWT | issuer、audience、type、expiry、tamper、missing user、header/cookie 冲突 |
| Refresh/logout | 并发 refresh、旧 refresh replay、logout 后撤销、introspection 与资源服务器一致性 |
| CORS/CSRF/cookie | allow/deny origin、preflight、cookie Secure/SameSite、cookie 认证写请求 |
| 登录方式 | 并发 bind、并发 set-primary、删除与 primary 切换组合竞态、跨用户所有权 |
| Email | 无效 purpose/输入、重试耗尽、频控并发、投递失败、事务失败、重复 pending code |
| Web3 | domain/address/URI/nonce/chain/expiry 篡改、并发 replay、nonce 覆盖、过期清理 |
| Flyway | checksum 变化、缺表、额外 auth 结构、错误 history、失败 migration 恢复 |
| Frontend | 401 自动刷新、refresh 失败、logout、路由恢复、Mock wallet 登录/绑定 |
| Tooling | ESLint 配置、统一验证入口、CI 中非零测试数量和生成物检查 |

## 4. 执行批次

### Batch A：测试基础扩充

本批不修改对外业务语义，只增加通过型特征测试、测试夹具、覆盖矩阵和必要的
可测试性边界。对于已知尚未实现的目标语义，本批定义可执行输入、并发编排方式和
预期契约；对应测试在 Batch B/C/D 的修复开始时先加入并确认因目标缺口而失败，
随后与修复一同转绿。任何批次都不得提交失败、跳过或仅记录日志的门禁测试。

以下 A1-A4 清单中的现有成功/拒绝路径在本批直接落地；明确依赖后续数据库约束、
撤销模型、CSRF/redirect 收敛、可靠邮件状态机或完整 SIWE challenge 的条目，
在本批完成夹具和用例设计，在所属修复批次落地为 red-green 测试。

#### A1. 后端 PostgreSQL 集成测试

1. 所有 Spring 行为测试继续继承 `PostgreSqlIntegrationTest`，不得读取 `.env`。
2. 为 JWT 建立参数化矩阵：
   - 正确 access token。
   - refresh token 不能访问资源。
   - 错 issuer、audience、type。
   - 过期、篡改、缺少 `userId/sub`。
   - Authorization header 与 cookie 同时存在且身份不同。
3. 为登录方式建立真实并发测试：
   - 同一 provider subject 并发绑定。
   - 同一用户并发设置不同 primary。
   - 两个登录方式并发互删。
4. 为 email 建立失败与并发矩阵：
   - 错误 code、重试耗尽、过期、重复发送。
   - 同一 email/purpose 并发发送。
   - 邮件服务 `FAILED`、`RATE_LIMITED`、不可用和异常。
   - 持久化失败时不报告可用 challenge。
5. 为 Web3 建立 tamper/replay 矩阵：
   - message 中的 domain、address、URI、chainId、nonce、issuedAt、expiration 任一被改动。
   - request `nonce` 与 message nonce 不一致。
   - 同一 challenge 并发提交。
   - 新 nonce 覆盖旧 nonce后的明确语义。
6. 为 OAuth2 success handler 使用 mock principal/client：
   - Google/GitHub/X 稳定 subject。
   - 登录与显式绑定必须区分。
   - provider account 已绑定其他用户时失败。
   - redirect 只允许配置 allowlist。

不得用真实 provider 调用替代上述测试。

#### A2. Shell HTTP E2E

扩展 `scripts/test-http-e2e.sh`，保持单脚本、自清理、一次性 PostgreSQL：

1. 启动后重启一次，确认 migration 幂等且用户数据保留。
2. 覆盖登录方式添加、设置 primary、删除和最后一个方式拒绝。
3. 覆盖 JWT issuer/audience/type 失败状态。
4. 覆盖 cookie-only、header-only，以及 header/cookie 冲突策略。
5. 覆盖邮件失败模拟和验证码重试耗尽。
6. 覆盖 Web3 message 篡改与并发 replay。
7. 结束时检查：
   - Flyway history 只有预期版本。
   - 无残留未消费 nonce。
   - 登录方式 primary 不变量成立。
   - 没有连接或修改 `blacksheep_dev`。

为 baseline guard 增加独立 Shell 测试：

- 匹配结构 rehearsal 成功。
- 缺表、额外 auth 列、错误 PostgreSQL major、已存在 history 时失败。
- Maven/Flyway 失败时临时凭据文件被删除。
- `apply` 缺少精确 confirmation token 时失败。

#### A3. Playwright

Playwright 继续使用 route/mock 和进程环境变量，不写持久 `.env.local`：

1. Mock EIP-1193 wallet，覆盖 Web3 登录成功、拒绝签名和绑定。
2. 覆盖 access token 过期后的单次 refresh 与原请求重试。
3. 覆盖 refresh 失败后的稳定登出状态，禁止重试循环。
4. 覆盖 logout 清理应用自己的认证状态，不清空无关 localStorage。
5. 覆盖 OAuth2 callback 在 React StrictMode 下只处理一次。
6. 覆盖登录方式列表的所有命令及错误状态。
7. 验证请求 method、path、body、Authorization 和 with-credentials 契约。

#### A4. Python 与跨语言契约

1. 保持离线 JWKS，不访问历史域名。
2. 增加 access/refresh type confusion、错误 issuer/audience/kid、过期和 key rotation
   兼容性测试。
3. 后端生成的固定测试 token 必须可由 Python 验证；非法 token 在两端结论一致。

#### A5. 质量工具

1. 增加与当前 TypeScript/React 版本匹配的 ESLint 配置。
2. 固化一个统一验证入口，串行执行：
   - Java compile/tests。
   - Shell syntax 和相关 E2E。
   - frontend type/build/lint/Playwright。
   - Python tests。
   - 文档链接和 `git diff --check`。
3. CI 必须报告真实非零测试数量；Docker/Testcontainers 不可用时 release gate 失败。

#### Batch A 退出条件

- 当前已实现 endpoint/flow 的成功、拒绝和持久化路径均有通过型自动化测试。
- 覆盖矩阵中的 P0 项都有已落地测试，或有明确所属修复批次、输入夹具、预期契约和
  不可提前转绿的原因；不得以永久跳过测试代替安排。
- 后续修复批次必须先加入对应失败测试并确认失败原因，再修改实现；真实暴露的问题
  不得通过降低断言掩盖。
- 测试夹具不访问共享数据库和真实外部服务。
- Batch A 实际新增的全部门禁通过后，执行连续三轮无修改检查。

#### Batch A 实际结果

2026-08-07 已完成完整硬门槛：

- PostgreSQL/Testcontainers 后端测试增至 63，新增 JWT issuer/audience/type/expiry/
  tamper/header-cookie 边界、OAuth2 success handler 和 Flyway checksum/failure recovery。
- HTTP E2E 扩展为 13/13，覆盖应用重启、Flyway 幂等与数据保留、登录方式生命周期、
  Web3 message tamper、邮箱 retry exhaustion 和最终数据库不变量。
- baseline guard 独立测试 7/7，覆盖 exact schema、缺表、额外 auth 结构、已有 history、
  错误 PostgreSQL major、缺少 apply confirmation 和临时凭据清理。错误 major 使用
  离线 `psql` fixture，不依赖或支持 PostgreSQL 15 runtime。
- Mock Playwright 扩展为 18 tests，覆盖钱包连接/签名边界、单次 refresh/retry、
  refresh failure 和登录方式错误状态。
- Python 扩展为 9 tests，覆盖 issuer、audience、expiry 和 key rotation。
- 前端依赖审计触发了 Axios、Ethers、React Router、Vite 与相关传递依赖的安全升级；
  `npm audit --audit-level=high` 通过。仍有 2 个 React Router moderate advisories；
  当前客户端导航 pathname 固定为同源值，OAuth 错误只进入编码后的 query，
  不触达公告中的外部输入决定目标 URL 路径；继续跟踪可用的无重叠修复版本。
- ESLint、`scripts/verify.sh` 和 `.github/workflows/verification.yml` 已建立；
  本地统一门禁已通过，远端 workflow 需在本次 push 后执行。

以下目标没有被误写成“已修复”，继续归属后续批次：

- 登录方式删除与 set-primary 等组合并发不变量：Batch B2。
- token blacklist、refresh replay、logout 撤销、cookie/CSRF/CORS/redirect：Batch C。
- Email 投递失败/并发状态机和完整 SIWE 字段绑定/nonce 原子消费：Batch D。
- Python access/refresh type confusion、跨语言固定 token；前端 logout 只清理应用存储、
  Web3 bind 和 StrictMode 单次 callback：与 Batch C/D 修复同步补齐。

### Batch B：PostgreSQL V2+ schema 与并发不变量

Batch A 通过后才开始。

#### Batch B1：登录方式约束与 bind/set-primary 并发

本切片只处理可通过 PostgreSQL 约束、显式条件更新和稳定冲突映射可靠保证的
登录方式不变量，不把全部 Batch B 风险一次混入。

##### B1.1 已完成的数据预检

2026-08-07 对 `blacksheep_dev` 执行了只读查询。数据库为 PostgreSQL 16.8、
`TimeZone=Etc/UTC`，尚无 `uniauth_flyway_schema_history`。结果：

- 无缺失登录方式或 primary 数量不为 1 的用户。
- 无重复 `(user_id, auth_provider)`、`(auth_provider, provider_user_id)` 或
  `local_username`。
- 无未知 provider、登录方式必需字段 null、无效 LOCAL/provider 行形状。
- 当前没有 `LOCAL + local_password_hash IS NULL` 记录，但代码允许邮箱验证码绑定
  创建这种 passwordless LOCAL 记录；V2 不得错误要求所有 LOCAL 行都有密码哈希。
- 按“未使用且未过期”统计没有重复 email challenge。该结果不足以定义可靠的
  challenge 唯一性和清理策略，因此 email 约束仍归 Batch D。

该查询只证明当前数据可进入 B1，不代替 migration 内的 fail-fast preflight。
不得对共享 dev 库执行 baseline 或 migration apply。

##### B1.2 固定实施范围

1. 新增不可修改的 Flyway V2：
   - migration 开始时检查 provider、必需字段、行形状和重复 primary。
   - `user_id`、`auth_provider`、`is_primary`、`is_verified`、`linked_at` 与实体
     nullability/default 对齐。
   - `linked_at`、`last_used_at` 从无时区列按已验证的 UTC 语义迁移为
     `TIMESTAMP WITH TIME ZONE`。
   - 增加 provider 枚举 check、LOCAL/provider 行形状 check。
   - 增加每用户至多一个 primary 的 partial unique index。
2. 修正 `UserLoginMethod` 的 `user` 与时间列映射。
3. bind 继续以数据库唯一约束为最终并发裁决：
   - 保留快速存在性检查以提供常见路径错误。
   - 强制 flush 捕获并发唯一冲突。
   - 按约束名映射为稳定业务错误，不返回 SQL 或数据库细节。
4. set-primary 改为显式数据库更新：
   - 校验目标属于当前用户。
   - 清除旧 primary 后设置目标，并在返回前 flush。
   - 并发竞争允许一个请求收到稳定的可重试冲突；最终必须恰好一个 primary。
5. 顺序删除 primary 必须兼容新的唯一索引；先清除旧 primary、选定替代项，再删除。
6. 测试先行覆盖 fresh V1 -> V2、existing baseline V1 -> V2、坏数据阻断、
   schema 约束、并发 bind、并发 set-primary 和 HTTP 错误契约。

##### B1.3 明确非目标

- 删除与其他登录方式变更并发时产生零登录方式或零 primary 的 write-skew 不在
  B1 宣称修复；该问题需要单独选择非悲观锁方案并归入 Batch B2。
- 不增加 JPA `@Version`，不默认使用 `SELECT FOR UPDATE` 或其他悲观锁。
- 不增加 email challenge partial unique index。
- 不创建安全审计/outbox 表。
- 不修改 token、cookie、CORS、CSRF、OAuth2 redirect、邮件或 SIWE 功能语义。
- 不对 `blacksheep_dev` 或其他共享数据库执行写操作。

##### Batch B1 退出条件

- fresh 数据库从 V1 升级到 V2，existing schema baseline V1 后升级到 V2。
- migration 对重复 primary、未知 provider、null 必需字段和非法行形状 fail closed。
- PostgreSQL 直接拒绝同一用户两个 primary 和非法登录方式行。
- 同一 subject/同一 provider 的并发 bind 只有一个持久结果，失败方获得稳定业务错误。
- 并发 set-primary 后恰好一个 primary；任何失败是稳定、可重试的业务冲突而非 500
  或数据库错误泄漏。
- 原有登录方式 HTTP 生命周期、Shell E2E、前端和跨语言门禁无回归。
- 完整统一门禁通过后，连续三轮固定范围无修改检查通过。

##### Batch B1 实际结果

2026-08-07 已完成实现与完整硬门槛：

- 新增不可修改的 Flyway V2，fresh V1→V2 与 existing baseline V1→V2 均通过。
- migration 对 primary 数量、null runtime 字段、未知 provider 和非法行形状 fail closed；
  `linked_at`/`last_used_at` 已迁移为 `TIMESTAMP WITH TIME ZONE`。
- PostgreSQL 已直接约束 provider/行形状和每用户至多一个 primary，同时保留
  passwordless LOCAL 合法形状。
- OAuth2 bind 使用数据库唯一约束作为并发裁决；同一 subject 跨用户竞争、同一用户
  同一 provider 的不同 subject 竞争，以及同一用户同一 subject 重放均返回真实且稳定
  的业务错误。
- set-primary 使用显式数据库更新；并发测试得到一个 `200`、一个稳定 `409`，
  最终恰好一个 primary，未使用悲观锁或 JPA `@Version`。
- Shell HTTP E2E 13/13、Flyway baseline guard 10/10、Java 74 tests、
  Mock Playwright 18/18、Python 9/9 和统一验证入口全部通过。
- `blacksheep_dev` 只执行过只读数据/结构预检，未 baseline、未 apply、未写入。
- baseline apply 在 rehearsal 后重新执行只读数据预检；竞态 fixture 已证明数据变化时
  会在创建 Flyway history 前失败关闭。
- 如果数据在 baseline 创建后、V2 migrate 前变化，V2 仍会 fail closed；脚本仅在
  受管 schema 未变且 history 为 baseline-only 时移除不完整 history。独立竞态
  fixture 已覆盖该失败恢复路径。

完整门禁后已连续完成三轮固定范围检查，期间无问题、无修改，本批退出条件满足。
删除与 set-primary 等组合并发的 write-skew 未在 B1 中修复，继续归 Batch B2。

#### Batch B2：登录方式删除并发保护与后续 schema 对齐

##### 2026-08-07 本轮固定范围：Batch B2a

本轮只加固登录方式删除与 set-primary 的组合并发，不扩展用户可见功能。

纳入范围：

1. 新增 Flyway V3，在 `users` 增加非负、非空、默认 `0` 的
   `login_methods_revision`，作为登录方式集合变更的用户级乐观 CAS token。
2. `removeLoginMethod` 和 `setPrimaryLoginMethod` 必须先读取 revision，再读取并验证
   当前登录方式状态，最后用带预期 revision 的条件更新取得本次变更权。
3. CAS 失败统一映射为稳定 `409`，不返回 SQL、约束或锁实现细节。
4. PostgreSQL HTTP 集成测试至少覆盖：
   - 两个登录方式被并发删除；
   - 删除 primary 与设置另一方式为 primary 并发；
   - 删除目标方式与把同一目标设为 primary 并发。
5. Shell HTTP E2E 必须实际并发调用登录方式 API，并验证最终至少一个登录方式且
   恰好一个 primary。
6. Flyway fresh/baseline adoption、baseline guard 和统一验证入口必须识别 V3。

明确不纳入本轮：

- `users`、email code、Web3 nonce、token blacklist 的其余 schema 对齐与索引清理；
- 最近认证、token security version、审计/outbox；
- email service、Web3、OAuth2 或前端的新功能与行为改写。

本轮验收要求：

- 不使用悲观锁，不增加 JPA `@Version`；
- 并发竞争允许一个请求成功、另一个返回可重试 `409`，不得返回 `500`；
- 每个场景结束后数据库中至少一个登录方式且恰好一个 primary；
- 完整门禁通过后，执行连续三轮无问题、无修改检查。

##### Batch B2a 实际结果

2026-08-07 已完成实现与完整硬门槛：

- 新增不可修改的 Flyway V3；fresh V1→V3 与 existing baseline V1→V3 均通过，
  baseline rehearsal/guard 的 restored 与 fresh 路径都验证 V3 history。
- `removeLoginMethod` 与 `setPrimaryLoginMethod` 使用
  `users.login_methods_revision` 条件更新取得用户级变更权；未使用悲观锁或 JPA
  `@Version`。
- PostgreSQL HTTP 集成测试共 7 个并发场景，包含并发 delete/delete、
  delete-primary/set-other-primary 和 delete-target/set-same-target-primary。
- Shell HTTP E2E 13/13 会安装一次性延迟 trigger，真实并发调用 API，验证一个
  `200`、一个稳定 `409`、revision 只被领取一次，并保持登录方式最终不变量。
- Mock Playwright 18/18 覆盖 `409` 提示保持可见且列表不被错误修改。
- 完整 `scripts/verify.sh` 已通过：Java 77、邮件参考服务 59、Flyway guard 10/10、
  Playwright 18、Python 9，以及编译、lint、typecheck、生产构建和文档链接检查。

连续三轮无问题检查按验证规则只在当次工作报告逐轮输出；无问题轮次不修改本文。

##### 2026-08-07 本轮固定范围：Batch B2b

本轮只通过 Flyway V4 对齐现有实体契约和查询索引，不改变任何 endpoint、认证流程、
验证码、Web3 nonce 或 token blacklist 的业务语义。

只读事实基线：

- `blacksheep_dev` 是 PostgreSQL 16.8，`TimeZone=Etc/UTC`，仍无
  `uniauth_flyway_schema_history`。
- `users` 3 行、`web3_nonces` 2 行、`email_verification_codes` 27 行、
  `token_blacklist` 0 行。
- `users.email_verified/enabled/created_at/updated_at`、
  `web3_nonces.created_at`、`email_verification_codes.is_used/retry_count` 和
  `token_blacklist.token_type/blacklisted_at` 当前均无 `NULL`。
- 规范化 email/username 无重复；同一规范化 `(email,purpose)` 无多个未使用且未过期
  challenge。该结果只用于 migration 可进入性判断，不提前实施 Batch D 的 challenge
  唯一性或 canonicalization 语义。
- 以上查询使用 read-only transaction 和
  `default_transaction_read_only=on`，未创建 history、未 apply、未修改共享库。

纳入范围：

1. 新增不可修改的 Flyway V4：
   - 将 `users.email_verified`、`enabled`、`created_at`、`updated_at` 设为非空，
     保留既有默认值和 `LocalDateTime` 对应的无时区类型。
   - 将 `web3_nonces.created_at` 设为非空，保留 `Instant` 对应的带时区类型。
   - 将 `email_verification_codes.is_used/retry_count` 设为非空并补齐
     `false/0` 默认值；增加 retry count 非负约束。
   - 将 `token_blacklist.token_type/blacklisted_at` 设为非空；增加现有
     `ACCESS/REFRESH/ID` 枚举约束，时间类型继续与 `LocalDateTime` 一致。
2. 为当前 email repository 查询增加：
   - pending challenge 最新记录 lookup 索引；
   - email 日发送计数索引；
   - 过期清理索引。
3. 只删除可由同列 unique index 或同定义 canonical index 完全覆盖的重复索引：
   - users 的 email/username 非唯一重复索引；
   - Web3 wallet address 非唯一重复索引；
   - token blacklist 的重复 jti/expiry 索引。
4. 增加独立 V4 preflight SQL；baseline rehearsal 和 apply 写入前必须同时重查
   V2 登录方式数据与 V4 实体契约数据。
5. PostgreSQL 集成测试覆盖 fresh V1→V4、existing baseline V1→V4、
   V3→V4、坏数据阻断、约束/default、目标索引存在和重复索引消失。
6. Shell HTTP E2E 与 Flyway baseline guard 必须识别 V4；guard 增加 V4 坏数据在
   创建 history 前 fail closed 的一次性 fixture。

明确不纳入本轮：

- 不将 token blacklist 接入验证、refresh 或 logout。
- 不改变 email code 发送、消费、重试、频控或投递失败语义。
- 不改变 Web3 message、nonce 覆盖或消费并发语义。
- 不增加 pending email challenge 唯一索引，不实施 canonical email。
- 不删除 `user_login_methods` 的历史 Web3 列；expand/contract 单独评审。
- 不引入悲观锁、JPA `@Version`、新表或用户可见功能。
- 不对 `blacksheep_dev` 或其他共享数据库执行 baseline/apply/write。

本轮验收要求：

- V4 对目标坏数据 fail closed，错误信息不得包含敏感数据。
- Hibernate `validate`、fresh migration、existing baseline adoption 和失败恢复通过。
- email 查询所需索引可由 PostgreSQL catalog 精确验证；被删除索引必须有等价覆盖。
- HTTP 业务响应保持不变，完整 Java/Shell/Playwright/Python/邮件服务门禁无回归。
- 完整门禁通过后执行连续三轮无问题、无修改检查。

##### Batch B2b 实际结果

2026-08-07 已完成实现与完整硬门槛：

- 新增不可修改的 Flyway V4；fresh V1→V4、existing baseline V1→V4 和 V3→V4
  均通过，Hibernate `validate` 无 schema 漂移。
- V4 对齐目标 nullability/default/check，建立 3 个 email repository 索引，并删除
  users、Web3 nonce 和 token blacklist 中有等价覆盖的 6 个重复索引；未改变 endpoint
  或认证、验证码、Web3、blacklist 业务语义。
- baseline 脚本增加独立只读 V4 preflight，并在 rehearsal 初始阶段和 apply 写入前
  重查；guard 的坏数据 fixture 验证创建 Flyway history 前 fail closed。
- 完整 `scripts/verify.sh` 已通过：Java 83、邮件参考服务 59、HTTP E2E 13/13、
  Flyway guard 11/11、Playwright 18、Python 9，以及编译、lint、typecheck、生产构建、
  Shell/Python 静态检查和文档链接检查。
- `blacksheep_dev` 仅执行只读事实核验；未创建 history、未 baseline/apply、未写入。

连续三轮无问题检查按验证规则只在当次工作报告逐轮输出；无问题轮次不修改本文。

##### B2.1 数据预检

在任何后续 migration 前，对目标库执行只读报告：

- 零/多个 primary 的用户。
- 无登录方式用户。
- 重复规范化 email/local username。
- 同一 `(email,purpose)` 多条未使用 code。
- 其余 entity 声明非空但数据库为 null 的记录。
- 超长 ID/subject 和未映射 Web3 字段。

发现冲突时停止 migration。自动修复规则必须单独评审，不能依赖查询返回顺序。

##### B2.2 后续 migration

1. 对齐 `users`、`web3_nonces`、`email_verification_codes`、`token_blacklist`
   的 nullability/default。
2. 将其余 Java `Instant` 对应列迁移为 `TIMESTAMP WITH TIME ZONE`，保留
   `LocalDateTime` 对应无时区列。
3. 为 email code 查询和过期清理增加索引。
4. 删除确认冗余的 users/blacklist 索引，但保留唯一性。
5. 处理 V1 中未映射的 Web3 列时采用 expand/contract，不在同一版本直接破坏旧代码。

##### B2.3 并发策略

- 优先数据库唯一约束、条件更新和 CAS。
- 不把悲观锁作为默认方案。
- 不为了形式引入 JPA `@Version`。
- 删除与 set-primary 等组合并发必须在测试下保持“至少一个登录方式且恰好一个
  primary”的业务不变量。
- 唯一冲突必须映射为稳定业务错误，不返回数据库细节。

#### Batch B3：最小持久安全审计基座

本工作只建立内部安全控制基础，不增加用户可见审计功能：

1. 通过独立 V2+ migration 建立不可变 security audit event 与可推进状态的
   transactional outbox/delivery 表，不把二者混成可任意更新的 JSON 日志表。
2. 建立单一 audit service/API，供后续 token、登录方式、email、Web3 和密钥状态变更
   复用；后续批次不得各自创建不兼容事件格式。
3. 事件只保存内部 ID 或带版本不可逆摘要、事件类型、结果、时间、request id、
   auth method 和稳定原因码；禁止保存原始 email/username/wallet、token、code、nonce、
   OAuth state/PKCE verifier、cookie/session、签名、私钥或完整 provider 响应。
4. 关键安全状态变更必须能与 audit row/outbox 在同一事务中失败关闭；本批先完成
   migration、append API、故障注入和不可变性测试，具体事件生产者随 Batch C/D 接入。
5. 定义 runtime 与 retention 权限/运行手册；自动化测试验证业务角色不能更新或任意删除
   已提交事件，retention 路径不能修改事件正文。

#### Batch B2/B3 退出条件

- fresh V1 -> 最新 V2+ 和 existing baseline V1 -> 最新 V2+ 均通过。
- migration 重复执行、失败恢复和 forward-fix 演练通过。
- schema/entity mapping 和并发矩阵通过。
- audit migration、append/outbox、事务回滚、不可变性和权限拒绝测试通过。
- G1 数据门禁完成；Batch C 不得绕过审计接口另建日志式替代品。
- 生产目标只生成预检报告，不自动 apply。

### 邮件服务边界加固切片

#### 2026-08-07 固定实施范围

本切片只加固 UniAuth 到外部邮件服务的 HTTP 边界和仓库内参考实现，不改变邮箱
challenge、注册、密码重置或登录的用户可见业务语义。

纳入范围：

1. UniAuth 邮件客户端使用独立 `RestTemplate`，实际绑定 connect/read timeout，
   支持可选 `X-Email-Service-Key`，验证绝对 HTTP/HTTPS URL 的 host/userinfo/
   query/fragment 约束，并覆盖真实本地 HTTP 超时和 header 契约。
2. 参考邮件服务增加可选 API key；非 loopback 暴露缺少密钥时必须在启动阶段失败。
3. 参考服务拒绝不安全 env 文件、共享数据库和不符合 profile 的数据库目标，并在
   Flyway 前通过 Java runtime guard 重复校验，避免绕过 Shell 入口。
4. HTTP 输入增加收件人、主题、模板、batch、HTML 和分页边界；异常响应不得泄露
   SMTP、数据库或内部 exception 细节；日志分页下推到数据库。
5. 队列 retry 上限改为配置驱动；event 与 scheduled recovery 共用单进程限流，
   通过条件更新原子 claim，event 不得绕过 `next_retry_time`，stuck `PROCESSING`
   可由恢复扫描重新 claim。
6. 禁用邮件或队列时拒绝继续入队，不能返回空对象伪装成功。
7. 参考服务新增不可修改的 Flyway V2，约束 retry 边界，建立日志外键和恢复/分页索引。
8. 根统一门禁纳入参考组件 Shell syntax、Maven、runtime guard、真实进程 HTTP E2E
   和 Flyway fail-closed guard；同步扩充根 Shell、Playwright 和 Python 契约覆盖。

明确非目标：

- 不把 UniAuth 邮件 challenge 改造成可靠 outbox/idempotency 状态机。
- 不改变“外部服务 `success=true` 只表示接受或入队”的现有语义。
- 不调用真实 SMTP、真实 OAuth provider，不连接或写入 `blacksheep_dev`。
- 不实现多实例分布式限流、供应商退信、生产备份/灾备或密钥轮换协议。
- 不增加用户功能、公共 endpoint、悲观锁或 JPA `@Version`。

#### 实际结果

2026-08-07 已完成实现与完整硬门槛：

- UniAuth 专用邮件客户端的 URL、超时和 API key 均由类型化配置绑定；真实本地 HTTP
  测试验证 template/health header 和 read timeout。
- 参考服务 API key、输入边界、错误脱敏、数据库分页和 Java/Shell 双层启动保护通过。
- Flyway V2 fresh/upgrade/坏数据/dirty schema/forward-fix 路径通过。
- event 与 recovery 使用同一限流器和独立事务 claim/delivery；配置化 retry、
  delayed retry、优先级恢复、stuck recovery、并发 claim 单投递者、禁用队列拒绝
  和邮件/队列关闭时不恢复存量均有自动化覆盖。
- simple 请求 HTML 和模板渲染后的最终 HTML 都有 1,000,000 字符上限；Flyway
  checksum 失配会失败关闭并保留已迁移数据；配置、实体、事件和请求 DTO 不通过
  自动 `toString()` 暴露 API key、收件人、验证码或 HTML。
- 根 Java 98 tests、HTTP E2E 14/14、Flyway guard 12/12、Mock Playwright 19、
  Python 14 全部通过。
- 参考邮件服务 94 tests；另有 Shell runtime 15/15、HTTP/PostgreSQL 8/8 和
  Flyway guard 8/8。
- 邮件服务统一入口使用进程专属临时源码快照，已由两套完整门禁并行运行验证，
  消除了共享 `target/` 被并发 `mvn clean` 删除的验证竞态；源文件变化时失败关闭。
- 所有自动化只使用 disposable PostgreSQL、本地 HTTP、进程内 GreenMail 和离线
  JWT/JWKS fixture；未访问共享数据库或真实外部服务。

连续三轮无问题检查按验证规则只在当次工作报告逐轮输出；无问题轮次不修改本文。

### 邮件服务 SMTP 传输安全加固切片

#### 2026-08-08 固定实施范围

本切片只加固参考邮件服务的 SMTP 配置、运行保护、测试和运维文档，不改变模板、
队列、重试、HTTP 契约或 UniAuth 邮箱业务流程。

纳入范围：

1. 新增 `SMTP_SSL_CHECK_SERVER_IDENTITY`，默认启用，并确认属性进入真实
   `JavaMailSender` Bean。
2. Java/Shell 双重 runtime guard 拒绝 required STARTTLS 脱离 enable、STARTTLS
   与 implicit SSL 同时启用，以及非法布尔值。
3. `prod` 只允许强制 STARTTLS 或 implicit SSL，并强制 server identity
   verification；`dev/test` 保留隔离本地 SMTP 的显式明文能力。
4. 扩充 Java guard、Spring ApplicationContext、Shell runtime、HTTP/Flyway
   启动夹具和统一验证入口回归。
5. 同步 `.env.example`、组件 README/AGENTS、根配置、开发、验证、文档导航和代理规则。

明确非目标：

- 不连接真实 SMTP，不验证真实证书链、TLS 握手或供应商鉴权。
- 不改变邮件内容、模板、队列状态机、重试次数、限流或至少一次投递语义。
- 不改变 UniAuth 到外部 REST 服务的 endpoint、请求/响应或成功语义。
- 不新增用户功能、数据库 migration、悲观锁或 JPA `@Version`。

#### 当前状态

邮件组件独立完整门禁已通过：101 tests、Java runtime guard 17 tests、
Shell runtime 21/21、HTTP/PostgreSQL 8/8、Flyway guard 8/8。本轮收敛发现的
Java/Shell 布尔值解析漂移已通过严格解析和回归测试修复；修复后根统一门禁重新通过
Java 98 tests、HTTP 14/14、Flyway 12/12、Mock Playwright 19 和 Python 14。

### 邮件服务 SMTP endpoint 配置加固切片

#### 2026-08-08 固定实施范围

本切片只补齐参考邮件服务 SMTP host/port 的启动期 fail-closed 保护，不改变模板、
队列、重试、HTTP 契约、Flyway schema 或 UniAuth 邮箱业务流程。

纳入范围：

1. `SMTP_HOST` 只允许最长 255 字符、无 URI 语法、空白或控制字符的 host/IP token。
2. `SMTP_PORT` 只允许 `1..65535` 的十进制整数。
3. Java/Shell 双重 runtime guard 使用一致错误语义，直接 JAR 和受保护 Shell 入口
   都在投递前拒绝配置错误。
4. H2 和 PostgreSQL/GreenMail ApplicationContext 断言有效 host/port 进入真实
   `JavaMailSender` Bean。
5. 扩充 Java guard、Shell runtime guard、组件统一门禁和 live 运维文档。

明确非目标：

- 不解析 DNS，不测试真实网络可达性、SMTP banner、TLS 握手或供应商鉴权。
- 不限制生产 SMTP 必须使用特定端口，不禁止合法的 loopback relay 或 IPv6 host。
- 不改变邮件内容、队列状态机、重试、限流、数据库 migration 或 REST 契约。

#### 当前状态

邮件组件完整门禁已通过：108 tests、Java runtime guard 24 tests、
Shell runtime 27/27、HTTP/PostgreSQL 8/8、Flyway guard 8/8。根统一门禁也已通过：
Java 98 tests、HTTP 14/14、Flyway 12/12、Mock Playwright 19/19、Python 14/14，
前端 lint/type/build 通过。

### 邮件服务持久化队列投递边界加固切片

#### 2026-08-08 固定实施范围

本切片只补齐最终 SMTP 投递对持久化队列载荷的重验证，不改变合法邮件、模板、
REST 契约、状态机、retry 次数、Flyway schema 或 UniAuth 邮箱业务流程。

纳入范围：

1. 最终投递复用当前 recipient、subject 和 1,000,000 字符 HTML 上限校验。
2. `emailType` 与内部 `sendMethod` 进入自定义 MIME header 前必须是有界 ASCII token；
   缺失或空白的历史 `emailType` 继续按 `GENERAL` 处理。
3. 非法持久化行只记录 queue id、通用失败原因和安全占位字段，不把恶意
   recipient、subject、HTML 或 header token 复制到失败审计。
4. PostgreSQL/GreenMail ApplicationContext 直接持久化绕过 HTTP 的异常行，验证
   无 SMTP 副作用、失败日志和现有 retry 语义。
5. Shell HTTP E2E 增加 `emailType` header injection 拒绝契约，并同步 live 文档。

明确非目标：

- 不新增或修改 Flyway migration、数据库约束或队列状态。
- 不改变合法邮件的 MIME/header 内容、发送顺序、重试次数、限流或至少一次语义。
- 不新增 endpoint、模板、幂等协议或真实 SMTP 验证。

#### 当前状态

保护测试先证明带 CR/LF 的持久化 subject 会被真实 GreenMail 接受并发送，并证明
超长注入型 `sendMethod` 会让原失败审计违反 `VARCHAR(20)`、回滚 retry；实现修复后，
定向 PostgreSQL/GreenMail 测试确认 CR/LF subject、过大 HTML、非法 `emailType`
和非法 `sendMethod` 均在 SMTP 前失败关闭，`NULL`/blank 历史 `emailType` 以
`GENERAL` 成功投递。完整邮件组件门禁已通过：110 tests、16 个
PostgreSQL/GreenMail ApplicationContext E2E、Java runtime guard 24 tests、
Shell runtime 27/27、HTTP/PostgreSQL 8/8、Flyway guard 8/8。当前组合工作树的
根统一门禁也已通过：Java 98 tests、HTTP 14/14、Flyway 12/12、
Mock Playwright 19/19、Python 14/14，前端 lint/type/build 通过。

### 邮件服务限流 reservation 异常路径加固切片

#### 2026-08-08 固定实施范围

本切片只修复参考邮件服务 event/recovery 在队列 claim 异常时泄漏单进程限流
reservation 的问题，不改变正常投递、失败 retry、REST 契约、Flyway schema 或
UniAuth 邮箱业务流程。

纳入范围：

1. 先增加 event/recovery 的 claim 异常释放和 delivery 异常消费行为测试。
2. 把“尚未进入 delivery”的 reservation 统一放入 `finally` 释放，覆盖 claim
   返回 false、claim 抛异常和 delivery 返回 `SKIPPED`。
3. 一旦调用 delivery bean 就按一次投递尝试计数；后续失败或异常不归还 slot，
   防止未知 SMTP 结果绕过限流。
4. PostgreSQL/ApplicationContext E2E 使用真实 `EmailRateLimiter`、listener/
   processor、repository 和事务 Bean，只对 claim 方法注入受控异常。
5. 同步组件 README/AGENTS、根架构、配置、验证和文档计划。

明确非目标：

- 不改变成功/失败投递、retry 次数、队列状态机或至少一次语义。
- 不新增或修改 Flyway migration、数据库约束、HTTP endpoint、模板或跨端契约。
- 不实现多实例分布式限流，不连接真实 SMTP 或共享数据库。
- 不为无关的 Shell/Playwright/Python 路径增加伪相关测试；完整统一门禁仍全部重跑。

#### 当前状态

保护测试先证明 event/recovery 的 claim 异常各泄漏一个限流 slot；delivery 异常
继续消费已开始尝试的既有语义正确。实现收拢 reservation 所有权后，15 个定向
event/recovery 行为测试和 18 个 PostgreSQL/GreenMail ApplicationContext E2E
通过。完整邮件组件门禁已通过：116 tests、Java runtime guard 24 tests、
Shell runtime 27/27、HTTP/PostgreSQL 8/8、Flyway guard 8/8。根统一门禁也已通过：
Java 98 tests、HTTP 14/14、Flyway 12/12、Mock Playwright 19/19、Python 14/14，
前端 lint/type/build、文档链接和 patch hygiene 通过。
统一门禁同时暴露并修复了邮箱注册 Playwright 的竞态夹具：验证响应使用 `user.id`，
首页 `GET /api/user` 则按真实契约返回 `userId`。测试现使用同一注册用户的真实 wire
形状并断言 `checkAuth()` 后的稳定状态，定向并发重复 10/10 和完整 19/19 均通过。

### 邮件限流窗口 ownership 与附加 E2E 切片

#### 2026-08-08 固定实施范围

本切片只修复旧窗口 reservation 迟到释放可能扣减新窗口额度的问题，并扩充两个
已有只读/失败关闭契约；不改变正常投递、retry、队列状态机、REST schema 或 Flyway
migration。

纳入范围：

1. 每次受限 acquisition 返回绑定当前窗口 generation 的 reservation。
2. reservation release 幂等，且只归还相同 generation 的额度；释放不依赖执行时
   rate-limit enabled 配置。
3. 单元测试覆盖窗口滚动、临时禁用、当前窗口释放、重复释放和禁用模式。
4. event/recovery PostgreSQL/ApplicationContext E2E 覆盖 claim 内窗口滚动后旧
   reservation 的迟到释放。
5. Shell HTTP E2E 固定 queue detail 不披露 HTML/metadata，并确认当前夹具验证码
   不出现在响应中；不把它描述为对允许返回字段的通用脱敏保证。
6. Java PostgreSQL migration 测试与 Shell Flyway guard 固定 checksum drift
   失败关闭、漂移 history 原样保持、数据不变和显式恢复路径。

#### 当前状态

邮件组件统一门禁已通过：124 tests、20 个 PostgreSQL/GreenMail ApplicationContext
E2E、Java runtime guard 24 tests、Shell runtime 27/27、HTTP/PostgreSQL 9/9、
Flyway guard 9/9。当前组合工作树的根 `scripts/verify.sh` 也已通过：Java 98、
HTTP 14/14、Flyway 12/12、Mock Playwright 19/19、Python 14/14，前端
lint/type/build、文档链接和 patch hygiene 通过。收敛检查补强了 Java 与 Shell
checksum drift 断言。并行根门禁随后暴露共享根 `target/` 的验证竞态：一个
`mvn clean` 可删除另一进程正在使用的测试 class。根统一入口已改为在进程专属临时
Git 快照中执行全部阶段，并在结束前校验原工作区指纹。收敛检查又发现快照清理会
删除 CI 原本尝试从工作区上传的 Playwright trace；统一入口现支持仓库外的
`VERIFICATION_ARTIFACTS_DIR`，按运行隔离保留 Surefire/Playwright 证据；邮件
子门槛显式回传 Surefire XML，根门槛立即检查子状态和报告存在性，CI 从同一根目录
上传。artifact 写入失败必须失败关闭，信号测试固定 `SIGINT=130`、
`SIGTERM=143`；成功证据写入后若最终输出失败，EXIT 清理必须覆写真实非零状态。
提交前必须通过带 artifact 目录和完整日志的相同根门禁。

### Flyway baseline guard 并发隔离切片

#### 2026-08-08 固定实施范围

本切片只修复 existing-schema baseline 运维脚本的临时配置文件隔离和失败诊断，
不改变 migration、schema、数据预检、confirmation token 或 apply 语义。

纳入范围：

1. 使用以 `XXXXXX` 结尾的 portable `mktemp` 模板，兼容 macOS/BSD 和 Linux。
2. exact-schema guard 断言一次 rehearsal 的 5 个 Maven/Flyway 调用分别使用唯一
   配置路径，并确认每个文件都已删除。
3. 预期成功场景失败或预期错误消息不匹配时，在清理临时目录前输出内部日志。
4. 并行运行两套完整 guard，验证不同进程不会覆盖或删除对方的 Flyway 配置。

明确非目标：

- 不修改 V1-V4 migration 或 Flyway 参数。
- 不连接共享开发数据库，不执行 baseline apply。
- 不修改邮件、认证或其他业务实现。

#### 当前状态

旧模板 `uniauth-flyway.XXXXXX.conf` 在 macOS 上产生字面同名路径，并发 guard 会互相
覆盖或删除配置文件。修复后自动唯一性/清理断言通过，两套完整 root Flyway guard
并行通过，各 `12/12`；当前组合工作树随后也通过完整根统一门禁。

### Batch C：JWT、refresh、blacklist 与 HTTP 边界

Batch B 通过后执行，H2.5 与 H3.1/H3.2 作为原子切换批次。

#### 认证 Cookie 与浏览器 refresh 存储预备切片

##### 2026-08-08 固定实施范围

本切片只消除当前签发入口之间已经存在的 Cookie 属性漂移，并让前端遵守现有
HttpOnly refresh Cookie 设计。它是 Batch C 原子切换前的兼容性加固，不宣称完成
H2.5、H3.1 或 H3.2，也不改变现有公开业务流程。

纳入范围：

1. 建立一个统一认证 Cookie writer：
   - local login、邮箱注册完成、邮箱验证完成、Web3 login、OAuth2 success 和
     refresh 都复用同一 access/refresh Cookie 写入逻辑。
   - access/refresh Cookie 固定 `HttpOnly`、`Path=/`、`SameSite=Lax`；Max-Age 从
     `jwt.expires.*` 读取，不在 controller 中重复硬编码。
   - `app.auth.cookie.secure` 在 base/dev/test 默认为 `false`，`prod` 固定为
     `true`；生产配置测试必须证明不能继承本地 HTTP 值。
   - local、API 和 Spring Security logout 的应用认证 Cookie 清理复用相同
     Path/Secure/SameSite 属性，避免设置和删除策略漂移。
2. 把 Spring Session Cookie 属性从无效的 `spring.session.cookie.*` 移到 Spring
   Boot 3.3.4 实际绑定的 `server.servlet.session.cookie.*`；prod 保持
   `Secure=true`，base 保持 `HttpOnly`、`Path=/`、`SameSite=Lax`。
3. 前端不再读取或保存 refresh token：
   - `useAuth`、邮箱验证、本地/Web3 登录、OAuth2 callback 和 Axios interceptor
     均不写 `localStorage.refreshToken`。
   - 应用启动时主动删除历史遗留的 `refreshToken` key。
   - 自动 refresh 直接调用 cookie-based `/api/auth/refresh`，不再以 JavaScript
     可读 refresh token 是否存在作为前置条件。
   - access token 暂时继续写入 localStorage，维持当前异构 Python 资源服务器演示。
4. Python 资源服务器在现有 RS256、kid、issuer、audience 和 expiry 校验之外，
   明确拒绝 `type != access` 或缺少 `type` 的 token。
5. 保持现有 response JSON 中的 `accessToken`、`refreshToken` 和过期时间字段，
   让 Shell/API 调用方继续工作；前端忽略 refresh token。彻底移除 JSON refresh
   token 仍属于 H2.5 原子切换。

明确不纳入：

- refresh token family、rotation、single-use、replay detection、blacklist 或 logout
  持久撤销。
- access header/cookie 双凭据拒绝、CSRF、CORS、OAuth2 redirect/绑定意图收敛。
- `__Host-` Cookie 名称、旧 cookie 名迁移、生产 access token localStorage 移除。
- token claim/schema、issuer、audience、签名 key 或数据库/Flyway migration 变更。
- 清理前端全部诊断日志、`auth_user` 持久化或 dev-only resource 页面。

##### 测试与门禁

1. PostgreSQL/ApplicationContext：
   - local、邮箱、Web3、OAuth2 和 refresh 的 access/refresh Cookie 属性完全一致。
   - refresh 只依赖 HttpOnly Cookie；access token 不能作为 refresh token。
   - logout 的 Cookie 清除属性与写入属性一致。
   - prod 属性绑定为 Secure，base/test 为本地 HTTP 可用值；Session Cookie 使用正确
     的 `server.servlet.session.cookie.*` 前缀。
2. Shell HTTP E2E：
   - local login、refresh、Web3 login 和 logout 的 `Set-Cookie` header 快照一致。
   - 邮箱验证完成响应写入同策略 Cookie。
   - 现有 Flyway history、migration 幂等和 baseline guard 继续全部通过；本切片
     不新增无意义 migration。
3. Playwright：
   - local、邮箱、Web3、OAuth2 callback 和 401 refresh 后
     `localStorage.refreshToken` 始终不存在。
   - 启动时移除遗留 refresh token，但保留无关 localStorage key。
   - 自动 refresh 不依赖 refresh token localStorage，失败时不进入重试循环。
4. Python：
   - 合法 access token 继续通过。
   - 使用相同合法签名、kid、issuer、audience 和 expiry 的 refresh/missing-type
     token 都失败关闭。
5. 完整 `scripts/verify.sh` 通过后才进入连续三轮无修改检查。任何实质修复都令计数
   归零并重跑受影响门禁；最终提交工作区全部适当修改，不丢弃、回滚或 stash
   其他开发者的并行工作。

##### 实际结果

2026-08-08 本切片已完成：

- local login、邮箱注册完成、邮箱验证完成、Web3 login、OAuth2 success 和 refresh
  复用 `AuthCookieService`；写入和 local/API/Spring Security logout 清除使用同一
  Path/Secure/SameSite 策略。
- Cookie Max-Age 读取 `jwt.expires.*`，非默认 TTL 单测证明没有继续硬编码默认值。
- `prod` 中认证 Cookie 或 Session Cookie 的 Secure 最终值被高优先级配置覆盖为
  false 时，启动期 guard 失败关闭；base/test 仍允许隔离的本地 HTTP 测试。
- Spring Session Cookie 已迁移到 `server.servlet.session.cookie.*`。
- 前端不再读取或保存 refresh token，启动时只删除历史 `refreshToken` key；
  access token localStorage 演示兼容性保持不变。
- Python 资源服务器拒绝 refresh token 和缺少 `type` 的 token。
- 完整根统一门禁通过：Java 127、HTTP 15/15、Flyway 13/13、邮件参考服务
  129 tests、Mock Playwright 21/21、Python 资源服务器 16/16、邮件 stub 8/8，
  前端 lint/type/build 和文档检查通过。

1. 建立单一严格 token validator，统一 Resource Server、Web3 bind、
   OAuth2 绑定 cookie、introspection 和 refresh。
2. refresh token rotation 使用 token family、单次消费和 replay detection。
3. 接入 `token_blacklist` 或其替代持久撤销模型。
4. logout 撤销当前 token/family，而不只是清 cookie。
5. introspection 返回与实际资源服务器一致的 active 结论。
6. 统一 token transport：
   - refresh token 不进入 JSON/localStorage。
   - 明确 access token 是 cookie 还是 bearer 的主路径。
   - 消除 header/cookie 身份歧义。
7. 集中 cookie factory，按 profile 配置 Secure、HttpOnly、SameSite、Path 和 Max-Age。
8. 对 cookie 认证的写请求启用并测试 CSRF。
9. CORS 收敛为一个配置来源。
10. OAuth2 redirect 和绑定意图使用服务端 allowlist 与一次性状态，不信任任意 Referer/state URI。

### Batch D：Email/password 与 Web3/SIWE

Batch C 通过后执行。

### 邮箱 challenge 投递接受与原子消费切片

#### 2026-08-08 固定实施范围

本切片只加固现有邮箱注册和密码重置流程，不引入新功能或新的公开成功契约。

纳入范围：

1. 外部邮件服务只有返回 `SUCCESS`/`QUEUED` 才能创建可验证 challenge；
   `FAILED`、`RATE_LIMITED`、`INVALID_EMAIL` 和网络不可达均失败关闭，不能返回
   “已发送成功”或留下可验证数据库记录。
2. 发送请求本身是权威接受边界，不再把一次易竞态的 health 预检查当作发送成功证据；
   health endpoint 仍属于部署、诊断和参考服务 REST 契约。
3. `max-retry-attempts`、`expiry-minutes` 和 `resend-cooldown-seconds` 由受校验的
   configuration properties 驱动；响应中的有效期和 cooldown 不再硬编码。
4. resend cooldown 按 `email + purpose + created_at DESC` 查询；注册发送接口只接受
   服务端支持的 `REGISTRATION`，拒绝 `LOGIN`、`PASSWORD_RESET` 和未知 purpose。
5. PostgreSQL 条件更新原子消费正确验证码；同一 challenge 并发验证只有一个请求成功。
   controller 不再按 email/purpose 二次标记，避免误消费原子验证后新建的 challenge。
   错误尝试使用 retry-count CAS，不能因并发覆盖而丢失计数，达到配置上限后沿用
   现有“删除 challenge”语义。
6. ApplicationContext/PostgreSQL 集成测试覆盖接受/拒绝结果、配置覆盖、并发正确消费和
   并发错误计数；Shell E2E 使用受控 REST stub 覆盖真实 HTTP client 契约和失败路径。
7. Flyway baseline guard 增加无效 email verification state 的独立失败矩阵；Playwright
   固定邮件发送失败时停留在验证界面并显示错误；Python 契约测试固定 REST stub 的
   API key、健康、接受、拒绝和频控语义。

明确不纳入：

- transactional outbox、delivery/challenge 双状态机、幂等 delivery id。
- opaque challenge id、HMAC/加密验证码存储、密钥轮换。
- canonical email、forgot-password 防枚举协议重构、移除只读预检查 endpoint。
- public response schema 的全面重设计或新增无密码邮箱登录。

任何超出上述范围的问题记入后续 Batch D，不在本切片顺手实现。

#### 2026-08-08 实施结果

- 同步接受边界、配置校验、动态响应、purpose 拒绝、正确验证码条件消费和错误重试
  CAS 已实现。
- PostgreSQL/ApplicationContext 集成测试、真实 client loopback HTTP 测试、Shell
  真实进程 E2E、Flyway guard、Playwright 和 Python stub contract 已覆盖本切片。
- 完整统一门禁结果：Java 120 tests、HTTP 15/15、Flyway 13/13、
  Mock Playwright 20/20、
  Python 资源服务器 14/14、邮件 REST stub 6/6，前端 lint/type/build 通过。
- 保留边界：外部已接受后本地 challenge 事务失败、异步 delivery 失败撤销、
  transactional outbox、单一 pending challenge、canonical email 和
  forgot-password 防枚举协议仍归后续 Batch D。

### 邮件参考服务敏感响应安全切片

#### 2026-08-08 固定实施范围

本切片只加固 `reference/email-service/` 的既有 `/api/email` HTTP 响应，不改变模板、
JSON body、状态码、队列、投递、retry、Flyway schema 或 UniAuth challenge 语义。

纳入范围：

1. 所有 `/api/email` 及其子路径响应统一设置 `Cache-Control: no-store`、
   `Pragma: no-cache` 和 `X-Content-Type-Options: nosniff`。
2. 响应策略必须覆盖成功、API key 拒绝、参数校验失败和 MVC 路由错误，并继续支持
   context path 与 matrix parameter 路径。
3. API key 与响应过滤器复用一个路径匹配器，响应过滤器先执行，避免 401 提前返回
   绕过安全 header。
4. PostgreSQL/ApplicationContext 真实 HTTP E2E、Shell HTTP 进程 E2E、Flyway 成功
   启动 guard 和 Python 邮件 stub contract 固定该基线。
5. Playwright 不新增与浏览器无关的直接邮件服务耦合测试，但完整根门禁仍运行现有
   Mock Playwright、ESLint、TypeScript 和构建回归。

明确不纳入：

- 新增 Spring Security、CORS、CSP、HSTS 或外部反向代理配置。
- 修改 UniAuth 邮件客户端的成功判定或要求其解析这些响应 header。
- 修改 migration、数据库数据、SMTP、模板、队列状态机或投递幂等语义。

#### 2026-08-08 实施结果

- 独立响应过滤器先于 API key 过滤器执行，二者复用 context path/matrix-safe 路径
  matcher；合法业务 body、状态码和持久化行为不变。
- 邮件组件独立门禁：Maven 127 tests，其中 21 个 PostgreSQL/GreenMail
  ApplicationContext E2E；Shell runtime 27/27、HTTP 10/10、Flyway guard 10/10。
- Python 邮件 stub contract 7/7；完整根门禁继续验证 Java 120、HTTP 15/15、
  Flyway 13/13、Mock Playwright 20/20、Python 资源服务器 14/14 和前端质量门槛。

### 邮件 API 鉴权 header 单值加固切片

#### 2026-08-08 固定实施范围

本切片只消除配置 API key 时对重复同名鉴权 header 的歧义，不改变单个正确
header、JSON body、状态码、模板、队列、SMTP、retry 或 Flyway V1/V2 行为。

纳入范围：

1. `EmailApiKeyFilter` 只接受恰好一个 `X-Email-Service-Key` 且整值精确匹配；
   缺失、错误、重复正确值和正确/错误混合值统一返回 `401`。
2. Python 邮件 REST stub 使用相同单值契约，避免根 Shell E2E 与参考实现的鉴权
   语义漂移。
3. PostgreSQL/ApplicationContext + 真实 Tomcat HTTP E2E 覆盖重复正确值、
   正确/错误和错误/正确三种顺序。
4. Shell curl HTTP E2E 使用真实重复 header；Flyway guard 在只迁移 V1 的
   disposable PostgreSQL 应用启用 API key 后验证重复凭据仍失败关闭。
5. Playwright 不新增与浏览器无关的直接邮件服务测试；完整根统一门禁仍运行现有
   Mock Playwright、ESLint、TypeScript、构建和 Python 资源服务器回归。

明确不纳入：

- 修改 API key 的生成、轮换、身份分级或端点级权限。
- 新增 Spring Security、反向代理配置或新的认证协议。
- 修改 UniAuth 客户端请求形状；它继续为每个请求发送一个 header。
- 修改 migration、数据库数据、SMTP、模板、队列或投递状态机。

#### 2026-08-08 定向验证结果

- 旧实现红灯证明：重复的两个正确 header 会被首值语义接受并返回 `200`。
- 邮件组件 Maven 129 tests，其中 22 个 PostgreSQL/GreenMail ApplicationContext
  E2E；Shell runtime 27/27、HTTP 10/10、Flyway guard 11/11。
- Python 邮件 stub contract 8/8；单个正确 header 与既有正常发送契约保持不变。
- 完整邮件组件和根统一门禁已通过：根 Java 121、HTTP 15/15、Flyway 13/13、
  Mock Playwright 21/21、Python 资源服务器 16/16、邮件 stub 8/8，以及前端
  lint/type/build；连续三轮无修改检查仍须在提交前执行。

#### Email/password

- 通过可靠 outbox/delivery 状态关闭“外部先接受、本地 challenge 后失败”的窗口。
- 把异步 delivery 最终失败与 challenge 可用状态关联起来。
- 同一 email/purpose 只存在一个有效 challenge。
- 保留已完成的原子消费/retry CAS，并继续加固发送频控和并发创建。
- 注册和密码重置使用统一 canonical email。
- 不在 verification metadata 中长期保存可直接使用的密码 hash。
- forgot-password 对存在/不存在账户保持一致外部语义。

#### Web3/SIWE

- 服务端保存并验证完整 challenge，而不是信任客户端回传 message。
- 严格绑定 domain、URI、address、nonce、chainId、issuedAt、expiration。
- nonce 通过单条条件删除/更新原子消费。
- 过期 nonce 有明确清理策略。
- 并发首次登录/绑定依赖数据库约束和稳定冲突映射。

## 5. 每批硬门槛

在进入三轮检查前，至少执行：

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn clean compile test-compile
TESTCONTAINERS_RYUK_DISABLED=true mvn test

scripts/test-http-e2e.sh

cd frontend
npx tsc --noEmit
npm run lint
npm run build
npm run test:e2e

cd ../python-resource-server
python3 -m unittest -v test_app.py
```

涉及 Flyway/baseline 时再执行只读 rehearsal。不得把 `apply` 合并进普通验证命令。

## 6. 连续三轮检查

每个批次通过基础门禁后执行：

```text
counter = 0
while counter < 3:
    按固定范围检查实现、测试、配置和文档
    if 发现实质问题:
        立即修复
        重跑受影响门禁
        counter = 0
    else:
        输出时间、范围、发现、处理和结果
        counter += 1
```

无问题轮次只在会话报告中记录，不为留痕修改仓库。行号和纯格式细节不触发重置；
正确性、安全性、可执行性、覆盖缺口和错误声明会触发重置。

## 7. 完成定义

- 未增加新用户功能。
- 现有功能的成功、失败、并发、重放和持久化路径有自动化证据。
- PostgreSQL migration 是唯一 schema 写入者。
- 没有测试连接共享开发库。
- 没有真实 provider/邮件/高成本外部调用。
- 没有默认采用悲观锁。
- live 文档与当前代码一致。
- 连续三轮无修改检查通过。
- 提交中不含 `.env`、`.local/`、数据库导出、私钥、`target/`、静态构建产物或测试报告。

## 8. 持续加固循环

单个 batch 的完成不是停止条件。每轮固定执行：

1. 重新充分探索，形成范围固定、无新功能的下一轮计划。
2. 扩充 PostgreSQL 后端集成测试与必要夹具。
3. 扩充 Shell HTTP E2E 和 Flyway baseline guard 测试。
4. 扩充 Playwright、Python 契约测试、ESLint 和统一验证入口。
5. 通过完整编译、测试、构建和 E2E 硬门槛。
6. 连续完成三轮无修改检查，更新文档并提交推送。
7. 立即回到第 1 步。

只有用户明确要求暂停，或经过全面代码、配置、文档和测试复查后确认已不存在任何
有意义的加固工作，才允许结束循环。发现需要新增用户功能的事项只能记录到计划，
不能借持续加固之名实施。

## 相关文档

- [全面加固实施规划](HARDENING_IMPLEMENTATION_PLAN.md)
- [验证指南](../VERIFICATION.md)
- [配置基线](../CONFIGURATION.md)
- [当前架构](../ARCHITECTURE.md)
- [数据库历史归档](../archive/database/README.md)
