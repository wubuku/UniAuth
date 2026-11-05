# 行为 → 测试映射

> 状态：Live。修改某个模块前先查本表，可以直接知道哪些测试保护它、保护到什么层级，
> 以及哪些行为目前只有 Playwright 覆盖。测试缺失或行为变更时必须同步更新本表。
>
> 权威门禁与数字见 [VERIFICATION.md](VERIFICATION.md)；分层验证命令见
> [AGENTS.md](../AGENTS.md) 与 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 1. 使用方式

- “保护层级”指该行为的最低自动化层：`unit`（无外部依赖）、`integration`
  （Testcontainers PostgreSQL + 真实 Bean）、`shell`（真实进程/HTTP/db）、
  `browser`（Playwright）。
- 一个行为可以出现在多行：越靠上的层越便宜，优先在上层定位回归。
- 修改安全链、token、Cookie、OAuth2、CORS、schema、前端 wire shape 时，至少运行
  表中该模块的全部层级；只跑单层不算通过。
- 本表不是覆盖率报告；它只登记有真实保护价值的行为。

## 2. 认证与 token

| 行为 | 保护测试 | 层级 |
|------|----------|------|
| `/api/auth/**` allowlist、按 method/path 拒绝、CSRF 关闭边界 | `controller/AuthApiSecurityIntegrationTest` | integration |
| Bearer/Cookie 消退歧义、大小写、空值、非 Bearer | `util/BearerTokenUtilsTest`（单元）、`controller/AuthenticationFlowIntegrationTest` | unit + integration |
| access/refresh 签发形状、`sid`/`ver`/`generation`/`auth_time` | `controller/AuthenticationFlowIntegrationTest`、`controller/JwtBoundaryIntegrationTest` | integration |
| refresh 单次轮换、replay 拒绝、logout 持久撤销、并发 refresh | `controller/TokenRevocationIntegrationTest`（真实双线程 + generation CAS） | integration |
| 密码修改后的 token family 撤销、旧 Cookie 失效 | `controller/PasswordChangeSecurityIntegrationTest` | integration |
| `POST /api/user/password` 的 recent-auth 拒绝与 CAS 409 | `controller/PasswordChangeSecurityIntegrationTest` | integration |
| recent-auth 边界（max-age、future skew、缺失 claim） | `service/RecentAuthenticationServiceTest` | unit |
| 认证 Cookie 属性与 prod `Secure` 覆盖保护 | `config/AuthCookieSecurityGuardTest`、`service/AuthCookieServiceTest` | unit |
| refresh 失败映射 401 vs 503、稳定错误体 | `controller/TokenRevocationIntegrationTest`、`controller/ApiWireContractIntegrationTest` | integration |
| refresh token claim 拒绝矩阵与 session 不活跃（type/alg/kid/issuer/jti/subject/username/sid/generation/timestamps/revoked/disabled/version） | `service/TokenValidationServiceTest`（34 个用例） | unit |
| access token 直接校验：session-aware decoder、generation 上限、撤销查询、introspection audience、validator 错误码 | `service/TokenValidationServiceTest`（access 路径 12 个用例） | unit |
| `CustomUserDetailsService` 契约（启用用户、未知用户、禁用用户、无密码 LOCAL 行失败关闭） | `service/CustomUserDetailsServiceTest`（4 个用例；框架可达、应用不可达，见实施记录 §11.6） | unit |
| `GET /api/user` 与 login/refresh wire 字段 | `controller/ApiWireContractIntegrationTest`（读取 `contracts/api-wire-contract.json`） | integration |
| token family replay 在 Python 侧不可见（架构边界） | `python-resource-server/test_app.py` 只校验签名/session claims | unit |

## 3. OAuth2 与 Web3

