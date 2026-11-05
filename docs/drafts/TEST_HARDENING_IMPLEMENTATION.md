# 测试与验收底座加固实施记录（2026-11-04）

> 状态：Live / 已完成。本记录是 2026-11-04 测试与验收底座加固的范围、
> 决策、证据和残余风险。它不替代 [验证指南](../VERIFICATION.md)。
>
> 约束：未修改已发布的 Flyway V1-V8；未写 `blacksheep_dev` 或任何共享库；
> 未读取 `.env`；未执行真实 OAuth/邮件供应商调用；未执行任何 git 写操作。

## 1. 目标

让仓库的测试与验收底座在“日后加功能、做重构”时仍然可信：

1. 盘点现有单元、集成、回归、验收、端到端测试的真实覆盖与盲区；
2. 补齐高风险缺口，尤其是安全不变量、并发/CAS、持久化约束、失败关闭和跨模块契约；
3. 消除不稳定、顺序依赖、伪成功和环境敏感夹具；
4. 让全量门禁可以重复通过，并把真实数字更新到文档基线。

## 2. 只读盘点

盘点对象：

- 根门禁 [scripts/verify.sh](../../scripts/verify.sh) 的 15 个阶段；
- 组件门禁 `reference/email-service/scripts/verify.sh`；
- `src/test/java` 全部测试类、`frontend/tests` 的 Mock/生产 Playwright、
  `python-resource-server/test_app.py` 和 `scripts/*e2e*.sh`；
- [VERIFICATION.md](../VERIFICATION.md) 已声明的 P0/P1/P2 缺口、
  [AGENTS.md](../../AGENTS.md) 的 “Current Rough Edges”、近期修复过的回归点。

覆盖矩阵（按层次的结论）：

| 层 | 现状 | 结论 |
|----|------|------|
| 后端纯单元测试 | 少量，集中于 Cookie/JWT 边界、输出策略、配置 guard | 安全工具类此前只有间接覆盖 |
| 后端集成测试 | 20 个 `@SpringBootTest` + PostgreSQL 16.13 Testcontainers，HTTP/security chain/持久化 | 主链路覆盖较强，新增功能（改密）缺负路径 |
| Shell HTTP/Flyway E2E | 17/17、17/17，使用 disposable PostgreSQL 和真实进程 | 顺序、端口、清理均为动态分配，无固定端口依赖 |
| 前端 | Playwright Mock 31 + 生产 2，无组件级单测 | 关键并发场景依赖浏览器 E2E，存在固定等待 |
| Python 资源服务器 | 20 个离线 RSA/JWKS/Flask 测试 + hash lock + pip-audit | 覆盖完整；供应链例外已到期 |
| 邮件参考服务 | Maven 组件套件 + Shell runtime/HTTP/Flyway/backup，独立门禁 | 覆盖完整；供应链抑制已到期 |

已确认**不存在**伪跳过：全仓库没有 `@Disabled`、`@Ignore`、`assumeTrue`、
`test.skip/fixme`、`xit/xdescribe` 或 Surefire/Failsafe 排除项。

## 3. 缺口清单（按风险 × 成本排序）

| 优先级 | 缺口 | 风险 | 处理 |
|--------|------|------|------|
| P0 | `PUT /api/user/password` 的 recent-auth 拒绝路径从未测试 | 用户凭 token 改密可绕过重新认证要求 | 新增集成测试 |
| P0 | `LOCAL` 登录方式但 `local_password_hash IS NULL`（邮箱注册账户）调用改密 | 返回 401“当前密码错误”，语义错误且掩盖真实状态 | 修正服务分层并测试 400 |
| P0 | 改密 PostgreSQL CAS 冲突（两个并发请求）无测试 | 并发改密可能双赢/双输 | 新增确定性并发测试，断言 200+409 |
| P0 | Web3 绑定钱包的 stale `auth_time` 路径无测试 | 过期 recent-auth 仍可绑定钱包 | 新增测试，并验证 fresh token 仍可成功 |
| P1 | `CsrfProtectionFilter` 无直接测试 | 重复 header、空 header、豁免范围回归只能靠间接 E2E | 新增 12 个分支单元测试 |
| P1 | `BearerTokenUtils` 的大小写/空值/非 Bearer 分支无直接测试 | 已修复过的 Bearer 大小写回归可能再次发生 | 新增 5 个单元测试 |
| P1 | `SensitiveResponseHeadersFilter` 无直接测试 | prod CSP/HSTS 与敏感路径 no-store 可能静默丢失 | 新增 4 个单元测试 |
| P1 | `RecentAuthenticationService` 边界（max-age、future skew、缺失 claim）无直接测试 | 边界回退导致“永远 recent”或误拒 | 新增 8 个单元测试 |
| P1 | 前端“跨标签页 logout 后迟到 refresh 不能恢复认证”使用 `waitForTimeout(400)` | 慢 CI 下可能伪通过或抖动 | 删除固定等待，依赖共享 refresh lock 的顺序断言 |
| P1 | Tomcat suppression 于 2026-09-01 到期 | 根/邮件依赖门禁今天必然失败 | 升级 Tomcat 至 10.1.61 并删除抑制 |
| P1 | Python `cryptography` 例外于 2026-10-01 到期 | Python 供应链门禁今天必然失败 | 重新评估并续期，记录平台约束 |
| P1 | Python 运行时/工具锁在 2026-11-04 已陈旧 | 门禁不可重复，且新披露的 PyJWT/Werkzeug 漏洞未处理 | 刷新锁，升级 PyJWT 2.15.1、Werkzeug 3.1.9 |
| P2 | 前端无组件级单测（无 vitest） | 纯函数/状态机回归只能靠 E2E | 记录为残余缺口，本轮不引入新测试框架 |
| P2 | Python 资源服务器无法感知 PostgreSQL 实时撤销 | 纯 JWKS 校验的架构限制 | 记录，属于设计边界而非测试缺口 |

## 4. 实施切片

### 4.1 改密安全负路径（P0）

- 新增 `PasswordChangeSecurityIntegrationTest`，4 个测试：
  1. `auth_time` 超过 600 秒 -> `403 RECENT_AUTH_REQUIRED`，密码不变、会话仍可用；
  2. `LOCAL` 但无密码 -> `400 LOCAL_PASSWORD_NOT_CONFIGURED`；
  3. 只有 `GITHUB` 登录方式 -> `400 LOCAL_PASSWORD_NOT_CONFIGURED`；
  4. 两个并发改密 -> 恰好 `200 + 409 PASSWORD_CHANGED_CONCURRENTLY`，
     旧密码失效，两个新密码恰好一个可用。
- 并发测试不使用固定延迟盲等：在 `user_login_methods` 上安装只对
  `local_password_hash` 变更生效的 `pg_sleep` 触发器，并轮询
  `pg_stat_activity` 确认第一个 `UPDATE` 已进入触发器后才发起第二个请求，
  从而确定性地制造“两个事务读到同一旧 hash”的前置条件。
- 修正 `LoginMethodService.changePassword`：`local_password_hash IS NULL`
  现在抛出 `IllegalStateException` 并由控制器映射为
  `400 LOCAL_PASSWORD_NOT_CONFIGURED`，不再与“当前密码错误”共用 401。

### 4.2 Web3 绑定 recent-auth（P0）

- 在 `Web3AuthenticationIntegrationTest` 新增
  `staleAuthenticationCannotBindWalletButFreshOneCan`：
  - 用 601 秒前的 `auth_time` 绑定钱包 -> `403 RECENT_AUTH_REQUIRED`，未写入 `WEB3` 登录方式；
  - 同一 challenge 换用新鲜 token -> 成功绑定，证明失败原因确为 recent-auth，
    且失败请求没有消费 challenge。

### 4.3 安全过滤器与工具类直接覆盖（P1）

- `CsrfProtectionFilterTest`（12 个用例）：安全方法、无认证 Cookie、精确 Cookie 名、
  access/refresh Cookie、缺失 session token、单/重复/冲突 header、空白/错误 token、
  `POST /api/auth/refresh` 豁免但 `PUT` 不豁免、`/oauth2/introspect` 豁免、
  稳定 JSON 错误体。
- `BearerTokenUtilsTest`（5 个用例）：null、空 token、非 Bearer scheme、
  `Bearer`/`bearer`/`BEARER`/混合大小写、内容原样传递。
- `SensitiveResponseHeadersFilterTest`（4 个用例）：基础安全头、敏感路径 no-store、
  非敏感路径不加 no-store、prod 才加 CSP/HSTS。
- `RecentAuthenticationServiceTest`（8 个用例）：max-age 内/外、future skew 内/外、
  空值、缺失/非数字 claim、JWT 数值 claim。

### 4.4 前端固定等待与跨标签页 flake 治理（P1）

`frontend/tests/e2e/security-boundaries.spec.ts` 的跨标签页 logout 场景删除
`page.waitForTimeout(400)`。第二标签页的 logout 必须获得同一个
`uniauth-token-refresh` Web Lock 才能执行，因此 `toHaveURL('/login')` 本身就
证明迟到的 refresh continuation 已经结束；断言强度不降低，去掉了时间猜测。

完整门禁复跑后该用例在低 CPU 预算环境仍会偶发失败。诊断结论（见决策 8）：

- 失败点是跨 renderer 的 Web Lock 交接与迟到 refresh 写回被浏览器对隐藏标签页的
  调度节流拖慢，而不是 `checkAuth`/清除顺序错误；
- 在人工 6 个 CPU burner + 3 worker 的极端压力下可稳定复现，正常门禁下 3 次
  连续整套跑均为 31/31。

最终处理（只改变测试时序契约，不降低断言强度）：

1. `frontend/playwright.config.ts` 的 chromium project 增加
   `--disable-background-timer-throttling`、`--disable-backgrounding-occluded-windows`、
   `--disable-renderer-backgrounding`，让隐藏标签页不再被节流；
2. 释放 refresh 前显式 `page.bringToFront()`，确保 refresh continuation 有执行预算；
3. 先等待 worker 侧的 `logoutCalls === 1`（`expect.poll`，不依赖 renderer 绘制），
   再断言 `toHaveURL('/login')`；
4. 该用例单独 `test.setTimeout(60_000)`，因为它验证的是顺序不变量而不是
   亚秒级时延；硬断言仍然是“logout 恰好一次、refresh 恰好一次、两个标签页
   认证状态全部清空”。

### 4.5 供应链门禁可重复性（P1）

- 根 `pom.xml` 与 `reference/email-service/pom.xml` 的 `tomcat.version`
  从 10.1.57 升级到 10.1.61（发布版本已包含 `CVE-2026-66299` 修复）。
- 删除两份 `dependency-check-suppressions.xml` 中已到期的 Tomcat examples-only
  条目；随后完整门禁在 2026-11-05 披露的 Spring Framework / Spring Security
  advisory 下失败，于是只新增有 owner、可达性证据和到期日的限定例外，
  见决策 5。
- `swagger-ui` WebJar 从 5.32.11 升级到 5.34.1：内嵌 DOMPurify 3.4.17
  真实修复 `CVE-2026-116803` / `CVE-2026-116804`，不需要 suppression。
- 前端 `npm audit --audit-level=moderate` 从 21 个漏洞降为 0：升级直接依赖
  （axios 1.21.0、eslint 10.14.0、typescript-eslint 8.72.1、vite 7.3.7），
  并新增 `overrides.minimatch=11.0.4` 以消除传递依赖 advisory。
- 刷新 `python-resource-server/requirements.lock` 与
  `requirements-tools.lock`，并升级：
  - `PyJWT 2.13.0 -> 2.15.1`：2026-10 后新披露的 15 个 advisory（含
    `PyJWKClient` 重定向、签名段解析、HS256/JWK 混淆）；
  - `Werkzeug 3.1.8 -> 3.1.9`：Windows 设备名 `safe_join` advisory。
- `scripts/verify-python-supply-chain.sh` 的两次 `pip-compile` 增加
  `--no-header`。此前锁文件头部会记录本次环境的 `--no-index` 等参数，
  导致同一仓库在不同机器上生成不同锁文件；去掉头部后比较只针对
  包版本和 hash，恢复跨环境可重复性。
- `cryptography` 仍保留 48.0.1 与 3 个精确例外，原因和风险见第 5 节。

## 5. 决策记录

### 决策 1：保留 `cryptography 48.0.1`，续期例外而不是升级

- 事实：本机是 macOS x86_64（Intel）。`cryptography` 49.0.0/50.0.2 的
  PyPI 发布物只有 arm64 macOS wheel，没有 x86_64 wheel；hash 校验安装会退化为
  源码构建，本机没有可用 Rust 工具链。
- 结论：升级会直接让本地 `verify-python-supply-chain.sh` 无法安装，从而永久
  破坏门禁。保留 48.0.1，续期 3 个精确例外到 `2027-02-01T00:00:00Z`，
  并补充“平台无 x86_64 wheel”的证据；例外仍然由脚本校验 advisory、
  包、版本、owner、理由、证据和到期日精确匹配。
- 风险：该包仍带 3 个已知 advisory；仓库代码只使用 PyJWT 的 RSA 校验，
  不调用 PKCS#7/S/MIME 或 X.509 chain/name-constraint 路径（证据已写入例外
  清单）。到期前必须重新评估；如果 CI/生产使用 Linux，应在下一次依赖窗口
  升级到 50.x 并删除例外。

### 决策 2：刷新 Python 锁并升级 PyJWT/Werkzeug，而不是继续扩大例外

- 事实：刷新锁后 `pip-audit` 报告 PyJWT 2.13.0 的 15 个 advisory 和
  Werkzeug 3.1.8 的 1 个 advisory；这些是 2026-10 之后新披露的，
  直接影响资源服务器的 JWT/JWKS 使用面。
- 结论：升级到 PyJWT 2.15.1、Werkzeug 3.1.9，不新增例外；其余传递依赖
  跟随 pip-compile 解析到当前可安装版本。
- 风险：PyJWT 2.15 的解析更严格，已通过 20 个离线 RSA/JWKS/Flask 测试；
  仍需在真实 JWKS 网络场景显式 opt-in 时复核。

### 决策 3：改密“无本地密码”语义修正

- 事实：邮箱注册会产生 `LOCAL` 且 `local_password_hash IS NULL`；
  旧实现把 null hash 与“当前密码错误”合并为 401。
- 结论：null hash 映射为 `400 LOCAL_PASSWORD_NOT_CONFIGURED`。
- 风险：该账户无法通过密码登录，只能通过已绑定的 OAuth/Web3 身份获得 token，
  因此返回 400 不会扩大可用攻击面；但客户端若只识别 401，需要同步提示文案。

### 决策 4：并发测试的确定性同步

- 事实：`MockMvc` 并发请求的调度不可控，靠固定 sleep 会造成偶发 401/409 漂移。
- 结论：用 PostgreSQL 触发器延长第一条 `UPDATE`，并轮询
  `pg_stat_activity` 作为可观测同步点；测试结束在 `@AfterEach` 删除触发器和函数。
- 风险：触发器只在测试库内安装，作用域限定为 `user_login_methods` 的密码 hash
  变更；不影响生产 schema，也不修改 Flyway。

### 决策 5：Spring Framework/Security 采用限定 suppression，而不是降级阻断阈值

- 事实：完整门禁在 2026-11-05 的 NVD 数据下报告 15 个 CVSS >= 7 的
  spring-core / spring-security advisory（12 + 3）。Spring Boot 3.5.16 对应
  的修复版本 6.2.20 / 6.5.12 尚未发布（Maven Central 返回 404），升级 Boot
  也不存在可用目标版本。
- 结论：在两份 `dependency-check-suppressions.xml` 中为每个 advisory 增加逐条
  `<suppress>`，要求同时匹配 CVE、包名和版本范围；`notes` 必须写清 owner、
  本仓库的可达性证据（不启用 WebFlux/RSocket/SSE/XSLT/SpEL 相关入口、
  邮件组件只走 servlet MVC + Tomcat）以及删除条件；`until="2027-01-31Z"`。
  `check-dependency-suppressions.py` 继续校验每条例外的必要字段和到期日，
  不允许出现无 owner 或无证据的宽泛抑制。
- 风险：例外到期即自动失败关闭，避免变成长期豁免；如果上游发布 6.2.20/6.5.12，
  下一步必须升级并删除这 15 条。另一个已知限制：dependency-check 对
  `until` 只接受 xsd:date，写成 `2027-01-31T00:00:00Z` 会导致整份 suppression
  文件解析失败且**看上去**仍然阻断，因此必须使用 `YYYY-MM-DDZ` 形式。

### 决策 6：前端用 `overrides` 固定传递依赖，而不是新增例外

- 事实：`npm audit --audit-level=moderate` 在锁文件刷新后报告 21 个漏洞，
  其中多数来自 `minimatch` 传递依赖；直接依赖升级无法消除它。
- 结论：升级可直接升级的 4 个直接依赖，并用
  `"overrides": { "minimatch": "11.0.4" }` 固定该传递依赖；最终
  `npm audit --audit-level=moderate` 为 0 vulnerabilities，lint/tsc/build 通过。
