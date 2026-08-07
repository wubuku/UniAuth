# UniAuth 下一轮加固实施计划

> 状态：Draft
> 事实基线：2026-08-07
> 范围：只加固、修复和验证现有功能，不增加新的用户功能
> 前置成果：PostgreSQL-only、Flyway V1、Testcontainers、Java/Shell/Playwright/Python
> 基础门禁已建立

## 1. 目标

下一轮先把现有功能放到足够强的自动化保护下，再实施数据库约束、token、
HTTP 安全、邮箱和 Web3 正确性修复。顺序不可倒置：

1. 扩充集成测试、Shell E2E 和 Playwright，形成现有功能覆盖矩阵。
2. 通过 PostgreSQL V2+ migration 加固身份数据不变量，并建立 G1 要求的最小持久
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
| 当前 migration | `V1__baseline_uniauth_auth_schema.sql` |
| Flyway history | `uniauth_flyway_schema_history` |
| ORM/初始化 | Hibernate `validate`；SQL init 和 Spring Session 自动建表关闭 |
| Java | `mvn clean compile test-compile` 和 42 tests 已通过 |
| HTTP E2E | `scripts/test-http-e2e.sh` 10/10 已通过 |
| 前端 | TypeScript、生产构建、12 个 Mock Playwright tests 已通过 |
| Python | 5 个离线 JWT/JWKS/Flask tests 已通过 |
| 既有库演练 | `blacksheep_dev` 只读 rehearsal 已通过 |
| Schema fingerprint | `12c67edaba1ca20833c0db634226b2cd3d9c07549cc8c9a390a5ff2df5eadebe` |

重要边界：

- `blacksheep_dev` 尚未执行 Flyway baseline apply。
- rehearsal 只读源库，所有恢复、baseline 和 fresh migration 写入均发生在一次性容器。
- 未经用户显式授权，不创建开发库 history table，不运行 apply。
- 不调用真实 Google/GitHub/X、真实邮件服务或任何高成本外部模型。

## 3. 当前覆盖与缺口

### 3.1 已有后端覆盖

- fresh PostgreSQL 执行 Flyway V1，Hibernate validate。
- 既有 V1 schema baseline 后应用启动。
- Spring Session JDBC create/read/delete。
- 本地注册、登录、错误密码、refresh、access/refresh type confusion。
- `/api/user`、登录方式查询、设置 primary、删除、添加本地方式。
- 邮箱注册、真实持久化验证码、密码重置。
- Web3 本地签名登录、nonce replay、钱包绑定。
- `/api/auth/**` allowlist、未知/已删除路由 fail closed。
- JWT key 文件权限、损坏文件和重载。

### 3.2 已有 Shell E2E 覆盖

- 真实 `start.sh` 启动。
- disposable PostgreSQL 和 Flyway history。
- 本地登录、JWT、refresh、Web3、邮箱、logout cookie、最终数据库不变量。

### 3.3 已有 Playwright 覆盖

- 本地登录成功/失败。
- 邮箱注册和验证码 UI。
- 忘记密码。
- OAuth2 callback 成功/失败。
- 登录方式 UUID 操作。
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
| 登录方式 | 并发 bind、并发 set-primary、并发删除最后两个方式、跨用户所有权 |
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

### Batch B：PostgreSQL V2+ schema、并发不变量与安全审计基座

Batch A 通过后才开始。

#### B1. 数据预检

在任何 V2 migration 前，对目标库执行只读报告：

- 零/多个 primary 的用户。
- 无登录方式用户。
- 重复 `(user_id, auth_provider)`。
- 重复 `(auth_provider, provider_user_id)`。
- 重复规范化 email/local username。
- 同一 `(email,purpose)` 多条未使用 code。
- entity 声明非空但数据库为 null 的记录。
- 无效 enum、超长 ID/subject、混合 LOCAL/provider 字段。

发现冲突时停止 migration。自动修复规则必须单独评审，不能依赖查询返回顺序。

#### B2. V2+ migration

1. 增加每用户至多一个 primary 的 partial unique index。
2. 对齐 `users`、`user_login_methods`、`web3_nonces`、
   `email_verification_codes`、`token_blacklist` 的 nullability/default。
3. 增加 login-method shape checks。
4. 将 Java `Instant` 对应列迁移为 `TIMESTAMP WITH TIME ZONE`，保留
   `LocalDateTime` 对应无时区列。
5. 为 email code 查询和过期清理增加索引。
6. 删除确认冗余的 users/blacklist 索引，但保留唯一性。
7. 处理 V1 中未映射的 Web3 列时采用 expand/contract，不在同一版本直接破坏旧代码。

#### B3. 并发策略

- 优先数据库唯一约束、条件更新和 CAS。
- 不把悲观锁作为默认方案。
- 不为了形式引入 JPA `@Version`。
- set-primary 和删除最后登录方式必须在并发测试下保持业务不变量。
- 唯一冲突必须映射为稳定业务错误，不返回数据库细节。

#### B4. 最小持久安全审计基座

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

#### Batch B 退出条件

- fresh V1 -> 最新 V2+ 和 existing baseline V1 -> 最新 V2+ 均通过。
- migration 重复执行、失败恢复和 forward-fix 演练通过。
- schema/entity mapping 和并发矩阵通过。
- audit migration、append/outbox、事务回滚、不可变性和权限拒绝测试通过。
- G1 数据门禁完成；Batch C 不得绕过审计接口另建日志式替代品。
- 生产目标只生成预检报告，不自动 apply。

### Batch C：JWT、refresh、blacklist 与 HTTP 边界

Batch B 通过后执行，H2.5 与 H3.1/H3.2 作为原子切换批次。

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

#### Email/password

- challenge 先持久化，再通过可靠发送状态/outbox 投递。
- 邮件失败不返回“已发送成功”。
- 同一 email/purpose 只存在一个有效 challenge。
- 重试、频控和消费使用原子条件更新。
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

## 相关文档

- [全面加固实施规划](HARDENING_IMPLEMENTATION_PLAN.md)
- [验证指南](../VERIFICATION.md)
- [配置基线](../CONFIGURATION.md)
- [当前架构](../ARCHITECTURE.md)
- [数据库历史归档](../archive/database/README.md)