| 行为 | 保护测试 | 层级 |
|------|----------|------|
| OAuth2 成功/失败/错误回跳策略与恶意 `redirect_uri` 回退 | `config/OAuth2RedirectPolicyTest`（单元）、`controller/OAuth2SuccessHandlerIntegrationTest` | unit + integration |
| provider profile 信任边界与 `x`/`TWITTER` 映射 | `config/SecurityConfigXOAuth2UserServiceTest`、`controller/OAuth2SuccessHandlerIntegrationTest` | unit + integration |
| bind intent 一次性消费、session/provider 不匹配拒绝 | `controller/OAuth2SuccessHandlerIntegrationTest` | integration |
| bind intent `expires_at` 过期、`token_security_version` 变更后拒绝且不消费 | `controller/OAuth2SuccessHandlerIntegrationTest`（JDBC 制造前置状态） | integration |
| OAuth2 HTTP timeout/代理/客户端参数边界 | `config/OAuth2HttpClientConfigTest`、`service/email/EmailServiceClientConfigValidationTest` | unit |
| Web3 nonce 一次性、SIWE message 绑定、stale auth_time | `controller/Web3AuthenticationIntegrationTest`、`controller/Web3ConfiguredChainIntegrationTest` | integration |
| Web3 challenge source/global 容量超限返回 429 `WEB3_CHALLENGE_CAPACITY`，消费后释放 | `scripts/test-http-e2e.sh`（真实进程 + PostgreSQL，第十一批补齐） | shell |
| Web3 签名地址/长度/recovery id/无法恢复公钥的失败关闭；真实签名 `v=27/28` 与归一化 `v=0/1` 恢复出精确地址；`v=0x1e`（recovery id 3）通过范围检查后在恢复阶段失败 | `util/Web3SignatureUtilsTest`（`treatsRecoveryIdThreeAsAnInRangeValue` 断言实际抛出且非范围拒绝） | unit |
| Web3 前端按钮/钱包拒绝/签名拒绝 | `frontend/tests/e2e/security-boundaries.spec.ts` | browser |

## 4. 邮箱与密码

| 行为 | 保护测试 | 层级 |
|------|----------|------|
| canonical email、challenge HMAC/重复消费/限流、outbox 投递状态 | `controller/EmailAuthenticationIntegrationTest`、`service/F1DurabilityIntegrationTest`、`controller/F1IdentityBoundaryIntegrationTest` | integration |
| canonical email 长度/字符/local-part/domain-label 拒绝边界 | `service/CanonicalEmailServiceTest` | unit |
| outbox 激活失败、最大尝试、deadline、非 UNUSED、退避越过 deadline 的失败关闭 | `service/EmailDeliveryOutboxStateServiceTest` | unit |
| 注册/重置预览不枚举所有权、密码策略、JSON credential 边界 | `controller/F1IdentityBoundaryIntegrationTest`、`scripts/test-registration-password-reset.sh` | integration + shell |
| 邮箱登录真实链路（参考邮件服务 + PostgreSQL + 浏览器） | `scripts/test-email-login-browser-e2e.sh`、`scripts/test-http-e2e.sh` | shell + browser |
| 参考邮件服务 HTTP 契约、幂等、鉴权 header 单值 | `reference/email-service` Maven tests、`scripts/test-http-e2e.sh`、`python-resource-server/test_app.py` stub | integration + shell |
| SMTP 投递失败/重试/脱敏/队列生命周期 | `reference/email-service/src/test`（PostgreSQL + GreenMail）、`reference/email-service/scripts` runtime/HTTP/Flyway guards | integration + shell |

## 5. 安全链、CORS、CSRF 与响应头