- 风险：`overrides` 只在 npm 生效；若 CI 切换到 pnpm/yarn 需要等价配置。
  升级 `minimatch` 跨越了大版本，已由 eslint/typescript-eslint 的实际运行
  （lint 0 warning）验证。

### 决策 7：AWS 官方文档示例 key 用精确敏感扫描例外，而不是改写他人文档

- 事实：完整门禁第 14 阶段在 `docs/drafts/AWS邮件发送服务配置.md` 发现 4 处
  `aws-access-key` 命中；该值是 AWS SES 官方文档公开的示例凭据（文档中的
  example access key 及其配套 example secret），不是可用凭据，也不是本轮
  引入的改动。
- 结论：在 `config/sensitive-scan-exceptions.json` 中增加一条按
  `rule + path + matched value` 指纹精确匹配的例外（owner、理由、到期日
  2027-01-31），不修改该文档内容；这样同一文件里任何**不同**的 key 仍然
  失败关闭，且例外到期自动失效。
- 风险：例外只覆盖这一个示例值；若后续文档复制该示例到新文件，指纹不同会
  再次失败关闭，需要重新评估（优先改为不可复制粘贴的占位符）。
  例外文件自身不能包含命中的明文值，否则会自触发扫描；任何复述该示例的
  记录文档也一样（本记录第一版曾因复述该示例被第 14 阶段拦下，已改为描述性
  措辞）。

### 决策 8：跨标签页 flake 通过关闭后台节流 + 顺序信号治理，而不是放宽断言

- 事实：`security-boundaries.spec.ts` 的跨标签页 logout 用例在完整门禁偶发失败；
  在人工 6 个 CPU burner + 3 worker 下可复现（约 1/10 到 2/10）。trace 显示
  refresh 已被 fulfill，但隐藏标签页的 Web Lock 交接和迟到写回迟迟得不到执行预算，
  最终 `logoutCalls` 仍为 0（并非认证状态被错误恢复）。
- 结论：分三层处理——(1) Chromium 启动参数关闭后台 timer/renderer/occluded-window
  节流；(2) 释放 refresh 前对发起标签页 `bringToFront()`；(3) 先等 worker 侧的
  `logoutCalls === 1`（不依赖 renderer 绘制）再断言 URL。该用例单独
  `test.setTimeout(60_000)`，因为它验证的是顺序不变量而非亚秒级时延；核心断言
  （refresh 恰好一次、logout 恰好一次、两个标签页认证状态清空）完全没有放宽。
- 风险：极端 CPU 饥饿下（如本机 6 个 burner + 3 worker）仍可能超出预算；正常门禁
  下已连续 3 次整套 31/31。若未来 CI 更繁忙，应优先减少并行 worker 或给该用例
  单独标记为 serial，而不是删除跨标签页断言。

## 6. 真实门禁数字（2026-11-04）

> 下列数字来自同一次完整 `scripts/verify.sh` 实跑（15/15，
> `PASS: complete repository verification gate`）。

| 门禁 | 结果 |
|------|------|
| 根 Maven（`mvn test`） | 326 tests，0 failures / 0 errors / 0 skipped |
| shared-schema 双进程 E2E | 8/8 |
| Shell HTTP E2E | 17/17 |
| Flyway baseline guard | 17/17 |
| Mock Playwright | 31/31（连续 3 次整套复跑均通过） |
| 生产 Playwright | 2/2 |
| 真实邮箱登录浏览器 E2E | 1/1 |
| Python 资源服务器离线测试 | 20/20 |
| 邮件 REST stub contract | 12/12 |
| Python 供应链（hash lock、pip-audit、例外校验） | PASS |
| 前端 `lint` / `tsc --noEmit` / `build` / `npm audit` | PASS（0 vulnerabilities） |
| 邮件参考服务组件门禁 | 154 tests；runtime 44/44、HTTP 11/11、Flyway 15/15、backup/restore 10/10 |
| 根依赖审计（dependency-check） | 94 项依赖 evidence，0 阻断（15 条限定 suppression） |
| 邮件依赖审计 | 54 项依赖 evidence，0 阻断（12 条限定 suppression） |
| 敏感产物扫描 | 710 文件（源码 + 候选构建产物），1 条精确例外 |
| 完整 `scripts/verify.sh` | 15/15 PASS |

## 7. 残余缺口与下一步

1. 前端仍无组件级测试框架；本轮只治理了固定等待，没有引入 vitest。
   若要降低 E2E 成本，下一步优先为 `authService` 的 single-flight/锁逻辑建立
   纯函数级测试，而不是继续堆浏览器用例。
   （已在 2026-11-05 第二批解决，见第 8 节。）
2. `cryptography 48.0.1` 例外必须在新到期日（2027-02-01）前重新评估；
   首选在 Linux CI/发布环境升级到 50.x 并删除例外。
3. Python 资源服务器只验证 JWT/JWKS，无法感知 PostgreSQL 实时撤销；
   这是架构边界，若需要实时撤销应引入 introspection 或 BFF。
4. 真实 OAuth provider、真实 SMTP/供应商送达、真实 JWKS 网络校验仍为
   显式 opt-in，不在默认门禁内。
5. `scripts/test-http-e2e.sh` 中“worker 已禁用所以 outbox 保持 pending”的
   0.5 秒等待是负向观测窗口；它不会造成假失败，但仍可能在极慢机器上
   放过一个比 0.5 秒更慢的错误同步投递。后续可改为断言 worker 配置本身。
   （已在 2026-11-05 第二批改为进程退出屏障，见第 8 节。）

## 8. 第二批（2026-11-05）：组件测试底座、确定性同步与盲区收敛

> 状态：Live / 已完成。本批只处理第 7 节列出的薄弱点，不恢复“加固阶段”循环；
> 未修改 Flyway V1-V8，未新增 V9，未执行真实 OAuth/邮件供应商调用。

### 8.1 实际改动

1. 前端组件级测试底座。
   - 新增 `frontend/vitest.config.ts`：`jsdom` 环境、只收集
     `tests/unit/**/*.test.{ts,tsx}`、`tests/unit/setup.ts` 在每个用例后
     清理 React 渲染树和 `localStorage`。
   - 新增 `tests/unit/authService.test.ts`（9）和 `tests/unit/useAuth.test.tsx`（3）：
     并发 refresh 单飞、marker 缺失/变化拒绝、logout 等待 in-flight refresh、
     logout 期间 refresh 拒绝、失败后单飞槽可重试、`clearLocalAuthentication`、
     每次刷新重写 marker；`checkAuth` 成功/失败、`localLogin` 持久化、
     refresh 失败触发登出。
   - 新增 `npm run test:unit`（`vitest run`），并接入
     `scripts/verify.sh` 第 10 阶段：`lint -> tsc --noEmit -> test:unit -> build`。
     Playwright 配置和阶段 11/12 未改动。
   - 依赖固定版本：`vitest 5.1.0`、`jsdom 30.1.3`、
     `@testing-library/react 16.3.3`、`@testing-library/dom 10.4.2`；
     `npm audit --audit-level=moderate` 仍为 0 vulnerabilities。
2. 负向观测窗口。
   - `scripts/test-http-e2e.sh` 删除 `sleep 0.5`；pending outbox/challenge 断言
     改到 `stop_application` 之后执行。该函数递归终止进程树并 `wait` 主进程，
     是确定的“调度器已停止”屏障；随后再以
     `EMAIL_DELIVERY_WORKER_ENABLED_VALUE=true` 重启并等待 `ACTIVE`。
3. 跨标签页负载韧性。
   - 保留 60s 用例预算、`bringToFront()`、worker 侧 `logoutCalls` 顺序信号和
     全部断言（refresh 恰好一次、logout 恰好一次、两个标签页认证状态清空）。
   - 6 CPU burner + 3 worker 复跑：10/10（42.7s）、12/12（1.3m），合计 22/22 通过；
     未观察到断言失败，只有历史记录中的超预算现象。
4. Mock Playwright 的采样竞态。
   - 完整门禁复跑时 `one protected 401 refreshes once and retries with the new access token`
     在 CPU 竞争下失败：空列表先渲染，此时一次性采样只看到两个 expired header。
   - 改成 `expect.poll(() => authorizationHeaders)` 观察重试请求本身，保留
     “重试必须携带 `Bearer refreshed.access.token`”的原始断言强度，不移除断言。
   - 6 burner 下定向复跑 6/6 通过（单次 7.5s），完整 Mock 套件 31/31（34.1s）。
5. 盲区收敛（只补真实风险）。
   - `OAuth2SuccessHandlerIntegrationTest` 新增 2 个失败关闭用例：
     `expiredBindingIntentIsRejectedWithoutConsumption`（JDBC 把 `created_at`/
     `expires_at` 移到过去，断言 `oauth2_processing_failed`、intent 未被消费、
     未创建 provider login method）；`bindingIntentIsRejectedAfterUserSecurityVersionChanges`
     （JDBC 递增 `users.token_security_version`，同样断言拒绝且未消费）。
     两个用例都必须同时调整 `created_at`，因为 V8 的
     `ck_oauth2_binding_intent_expiry` 要求 `expires_at > created_at`。
   - `CorsSecurityIntegrationTest` 新增 4 个参数化用例：四条安全链对 allowlist
     之外的方法（`PATCH`）预检返回 403 且不回显 `Access-Control-Allow-Origin`。
   - 复核后明确不补的对象：
     token family replay 并发已由 `TokenRevocationIntegrationTest` 的真实双线程
     + generation CAS 覆盖；strict introspection 的缺失/错误/重复 Basic、query、
     额外字段矩阵已在 `AuthenticationFlowIntegrationTest`；`allowed-headers` 配置为
     `*` 且凭据模式合法，不存在可拒绝的 header 维度；仓库当前没有 V9+ 迁移，
     V9 出现时必须同步补 schema 指纹、fresh migrate→新版本 + Hibernate `validate`
     和 baseline guard；Python 20/20 与邮件 154 tests 的契约边界由各自门禁覆盖。

### 8.2 决策记录（续）

#### 决策 9：Vitest 与生产构建/Playwright 隔离，而不是共用同一 `vite.config.ts`

- 事实：生产构建使用 `vite.config.ts`，Playwright 使用
  `playwright.config.ts` 和 `tests/e2e/**`；两者已有稳定门禁。
- 结论：单测使用独立 `vitest.config.ts`，`include` 限定 `tests/unit/**`，
  因此不会把 Playwright spec 当单测收集，也不需要修改 Playwright 配置。
- 风险：三套配置需要各自维护；新增测试目录时必须确认归属，避免把浏览器
  用例误放进 `tests/unit`。

#### 决策 10：只测试真实跨标签页机制，不发明 `storage` 事件或“登出广播”

- 事实：前端跨标签页协调的实际机制是 `uniauth-token-refresh` Web Locks、
  runtime single-flight 和 `auth_session_marker` 失效判定；代码没有使用
  `window.storage` 事件，也没有显式广播通道。
- 结论：单测直接覆盖上述真实机制，不引入虚构的广播抽象；文档明确记录
  “无 storage 事件”。
- 风险：若未来改用 `BroadcastChannel`/`storage`，必须同时更新单测与
  Playwright 跨标签页用例。

#### 决策 11：0.5 秒窗口改为进程退出屏障，而不是延长等待

- 事实：原断言在 worker 关闭时等待 0.5 秒后读库；它既是时间猜测，也可能在
  极慢机器上放过“比 0.5 秒更慢的错误同步投递”。
- 结论：在 `stop_application`（终止进程树并 `wait`）之后读库，调度器已不存在，
  观测是确定的；再用显式重启验证 worker 打开后 challenge 进入 `ACTIVE`。
- 风险：若未来投递改为独立外部进程或停机 hook 异步落库，该屏障失效，
  必须改用事件/审计行等新的同步点。

#### 决策 12：跨标签页用例不标 serial，保留 60s 预算与断言强度

- 事实：压力复跑中出现的是超预算，而不是断言失败；标 serial 会掩盖 worker
  并行下的真实竞争，并显著增加门禁时长。
- 结论：保留 `test.setTimeout(60_000)` 与全部断言；记录 22/22 复跑数据。
- 风险：CI 并行度显著升高时仍可能超预算；届时优先减少 worker，或将单文件
  标记 serial，而不是删除断言。

### 8.3 真实门禁数字（2026-11-05）

> 下列数字来自同一次完整 `scripts/verify.sh` 实跑（15/15，
> `PASS: complete repository verification gate`）。

| 门禁 | 结果 |
|------|------|
| 根 Maven（`mvn test`） | 332 tests，0 failures / 0 errors / 0 skipped（+2 OAuth2 intent、+4 CORS） |
| 前端组件单测（`npm run test:unit`） | 12 tests / 2 files，全部通过 |
| shared-schema 双进程 E2E | 8/8 |
| Shell HTTP E2E | 17/17（负向 pending 断言改为 `stop_application` 后观测） |
| Flyway baseline guard | 17/17 |
| Mock Playwright | 31/31；6 burner + 3 worker 下 10/10（42.7s）+ 12/12（1.3m） |
| 生产 Playwright | 2/2 |
| 真实邮箱登录浏览器 E2E | 1/1 |
| Python 资源服务器离线测试 | 20/20 |
| 邮件 REST stub contract | 12/12 |
| Python 供应链（hash lock、pip-audit、例外校验） | PASS |
| 前端 `lint` / `tsc --noEmit` / `build` / `npm audit` | PASS（0 vulnerabilities） |
| 邮件参考服务组件门禁 | 154 tests；runtime 44/44、HTTP 11/11、Flyway 15/15、backup/restore 10/10 |
| 根依赖审计（dependency-check） | 94 项依赖 evidence，0 阻断（15 条限定 suppression） |
| 邮件依赖审计 | 54 项依赖 evidence，0 阻断（12 条限定 suppression） |
| 敏感产物扫描 | 714 文件；1 条精确指纹例外覆盖 4 处匹配 |
| 完整 `scripts/verify.sh` | 15/15 PASS |

### 8.4 本批残余风险

1. 跨标签页用例对 CPU 预算仍敏感：本批 22/22 通过，但历史上同配置出现过
   超预算失败。CI 并行度升高时优先降低 worker 数。
2. Vitest 只覆盖认证状态机与跨上下文协调；页面组件、路由和表单仍由
   Playwright 覆盖，不宣称组件层全覆盖。
3. 仓库仍无 V9+ 迁移；首个 V9 必须同步补 schema 指纹、fresh migrate/validate
   和 baseline guard 断言。
4. bind intent 的过期与版本失效用例通过 JDBC 制造前置状态，不覆盖真实时间
   流逝与外部并发；真实并发消费仍依赖 `consume` 的单条条件 UPDATE 语义。
5. Python 资源服务器仍只能通过 JWKS 校验签名与 session claims，无法感知
   PostgreSQL 实时撤销；这是架构边界，未在本批改变。

## 9. 第三批（2026-11-05）：页面层、跨端契约、行为映射与确定性

> 状态：Live / 已完成。本批不扩大“加固阶段”，只处理第二批列出的薄弱点：
> 前端页面/路由层、跨端 wire shape、行为映射和确定性清扫。

### 9.1 实际改动

1. 前端页面与路由层测试。
   - 新增 `frontend/tests/unit/loginPage.test.tsx`（7 个用例）：
     本地登录被拒时显示后端消息且不跳转；成功登录按 `returnTo` 回到受保护路由；
     `EMAIL_ALREADY_REGISTERED` 映射；注册需要邮箱验证时打开验证弹窗并自动发送
     `REGISTRATION` 验证码；验证码输入只保留 6 位数字；`TestPage` 在
     `GET /api/user` 401 时只显示加载态、不渲染受保护内容；成功后才渲染管理页。
   - 没有为测试改写组件结构，也没有新增全局状态或依赖注入点。
2. 跨端 wire 契约。
   - 新增 `contracts/api-wire-contract.json`，登记 `GET /api/user`、login、refresh
     的必需/可选字段、login `user` 对象字段和稳定错误体。
   - 新增 `ApiWireContractIntegrationTest`（3 个用例）：用真实 PostgreSQL + MockMvc
     断言 login 顶层与 `user` 字段集合、`GET /api/user` 字段集合精确匹配、
     refresh 成功字段子集与缺失 Cookie 的 401 错误体。
   - 新增 `frontend/tests/unit/wire-contract.test.ts`（3 个用例）：用 TypeScript
     AST 解析 `src/types/index.ts`，断言 `User` / `TokenRefreshResult` 覆盖契约字段。
   - 为对齐真实 wire，`User` 类型补充可选 `authorities?: string[]`（后端 `UserDto`
     一直返回该字段，前端此前未声明）。
   - 发现一处既有的前端防御性映射：`EMAIL_ALREADY_REGISTERED` 在后端没有对应
     emitter。保留该映射（不会误伤），但不写入跨端契约，避免把死分支固化。
3. 行为 → 测试映射。
   - 新增 `docs/TEST_MAP.md`，按认证/token、OAuth2/Web3、邮箱/密码、安全链、
     迁移/并发、前端、Python、邮件参考服务列出保护测试和层级，并单列“只有
     Playwright 覆盖的行为”。
4. 确定性清扫。
   - 复核全部 `sleep`：无无界等待、无负向观测窗口；剩余都是带次数上限的
     readiness/observable-state 轮询（`pg_isready`、JWKS、health、advisory lock）。
   - 端口全部动态分配；`localhost:5002` 等字面量只用于 Playwright route 拦截和
     validation，不绑定监听。
   - 共享可变夹具：Vitest 同文件内仍共享模块单例（`csrfToken`、single-flight 槽），
     已要求每个用例 await 完成异步操作；跨文件由 Vitest 隔离。未引入仅供测试的
     reset API，避免为测试改生产代码。
   - 复跑最终门禁时命中一个真实假失败：`flyway-baseline-existing.sh` 用
     `pg_stat_activity` 里“holder 会话是否已出现”判断锁冲突，而该会话在刚连上、
     尚未拿到 advisory lock 时也会被当成冲突，负载下直接报
     `unable to acquire the shared Flyway advisory lock`（第 15/17 个 guard 用例）。
     已改为只把 `pg_locks` 中同 key、同库、被**其它会话实际授予**的 advisory lock
     视为 busy；自己的 holder 未授予时继续有界轮询。guard 套件随即 17/17 通过。
     这是本批“确定性清扫”唯一的代码级修复。
5. Flaky 复跑入口。
   - 新增 `scripts/stress-playwright.sh`：N 次重复、可选 worker 数、CPU burner 和
     `PLAYWRIGHT_GREP`，逐次输出 PASS/FAIL，失败即非零退出，日志保留在临时目录。
   - 未接入 `scripts/verify.sh`，避免日常门禁时长乘以 N。

### 9.2 决策记录（续）

#### 决策 13：页面测试只覆盖失败路径与路由契约，不做快照/渲染样式测试

- 事实：组件结构复杂，强行渲染所有页面会制造脆弱断言。
- 结论：只覆盖认证失败消息、错误码映射、验证码输入约束、成功回跳和受保护页
  401 守卫；视觉与布局继续由既有 Playwright 覆盖。

#### 决策 14：wire 契约用仓库级 JSON 作为单一事实源，由两端各自消费

- 事实：Java 测试和前端测试无法直接共享运行时对象。
- 结论：`contracts/api-wire-contract.json` 同时被 Java MockMvc 断言和前端
  TypeScript AST 断言读取；任何字段漂移都会在至少一端失败。
- 风险：新增字段必须同时更新 JSON、DTO 和 TS 类型，否则门禁失败；这是有意设计。

#### 决策 15：`window.location` 重定向只在 Playwright 断言

- 事实：jsdom 不实现真实导航，无法在组件层观察 `window.location.href` 结果。
- 结论：组件层只断言认证状态清理与受保护内容消失；真实重定向继续由
  `refresh failure clears auth state without retrying the refresh endpoint` 覆盖。
  该边界已写入 `docs/TEST_MAP.md` 的“仅 Playwright 覆盖”。

#### 决策 16：确定性清扫以“是否有界/可观测”为标准，不盲目删除轮询

- 事实：启动等待必须轮询；删除会引入固定等待或直接失败。
- 结论：保留全部有次数上限的 readiness 轮询；只移除无上限、固定时长或负向观测的
  `sleep`。`test-http-e2e.sh` 的负向断言已在第二批改为进程退出屏障。

#### 决策 17：锁就绪判定以“锁是否真被授予”为准，不以会话是否存在为准

- 事实：holder 进程先建立连接、再执行 `pg_try_advisory_lock`，两者之间有窗口；
  用 `pg_stat_activity` 出现即判 busy 会产生与负载相关的假失败。
- 结论：只检查 `pg_locks` 中同一 key（`classid`/`objid`/`objsubid=1`）、同一
  数据库、已被其它会话授予的 advisory lock 才算冲突；其余继续轮询直到 holder
  退出或超时。保持失败关闭语义，不放松对真实持锁者的拒绝。

### 9.3 本批真实数字（完整门禁 15/15）

| 门禁 | 结果 |
|------|------|
| 根 Maven | 335 tests，0 failures / 0 errors / 0 skipped（+3 wire 契约） |
| 前端组件单测 | 22 tests / 4 files（+7 页面、+3 契约） |
| Mock / 生产 Playwright | 31/31、2/2 |
| Shell HTTP / Flyway / shared-schema | 17/17、17/17、8/8 |
| 真实邮箱浏览器 E2E | 1/1 |
| Python / 邮件 stub | 20/20、12/12 |
| 邮件参考服务 | 154 tests；runtime 44/44、HTTP 11/11、Flyway 15/15、backup 10/10 |
| 敏感产物扫描 | 720 文件；1 条精确指纹例外覆盖 4 处匹配 |
| 文档链接/patch hygiene | 63 个 Markdown 文件通过 |
| `scripts/verify.sh` | 15/15 PASS |

残余风险：同文件 Vitest 用例共享模块单例；`window.location` 重定向仍只有
Playwright 覆盖；wire 契约新增字段必须三处同步（JSON、DTO、TS 类型）；
`scripts/stress-playwright.sh` 尚未在 CI 中定时运行。

## 10. 第四批（2026-11-05）：覆盖率度量、失败关闭补强与跳转接缝

> 状态：Live / 已完成。本批不扩大“加固阶段”，只做用户指定的六项：
> 覆盖率报告（无阈值）、按报告补高风险测试、Vitest 单例隔离、
> `window.location` 可测接缝、CI 定时 flaky 探测和完整门禁复跑。

### 10.1 覆盖率度量（report-only，不设阈值）

1. 后端：`pom.xml` 增加 `org.jacoco:jacoco-maven-plugin:0.8.13`，
   `prepare-agent` + `test` 阶段 `report`；没有 `check` goal、没有阈值，
   `mvn test` 成功或失败都不因覆盖率改变。报告输出 `target/site/jacoco/`。
2. 前端：`frontend/package.json` 增加 `test:unit:coverage`；`vitest.config.ts`
   使用 v8 provider，输出 `text-summary`、`text`、`json-summary`、`html` 到
   `frontend/coverage/`；`include` 为 `src/**/*.{ts,tsx}`。`coverage/` 已加入
   `.gitignore` 和 `eslint.config.js` 的 `globalIgnores`，避免生成物进入
   lint 和敏感扫描。
3. 实跑结果（`mvn test` + `npm run test:unit:coverage`）：
   - 后端 159 个类：行覆盖 85.6%（3811/4450）、分支 68.5%（1113/1624）、
     方法 89.3%（654/732）、类 97.5%（155/159）。
   - 前端：语句 32.17%、分支 30.66%、函数 46.42%、行 31.9%。低值是预期：
     页面组件主要由 Playwright 覆盖，Vitest 只保护纯逻辑和关键失败路径。
   - 已知度量限制：`App.tsx` 和 `HomePage.tsx` 依赖 Vite 虚拟模块
     `virtual:diagnostics-routes`，v8 provider 的静态解析会跳过这两个文件；
     它们是 Playwright 覆盖的页面，不因此误判为“已覆盖”。

### 10.2 覆盖率发现的真实盲区与处理

| 维度 | 盲区 | 处理 |
|------|------|------|
| 认证/授权 | `GlobalExceptionHandler` 的 401、403、503 映射和 validation 无 field error 回退分支完全没有用例 | 补充单元测试 |
| 认证/token | `TokenValidationService` 的 refresh claim 拒绝矩阵（type/alg/kid/issuer/jti/subject/username/sid/generation/timestamps/revoked/user/family/disabled/version/generation mismatch）只有部分被间接覆盖 | 补充单元测试（22 个用例） |
| 认证/限流 | `AuthRateLimiter` 的禁用旁路、429 retry-after、数据库失败 503、清理失败、输入截断 | 补充单元测试 |
| 持久化/投递 | `EmailDeliveryOutboxStateService` 的“外部已接受但本地不能激活”、最大尝试、deadline、非 UNUSED、退避越过 deadline | 补充单元测试 |
| 身份边界 | `CanonicalEmailService` 的长度/字符/本地部分/域名 label 拒绝分支 | 补充单元测试 |
| Web3 | `Web3SignatureUtils` 的地址形状、签名长度、recovery id、无法恢复公钥的失败关闭 | 补充单元测试 |
| 路由 | `SpaController` 的 react/non-react 回退与 `/api`、`h2-console`、`favicon` 排除 | 补充单元测试 |

明确**不补**的类（避免把死代码/生成代码固化为契约）：
`CustomUserDetailsService`（第五批重新核查后修正结论：框架可达、应用不可达，
见 §11.6；第四批当时判断为“待清理死代码”）、`VerificationCodeDeliveryException`
（纯异常数据类）、
`TokenBlacklistEntity`（实体）、`SecurityConfig$3`（lambda）、`UniAuthApplication`
（启动类）。这些条目保留为残余风险，而不是用测试掩盖。

### 10.3 新增测试（共 67 个用例）

| 测试类 | 用例数 | 为什么值得覆盖 |
|--------|--------|----------------|
| `config/GlobalExceptionHandlerTest`（扩展） | +4 | 401/403/503 是安全链最外层失败关闭；错误码和 path 漂移会直接改变客户端契约 |
| `service/CanonicalEmailServiceTest` | 12 | canonical email 是注册/重置/challenge 的身份主键，放宽校验会让两个身份合并 |
| `util/Web3SignatureUtilsTest` | 8 | 钱包签名是凭据边界，畸形签名必须返回 false/抛错而不是恢复出意外地址 |
| `service/AuthRateLimiterTest` | 6 | 限流数据库故障必须 503 而不是放行；429 的 retry-after 语义是稳定契约 |
| `service/EmailDeliveryOutboxStateServiceTest` | 12 | outbox 决定 challenge 是否可验证；错误地激活/重试会削弱邮箱验证 |
| `service/TokenValidationServiceTest` | 22 | refresh claim 与 session 状态是 token family 的最后一道闸门 |
| `controller/SpaControllerTest` | 3 | SPA fallback 不得吞掉 `/api` 等后端前缀 |

以上全部为纯单元测试（无容器、无网络、无时钟依赖），定向运行
`mvn test -Dtest=...` 69/69 通过；随后完整 Maven 从 335 增至 402。

### 10.4 Vitest 模块单例隔离

选择 **`beforeEach` 显式重置 + 隔离断言**，不拆文件。理由：

- 拆文件只能隔离文件之间的模块状态；同一文件内 `csrfToken` 和 single-flight
  槽仍会跨用例泄漏，问题本质没有解决。
- `AuthService.clearLocalAuthentication()` 是生产已有的清理路径（同时清
  `csrfToken`、本地用户和 refresh 存储），用它做重置不会为测试新增生产 API。
- 新增三条 CSRF 用例，其中“clear 后下一次 `csrfHeader()` 必须重新 GET”直接
  断言隔离有效；single-flight 新增“成功后槽已释放”断言。失败或泄漏时用例
  会以“只发起一次请求”的形式暴露。

### 10.5 `navigateTo` 接缝

- 新增 `frontend/src/utils/navigation.ts`：`navigateTo(url, location = window.location)`
  调用 `location.assign(url)`。`assign` 与 `href =` 行为等价，生产行为不变。
- 替换 5 处直接赋值：`useAuth.logout`、`useAuth.oauthLogin`、`HomePage` 改密回跳、
  `LoginPage` 登录回跳、`TestPage` OAuth 绑定。
- 新增 `navigation.test.ts` 2 个用例（注入 location 的目标断言 + 默认
  `window.location.assign` 路径）；`useAuth.test.tsx` 通过 mock 断言 logout 目标
  `/login` 和 oauthLogin 目标 `/oauth2/authorization/github`。真实浏览器跳转仍由
  Playwright 覆盖，接缝不替代浏览器层验证。

### 10.6 CI 定时 flaky 探测

- 新增 `.github/workflows/playwright-flaky-probe.yml`：每周一 03:00 UTC 定时 +
  `workflow_dispatch`；`npm ci` 后安装 chrome，调用
  `scripts/stress-playwright.sh`（默认 5 次、3 worker），`if: always()` 上传
  stress 日志和 `frontend/test-results`（trace），保留 14 天。
- 与 `verification.yml` 完全独立：定时 job 失败不会改变 PR/push 必需门禁的成功
  语义，只在 Actions 中暴露 flaky 证据。没有把 stress 脚本放进 `verify.sh`，
  避免日常门禁时长乘以 N。

### 10.7 度量过程中的一个事实修正

`Web3SignatureUtils.verifySignatureStructure` 只校验 `0x` 前缀和 65 字节长度，
**不校验十六进制字符**（web3j 的 `hexStringToByteArray` 对该输入也不抛错）。
本批没有改生产行为，而是在公开边界新增断言：非 hex 载荷经 `verifySignature`
仍然返回 false。该事实写入测试注释，防止后续误以为结构检查已经做了字符校验。

### 10.8 本批真实数字（完整门禁 15/15）

| 门禁 | 结果 |
|------|------|
| `mvn test` | 402 tests，0 failures / 0 errors / 0 skipped（+67） |
| 前端 `test:unit` | 29 tests / 5 files（+7） |
| Mock Playwright | 31/31 |
| 生产 Playwright | 2/2 |
| Shell HTTP / Flyway / shared-schema | 17/17、17/17、8/8 |
| 真实邮箱浏览器 E2E | 1/1 |
| Python 资源服务器 / 邮件 stub | 20/20、12/12 |
| 邮件参考服务 | 154 tests；runtime 44/44、HTTP 11/11、Flyway 15/15、backup 10/10 |
| 前端 lint / tsc / build / audit | PASS（0 vulnerabilities） |
| 敏感产物扫描 | 729 文件；1 条精确指纹例外覆盖 4 处匹配 |
| 文档链接/patch hygiene | 63 个 Markdown 文件通过 |
| `scripts/verify.sh` | 15/15 以 `PASS: complete repository verification gate` 结束 |

### 10.9 本批残余风险

1. `CustomUserDetailsService` 和 `AuthenticationManager` bean 仍是无调用方的死代码；
   本批没有删除它们（超出测试批次范围），也没有用测试固化该路径。
   **修正（第五批 §11.6）**：`CustomUserDetailsService` 被 Spring Security 注册进
   全局 `AuthenticationManager`，属于“框架可达、应用不可达”，已在 §11.6 补直接
   单测；`SecurityConfig.authenticationManager()` bean 才仍然没有任何注入点。
2. `TokenValidationService` 的 access-token 分支（约第 61、235、271、303、305、325 行）
   仍未被单元测试直接覆盖；生产访问路径由 Resource Server 解码和既有集成测试保护。
3. `AuthRateLimiter.limit()` 的逐策略 switch 分支没有逐个单测；策略枚举是编译期
   穷尽的，单独补测只会增加覆盖数字。
4. 前端行覆盖仍低（页面组件依赖 Playwright）；`App.tsx`/`HomePage.tsx` 无法进入
   v8 报告。不能把前端覆盖率理解为“页面已充分测试”。
5. 定时 flaky job 只覆盖 Mock Playwright，未覆盖生产 Playwright、真实邮箱浏览器
   E2E 和 Shell E2E；它也不能在本地复现 GitHub runner 的调度/资源差异。
6. 覆盖率仍是 report-only；数字下降不会让门禁失败，必须靠人工按本记录复查。

## 11. 第五批（2026-11-05）：测试强度、顺序独立性与覆盖率棘轮

> 状态：Live / 已完成。本批不再追加数量指标，而是验证“这些测试本身够不够强”：
> 随机顺序、覆盖率棘轮、作用域变异测试、`TokenValidationService` access 分支、
> CI 探测扩展、`CustomUserDetailsService` 可达性证明和完整门禁复跑。

### 11.1 测试顺序独立性

1. **后端**：Surefire 本身支持 `-Dsurefire.runOrder=random`，不需要在 `pom.xml`
   里加常驻配置（常驻 random 会破坏失败复现）。本次以固定种子跑完整套件：

   ```bash
   mvn test -Dsurefire.runOrder=random -Dsurefire.runOrder.random.seed=20261105
   ```

   结果：402 tests（当时尚未加入本批新用例），0 failures / 0 errors，
   `BUILD SUCCESS`，类执行顺序与文件名字典序明显不同。没有发现依赖执行顺序、
   共享数据库状态或静态可变状态的用例，因此本批没有为顺序独立性改动任何夹具。
   集成测试都使用 disposable PostgreSQL/Testcontainers 和每类独立的 Spring
   上下文，`@DirtiesContext`/单例容器没有暴露跨类污染。补入本批新用例后，再用
   不同种子 `20261106` 复跑：**422 tests、56 个测试类，0 failures / 0 errors /
   0 skipped**，仍未出现顺序依赖。
2. **前端**：Playwright 没有 `--shuffle`。新增
   `scripts/test-playwright-shuffled.sh`：先 `--list` 列出全部测试，用可复现种子
   打乱，再用 `--test-list` 以 `--workers=1` 顺序重放。本次以种子 `20261105`
   实跑 **31/31 通过**；执行顺序与文件自然顺序不同（`security-boundaries` 用例
   被排到末尾）。没有发现跨文件状态泄漏。