| 行为 | 保护测试 | 层级 |
|------|----------|------|
| 四条安全链的 CORS 预检 allowlist、非法 origin/method 拒绝 | `controller/CorsSecurityIntegrationTest`（含 4 条链的 `PATCH` 预检） | integration |
| CORS 配置解析与精确 origin 校验 | `config/ConfigurationSafetyTest` | unit |
| CSRF 重复/空 header、豁免路径 | `config/CsrfProtectionFilterTest`（12 分支） | unit |
| 敏感响应头与 no-store 策略 | `config/SensitiveResponseHeadersFilterTest`、`security/SensitiveOutputPolicyTest` | unit |
| strict introspection（Basic-only、单 header、form-only、拒绝矩阵） | `controller/AuthenticationFlowIntegrationTest` | integration |
| 生产 HTTP 边界（HSTS/CSP/暴露面） | `controller/ProductionHttpBoundaryIntegrationTest`（CSP 自动注册 + 200/302 五个关键头单值断言，2026-11-05 第十一/十二/十三批补齐） | integration |
| 全局异常 handler 稳定错误体（含 401/403/503 失败关闭与 validation 回退） | `config/GlobalExceptionHandlerTest` | unit |
| 限流禁用旁路、429 retry-after、数据库故障 503、清理失败与输入截断 | `service/AuthRateLimiterTest` | unit |
| SPA fallback 不吞掉 `/api`/`h2-console`/`favicon` | `controller/SpaControllerTest` | unit |

## 6. 持久化、迁移与并发

| 行为 | 保护测试 | 层级 |
|------|----------|------|
| fresh migrate V1→V8、Hibernate `validate`、约束/索引、append-only；期望版本由迁移目录推导，行数只统计 versioned SQL | `database/FlywayMigrationIntegrationTest`、`scripts/test-http-e2e.sh`（第十二/十三批：集合推导 + repeatable/baseline 排除） | integration + shell |
| existing schema baseline、临时 Flyway 配置隔离、guard 失败矩阵 | `database/FlywayExistingSchemaBaselineIntegrationTest`、`scripts/test-flyway-baseline-guard.sh` | integration + shell |
| checksum drift、失败恢复、V2-V6 grouped rollback | `database/FlywayFailureRecoveryIntegrationTest`、`scripts/test-flyway-baseline-guard.sh` | integration + shell |
| 登录方式并发 bind/delete/set-primary 409、至少一个登录方式 | `controller/LoginMethodConcurrencyIntegrationTest`、`scripts/test-http-e2e.sh` | integration + shell |
| shared `public` schema 双组件 history/relation 校验 | `database/UniAuthSharedSchema*`、`scripts/test-email-shared-schema-e2e.sh` | integration + shell |
| Session round-trip 与 security version 失效 | `database/FlywayMigrationIntegrationTest`、`controller/TokenRevocationIntegrationTest` | integration |
| 备份/恢复 rehearsal | `scripts/test-auth-backup-restore-rehearsal.sh`、`reference/email-service/scripts/backup-postgres.sh` | shell |

> 当前没有 V9+ 迁移。新增 V9 时必须同时增加：schema 指纹常量、fresh migrate→V9
> + Hibernate `validate`、相关 preflight 和 baseline guard 用例；不得只加 SQL。
> `scripts/test-http-e2e.sh` 的期望版本自第十二批起从迁移目录推导，V9 无需改该脚本
> 的计数；第十三批起行数统计只覆盖 `success AND type='SQL' AND version IS NOT NULL`，
> 因此新增 `R__` 或 V0 baseline 不会假失败，漏应用、重复或失败版本仍会由集合与
> 行数断言拦住。

## 7. 前端