3. 结论：当前套件对顺序不敏感；随机顺序保留为可选探针，不进入主门禁
   （主门禁保持确定性顺序，便于稳定复现失败）。`scripts/verify.sh` 仍按固定顺序
   执行。

### 11.2 覆盖率棘轮（不设硬阈值）

新增 `scripts/coverage-ratchet.py` 与 `config/coverage-baseline.json`：

- 后端读取 `target/site/jacoco/jacoco.csv`（`mvn test` 已生成），按 `LINE_*` /
  `BRANCH_*` 汇总；前端读取 `frontend/coverage/coverage-summary.json` 的
  `total.lines.pct` / `total.branches.pct`。
- 基线不锁定绝对阈值，只锁定“本次不得比上次低超过容忍值”：
  后端 1.0pp、前端 2.0pp（前端页面层由 Playwright 覆盖，波动更大，故放宽）。
- 失败时输出 `baseline / current / drop / tolerance`，并给出**显式更新步骤**：
  `python3 scripts/coverage-ratchet.py --update`；若要把基线调低到容忍值以下，
  必须额外传 `--allow-lower`，否则脚本拒绝写入。
- 接入方式：**并入 `scripts/verify.sh` 现有阶段**，不新增第 16 阶段。
  - 阶段 7 `mvn test` 之后执行 `coverage-ratchet.py --backend-only`；
  - 阶段 10 把 `npm run test:unit` 改为 `npm run test:unit:coverage`，
    并执行 `coverage-ratchet.py --frontend-only`。
  理由：主门禁已经是仓库唯一的统一入口；另起脚本容易被绕过。覆盖率的
  “报告-only”属性保持——只有**相对基线下跌**才失败，绝对数字仍不设阈值。
- 当前基线：后端行 85.64%、分支 68.53%；前端行 31.9%、分支 30.66%。

### 11.3 作用域受限变异测试（PIT）

在 `pom.xml` 新增**可选 profile** `mutation-testing`（`pitest-maven 1.17.2` +
`pitest-junit5-plugin 1.2.3`），只锁定三个安全关键类及其测试：

```bash
mvn -Pmutation-testing test-compile \
  org.pitest:pitest-maven:mutationCoverage
```

- **环境约束（真实踩坑）**：本机默认 JDK 25 时 PIT 1.17.2 报
  `Unsupported class file major version 69`，把 `TokenValidationService` 的错误
  变异体全部标成 `NO_COVERAGE`，结果不可用。改用仓库已安装的 JDK 21 后正常
  （项目 target 仍是 17）。该约束写入本记录；日常 `mvn test` 不受影响。
- 基线（补强前）：150 mutants、124 killed（82.7%）、test strength 87%。
- 补强后：**150 mutants、143 killed（95.3%）、4 survived、3 no-coverage**，
  行覆盖（被变异类）93%。

存活变异体逐个判定：

| 类 / 行 | 变异 | 判定 | 处理 |
|---------|------|------|------|
| `CanonicalEmailService` L32 | `separator <= 0` → `< 0` | 等价：`@example.com` 在后续 local-part 校验仍被拒绝 | 记录，不补 |
| `CanonicalEmailService` L35 | `length - 1` → `length + 1` | 等价：仅相差尾随 `@`，后续空域名校验拒绝 | 记录，不补 |
| `CanonicalEmailService` L69 | `domain.length() > 253` → `>= 253` | 等价：canonical email 上限 254 使域名最多 252 字符，两分支都不可达 | 记录，不补 |
| `CanonicalEmailService` L85 | `character > 0x7f` → `>= 0x7f` | 等价：0x7F 是 ISO control，两种比较都拒绝 | 记录，不补 |

`no-coverage` 三项：`TokenValidationService` 的两个 record 默认返回
（`Optional.empty()`/访问器）和 `Web3SignatureUtils` L118 的
`hexStringToByteArray` catch。后者不是测试遗漏：web3j 的 parser 对偶数长度
非 hex 输入**不抛异常**，该 catch 是防御性死分支（10.7 已记录同一事实）。

补强后新增/加强的断言（都是“存活且代表真实漏洞”的变异）：

- `TokenValidationService`：`generation`、`ver`、`auth_time` 为 **0** 时是合法值
  （首个 token generation、从未改密的用户、非 recent-auth），朴素的 `<= 0`
  会把这类用户锁死；`auth_time == 0` 必须映射为 `null` 而不是 epoch。
- `CanonicalEmailService`：255 字符登录标识符必须被接受；任意位置的 `@`
  （包括 index 0）都必须走邮箱 canonicalize，不能当作普通用户名。
- `Web3SignatureUtils`：真实签名 round-trip —— 已知私钥签名后恢复出**精确地址**；
  覆盖 `v=27/28` 与归一化 `v=0/1` 两条路径（打死 `v += 27` / `recId = v - 27` /
  `arraycopy` 删除）；`recId=3` 必须通过范围检查；`verifySignature` 的 true 分支
  和错误地址的 false 分支。

### 11.4 `TokenValidationService` access-token 直接单测

`TokenValidationServiceTest` 从 22 增至 34 个用例（新增 12 个），明确两条路径差异：

| 关注点 | access token | refresh token |
|--------|--------------|---------------|
| 解码器 | 注入的 session-aware `activeAccessTokenDecoder` | `parseSignedToken` + 本地 claim/session 校验 |
| generation | `family.currentGeneration >= token.generation`（正常轮换后旧 access 仍有效） | 必须与当前 generation **精确相等** |
| 撤销查询 | `accessTokenForRevocation` 只用 signed-only decoder，能读取已撤销 token | `refreshTokenForRevocation` 同样 signed-only |
| introspection | 返回规范化 audience（默认 `resource-server`） | audience 为 null |
| 会话失败 | 以 `OAuth2Error("invalid_token", "Token session is inactive")` 返回，不抛异常 | 抛出 `JwtException` |

实现事实：`decodeAccessToken` 完全委托给注入的 decoder，claim 校验实际发生在
`validateActiveAccessToken`（被包进 decoder 的 validator）里。因此直接单测必须走
`validatedAccessToken` / 捕获 validator；只调 `decodeAccessToken` 不会触发 claim
拒绝。这一点已写入测试注释，避免后来者误判覆盖。

### 11.5 CI 定时探测扩展

`.github/workflows/playwright-flaky-probe.yml` 在同一 schedule 下新增三个独立 job
（仍与 `verification.yml` 成功语义完全解耦）：

| job | 覆盖 | 关键约束 |
|-----|------|----------|
| `stress-playwright`（既有） | Mock Playwright 重复跑 | 5 次、3 worker |
| `shuffled-playwright`（新） | 乱序 Mock Playwright | 固定种子 20261105/06/07，1 worker |
| `production-playwright`（新） | 生产 Playwright 2/2 | 先 `npm run build`，再 `test:e2e:production` |
| `shell-http-e2e`（新） | Shell HTTP E2E 17/17 | ubuntu-latest + Docker + Java 17/Maven + `postgresql-client` |

替代方案：`shell-http-e2e` 依赖 Docker 和 PostgreSQL client；若 runner 不满足，
在文档中保留本地命令 `scripts/test-http-e2e.sh`，并改用 self-hosted runner。
`production-playwright` 只验证构建产物，不启动后端真实服务。

### 11.6 `CustomUserDetailsService` 可达性结论

调查范围：Spring 装配、反射/`getBean`、配置引用、启动日志。

- 它是 `@Service`，Spring 会实例化；`WebSecurityConfig` 构造注入它但**从不调用**。
- `SecurityConfig` 的 `authenticationManager()` bean 注入了它，但该 bean 没有任何
  注入点；本地登录走 `AuthController` → `CredentialAuthenticationService`，与它无关。
- 但启动日志（`mvn test` 的上下文日志）出现
  `Global AuthenticationManager configured with UserDetailsService bean with name customUserDetailsService`：
  Spring Security 的 `InitializeUserDetailsManagerConfigurer` 把它注册进**全局**
  `AuthenticationManager`。也就是说，一旦任何人启用 `formLogin()`/`httpBasic()` 或
  注入全局 `AuthenticationManager`，它会立即变成可执行认证入口。
- 结论：**框架可达、应用不可达**。它不是纯死代码，不能无声删除；本批保留并补
  `CustomUserDetailsServiceTest`（4 个用例：正常加载、未知用户、禁用用户、
  V6 允许的“无密码 LOCAL 行”必须失败关闭而不是构造空密码 principal）。
  后续若要清理，必须先移除全局 `UserDetailsService`/`AuthenticationManager` 装配，
  作为独立变更评审，不做本批大范围删除。

### 11.7 本批真实数字（完整门禁 15/15）

| 门禁 | 结果 |
|------|------|
| `mvn test` | 422 tests，0 failures / 0 errors / 0 skipped（+20） |
| 随机顺序完整套件 | **422 tests / 56 classes 全通过**（种子 20261106；补测前 402 tests 亦通过） |
| Mock Playwright 乱序 | 31/31（种子 20261105，1 worker） |
| 前端 `test:unit:coverage` | 29 tests / 5 files |
| Mock Playwright | 31/31 |
| 生产 Playwright | 2/2 |
| Shell HTTP / Flyway / shared-schema | 17/17、17/17、8/8 |
| 真实邮箱浏览器 E2E | 1/1 |
| Python 资源服务器 / 邮件 stub | 20/20、12/12 |
| 邮件参考服务 | 154 tests；runtime 44/44、HTTP 11/11、Flyway 15/15、backup 10/10 |
| PIT（3 类） | 150 mutants，143 killed（95.3%），4 等价存活，3 no-coverage |
| 覆盖率棘轮 | 基线：后端 85.64/68.53、前端 31.9/30.66；门禁实跑：后端 85.80/68.29、前端 31.9/30.66，均在容忍内；人为抬高基线时脚本失败 |
| 前端 lint / tsc / build / audit | PASS（0 vulnerabilities） |
| `scripts/verify.sh` | 15/15 以 `PASS: complete repository verification gate` 结束 |

门禁复跑说明：第一次完整复跑在阶段 12（真实邮箱浏览器 E2E）的
`Email browser E2E 1/6` 处报 `FAIL: PostgreSQL did not become ready`。当时 Docker
已有 15 个与本仓库无关的容器在运行；该容器在 60s 预算内未就绪属于环境资源抖动，
不是代码或测试断言失败。其余阶段（含阶段 7/10 的两次覆盖率棘轮）均已通过。
未改动任何脚本或夹具，直接重新完整复跑，第二次 15/15 通过，阶段 12 为 1/1。

### 11.8 本批残余风险

1. 随机顺序只跑了固定种子；后续新增测试仍可能引入顺序依赖。探针保留为命令
   和脚本，但未加入定时 CI。
2. PIT 需要 JDK 21（或任何 PIT 1.17.2 ASM 能读的运行时），默认 JDK 25 会产生
   全 `NO_COVERAGE` 的假结果；profile 本身不检查 JDK，需按记录选用。
3. 覆盖率棘轮只比较行/分支百分比，不防止“高覆盖低断言”（例如只执行不校验）。
   变异测试是补充，不是持续门禁。
4. `CustomUserDetailsService` 仍是“框架可达、应用不可达”的潜在入口；保留而非删除。
5. 生产 Playwright 与 Shell HTTP E2E 只在 schedule 上探测；失败不阻塞 PR。
6. `TokenValidationService` 的 access 直接单测依赖 mock decoder，真实签名/过期/
   issuer 校验仍由 Spring `JwtDecoder` 和集成测试保证。

## 12. 第六批：资源紧张下的假失败与测试腐化守卫

目标：让底座在被压满的宿主机上不产生假失败，并阻止测试质量随时间下降。

### 12.1 PostgreSQL 就绪抖动的根因

现象：第五批第二次完整门禁时，阶段 12 的 `Email browser E2E 1/6: start disposable
PostgreSQL` 报 `FAIL: PostgreSQL did not become ready`，当时本机已有 15 个无关容器。

排查结论：**不是 60 秒预算不够，而是父脚本实际只等了 12 秒。**
`scripts/test-email-login-browser-e2e.sh` 自己的就绪循环是
`for _ in $(seq 1 60)` + `sleep 0.2`，合计 12s；真正的容器启动脚本
`scripts/email-login-e2e/start-postgres.sh` 内部却是 `60 × sleep 1` = 60s。
父脚本先放弃，子脚本的诊断信息还没来得及输出，失败被记成“60s 未就绪”，掩盖了
“预算不一致”这个真实问题。固定次数 × 固定 sleep 的轮询既不可校准，也无法给出
可诊断的失败现场。

### 12.2 修法：共享的有界自适应就绪

新增 `scripts/lib/postgres-readiness.sh::wait_for_disposable_postgres`：

- 退避 0.25s → 0.5s → 1s → 2s 封顶，避免在宿主机饱和时刷屏或空转；
- 总预算默认 60s，可用 `POSTGRES_READY_TIMEOUT_SECONDS` 显式覆盖；
- 每 5 秒打印 `elapsed / attempts / last pg_isready`；
- 超时后打印最后一次 `pg_isready`、`docker inspect` 状态/退出码和最后 100 行容器
  日志，然后**返回非零**——真实故障仍然失败关闭，不会因为重试被吞掉；
- `postgres:16.13` 启动不再使用 `docker run --rm`：容器崩溃时日志必须还在，
  清理继续由各 harness 的 `EXIT` trap 完成。

应用位置：`test-email-login-browser-e2e.sh`、`start-postgres.sh`、
`test-http-e2e.sh`（两个数据库）、`test-email-shared-schema-e2e.sh`、
`test-flyway-baseline-guard.sh`、`test-auth-backup-restore-rehearsal.sh`、
`flyway-baseline-existing.sh`。`scripts/verify.sh` 阶段 1 的 `bash -n` 同步覆盖
`scripts/lib/*.sh`。

未触达 `reference/email-service` 的自带 harness：它是独立组件、有自己的
`scripts/verify.sh` 门禁，本批不扩大改动面；若其脚本仍使用固定次数轮询，属于
一致性遗留（见 12.7）。

### 12.3 压测与失败路径实测

- 本机在第六批开始时就持续运行着 **15 个与本仓库无关的容器**
  （brave-search/perplexity-ask MCP、其他项目的 PostgreSQL 等），复现了第五批的
  原始条件。在该状态下启动 disposable PostgreSQL：
  `READY PostgreSQL 127.0.0.1:55432 after 4s (5 attempts)`。
  旧实现只有 12s 预算，只剩 8s 余量；任何额外抖动（镜像层加载、initdb、IO 竞争）
  都会越过它。
- 失败路径探针：用 `--entrypoint /bin/false` 启动一个必然退出的容器，预算 4s。
  输出 `docker state: status=exited running=false exit=1` 并以 `EXIT=1` 结束，
  证明超时不会伪成功。
- 本批两次完整门禁都在同一台仍保有 15 个无关容器的机器上通过，见 12.6。

### 12.4 测试腐化守卫

先跑真实清单再定规则，避免误伤既有合法写法。`--list` 的初始命中是 **7 处固定等待**
（**已过时**：第七批已把这 7 处全部改成确定性等待，`config/test-rot-baseline.json`
随后清空，见 §13.1；本节仅保留当时的历史结论）：

| 位置 | 模式 | 判定 |
|------|------|------|
| `RestTemplateEmailServiceIntegrationTest`、`EmailDeliveryPipelineIntegrationTest` 等 4 处 Java | `Thread.sleep(...)` | 历史并发/超时窗口，保留 |
| `frontend/tests/e2e/security-boundaries.spec.ts` 等 3 处 | `new Promise(...setTimeout(100))` | 显式竞态窗口，保留 |

没有发现 `.skip`、`.only`、`@Disabled`、恒真断言、空测试或 `TODO/FIXME`。
规则最终为：`skipped-test`、`focused-test`、`constant-assertion`、`no-assertion`、
`empty-test`、`fixed-wait`、`unfinished-marker`，每条都带“为什么危险”的说明。

- 基线 `config/test-rot-baseline.json` 当时只记录这 7 处；新增命中打印
  `path:line [rule]` 与原因并失败。**已过时**：第七批清空；第十一批收紧
  `no-assertion`（`assertDoesNotThrow` / `doesNotThrowAnyException` 不再算断言）
  后为 11 条人工复核的“仅验证不抛异常”守卫用例，见 §17.6。
- `python3 scripts/check-test-rot.py --update` 是登记例外的显式步骤。
- `scripts/test-test-rot-guard.sh` 用临时夹具做正负向自测：违规树被拒、基线后可过、
  在基线之上再加一条新违规仍失败、已基线条目不会重复报告；第十一批补入
  `assertDoesNotThrow` 与 `assertThatCode(...).doesNotThrowAnyException()` 必须
  命中、`assertThatCode(...).isInstanceOf(...)` 不得误伤两条用例。
- 接入 `scripts/verify.sh` 阶段 1，不新增阶段。

误伤处理记录（先跑清单的直接收益）：

1. 初版把 Playwright `test.setTimeout(60_000)` 当成固定等待；已把 TS 规则收窄为
   `waitForTimeout` 和 `new Promise...setTimeout`，预算声明不再命中。