| 行为 | 保护测试 | 层级 |
|------|----------|------|
| refresh 单飞、Web Locks、marker 失效、logout 等待 in-flight refresh | `frontend/tests/unit/authService.test.ts` | unit |
| `checkAuth`/`localLogin`/refresh 失败清理认证状态 | `frontend/tests/unit/useAuth.test.tsx` | unit |
| 登录/注册表单、后端错误消息、邮箱验证码输入、登记成功回跳 | `frontend/tests/unit/loginPage.test.tsx` | unit |
| 受保护页面在 401/未认证时只显示加载态、不渲染受保护内容 | `frontend/tests/unit/loginPage.test.tsx`（`TestPage` 守卫） | unit |
| 跳转目标与触发条件（logout `/login`、oauthLogin provider URL、改密回跳） | `frontend/tests/unit/navigation.test.ts`、`frontend/tests/unit/useAuth.test.tsx`（mock `navigateTo`） | unit |
| wire 字段与 TS 类型一致性 | `frontend/tests/unit/wire-contract.test.ts`（读取同一份 `contracts/` JSON） | unit |
| 账户页登录方式增删、OAuth 绑定、409 冲突保持可见 | `frontend/tests/e2e/security-boundaries.spec.ts`（account page 用例） | browser |
| 登录 401 重试带新 access token、并发 401 共享一次 refresh | `frontend/tests/e2e/security-boundaries.spec.ts` | browser |
| 跨标签页 refresh 单次轮换、logout 不被迟到 refresh 复活 | `frontend/tests/e2e/security-boundaries.spec.ts`（60s 预算 + worker 侧顺序信号） | browser |
| OAuth callback StrictMode 只处理一次、错误落到登录页 | `frontend/tests/e2e/security-boundaries.spec.ts` | browser |
| 生产静态构建不含诊断路由/token 持久化 | `frontend/tests/production/production-routes.spec.ts` | browser |

### 7.1 目前只有 Playwright 覆盖的行为

- `window.location` 的真实导航结果（jsdom 不实现导航）。跳转**目标与触发条件**
  现在由 `navigateTo` 接缝在 Vitest 断言，但“浏览器真的导航到该 URL”仍只有
  Playwright 覆盖。
- 真实浏览器标签页之间的 Web Locks 竞争与后台节流行为。
- 生产 bundle 的静态路由裁剪与资源加载。
- OAuth callback 在 React StrictMode 下的单次处理。

> 新增浏览器用例前先判断能否落到 Vitest；能在纯逻辑层保护的优先放 Vitest。

## 8. Python 资源服务器

| 行为 | 保护测试 | 层级 |
|------|----------|------|
| RSA/JWKS、`kid` 选择、issuer/audience、过期、算法白名单 | `python-resource-server/test_app.py` | unit |
| `type`/`jti`/session claims 必需、refresh token 拒绝 | `python-resource-server/test_app.py` | unit |
| Bearer 大小写与 malformed header | `python-resource-server/test_app.py` | unit |
| JWKS 不可用失败关闭 | `python-resource-server/test_app.py` | unit |
| 实时撤销不可见（只做离线 JWKS 校验） | 架构边界，无自动化 | — |

## 9. 确定性与 CI 并行度

- 全仓测试没有无界 `sleep`。Shell E2E 的等待都是带预算的可观测状态轮询；数据库就绪
  统一走 `scripts/lib/postgres-readiness.sh`（0.25s→2s 自适应回退，超时打印
  `pg_isready`、容器状态和日志尾部后失败关闭），不再各脚本手写 `for + sleep`。
  2026-11-05 第七批已把历史 7 处固定等待（4 处 Java、3 处前端）全部改为确定性
  写法：Awaitility 轮询真实数据库状态、等待客户端真实断连、测试显式释放的门闩、
  `expect.poll`；第七批结束时 `config/test-rot-baseline.json` 为空数组。第十一批
  收紧 `no-assertion` 规则（`assertDoesNotThrow` / `doesNotThrowAnyException`
  不再算断言）后，基线为 11 条人工复核的“仅验证不抛异常”守卫用例；新增命中仍
  即拦截（§9.4）。
  邮件参考组件 `reference/email-service/scripts/lib/postgres-readiness.sh` 是同一逻辑
  的组件内副本，三个 harness 均已统一，并同样去掉 `--rm`。两份副本的共享代码区用
  `BEGIN/END SHARED POSTGRES READINESS` 标记，`scripts/check-readiness-parity.py`
  做规范化比对并在漂移时失败；`scripts/test-readiness-parity.sh` 11 项自测覆盖
  单边改动与缺标记（§9.6；第十一批核对为 11 项，此前误写“六项”）。
  邮件组件的应用启动就绪（`wait_for_application` / `expect_startup_failure`）已改用
  组件专有 `scripts/lib/application-readiness.sh` 的有界自适应轮询；超时是失败，
  不会被当成“期待失败成立”，并有 `scripts/test-application-readiness.sh` 固定该语义。
- 端口一律由 `allocate_port` / 容器随机端口分配；代码里的 `localhost:5002` 等字面量
  是 Playwright route 拦截和校验用的契约值，不用于绑定监听。
- disposable PostgreSQL 容器不再使用 `docker run --rm`：崩溃容器需要保留日志供
  失败诊断，清理统一由各 harness 的 `EXIT` trap 完成。
- 同一 Vitest 文件内的用例共享模块单例（`csrfToken`、single-flight 槽）。
  `authService.test.ts` 每个 describe 的 `beforeEach` 调用
  `AuthService.clearLocalAuthentication()` 重置 `csrfToken`，并用“clear 后重新
  GET”和“成功后槽释放”断言证明隔离；新增用例仍必须 `await` 完成异步操作，
  跨文件由 Vitest 默认隔离。
- `flyway-baseline-existing.sh` 的 advisory lock 就绪判定只看 `pg_locks` 里该 key
  是否被**其它会话实际持有**，不再把“自己的 holder 会话刚连上、尚未拿锁”误判为
  锁冲突；后者在负载下会产生假失败。
- 跨标签页 Playwright 用 60s 显式预算、`bringToFront()` 和 worker 侧顺序信号，
  不依赖固定等待。
- CI 建议：Mock Playwright `--workers=3`（不超过可用 CPU 的一半）；4 个以上 worker
  先跑 `scripts/stress-playwright.sh 5`。完整门禁固定单进程顺序执行。

### 9.1 Flaky 复跑入口

```bash
# 默认 5 次，3 worker
./scripts/stress-playwright.sh

# 8 次，2 worker，6 个 CPU burner，只跑跨标签页用例
PLAYWRIGHT_WORKERS=2 CPU_BURNERS=6 \
PLAYWRIGHT_GREP='cross-tab logout' ./scripts/stress-playwright.sh 8
```

脚本逐次输出 PASS/FAIL，保留每次日志到临时目录，任一次失败即非零退出；
它不在 `scripts/verify.sh` 内，避免把门禁时长乘以 N。

CI 侧由 `.github/workflows/playwright-flaky-probe.yml` 每周一 03:00 UTC 定时运行
（也支持手动触发），`if: always()` 上传 stress 日志和 Playwright trace；它与
`verification.yml` 独立，不改变 PR/push 必需门禁的成功语义。同一 schedule 下还有
三个独立 job：`shuffled-playwright`（乱序执行）、`production-playwright`
（生产构建 + 2 个生产用例）、`shell-http-e2e`（真实 PostgreSQL/邮件服务 HTTP E2E）。

### 9.2 覆盖率报告与棘轮

```bash
# 后端：test 阶段生成 target/site/jacoco/
mvn test

# 前端：生成 frontend/coverage/
cd frontend && npm run test:unit:coverage
```

绝对覆盖率仍是 report-only：没有 `jacoco:check`、没有硬阈值。但新增了**棘轮**：

```bash
python3 scripts/coverage-ratchet.py                  # 检查（verify.sh 阶段 7/10 自动调用）
python3 scripts/coverage-ratchet.py --backend-only
python3 scripts/coverage-ratchet.py --frontend-only
python3 scripts/coverage-ratchet.py --update         # 显式抬高基线并提交 JSON
python3 scripts/coverage-ratchet.py --update --allow-lower  # 显式允许调低
```

基线在 `config/coverage-baseline.json`；容忍值后端 1.0pp、前端 2.0pp（页面层由
Playwright 覆盖，波动更大）。跌幅超过容忍值会让 `scripts/verify.sh` 失败，并打印
更新基线的显式步骤。覆盖率数字仍然用于发现“整类未测试”和失败关闭分支未测试。

### 9.3 测试顺序独立性与变异测试