2. 初版 Java 花括号计数被字符串里的 `${...}` 干扰，导致 `@Test` 方法被误判为
   “无断言”；现在先剥离字符串/行注释再计数。
3. 初版“方法前 6 行有 `@Test`”会把紧跟在测试后的 private helper 当成测试；
   改为只认紧邻注解，并跳过 `private` 方法。

### 12.5 文档与变异工具常规化

- 新增 [测试约定](../TESTING.md)：测试分层与存放位置、Testcontainers/夹具纪律、
  禁止 sleep/固定端口、wire 契约三处同步、覆盖率棘轮、PIT、腐化守卫、随机顺序。
  已加入 [文档导航](../README.md) 的“首先阅读”。
- 变异测试从一次性抽查变为固定工具：命令固定为
  `JAVA_HOME=<jdk21> mvn -Pmutation-testing test-compile org.pitest:pitest-maven:mutationCoverage`，
  范围固定为三个安全关键类；
  本机 PIT 统计约 5–8 秒。结果基线写入 `config/mutation-baseline.json`
  （150 mutants / 143 killed / 3 no-coverage），由
  `scripts/check-mutation-baseline.py` 按类比较，下降即失败。
- 新增 `.github/workflows/mutation-probe.yml`：每周四 04:00 UTC 定时或手动触发，
  JDK 21，独立于 `verification.yml`。不放进 `scripts/verify.sh`，因为主门禁使用
  本机默认 JDK，而 PIT 在 JDK 25 会把变异体误标为 `NO_COVERAGE`。

### 12.6 两次完整门禁

两次都在同一台仍保有 15 个无关容器的机器上执行完整 `scripts/verify.sh`。
第 2 次刻意不清理第 1 次在仓库外留下的产物/日志，只复用同一工作树，用于验证
可重复性和“无残留状态依赖”。

| 项目 | 第 1 次 | 第 2 次（沿用第 1 次产物） |
|------|---------|---------------------------|
| `scripts/verify.sh` | 15/15，`VERIFY1_EXIT=0` | 15/15，`VERIFY2_EXIT=0` |
| `mvn test` | 422/422，0 failures/errors/skips | 422/422，0 failures/errors/skips |
| 前端覆盖率棘轮 | 行 86.09%、分支 68.78%（基线 85.64/68.53） | 同左 |
| 前端覆盖率棘轮 | 行 31.90%、分支 30.66%（与基线一致） | 同左 |
| Vitest 组件单测 | 29/29（5 个文件） | 29/29（5 个文件） |
| Mock Playwright | 31/31（18.7s） | 31/31（10.7s） |
| 生产 Playwright | 2/2（4.9s） | 2/2（4.3s） |
| 邮箱登录浏览器 E2E | 1/1（8.0s） | 1/1（7.0s） |
| 邮件参考服务 | 154 tests；runtime 44/44、HTTP 11/11、Flyway 15/15、backup/restore 10/10 | 同左 |
| 根 HTTP E2E / Flyway guard | 17/17、17/17 | 17/17、17/17 |
| Shared-schema process E2E | 8/8 | 8/8 |
| Python 资源服务器 / 邮件 stub contract | 20/20、12/12 | 20/20、12/12 |
| 测试腐化守卫 | 7 命中/7 基线/0 新增；自测 4/4 | 同左 |
| 敏感产物扫描 | 741 个文件、4 条活动例外 | 741 个文件、4 条活动例外 |
| 文档链接 | 64 个 Markdown 全部可解析 | 64 个 Markdown 全部可解析 |
| disposable PostgreSQL 就绪 | `READY after 4s (5 attempts)` | `READY after 6s (6 attempts)` |

结论：两次结果逐项一致，没有因第 1 次遗留 `target/`、`node_modules/`、覆盖率报告或
Playwright 产物而改变行为；就绪耗时从 4s 变为 6s，但都远低于 60s 预算，且每次都由
同一 helper 记录 attempts 与最后一次 `pg_isready`，说明轮询本身不是假成功来源。

### 12.7 残余风险

1. 就绪 helper 只覆盖根仓库的 7 个 harness；`reference/email-service` 自带 Shell
   harness 仍是独立的固定次数轮询，未纳入本批改动面。
2. 60s 预算是经验值。宿主机极端饱和（例如同时拉起多个 `postgres:16.13` 首次
   `initdb`）仍可能超时；此时会打印容器状态和日志并失败关闭，需要人工判断是
   资源不足还是真实故障，而不是自动放宽超时。
3. 腐化守卫是静态规则：它能拦截跳过、只跑、恒真、空测试、固定等待和未完成标记，
   但不能证明断言“有意义”。覆盖率棘轮同样只比较高覆盖低断言的百分比。
4. 基线白名单是人工登记的例外。后续若确需新增固定等待，必须显式执行
   `--update` 并在评审中说明理由，否则门禁会失败。
5. PIT 需要 JDK 21；每周 job 与主 `verification.yml` 分离，因此 PIT 回归最多延迟
   一周才被发现，不阻塞 PR。变异分数下降由 `check-mutation-baseline.py` 拦截。
6. `Thread.sleep` 类固定等待仍是现有并发测试的实现手段；本批只阻止新增，不承诺
   消除存量。（第七批已把登记的 7 处全部改写，见第 13 节。）

## 13. 第七批：固定等待清零、邮件就绪统一与快速反馈入口

### 13.1 七处固定等待的改写

原则：等待“真实状态或真实事件”，并带明确上限和失败信息；仅用于模拟延迟的夹具
不再睡固定毫秒，而是等客户端断连或等测试显式释放。

| # | 位置 | 原写法 | 新写法 |
|---|------|--------|--------|
| 1 | `OAuth2HttpClientConfigTest`（两处，约 L165/L204） | `Thread.sleep(5_000)` 保持连接 | `accept` 后循环 `read()` 直到客户端在 250ms 读超时后断连（EOF/复位），`setSoTimeout(5s)` 只作安全上限 |
| 2 | `PasswordChangeSecurityIntegrationTest` L264 | `while (300) { ...; Thread.sleep(10) }` | `Awaitility.await("password-change UPDATE in progress").atMost(3s).pollInterval(10ms).until(...)` |
| 3 | `RestTemplateEmailServiceIntegrationTest` L157 | 慢端点 `Thread.sleep(500)` | 静态 `CountDownLatch`；测试在客户端返回 `FAILED` 后 `countDown`，handler `await(5s)`，超时抛出带说明的异常 |
| 4 | `frontend/tests/e2e/security-boundaries.spec.ts` L326 | refresh route 内 `setTimeout(100)` | 直接 fulfill；并发由既有 401 barrier 释放全部调用方保证 |
| 5 | `frontend/tests/e2e/security-boundaries.spec.ts` L405 | 跨标签 refresh route 内 `setTimeout(100)` | 同上，由跨标签过期调用 barrier 保证 |
| 6 | `frontend/tests/email-login-e2e/email-login-resource.spec.ts` L40 | `while (30s) { ...; setTimeout(100) }` | `expect.poll(..., {timeout: 30s, intervals: [100,250,500,1000]})` + `.toMatch(/^\d{6}$/)` |

7 处命中 = 4 处 Java（第 1 行两处、第 2/3 行各一处）+ 3 处前端（第 4/5/6 行）。

验证：`OAuth2HttpClientConfigTest` 18/18、`PasswordChangeSecurityIntegrationTest`
与 `RestTemplateEmailServiceIntegrationTest` 通过；`security-boundaries.spec.ts`
两个并发用例 `--repeat-each=8` 共 16/16 通过，证明去掉人工延迟后仍能稳定断言
“只刷新一次”。

基线处理：`python3 scripts/check-test-rot.py --update` 后
`config/test-rot-baseline.json` 变为 `{"version":1,"entries":[]}`；
`--list` 输出 “0 hit(s) across 0 file(s)”；`scripts/test-test-rot-guard.sh` 4/4
仍证明新增违规会失败。

### 13.2 邮件组件就绪统一

`reference/email-service` 是独立工程，`scripts/verify.sh` 只快照本目录，因此不能
直接 source 仓库根 helper。新增组件内等价实现
`reference/email-service/scripts/lib/postgres-readiness.sh`（逻辑与根一致：0.25→2s
退避、60s 预算、每 5s 进度、超时打印 `pg_isready`/`docker inspect`/log tail、
非零返回）。

改写的三个 harness：`test-http-e2e.sh`、`test-flyway-baseline-guard.sh`、
`test-backup-restore-rehearsal.sh`；同时去掉 `docker run --rm`（崩溃日志必须保留
到 EXIT trap 清理），并把 `scripts/lib/*.sh` 纳入组件与根门禁的 `bash -n`。

组件门禁实测：154 tests；runtime 44/44、HTTP 11/11、Flyway 15/15、
backup/restore 10/10；三处就绪分别为 `READY 6s (6 attempts)`、
`6s (6 attempts)`、`4s (5 attempts)`。

### 13.3 快速反馈入口

新增 `scripts/verify-fast.sh`：后端 `mvn clean compile test-compile` + `mvn test`，
前端 `npm run lint` + `npx tsc --noEmit` + `npm run test:unit`。前置检查
`frontend/node_modules` 存在，否则提示先 `npm ci`。结束语固定打印“不是交付门禁”
以及被跳过的阶段。

实测：退出码 0，**126 秒**（本机，与邮件组件门禁串行执行后立即运行）。它不启动
真实后端/Vite，不跑 Playwright、Shell E2E、Flyway、邮件组件、Python、供应链审计、
敏感扫描、文档链接和覆盖率棘轮，因此不能替代 `scripts/verify.sh`。

### 13.4 最慢用例分析

后端 422 个用例的 testcase 时间总和 29.6s；最慢 5 个：

| 用例 | 时间 | 结论 |
|------|------|------|
| `FlywayExistingSchemaBaselineIntegrationTest.approvedExistingSchemaCanBeBaselinedAndApplicationStartsWithoutSchemaMutation` | 2.84s | 真实 baseline + 启动 + schema 指纹断言，压缩只能靠 mock，牺牲断言，**不改** |
| `PasswordChangeSecurityIntegrationTest.concurrentChangesAllowExactlyOneWinner` | 1.91s | 用 PostgreSQL trigger `pg_sleep` 制造真实重叠；缩短会减小竞争窗口，**不改** |
| `LoginMethodConcurrencyIntegrationTest.concurrentPrimaryRemovalAndPrimarySwitchKeepsLoginMethodInvariant` | 1.60s | 真实并发事务 + 约束竞争，**不改** |
| `LoginMethodConcurrencyIntegrationTest.concurrentTargetRemovalAndPrimarySwitchKeepsLoginMethodInvariant` | 1.60s | 同上 |
| `LoginMethodConcurrencyIntegrationTest.concurrentRemovalOfBothMethodsReturnsStableConflictAndKeepsOnePrimary` | 0.84s | 同上 |

前端：Vitest 5 个文件 1.03s（最慢 `useAuth.test.tsx` 376ms）；Mock Playwright
31/31 墙钟 10.1s（最慢 `resource test page exercises health, JWKS, and protected
resource mocks` 1.32s，跨标签 logout 0.81s）。没有发现纯等待型热点；未做并行化，
因为 Surefire 并行会共享 Spring context/数据库连接池并放大资源竞争。

结论：本批**没有做任何牺牲断言的压缩**；唯一真实收益来自把固定 sleep 换成事件
等待（OAuth2 夹具从“取消 5s sleep”变为客户端断连即返回，慢端点夹具从 500ms
固定延迟变为 150ms 读超时后立即返回）。

### 13.5 门禁真实数字

| 项目 | 结果 |
|------|------|
| `scripts/verify-fast.sh` | 通过，126s |
| `mvn test`（根） | 422/422，0 failures/errors/skips |
| `npm run lint` / `tsc --noEmit` / `npm run test:unit` | 通过；29/29（1.03s） |
| 覆盖率棘轮 | 后端 85.80/68.29（基线 85.64/68.53）、前端 31.90/30.66 |
| 测试腐化守卫 | 0 命中、0 基线；自测 4/4 |
| 作用域 PIT | JDK 21 复跑 150/143/3，基线检查通过 |
| Mock / 生产 Playwright | 31/31（10.1s）、2/2 |
| 邮箱登录浏览器 E2E | 1/1 |
| 邮件组件门禁 | 154；44/44、11/11、15/15、10/10 |
| 根 HTTP / Flyway / shared-schema | 17/17、17/17、8/8 |
| Python 资源服务器 / 邮件 stub | 20/20、12/12 |
| `scripts/verify.sh` | 15/15，`PASS: complete repository verification gate` |

### 13.6 残余风险

1. 组件内就绪 helper 是根脚本的副本，逻辑变更必须手工同步两处；没有自动 diff
   断言，靠文档和评审维持一致。
2. `verify-fast.sh` 只覆盖编译、后端 Surefire、前端静态检查和 Vitest；它不产生
   交付证据，CI 仍只跑完整 `verify.sh`。
3. 快速入口实测 126s 里后端 Maven 占主要部分；若继续压缩，只能并行化 Surefire
   或拆分 Spring context，这会引入共享数据库/上下文竞争，本批未做。
4. 前端并发用例去掉人工延迟后依赖 barrier 的正确性；若未来移除 barrier，测试会
   退化为顺序执行而仍然通过（断言“只刷新一次”仍成立，但不再证明并发合并）。
5. 邮件组件仍有应用启动就绪的固定 60×1s 轮询（`wait_for_application` /
   `expect_startup_failure`），它们等待进程退出或 HTTP，不属于本批 PostgreSQL
   就绪范围，未改写。

## 14. 第八批：消除双份脚本漂移与“退化仍通过”

第八批只做五件事：给两份就绪脚本加自动一致性守卫、把组件应用启动就绪改成有界
自适应轮询、让前端并发用例自带可观测证据、评估邮件侧作用域变异测试、复查
`CustomUserDetailsService` 定位。所有决策以“能失败关闭、证据可复现”为准，不做
对称性改造。

### 14.1 就绪脚本的双份契约

**问题**：组件 `scripts/verify.sh` 只快照 `reference/email-service/`，因此组件
harness 不能 source 仓库根的 `scripts/lib/postgres-readiness.sh`；上一批的副本
没有任何自动一致性保护，任一侧单独改动都无人发现。

**决策**：不引入构建期模板生成（会让组件快照出现“生成文件”的新语义），改为
显式标记 + 规范化比对：

- 两个文件在共享代码前后加
  `# === BEGIN/END SHARED POSTGRES READINESS ===`。
- `scripts/check-readiness-parity.py` 只比对标记内区域，规范化仅限 CRLF→LF 与
  行尾空白；其余差异全部算漂移，输出 unified diff 并非零退出。
- 标记外允许差异：两个文件头各自解释存在原因；组件专有
  `scripts/lib/application-readiness.sh` 完全在标记之外，不受比对约束。
- 标记缺失、重复或 END 在 BEGIN 之前，直接以退出码 2 失败关闭。
- `scripts/test-readiness-parity.sh` 用临时夹具自测：真实仓库通过、相同区域不同
  文件头通过、根侧单改失败、组件侧单改失败、标记外新增 helper 通过、缺 END
  失败。六项均按预期通过。
- 两者接入 `scripts/verify.sh` 阶段 1，与腐化守卫同层。

### 14.2 邮件组件的应用启动就绪

**改动**：新增组件专有 `reference/email-service/scripts/lib/application-readiness.sh`：

- `wait_for_application_health <pid> <log> <url> [key] [budget]`：`curl`
  `--connect-timeout 1 --max-time 2`，每 5s 打印 `elapsed/probes/last`，进程提前
  退出立即失败；默认预算 90s，可用 `APPLICATION_READY_TIMEOUT_SECONDS` 覆盖。
- `wait_for_process_exit <pid> [budget]`：**返回 0 只在进程真的退出**；预算耗尽
  返回 1。默认 60s，可用 `APPLICATION_STARTUP_FAILURE_TIMEOUT_SECONDS` 覆盖。
- `expect_startup_failure` 保留“期待失败”语义：拿到 0 才检查退出码并接受；拿到 1
  时打印日志尾部、停止进程并 `fail`。超时永远不会被当成“启动失败成立”。
- 三个 harness（HTTP E2E、backup/restore、Flyway guard）全部改为调用上述 helper，
  `seq 1 90` / `seq 1 60` 固定轮询清零。
- 新增 `scripts/test-application-readiness.sh` 固定语义：已退出进程可检测、仍在
  运行的进程在预算内返回 1（不是成功）、可访问 HTTP endpoint 探测成功、死进程
  健康探测立即失败并打印诊断；接入组件门禁阶段 1。

### 14.3 前端并发用例的可观测证据

**改动**：`security-boundaries.spec.ts` 两个并发用例新增在途计数器：

- 单页用例：`expiredCallsInFlight` / `maxExpiredCallsInFlight`，在两次 401 的
  route handler 内增减，刷新后断言
  `await expect.poll(() => maxExpiredCallsInFlight).toBeGreaterThanOrEqual(2)`。
- 跨标签用例：同一对计数器，跨两个 page 的 `/api/user` 401 共享，断言同上。