```bash
# 后端随机顺序（Surefire 内置属性，无需改 pom）
mvn test -Dsurefire.runOrder=random -Dsurefire.runOrder.random.seed=20261105

# Playwright 乱序（list -> shuffle -> --test-list）
scripts/test-playwright-shuffled.sh
PLAYWRIGHT_SHUFFLE_SEED=20261106 PLAYWRIGHT_WORKERS=1 scripts/test-playwright-shuffled.sh

# 作用域变异测试（可选 profile；需要 JDK 21，JDK 25 会让 PIT 1.17.2 报
# Unsupported class file major version 69）
JAVA_HOME=/path/to/jdk-21 mvn -Pmutation-testing test-compile \
  org.pitest:pitest-maven:mutationCoverage
```

变异测试只锁定 `TokenValidationService`、`CanonicalEmailService`、
`Web3SignatureUtils` 及其测试；报告在 `target/pit-reports/`。当前基线 150 mutants、
143 killed（95.33%）、3 个 no-coverage，记录在 `config/mutation-baseline.json`：

```bash
JAVA_HOME=/path/to/jdk-21 mvn -Pmutation-testing test-compile \
  org.pitest:pitest-maven:mutationCoverage
python3 scripts/check-mutation-baseline.py   # 与基线比较，下降即失败
python3 scripts/check-mutation-baseline.py --update  # 仅在复核等价变异后
```

本机 PIT 统计约 5–8 秒；4 个等价存活变异体（`CanonicalEmailService` L32/L35/L69/L85）
逐条判定见实施记录 §11.3。它不进入 `scripts/verify.sh`（主门禁用本机默认 JDK，
PIT 需要 JDK 21），改由 `.github/workflows/mutation-probe.yml` 每周四 04:00 UTC
定时或手动运行，与 `verification.yml` 成功语义解耦。

邮件参考服务有独立的作用域探针（`reference/email-service/pom.xml` 的
`mutation-testing` profile），只覆盖 `EmailApiKeyFilter`、`EmailResponseSecurityFilter`、
`EmailRateLimiter`；实测 39 mutants、31 killed（79%）、0 no-coverage、PIT 约 2 秒。
基线在 `reference/email-service/config/mutation-baseline.json`，比对脚本是
组件内 `scripts/check-mutation-baseline.py`（与根脚本同为失败关闭：顶层指标与
`classes` 段必需、`generated` 下降失败），负向自测
`reference/email-service/scripts/test-mutation-baseline.sh` 11 项并接入组件
`verify.sh` 阶段 1；同一周度 workflow 追加运行。把
`EmailService` 纳入会因主要保护来自 E2E 而得到 145 mutants / 25 killed 的假低分，
故不在作用域内。

### 9.4 测试腐化守卫

```bash
python3 scripts/check-test-rot.py          # 门禁（verify.sh 阶段 1 自动调用）
python3 scripts/check-test-rot.py --list   # 打印全部命中（path:line + 原因）
python3 scripts/check-test-rot.py --update # 显式登记合法例外
bash scripts/test-test-rot-guard.sh        # 守卫正负向自测
```

规则覆盖 `.skip`/`.only`、`@Disabled`/`@Ignore`、恒真断言、无断言 `@Test`、空测试、
固定等待和 `TODO`/`FIXME`。基线 `config/test-rot-baseline.json` 现为空数组（第
七批已清零）；新增命中失败并输出文件、行号和危险原因。完整规则表见
[测试约定](TESTING.md#7-测试腐化守卫)。

### 9.5 快速反馈入口与实测耗时

```bash
PYTHON_BIN=python3 scripts/verify-fast.sh
```

只跑后端编译 + `mvn test`、前端 lint/tsc/Vitest，实测 126s；不跑任何 E2E，
不产生交付证据。最慢用例分析（Flyway baseline 2.84s、密码修改并发 1.91s、
登录方式并发 1.60s）结论是延迟来自真实迁移/并发语义，不做压缩。完整清单见
[实施记录](drafts/TEST_HARDENING_IMPLEMENTATION.md#134-最慢用例分析)。

### 9.6 就绪脚本一致性与并发证据

```bash
python3 scripts/check-readiness-parity.py
bash scripts/test-readiness-parity.sh
```

根与邮件组件的 PostgreSQL 就绪 helper 只能在 `BEGIN/END SHARED POSTGRES READINESS`
标记内保持一致；规范化只忽略 CRLF 与行尾空白，其余差异（含注释、空行）都算漂移。
标记外允许差异：组件头注释不同，组件专有的
`reference/email-service/scripts/lib/application-readiness.sh` 完全在标记外。共享区内
定义的函数名不得在标记外再次定义——把同名函数复制到标记外再改会以
`redefined outside the markers` 失败（自测第 6 步），组件专有 helper 只需换名即可。
函数识别覆盖 `name(){`、`name () {`、`function name {`、`function name() {` 等
bash 合法写法，同时跳过行首注释、多行引号字符串与 heredoc 内容，避免把示例文本
误判为 shadow（自测第 8–11 步）。两项检查接入 `scripts/verify.sh` 阶段 1，任何
一侧单改都会让完整门禁失败。

前端两个并发用例（重复 401 合并刷新、跨标签 cookie 轮换）除了 barrier，还统计
同时在途的 401 数量并断言 `max >= 2`；断言排在行为断言之前，因此应用退化成串行
请求时会先在这里失败。注意移除 barrier 本身不会让请求串行（两个独立 `useEffect`
在挂载时并发发出），该断言拦截的是串行化回归，不代表“删 barrier 就会失败”。

事件序列证据（`401:user`、`401:login-methods`、`refresh`；跨标签为 `401:tab-*`）
断言所有 401 都先于第一次 refresh，并校验时间戳单调不减。单页用例断言 refresh
恰好一次；跨标签用例按真实契约断言“至少一次、每标签最多一次”，因为 marker 是在
refresh 启动时才读取的，允许第二个标签合法地再次刷新。

### 9.7 健康快照

```bash
python3 scripts/health-snapshot.py
bash scripts/test-health-snapshot.sh
```

一条命令汇总门禁结果、后端/前端测试数、覆盖率、变异基线、腐化命中、就绪耗时与
组件门禁记录，打印表格并把 JSON 写到 `.local/uniauth/health/snapshot.json`。
`verify.sh` 结束时把本轮在临时快照里测得的真实数字写入 `last-verify.json`，因此
快照不依赖工作区里可能过期的 `target/`。前端测试数只读 Vitest / Playwright JSON
reporter 的运行时报告（`frontend/reports/`）；门禁记录存在时只认记录里的指标，
缺失即 MISSING，不会回读工作区残留报告（第十一批移除该回退分支）。报告缺失或
内部计数不一致即失败，不回落到声明值。覆盖率基线缺 scope/metric 直接失败，不再
按 0.0 静默放宽（`coverage-ratchet.py` 与快照同口径）。组件门禁写入
`.local/uniauth/health/email-last-verify.json`，快照据此显示组件 Surefire 总数，
并校验记录新鲜度：`recordedAt` 超 24h、组件源码 mtime 晚于记录、或按组件门禁
同一路径子集重算的 `sourceFingerprint` 不匹配都会标 STALE（第十二批）。第十三批
起 mtime 与指纹共用 `git ls-files -co --exclude-standard` 文件集，组件记录缺失为
`MISSING` 而非 N/A。必需层缺失、覆盖率超容忍下降、门禁记录 `passed` 与
`exitCode` 不一致都会失败。自测 20 项覆盖上述路径。详见
[测试约定](TESTING.md#13-健康快照)。

### 9.8 就绪预算校准

`90s`（应用健康）与 `60s`（启动失败退出）的实测分布、并发压力数据、余量倍数与
测量局限见 [测试约定](TESTING.md#12-就绪预算校准)。摘要：顺序冷启动 10 次中位数
3.52s、最大 4.32s；4 个 JVM 并发 12 个样本中位数 5.45s、最大 5.69s（余量 15.8×）；
守卫启动失败 3 次 ≤1.58s（余量 38×）。