**负向验证**：临时复制用例，把第二次在途计数改为“等第一次完全结束才递增”，
模拟应用退化成串行请求；该断言按预期首个失败（`Expected: >= 2, Received: 1`）。
临时文件已删除。

**诚实结论**：直接删除 barrier **不会**让这个断言失败，因为 `TestPage` 的
`/api/user` 与 `/api/user/login-methods` 来自两个独立 `useEffect`，请求在挂载时
本就并行发出，重叠不依赖 barrier。这与第七批残余风险第 4 条的假设不同。该断言
的真实价值是拦截“应用改成串行请求”的回归；barrier 仍负责“两个 401 同时返回、
刷新只发生一次”的确定性时序。两条证据互补，报告中不把“删 barrier 会失败”当作
事实。

### 14.4 邮件参考服务的作用域变异测试

先按根项目形态加 `mutation-testing` profile，第一次把 `EmailApiKeyFilter`、
`EmailRateLimiter`、`EmailService` 一起纳入：

| 试验 | 作用域 | 结果 | 耗时 |
|------|--------|------|------|
| 1 | 3 类含 `EmailService` | 145 mutants / 25 killed（17%）、112 no-coverage、行覆盖 22% | 5.04s |
| 2 | 3 个安全边界类 | 39 mutants / 31 killed（79%）、0 no-coverage、行覆盖 100% | 6.34s（PIT 2s） |

试验 1 的低分主要来自“错误作用域”：`EmailService` 的保护主要在
ApplicationContext/GreenMail E2E，单测 PIT 看不到，这些 `NO_COVERAGE` 不是弱断言。
试验 2 耗时与根项目同量级（6 秒），且 8 个存活变异体正好落在 `EmailApiKeyFilter`
的单值 header 解析、`EmailRateLimiter` 的窗口边界与计数上，属于真实弱断言。
**结论：固化**。基线 `reference/email-service/config/mutation-baseline.json` 记录
39/31/0；`scripts/check-mutation-baseline.py` 按类比较 killed 与 no-coverage，
并校验探针作用域未被悄悄扩大；`.github/workflows/mutation-probe.yml` 增加组件
探针与基线比对。它不进入 `scripts/verify.sh`，理由与根一致性：默认 JDK 不能跑 PIT。

### 14.5 `CustomUserDetailsService` 定位复查

复查结果：定位仍准确，无需改装配。

- `WebSecurityConfig` 注入 `CustomUserDetailsService`，`SecurityConfig` 把它作为
  唯一 `UserDetailsService` 交给 `DaoAuthenticationProvider`，Spring Security 的
  全局 `AuthenticationManager` 因此可达它。
- 主代码中没有任何 `authenticationManager.authenticate(...)` 调用；登录走
  `CredentialAuthenticationService`，用户名/密码校验不经过
  `UserDetailsService`。所以仍是“框架可达、应用不可达”。
- 代码位置（`security/CustomUserDetailsService.java`）与上批记录一致，未新增调用方。
- 之所以保留 bean：Spring Security 需要在全局认证管理器装配它；删除会改变框架
  装配结构，超出本批范围。

### 14.6 门禁真实数字

| 项目 | 结果 |
|------|------|
| `scripts/check-readiness-parity.py` | 通过，共享区 90 行一致 |
| `scripts/test-readiness-parity.sh` | 6/6 |
| `scripts/test-application-readiness.sh` | 4/4 |
| `mvn test`（根） | 422/422，0 failures/errors/skips |
| 覆盖率棘轮 | 后端 86.00/68.66（基线已更新为同值）、前端 31.90/30.66 |
| Mock / 生产 Playwright | 31/31（10.2s）、2/2（4.5s） |
| 前端并发证据用例 | 2/2；串行化临时夹具下 `max >= 2` 首个失败 |
| 邮箱登录浏览器 E2E | 1/1（6.4s） |
| 邮件组件门禁 | 154 tests；44/44、11/11、15/15、10/10；就绪 `4s(5)`/`6s(6)`/`2s(4)` |
| 邮件侧 PIT | 39 mutants / 31 killed（79%）、0 no-coverage、PIT 2s、总 6.34s |
| 根 HTTP / Flyway / shared-schema | 17/17、17/17、8/8 |
| Python 资源服务器 / 邮件 stub | 20/20、12/12 |
| `scripts/verify.sh` | 15/15，`PASS: complete repository verification gate` |
| 文档链接 / 敏感扫描 | 64 个 Markdown 全部可解析；749 文件、4 条活动例外 |

### 14.7 残余风险

1. 就绪一致性守卫只覆盖标记区域；若有人把共享函数复制到标记外再改，守卫看不到。
   文档明确“组件专有行为只能放在标记外”，评审仍需检查。
2. `wait_for_application_health` 默认 90s、`wait_for_process_exit` 默认 60s，均为
   本机实测余量；CI 更慢时可分别用环境变量调高，调低不会让门禁更可信。
3. 前端并发证据只证明“同时在途 ≥ 2”，不证明刷新与 401 的具体交错顺序；后者仍由
   barrier 和“refreshCalls === 1”保证。
4. 邮件侧 PIT 只覆盖三个纯逻辑类；`EmailQueueService`、SMTP 组装等仍依赖
   ApplicationContext/GreenMail E2E，不在变异探针作用域内。
5. `expect_startup_failure` 的退出预算若小于真实启动失败时间，会把“慢失败”误判为
   门禁失败；这是有意的失败关闭方向，但不能用调大预算来掩盖“本该失败却启动成功”。

## 15. 第九批：堵住绕行口、交错证据、预算校准与健康快照

第九批处理六个问题：一致性守卫的绕行口、并发断言从“数量”升级为“顺序”、就绪预算
的可复核来历、一条命令的健康快照、新增脚本的负向自测、完整门禁复跑。原则仍是
“证据可复现、失败关闭、不伪造通过”。

### 15.1 一致性守卫的绕行口

**问题**：旧守卫只比对标记区。把共享函数复制到标记外、在新副本上改行为，标记区
仍然一致，守卫看不见。

**决策**：解析共享区内每个函数定义名，再扫描两个文件标记外的同名函数定义；
命中即失败并打印 `path:line: name()`。没有选择“禁止标记外任何代码”这种会误伤
组件的规则：组件专有 helper 只要换名字仍合法。

- 正则只认行首 `name() {`，不解析函数体，避免引入 shell 语法解析器。
- 标记布局错误仍以退出码 2 失败关闭。
- 自测新增第 6 步“复制到标记外修改”：组件夹具追加同名
  `wait_for_disposable_postgres(){...}`，断言失败且 stderr 含
  `redefined outside the markers` 和函数名；第 7 步保留缺 END 的失败关闭。
- 真实仓库运行输出：共享区 90 行一致、2 个共享函数。

### 15.2 从并发数量到交错证据

**改动**：两个并发用例新增带时间戳的事件日志：

| 用例 | 事件名 | 断言 |
|------|--------|------|
| 单页重复 401 | `401:user`、`401:login-methods`、`refresh` | 两个 401 都先于第一次 refresh；refresh 恰好一次；时间戳单调不减 |
| 跨标签 cookie 轮换 | `401:tab-1`、`401:tab-2`、`refresh` | 两个不同标签的 401 都先于第一次 refresh；refresh ≥1 且 ≤2；时间戳单调不减 |

**真实契约修正（重要）**：第一次尝试对跨标签也断言“refresh 恰好一次”。完整门禁
在阶段 12 失败：`Expected: 1, Received: 2`。根因不是竞态夹具，而是生产契约：

`coordinateTokenRefresh` 在 refresh **启动时**才读取 session marker。如果标签 A 的
refresh 在标签 B 读取 marker 之前完成，B 看到的是新 marker，会合法地再刷新一次。
因此“恰好一次”只对单页成立（同页有静态 single-flight 槽）；跨标签的真实契约是
“至少一次、每标签最多一次”。按用户要求改为按真实契约断言，并把原因写进测试注释
与文档；第三次 refresh 仍会失败，可拦截重试风暴。

**负向探针**：临时在第一个 401 事件后注入 `refresh` 事件，模拟“refresh 先于第二个
401”。用例确定性失败（refresh 计数与顺序断言同时触发），证明时间戳日志不是装饰；
探针代码随后移除，`--repeat-each=5` 两个用例 10/10 通过。

### 15.3 就绪预算校准

测量脚本放在仓库外（`/tmp`），不新增需要长期维护的脚本。方法：disposable
`postgres:16.13`，每个样本新建数据库（含完整 Flyway 首次迁移），随机端口启动
`java -jar`，轮询 `GET /api/email/health` 首次 200，毫秒墙钟计时；并发组同时启动
4 个 JVM，各自独立数据库，共 3 轮。机器：macOS，Apple M4 Pro，14 核，64 GB。

| 场景 | 样本 | 中位数 | 最大值 | P95 | 预算 | 余量 |
|------|------|--------|--------|-----|------|------|
| 顺序冷启动 | 10 | 3.52s | 4.32s | ≈4.25s | 90s | 20.8× |
| 并发 4 JVM × 3 轮 | 12 | 5.45s | 5.69s | ≈5.67s | 90s | 15.8× |
| 配置守卫启动失败 | 3 | 1.50s | 1.58s | n/a | 60s | 38× |

顺序样本（ms）：3154、3323、3418、3443、3498、3539、3651、3872、4205、4317。
并发样本（ms）：4664、4695、4797、4800、5368、5413、5482、5520、5569、5624、
5654、5686。完整门禁实测应用就绪 3–4s、失败退出 2s。

**局限**：P95 来自小样本，统计上接近最大值；只测了单机与单 Docker 后端；CI 的
CPU/磁盘争用可能更慢。15× 以上余量意味着即使 CI 慢一个数量级也不误报超时；若未来
把预算收紧到 10s 量级，必须重新采样。数据与口径同步写入
[测试约定](../TESTING.md#12-就绪预算校准)。

### 15.4 健康快照

新增 `scripts/health-snapshot.py`，两种模式：

- `--record-gate`：`verify.sh` 退出时由 cleanup 调用，把本轮在临时快照里测到的
  Surefire 计数、JaCoCo CSV、前端覆盖率、声明测试数、就绪样本写入
  `.local/uniauth/health/last-verify.json`。之所以要“记录”而不是直接读工作区：
  完整门禁在临时快照中运行，工作区的 `target/` 往往是上一次的旧产物。
  第一次实现直接读临时目录的 `target/` 得到空值，因此改为在阶段 7 复制
  `jacoco.csv`、并传入 Surefire 快照目录。
- 默认模式：打印表格并写 `snapshot.json`，汇总 gate、backend-tests、
  frontend-tests（声明数）、backend/frontend-coverage、mutation、test-rot、
  readiness。必需层失败、证据缺失或 `passed` 与 `exitCode` 自相矛盾都以 1 退出。
- 就绪指标由 `application-readiness.sh` 在设置 `APPLICATION_READINESS_METRICS_FILE`
  时追加 TSV；只保留预算为 90s/60s 的真实样本，避免自测的小预算污染。
- 输出目录 `.local/` 已被 `.gitignore` 覆盖；JSON 同时写入 `snapshot.json`。

真实输出：`Overall: HEALTHY`，gate/backend-tests/backend-coverage/
frontend-coverage/mutation/test-rot 全绿；就绪 `last app READY 3.0s/90s`、
`last failure-exit 2.0s/60s`。

### 15.5 新增脚本的负向自测

| 脚本 | 自测 | 覆盖的失败路径 |
|------|------|----------------|
| `check-readiness-parity.py` | `test-readiness-parity.sh` 7/7 | 单边漂移（两侧）、标记外 shadow 副本、缺 END |
| `check-mutation-baseline.py` | `test-mutation-baseline.sh` 6/6 | killed 下降、no-coverage 增长、作用域变化、缺 CSV、缺基线 |
| `health-snapshot.py` | `test-health-snapshot.sh` 7/7 | 空仓库、覆盖率退化、Surefire 失败、`passed=true/exitCode=1`、缺变异基线；以及 HEALTHY 对照与 JSON 一致性 |
| `application-readiness.sh` | `test-application-readiness.sh` 6/6 | 死进程立即失败、仍在运行超时返回 1、metrics 写入失败不得改变成功结果 |
| 前端交错断言 | 一次性负向探针 | 注入“refresh 早于第二个 401”必须失败 |

`test-mutation-baseline.sh` 与 `test-health-snapshot.sh` 已接入 `verify.sh` 阶段 1，
不是一次性命令。

### 15.6 门禁真实数字

| 项目 | 结果 |
|------|------|
| `scripts/verify.sh` | 通过，15/15，`VERIFY_EXIT=0` |
| `mvn test`（根） | 422/422，0 failures/errors/skips |
| 覆盖率棘轮 | 后端 86.00/68.66、前端 31.90/30.66（均与基线同值） |
| Vitest / Mock / 生产 Playwright | 29/29、31/31（10.3s）、2/2（4.5s） |
| 邮箱登录浏览器 E2E | 1/1（6.7s） |
| 邮件组件门禁 | 154 tests；44/44、11/11、15/15、10/10；就绪 `3s(5)`/`4s(5)` |
| 一致性守卫 | 90 行一致、2 个共享函数；自测 7/7 |
| 健康快照 | HEALTHY；8 层全绿；自测 7/7 |
| 变异基线守卫自测 | 6/6 |
| 组件就绪自测 | 6/6 |
| 测试腐化守卫 | 0 命中、基线 `entries: []` |
| 根 PIT（最近产物） | 150 mutants / 143 killed / 3 no-coverage |
| 文档链接 / 敏感扫描 | 64 个 Markdown 全部可解析；752 个文件，1 条精确指纹例外覆盖 4 处匹配（第十批当时值；已被 §17.13 的 753/4 取代） |

复跑记录：第 1 次完整门禁因阶段 4 pip-audit 访问 PyPI `ReadTimeout` 失败（环境
网络问题，不是代码）；第 2 次因跨标签“恰好一次”与真实契约不符失败（见 15.2）；
修正后第 3 次 15/15 通过。三次失败都没有被当作通过。

### 15.7 残余风险

1. shadow-definition 规则只认行首 `name() {`；`function name {` 写法或把函数包在
   其他结构里理论上可绕过。仓库现有 helper 统一用 `name() {`，新增脚本须遵守。
2. 跨标签页的 refresh 次数仍是 1–2 的调度相关值；测试只保证“每标签最多一次”
   与交错顺序，不能保证单个 cookie 轮换。
3. 就绪预算数据来自单机小样本；CI 极端资源争用仍可能超时，届时看诊断日志判断是
   资源不足还是真实故障，不自动放宽预算。
4. 健康快照的前端测试数是声明数（28）而非运行时数（29）；变异层无实时 PIT CSV 时
   标为 N/A。它是“一眼看退化”的入口，不替代完整门禁。
5. `last-verify.json` 可能过期；快照会显示记录时间，但不按时间判定 STALE，避免
   把“昨天通过、今天未跑”误报成回归。需要最新证据时重跑完整门禁。

## 16. 第十批（2026-11-05）：运行时报告口径、STALE 与反向审计

第十批先修掉第九批自己暴露的准确性问题，再对整套加固做一次“测试通过但没测到”
的反向审计。原则不变：证据必须来自实际运行，失败必须失败关闭。

### 16.1 前端测试数只认运行时 JSON 报告

**问题**：第九批快照用 `npx vitest list` / `playwright --list` 得到“声明数”，
Vitest 显示 28，而真实运行是 29（参数化用例只在运行时展开）。这既与后端
Surefire“读实际报告”的口径不一致，也会在报告丢失时继续显示一个看似合理的数字。

**决策**：删除声明数路径。`frontend/vitest.config.ts` 用 `json` reporter 把
`reports/vitest-results.json` 写到 Playwright 的 `test-results/` 之外（否则
Playwright 启动时会清空它）；两份 Playwright config 分别写
`reports/playwright-mock.json` 与 `reports/playwright-production.json`。
`frontend/reports/` 已加入 `.gitignore`，快照只读这些运行时产物。

一致性检查（全部失败关闭）：

| 报告 | 运行时总数 | 内部记录数 | 不一致时 |
|------|------------|------------|----------|
| Vitest | `numTotalTests` | 所有 `assertionResults` 之和 | FAIL，打印 `total=N records=M` |
| Playwright | `stats` 的 expected+unexpected+skipped+flaky | 递归 `specs` 计数 | FAIL |
| Surefire | `tests` 属性 | `testcase` 元素数 | FAIL，打印 `N tests but M testcase` |

报告缺失 → `MISSING`（必需层），不再回落到声明值或工作区旧报告。自测第 7、8、
12 步分别覆盖 Vitest 报告缺失、Vitest 内部不一致、Surefire 内部不一致。

### 16.2 STALE 语义

`last-verify.json` 任一条成立即 STALE：

- 记录时间超过 24 小时；
- 门禁实际执行的源码/脚本（`scripts/`、`src/`、`frontend/src`、`frontend/tests`、
  `config/`、`contracts/`、两份 `playwright*.config.ts`、`pom.xml` 等）的 mtime
  晚于记录时间，容差 2s。

**选择 exit 非零而非仅标注**：快照的契约是“当前树的健康”，不是“上一次门禁的历史”。
记录过期后门禁结论对当前树是未知；若仍输出 HEALTHY，就会把“昨天通过、今天改过”
当成健康，正是快照要消除的假信心。STALE 在 gate 行显式打印原因（年龄或 mtime 差值），
overall 变为 DEGRADED，exit 1。修改代码后立即看快照会 STALE，这是预期；重跑门禁
即可恢复。docs 不在源码路径内，因此门禁结束后回填文档不会触发 STALE。

自测第 9、10 步分别覆盖“30h 旧记录（源码也移旧）”与“记录很新但源码 mtime 在未来”。

### 16.3 shadow 规则补全

`check-readiness-parity.py` 不再用单一行首正则，而是逐行扫描：

- `name(){`、`name () {`（含任意空格）、`function name {`、`function name() {`
  都识别为定义；
- 行首 `#` 注释、多行单/双引号字符串、heredoc 正文都跳过；行内注释不会被当成
  引号起点；
- 识别仍限制在行首（允许缩进），普通调用 `wait_for_disposable_postgres` 不误判。

自测从 7 增至 11：新增 `function` 关键字副本、带空格副本、注释/字符串/heredoc
反例、两侧都用关键字写法仍通过。真实仓库仍是 90 行一致、2 个共享函数。

### 16.4 跨标签 refresh 1–2 次契约确认

读实现确认这不是偶然：`refreshToken` 的 `observedMarker` 在**调用时**读取，
`coordinateTokenRefresh` 的 `currentMarker` 在**拿到 Web Lock 后**读取。若标签 A
先完成刷新，标签 B 随后拿到锁会看到 marker 已变并直接返回 → 总计 1 次；若 B 的
401 续作在 A 完成后才执行，它调用时读到的已是新 marker，会合法再刷一次 → 总计 2 次。
每标签的 `refreshTokenRequest` 静态槽保证同一页不会超过 1 次并发刷新，因此上限是 2。

已把上述时序写进 `authService.ts` 注释；E2E 继续断言 `1 ≤ refreshCalls ≤ 2` 且
两个不同标签的 401 都先于第一次 refresh。没有改成确定性的 1 次：要强制 1 次必须
在请求发出时捕获旧 marker 并在锁内比较，这会改变“401 之后才决定是否刷新”的语义，
且需要服务端配合区分同一轮 cookie；收益不足以抵消风险，故记录为已知的调度相关
行为，而不是缺陷。

### 16.5 反向审计：测试通过但没测到

逐项挑刺后确认并修复 2 个真实问题，另 2 个为已证伪或边界声明：

| # | 疑点 | 结论 | 处置 / 证据 |
|---|------|------|-------------|
| 1 | 门禁记录存在但某项指标缺失时，快照回落到工作区 `target/`；旧报告会让失败门禁看起来健康 | 成立 | 改为：有记录时只用记录内指标，缺失即 `MISSING`；仅无记录时才读工作区。自测第 3、4 步改为先重录门禁再断言 |
| 2 | Surefire 只累加 `tests` 属性，不校验 `<testcase>` 元素数，手改/截断报告可虚增 | 成立 | 累加 `testcase` 数并标记 `inconsistent`；新增自测第 12 步（10 declared / 9 records 必须失败） |
| 3 | 跨标签 E2E 只断言 localStorage 与 mock 路由，未断言服务端 cookie 轮换 | 部分成立，属浏览器层边界 | mock 路由无法观察真实服务端状态；服务端轮换由 `AuthenticationFlowIntegrationTest` 覆盖：refresh 返回的新 `refreshToken` 与旧值不同（`assertThat(...).isNotEqualTo(refreshToken)`），旧 refresh token 再用返回 401。E2E 只负责“标签协调 + 客户端令牌落地” |
| 4 | 旧快照把 `vitest list` 声明数（28）当成运行时数（29） | 成立 | 删除声明值路径，改读 JSON reporter；报告缺失/内部不一致均失败（见 16.1） |

审计还确认了两处不是问题：`test-health-snapshot.sh` 的 HEALTHY 对照原先依赖
“空 metrics + 回落实时报告”，修复 #1 后改为走真实 `--record-gate` 路径，夹具不再
在断言前满足条件；E2E 的 barrier 断言 `max in-flight ≥ 2` 由两个不同页面的 401
集合控制，单页两个并发请求无法伪造。

### 16.6 门禁真实数字

第十批最终复跑（2026-11-05，`VERIFY_EXIT=0`，15/15 阶段通过）：

| 层 | 真实数字 | 来源 |
|----|----------|------|
| 根门禁 | 15/15，exit 0 | `scripts/verify.sh` |
| 后端测试 | 422/422，0 skipped | 门禁快照 Surefire |
| 前端单元 | 29/29 | Vitest JSON reporter |
| 前端 mock E2E | 31/31 | Playwright JSON reporter |
| 前端生产 E2E | 2/2 | Playwright JSON reporter |
| 邮件组件测试 | 154/154 | 组件门禁 Surefire |
| 真实邮件 E2E | 1/1 | Playwright |
| 覆盖率 | 后端 86.00%/68.66%，前端 31.90%/30.66% | JaCoCo / v8 |
| 变异 | 143/150 killed（95.33%），与基线一致 | PIT |
| 腐化命中 | 0（0 new） | `check-test-rot.py` |
| 就绪 | 根门禁成功 3.0s/90s、失败退出 2.0s/60s；组件独立复跑后最新样本 4.0s/1.0s | readiness.tsv |
| 敏感扫描 | 752 文件，4 个例外命中（1 个 fingerprint 例外） | `sensitive artifact scan` |
| 文档链接 | 64 个 Markdown 文件全部可解析 | 阶段 15 |

健康快照：`Overall: HEALTHY`，前端运行时合计 62，gate 记录
`2026-11-05T05:04:30Z`（UTC），未 STALE。

### 16.7 本批残余风险

1. STALE 的 mtime 规则依赖文件系统时间；如果检出/解压在门禁之后发生，会立刻
   STALE（这是有意的保守方向），但 NFS/时钟漂移可能带来误报。
2. 运行时报告只覆盖本机最近一次运行；Playwright 的 JSON 报告在 `reports/` 与
   `test-results/` 分离，清理脚本若直接删 `frontend/reports` 会让快照 MISSING。
3. shadow 扫描器仍是行级启发式：函数定义写在 `if ...; then name() {` 同一行，或
   非常规 heredoc 语法，可能漏检；它只守护仓库现有 helper 风格，不是完整 bash 解析器。
4. 跨标签 refresh 仍是 1–2 次的调度相关行为；E2E 的 1–2 断言能拦截重试风暴，
   但不能证明只轮换一次 cookie。
5. 反向审计是抽样性质，不能证明所有断言恒真；但它把“回落、内部计数、
   服务端状态、声明数”四类最可能误判的路径都变成了有失败用例的证据。



## 17. 第十一批（2026-11-05）：独立审计逐条核实与失败关闭补强

独立只读审计提出 12 条。逐条复核结论如下；“证伪”意味着审计给出的前提不成立，
本节保留证据而不是照单修改。

### 17.1 Web3 challenge 容量限流（中等，已修）

- 复核：`scripts/test-http-e2e.sh` 当时只有 Web3 登录、tamper、并发 replay，
  没有任何 source/global 容量断言；VERIFICATION 的 F3 段落却把容量写在
  “真实进程验证”里。这是 **真实缺口**，不是文档笔误。
- 修法：新增第 17/18 步，分两次重启真实应用：
  - source 容量：`WEB3_MAX_ACTIVE_SOURCE=1`、`WEB3_MAX_ACTIVE_GLOBAL=10`。
    第一个钱包拿到 nonce；第二个钱包的 `GET /api/auth/web3/nonce/{address}`
    必须 429、`error=WEB3_CHALLENGE_CAPACITY`、`Retry-After: 1`，且全局计数仍为 1
    （证明拒绝来自 source 桶、没有泄漏全局配额）。消费第一个 challenge 后，
    第二个钱包再取 nonce 必须 200，并可完成 verify。
  - global 容量：`WEB3_MAX_ACTIVE_SOURCE=10`、`WEB3_MAX_ACTIVE_GLOBAL=1`。
    第一个钱包占用唯一全局槽；第二个钱包（source 仍有空位）必须 429 同错误码；
    消费后再次放行。
  - 两次容量场景之间同时清空 `web3_nonces` 与 `web3_challenge_counters`，避免
    前一场景的 active challenge 干扰后一场景。
- 文档：VERIFICATION 的步骤号 17→18，并明确该容量验证由第十一批新增。

### 17.2 CSP 生产边界（中等，已修 + 发现真实缺陷）

- 复核：CSP 只由 `SensitiveResponseHeadersFilterTest` 直接调用过滤器覆盖；
  `ProductionHttpBoundaryIntegrationTest` 原先只断言 HSTS 与 Cookie。TEST_MAP
  把 CSP 映射到生产边界测试属于“期望而非事实”。
- 修法：在生产边界测试中同时断言 200（CSRF 路径）与 302（OAuth2 redirect）
  响应的 `Content-Security-Policy` 精确值。
- **额外发现**：加入断言后 302 上没有 CSP，测试真实失败。根因是
  `SensitiveResponseHeadersFilter` 作为 `@Component` 未声明顺序，默认
  `LOWEST_PRECEDENCE`，落在 Spring Security 链内部；OAuth2 redirect filter
  发送 302 后不再回调链尾，响应头过滤器因此不执行。修法：给过滤器加
  `@Order(Ordered.HIGHEST_PRECEDENCE)`，让它在 Security 链外层包裹所有响应。
  这不是为了测试而改测试：没有该顺序，所有提前提交的响应（302/错误页）都缺
  CSP 与 `X-Content-Type-Options`。测试因此从“假通过”变成真实约束。
- TEST_MAP 已改为 `config/SensitiveResponseHeadersFilterTest` + 生产边界双向覆盖。

### 17.3 覆盖率棘轮缺键（中等，已修）

- 复核：`coverage-ratchet.py` 的 `compare`/`update_scope` 与
  `health-snapshot.py` 的 `compare_coverage` 都用 `recorded.get(metric, 0.0)`；
  删除一个 scope 或指标即可把基线降到 0，门禁仍 PASS。**真实缺陷**。
- 修法：
  - `coverage-ratchet.py`：缺 scope 直接失败；缺 `line`/`branch` 逐指标失败并打印
    `baseline=MISSING`。`--update` 对缺失指标输出 `baseline unset ->` 并写入，
   这是显式迁移，不再用 0.0 冒充旧值。
  - `health-snapshot.py`：同一口径；`compare_mutation` 也要求
    `generated/killed/noCoverage/mutationScore` 齐全，缺一即失败。
  - 新增 `scripts/test-coverage-ratchet.sh` 8 项自测：缺 scope、缺指标、超容忍
   下降、容忍内通过、`--update` 迁移、拒绝未授权下调、`--allow-lower` 显式接受。
  - 接入 `verify.sh` 阶段 1。

### 17.4 健康快照回读旧报告（轻微，已修）

- 复核：`health-snapshot.py:522` 为
  `metrics.get("frontendTests") or read_frontend_test_reports(repo)`。记录存在但
  指标缺失时会回读工作区报告，与文档“记录优先、缺失即 MISSING”不符。
- 修法：记录存在时只取记录指标；缺任一 suite 即 `frontend-tests MISSING`。
  `source` 列也改为“有记录即 gate”，不再用“是否含后端测试数”猜测来源。
  自测新增第 14 步：删掉记录里的 `frontendTests`、同时保留工作区报告，快照必须
  失败而不是回读。

### 17.5 Web3SignatureUtilsTest（轻微，已修）

- 复核：`treatsRecoveryIdThreeAsAnInRangeValue` 用
  `if (thrown != null) { ... }`，不抛异常时整个用例静默通过。**真实缺陷**。
- 修法：先断言 `thrown` 非空（即行为是抛出），再断言消息不含
  `Invalid recovery id`。这样既证明 v=0x1e 通过范围检查，又不会把“直接返回地址”
 误判为通过。

### 17.6 腐化守卫 no-assertion（轻微，已修）

- 复核：`JAVA_TOKEN` 的 `\bassert[A-Za-z]*\b` 把 `assertDoesNotThrow` 和
  `assertThatCode` 都算作断言；`assertThatCode(...).doesNotThrowAnyException()`
  也因 `assertThatCode` 前缀被放过。**真实缺陷**。
- 修法：
  - 排除 `assertDoesNotThrow`、排除裸 `assertThatCode`；只有
    `assertThatCode(...).isInstanceOf/hasMessage/...` 才算强断言。
  - 把 Mockito `verifyNoInteractions` 等纳入强断言，避免误伤。
  - Java 方法体先剥离字符串/注释再匹配，避免 `"no assertion here"` 这类字面量
    造成假阴性。
  - 自测新增：仅 `assertDoesNotThrow` 必命中、仅
    `assertThatCode(...).doesNotThrowAnyException()` 必命中、
    `assertThatCode(...).isInstanceOf(...)` 与 `assertThat` 不得命中。
- 收紧后仓库新增 11 条命中，全部是只验证“合法配置不抛异常”的守卫用例
  （`AuthCookieSecurityGuardTest`、`ProductionConfigurationGuardTest`、
  `EmailServiceRuntimeGuardTest`、`RecentAuthenticationServiceTest` 等）。这些
  方法的唯一可观测契约就是“不抛”，因此作为**人工复核**条目写入
  `config/test-rot-baseline.json`；新增同类写法仍会失败。

### 17.7 变异基线 generated（轻微，已修）

- 复核：`check-mutation-baseline.py` 只比较 killed/noCoverage/score，删除生产
  代码缩小变异体集合时 `generated` 下降但门禁仍可能通过。**真实缺陷**。
- 修法：必需字段缺失直接失败；总体 `generated` 下降失败；每个类的 `generated`
  下降同样失败。允许的等价调整必须显式 `--update`。自测扩到 8 项（新增
  generated 下降与必需指标缺失）。

### 17.8 文档口径（轻微，已修）

- readiness parity：TEST_MAP 写“六项”，脚本实为 11 项，已改并注明核对批次。
- 测试类路径 5 处已改：`util/BearerTokenUtilsTest`、
  `service/RecentAuthenticationServiceTest`、`config/CsrfProtectionFilterTest`、
  `config/SensitiveResponseHeadersFilterTest`、`security/SensitiveOutputPolicyTest`。
- TEST_MAP 第 68 行断行表格（缺闭合反引号/竖线）已修复。
- 实施记录 §12.4 的“7 处固定等待”保留为历史，并显式标注“已过时/被第七批取代”。
- TESTING 声称“Python compile() 检查”但阶段 1 没有：选择**补检查**而不是删声明；
  阶段 1 现遍历 `scripts/*.py` 与 `python-resource-server/*.py` 做 `compile()`。

### 17.9 Chrome 预检（轻微，已修）

- 复核：`playwright.config.ts` 固定 `channel: 'chrome'`，`verify.sh` 预检不检查
  Chrome，缺失时要到第 11 阶段才失败。**真实缺口**。
- 修法：预检增加 macOS `/Applications`、`~/Applications` 与
  `google-chrome`/`google-chrome-stable`/`chrome` PATH 检测；失败时打印安装提示
  `npx playwright install chrome`。

### 17.10 组件“154/154”可复核（轻微，已修）

- 复核：组件数字只写在文档；工作区 `reference/email-service/target/surefire-reports`
  残留一次脱离门禁运行的 1 error 报告。**真实的可复核性缺口**。
- 修法：组件 `verify.sh` 在 cleanup 中写
  `.local/uniauth/health/email-last-verify.json`，包含 runId、exitCode、passed、
  sourceFingerprint 与 Surefire 运行时聚合（tests/failures/errors/skipped/
  testcase 记录数/inconsistent）。根门禁调用组件门禁时把 `EMAIL_SERVICE_HEALTH_DIR`
  指向根 `HEALTH_DIR`，记录得以跨临时快照保留。健康快照新增可选
  `component-tests` 行读取该记录。
- 清理：删除工作区 `reference/email-service/target/` 与本次定向测试产生的根
  `target/surefire-reports/`；两者均被 git 忽略，门禁始终在临时副本构建。
- 自查补充（同批修复）：初版把条件写成 `if ! python3 ...`，语义正好反了——记录
  写入成功时反而打印 `WARNING: failed to write component health record`，失败时
  反而打印成功。根门禁与独立组件门禁日志中的该 warning 都是这个假阴性；实际
  `email-last-verify.json` 已按 154/0/0/0 写入。已改为 `if python3 ...`，并用同一
  函数在隔离夹具中验证：成功输出 `Component health record: ...`，失败才输出
  warning；临时调试用 `2>> record-error.log` 与残留错误文件已删除。

### 17.11 敏感扫描与 Markdown 数量（轻微，已修）

- 复核：752/4 与 697/0 是**不同时间、不同快照范围**的数字：697/0 来自
  2026-08-12 的历史节，当时快照未包含后续新增文件；752/4 来自第十批当前门禁
  （1 条 fingerprint 例外覆盖 4 处匹配）。64 与 59 同理（Markdown 文件随批次增加）。
- 处置：历史节保留原数字并标注“Historical / 当时快照”；当前节在最终门禁后
  回填同一轮实测值（见 §17.13），两套口径不再并存于“Current”。

### 17.12 组件 surefire 残留 error 结论（轻微，结论：夹具/环境问题）

- 复核：残留文件
  `TEST-org.dddml.email.database.EmailSharedSchemaApplicationContextIntegrationTest.xml`
  为 `tests=1 errors=1`，错误是
  `IllegalStateException: Refusing automatic email-service baseline because managed
  relations already exist: email_logs...`。该次运行是脱离组件的直接 `mvn test`，
  没有传入组件 `verify.sh` 提供的 `-Duniauth.migrations.dir`，测试因此回落到
  相对路径并对着已有 schema 尝试 baseline。
- 证据：组件门禁独立运行同一测试类为
  `Tests run: 1, Failures: 0, Errors: 0`，总计 154/154；错误在门禁中不可复现。
- 结论：**不是产品失败，也不是测试断言错误**，是缺少迁移目录环境夹具导致的
  工作区残留产物。已删除残留 `target/`，并由 §17.10 的记录机制提供可复核数字。

### 17.13 最终复跑（2026-11-05，同一次根门禁 + 独立组件门禁）

| 检查 | 结果 |
|------|------|
| `scripts/verify.sh` | 15/15，`VERIFY_EXIT=0`；`last-verify.json` 记录 `2026-11-05T06:47:20Z`（记录修正后最终复跑） |
| `mvn test`（根） | 422/422，0 failures/errors/skips |
| Vitest / Mock Playwright / 生产 Playwright | 29（5 文件）/ 31 / 2；运行时合计 62 |
| 邮箱浏览器 E2E | 1/1 |
| Python 资源服务器 / 邮件 stub | 20/20 / 12/12 |
| 后端覆盖率 | line 86.00%、branch 68.66%（与基线同值） |
| 前端覆盖率 | line 31.90%、branch 30.66%（与基线同值） |
| 覆盖率棘轮自测 | 8/8 |
| 变异基线 | 143/150 killed（95.33%）、3 no-coverage；自测 8/8 |
| 腐化守卫 | 11 hit(s)，全部在基线，0 new；弱断言夹具自测通过 |
| 就绪 | 最后应用启动 4.0s/90s；失败退出 2.0s/60s |
| 组件门禁（根内 + 独立） | 154 tests，0 failures/errors/skips；根内阶段输出 `Component health record: ...`，独立复跑 `EMAIL_VERIFY_EXIT=0` |
| 组件记录 | `.local/uniauth/health/email-last-verify.json`：runId、exitCode=0、passed、sourceFingerprint、154/0/0/0；快照 `component-tests OK 154 tests, 0 skipped` |
| 健康快照 | `Overall: HEALTHY`；自测 14/14 |
| Markdown / 敏感扫描 | 64 个 Markdown 全部可解析；753 个文件、1 条精确指纹例外覆盖 4 处匹配 |

说明：§16 的 752/64 与更早的 749/4、697/0 是各批次当时的快照范围；当前口径
统一为本节 753/4 与 64。组件门禁记录在根门禁的临时快照之外持久化，因此
“154/154”不再只是文档自述。工作区中脱离门禁的 `reference/email-service/target/`
与本次定向测试产生的根 `target/surefire-reports/` 已删除；两者本就被 git 忽略。
未执行任何 git 写操作（未提交由用户决定）。

## 18. 第十二批（2026-11-05）：复核残余 6 条收口

独立复核确认第十一批 12 条实质关闭，但留下 6 条轻量残余（另有 2 条复核要求）。
本节逐条记录处置与证据。

### 18.1 组件测试记录新鲜度（已修）

- `health-snapshot.py` 的 `component-tests` 行不再只看记录内部自洽：先按根门禁同一
  规则校验 `recordedAt`（24h）与组件源码 mtime（2s 容差），再用与
  `reference/email-service/scripts/verify.sh` 相同的路径子集重算
  `sourceFingerprint` 并比对。过期/不匹配 → `STALE` 并降级 Overall。
- 第三轮复核后，mtime 扫描与指纹使用同一个 `git ls-files -co --exclude-standard`
  文件集：修改被忽略的 `.env` 不会再出现“指纹不变却 STALE”。git 不可用时两侧都
  返回 `None`，只保留年龄规则，不臆造新鲜度。
- 组件记录缺失由 N/A 改为 `MISSING` 且 required；写入失败由 WARNING 改为 ERROR
  并让组件门禁非零退出（根门禁以阶段退出码看到失败）。理由：组件是交付面的一部分，
  没有当前树的记录就等于没有该层证据，不能继续打印 HEALTHY。
- 组件记录指纹排序改为 `LC_ALL=C sort -z`：验证时发现该 section 之前依赖环境
  locale，导致 Python 字节序重算与 shell 记录不一致，真实仓库上误报指纹变化。
  当时实测 `83cdc7...` 已因后续脚本修改失效；第三轮复测当前树为
  `8071af0b...`（见 §19.9）。
- 自测由 14 项扩到 20 项：15（30 小时旧记录 STALE）、16（指纹不匹配 STALE）、
  17（指纹匹配且新鲜 → HEALTHY，含 shell/Python 指纹一致性对照）、18（被忽略的
  `.env` 不影响判定）、19（受跟踪源文件晚于记录 → STALE）、20（记录缺失 →
  `MISSING` 并降级）。

### 18.2 变异基线类级默认值（已修）

- 删除 `expected.get("killed", 0)` / `expected.get("noCoverage", 0)`；
  类级 `generated`、`killed`、`noCoverage` 现在都是必填字段，缺失即失败并指名
  缺失项。非 dict 的类级条目也按 malformed 失败。
- 本批当时只收紧了类级字段，顶层仍保留 `baseline.get(...)` 默认值；组件脚本也只
  同步了类级部分。第三轮复核指出这一点，已在 §19.1/§19.2 补齐：根与组件的顶层
  必需字段、`classes` 必需段、`generated` 下降判定现在完全一致。
- 根自测扩到 11 项：9 删除类级 `killed` 失败、10 删除类级 `noCoverage` 失败、
  11 删除整个 `classes` 段失败。组件新增同构自测 11 项（见 §19.1）。

### 18.3 Flyway 期望版本推导（已修）

- 根 `scripts/test-http-e2e.sh` 不再写死 `count=8`：`flyway_expected_versions()`
  从 `src/main/resources/db/migration/postgresql` 收集 `V*__*.sql`、把
  `V<ver>__` 转成点分版本并数值排序（`LC_ALL=C sort -t. -k1,1n -k2,2n -k3,3n`）；
  最终不变量断言：已应用成功版本集合 == 目录版本集合、成功版本行数 == 目录版本数、
  不存在 `success=false` 行。行数统计与集合断言使用同一过滤条件
  （`success AND type='SQL' AND version IS NOT NULL`），因此 repeatable（`R__`，
  version 为 NULL）和 V0 baseline 不会造成假失败，而漏应用、重复或失败版本仍失败。
  新增 `V9` 自动纳入期望。
- 脚本开头新增推导自测：`V1/V10/V2_1/R__` 必须解析为 `1/2.1/10` 且忽略 repeatable。
- 未解析 SQL 内容，只按版本文件存在性推导；repeatable 迁移不计入期望版本。

### 18.4 Chrome 预检身份（已修）

- macOS 应用包分支保持原样；PATH 中的 `google-chrome`、`google-chrome-stable`、
  `chrome` 现在必须 `--version` 输出包含 `Google Chrome` 才通过，不再静默放行到
  第 11 阶段才失败。第三轮改为：单个候选失败只记录到摘要，全部候选失败后才打印
  一次合并的错误列表，避免把某个 Chromium 二进制误读为门禁结论。

### 18.5 VERIFICATION 历史节串代（已修）

- 第十一批曾在 2026-08-12 历史节里改写步骤号为 `16/18` 并加入 `source/global
  capacity` 说明；现已恢复为当时的 `16/17` 与原始事实。容量验证只保留在当前批次
  （第十一批/第十二批/第十三批）节中，不回写历史节。

### 18.6 关键响应头单值断言（已修）

- `ProductionHttpBoundaryIntegrationTest` 对 200 与 302 响应断言 CSP、HSTS、
  `X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy` 的
  `allValues(...)` 恰好一个值且内容精确。第三轮补齐了 302 的
  `X-Frame-Options`/`Referrer-Policy`，与 200 的五个头完全同构。测试注释说明：
  过滤器用 `setHeader`
  （替换语义）且在安全链之前运行；若未来在 `SecurityConfig` 配 `.headers(...)`，
  同名头会追加第二个值，这些断言会让双写/覆盖问题在测试中立即失败。

### 18.7 根与组件 baseline guard 语义复核（记录）

- 根 `scripts/test-flyway-baseline-guard.sh` 与组件同名脚本都不校验“历史总行数”，
  而是逐项断言版本与失败关闭场景：根侧断言 V2–V6（及组合 V2–V6）已应用/未应用、
  V1 源数据拒绝等；组件侧围绕 V1–V5 的精确 history 契约。
- 因此根侧改为“目录推导 + 集合相等”是**加强**而非改变语义：新增 V9 自动纳入，
  漏应用/失败仍失败。组件侧仍固定 V1–V5，因为组件 gate 的契约是“仅这些版本”
  的精确断言（例如 V1-only 启动不得出现 V2–V5）；新增 V6+ 必须显式更新组件
  脚本与文档，这是有意 fail-closed，不是遗漏。
- 组件 `test-http-e2e.sh` 仍有 `count=5` 断言，理由同上（独立组件版本线冻结在
  V1–V5）；不与根侧推导逻辑冲突。

### 18.8 最终复跑与门禁数字

| 检查 | 结果 |
|------|------|
| `scripts/verify.sh` | 15/15，`VERIFY_EXIT=0`；`last-verify.json` 记录 `2026-11-05T07:39:07Z` |
| `mvn test`（根） | 422/422，0 failures/errors/skips |
| 健康快照自测 / 变异基线自测 | 17/17 / 10/10 |
| 组件门禁（根内 + 独立） | 154 tests，0 failures/errors/skips；独立 `EMAIL_VERIFY_EXIT=0` |
| Flyway 推导 | 目录 V1–V8，期望与应用集合一致；变体自测 `1/2.1/10` |
| 覆盖率 | 后端 86.09/68.78（基线 86.00/68.66）；前端 31.90/30.66（与基线同值） |
| 变异基线 | 143/150（95.33%）、3 no-coverage（既有 PIT 报告值） |
| 腐化守卫 | 11 hit(s)，全部基线，0 new |
| 健康快照 | `Overall: HEALTHY`；`component-tests OK 154 tests` |
| Markdown / 敏感扫描 | 64 个全部可解析；753 个文件、1 条指纹例外覆盖 4 处 |

说明：本节未执行 git 写操作；工作区仍未提交，由用户决定。

## 19. 第十三批（2026-11-05）：第三轮复核 9 条收口

第三轮复核给出 3 项关闭、3 项部分关闭、1 项未关闭，并新增 7 条残余。本节按复核
顺序记录处置；所有决策均为 fail-closed，不引入新默认值。

### 19.1 组件变异守卫对齐根语义（已修）

- `reference/email-service/scripts/check-mutation-baseline.py` 删除全部
  `baseline.get(...)` / `if "generated" in baseline` / `get("classes", {})`：
  `generated`、`killed`、`noCoverage`、`mutationScore`、`classes` 都是必需键，
  缺失即失败；`classes` 非 dict 也失败；`generated` 下降失败；类级三字段仍必填。
- 新增 `reference/email-service/scripts/test-mutation-baseline.sh`（11 项）：
  匹配通过、killed 下降、noCoverage 增长、探针范围变化、CSV 缺失、基线缺失、
  generated 下降、顶层字段缺失、整个 `classes` 缺失、类级 `killed` 缺失、类级
  `noCoverage` 缺失。已接入组件 `verify.sh` 第 1 阶段。
- 不再声称“已同步”：§18.2 明确记录上一批只同步了类级字段，顶层收口在本批完成。

### 19.2 根变异守卫 `classes` 必需（已修）

- 根 `scripts/check-mutation-baseline.py` 在必需标量之后单独校验 `classes`：
  缺失报 `missing the required classes section`，非 dict 报 `malformed`，随后才
  比对类级字段；循环改为 `baseline["classes"].items()`。
- 根自测 10 → 11 项，新增“删除整段 `classes` 必须失败”。

### 19.3 Flyway 行数不变量排除 repeatable/baseline（已修）

- `test-http-e2e.sh` 的成功行数统计改为
  `success AND type='SQL' AND version IS NOT NULL`，与“已应用版本集合 == 目录版本
  集合”完全同源。`R__`（version 为 NULL）和 V0 baseline 不再造成假失败；漏应用、
  重复版本或失败行仍然失败。失败行检查保持全表 `NOT success`，repeatable 失败也会
  被捕获。文档口径同步到 TESTING/TEST_MAP/VERIFICATION。

### 19.4 组件 mtime 与指纹文件集对齐（已修）

- 新增 `_component_source_files()` 作为唯一文件清单：指纹与 mtime 都来自
  `git ls-files -co --exclude-standard -z -- <paths>`。被忽略的 `.env` 或因
  `target/` 等生成物不再触发“指纹未变却 STALE”。git 不可用时两者都返回 `None`，
  只保留 24h 年龄规则。
- 新增自测 18（忽略文件未来 mtime 仍 HEALTHY）与 19（受跟踪源文件晚于记录 →
  STALE）；19 在 touch 后重算指纹，确保只有 mtime 规则被触发。

### 19.5 组件记录缺失/写入失败失败关闭（已修）

- `component-tests` 缺失由 `N/A` 改为 `MISSING`，required 默认为真，Overall 降级。
- 组件 `verify.sh` 的 `record_component_health_status()` 在目录创建或写入失败时
  返回非零；`cleanup` 在原本成功时把它提升为 `exit_code=1`。根 `verify.sh` 的
  `record_health_status()` 同样改为 ERROR + 非零。这样根门禁能看到失败，而不是只留
  一行 WARNING。
- 新增自测 20（删除组件记录 → `component-tests MISSING` 且非 HEALTHY）。

### 19.6 CSP 302 五头同构（已修）

- 302 分支补齐 `X-Frame-Options` 与 `Referrer-Policy` 的 `allValues(...)`
  `containsExactly`，与 200 的 CSP/HSTS/X-Content-Type-Options 完全一致。定向运行
  `ProductionHttpBoundaryIntegrationTest` 通过，证明 302 上五个头都只有一个值。

### 19.7 VERIFICATION 历史节还原（已修）

- 2026-08-12 历史节删除 `source/global capacity`，把 `16/18` 恢复为 `16/17`；
  容量 E2E 只保留在 2026-11-05 的当前批次记录中。

### 19.8 Chrome 预检噪声（已修）

- 单个 PATH 候选失败只进入 `rejected` 摘要；只有全部候选都失败后才输出一次合并的
  `ERROR: PATH candidates are not Google Chrome`，并保留安装提示。macOS 应用包分支
  不变。

### 19.9 文档证据与 locale 清理（已修）

- §18.1 的 `83cdc7...` 标注为历史测量；本批修改组件脚本后复测当前树为
  `8071af0b...`。组件门禁记录会写入同一值，健康快照按同法比对。
- 根 `verify.sh` 的两处 `sort -z` 补 `LC_ALL=C`（第 37、48 行附近），与组件指纹
  排序一致，避免不同 locale 下未跟踪文件顺序漂移。

### 19.10 最终复跑与门禁数字

| 检查 | 结果 |
|------|------|
| `scripts/verify.sh` | 15/15，`VERIFY_EXIT=0`；`last-verify.json` 记录 `2026-11-05T08:48:21Z`（runId `20261105T082846Z-4365`） |
| `mvn test`（根） | 422/422，0 failures/errors/skips |
| 健康快照自测 / 根变异自测 / 组件变异自测 | 20/20 / 11/11 / 11/11 |
| 组件门禁（根内 + 独立） | 154 tests，0 failures/errors/skips；独立 `EMAIL_VERIFY_EXIT=0`；记录 `email-20261105T083755Z-27855` @ `2026-11-05T08:41:23Z`，指纹 `8071af0b…` |
| Flyway 推导 | 目录 V1–V8，期望与应用集合一致；行数按 versioned SQL 过滤 |
| 覆盖率 | 后端 line 86.0% / branch 68.66%（基线 86.00%/68.66%）；前端 31.9%/30.66%（基线 31.90%/30.66%） |
| 变异基线 | 143/150（95.33%）、3 no-coverage；本次无 live PIT CSV，快照为 N/A（周度探针） |
| 腐化守卫 | 11 hit(s)，全部基线，0 new |
| 健康快照 | `Overall: HEALTHY`；`component-tests OK 154 tests` |
| Markdown / 敏感扫描 | 64 个 Markdown 相对链接全部通过；敏感扫描 754 文件、4 处例外 |

说明：本节未执行 git 写操作；工作区仍未提交，由用户决定。
