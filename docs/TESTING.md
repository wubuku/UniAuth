# 测试约定

> 状态：Live
> 面向后续新增或修改测试的人。权威交付门槛是 [验证指南](VERIFICATION.md)，
> 行为 → 测试映射是 [行为→测试映射](TEST_MAP.md)。本文只讲“怎么写、放哪、别踩什么坑”。

## 1. 测试放哪、用哪一种

| 层 | 目录 | 适用 | 命令 |
|----|------|------|------|
| 后端单元 | `src/test/java/**/<Class>Test.java` | 纯逻辑、不需要 Spring 上下文（如 `TokenValidationService`、`Web3SignatureUtils`） | `mvn test -Dtest=<Class>Test` |
| 后端集成 | `src/test/java/**/*IntegrationTest.java` | HTTP、安全过滤链、controller、service 到 PostgreSQL 的完整边界 | `mvn test -Dtest=<Class>IntegrationTest` |
| Shell HTTP E2E | `scripts/test-http-e2e.sh` | 真实 JAR、真实 HTTP 端口、真实 PostgreSQL、真实邮件参考服务 | `scripts/test-http-e2e.sh` |
| Mock 浏览器 | `frontend/tests/e2e/` | 核心用户路径；请求由 Playwright route mock，不依赖后端 | `cd frontend && npm run test:e2e` |
| 生产浏览器 | `frontend/tests/production/` | 真实生产 bundle（诊断路由已剔除、无 localStorage token） | `cd frontend && npm run test:e2e:production` |
| 前端组件单测 | `frontend/tests/unit/` | hook、service、纯函数、wire 契约 | `cd frontend && npm run test:unit` |
| 真实邮箱浏览器 E2E | `frontend/tests/email-login-e2e/` | 五服务（PostgreSQL/邮件/UniAuth/Vite/Python）真实链路 | `PYTHON_BIN=... scripts/test-email-login-browser-e2e.sh` |
| Python 资源服务器 | `python-resource-server/test_app.py` | 离线 RSA/JWKS/Flask 契约 | `cd python-resource-server && python3 -m unittest -v test_app.py` |
| 邮件参考服务 | `reference/email-service/src/test/` | 组件自己的 ApplicationContext、PostgreSQL、GreenMail | `cd reference/email-service && scripts/verify.sh` |

选择顺序：能用一个直接单测证明的行为不要上升为集成测试；但只要涉及 HTTP、
安全链、数据库约束或并发，**必须**有集成测试，单测不能代替。

- `scripts/test-http-e2e.sh` 的 Flyway 期望版本由
  `src/main/resources/db/migration/postgresql` 的 `V*__*.sql` 推导（`R__` repeatable
  不计入），最终断言“已应用成功集合 == 目录集合、**版本化 SQL** 行数一致、无失败
  行”。行数与集合使用同一过滤条件
  `success AND type='SQL' AND version IS NOT NULL`，所以 `R__` 和 V0 baseline 不会
  假失败，漏应用或重复版本仍失败；新增 V9 无需修改脚本。脚本开头附推导自测
  （`V1/V10/V2_1/R__` → `1/2.1/10`）。
- 使用 Playwright `channel: 'chrome'` 的门禁要求真实 Google Chrome。根门禁预检对
  PATH 候选校验 `--version` 品牌输出；Chromium（如 Debian 的 `chrome`）不会被放行，
  但只有全部候选都失败后才打印一次合并错误，macOS 仍按应用路径检查。

## 2. Testcontainers 与夹具纪律

- 只使用 disposable PostgreSQL 16.13（Java 侧 Testcontainers，Shell 侧
  `postgres:16.13`）。默认门禁不得读取 `.env`，不得连接 `blacksheep*` 或任何共享
  开发库。
- 每个 harness 使用唯一容器名，并在 `EXIT` trap 中 `docker rm -f`。**不要**使用
  `docker run --rm`：容器崩溃时会把日志一起删除，失败现场无法诊断。
- 宿主端口不要写死；用 `-p 127.0.0.1::5432` 之类的随机端口，避免并行任务冲突。
- 数据库就绪必须调用 `scripts/lib/postgres-readiness.sh` 的
  `wait_for_disposable_postgres`：它用 0.25s → 2s 封顶的自适应回退，在预算内轮询，
  超时后打印最后一次 `pg_isready`、容器状态和日志尾部，然后失败关闭。
  禁止再手写 `for _ in $(seq 1 N); do ... sleep ...`，历史上正是这种写法的
  12 秒预算与子脚本的 60 秒预算不一致，在宿主机被压满时产生假失败。
- 就绪预算是固定上限，不是无限重试；`POSTGRES_READY_TIMEOUT_SECONDS` 只用于显式
  调整，真实故障仍然失败。不得用更长 sleep 掩盖启动慢。
- 每个用例自己建立状态（`@BeforeEach`、独立 schema/事务、显式 upsert），不要依赖
  前一个用例的残留数据或执行顺序。

## 3. 禁止固定等待与固定端口

- 测试代码里禁止无上限 `sleep`。要等待状态变化时写“轮询 + deadline”，不是
  “睡固定毫秒”。Playwright 用 locator/`expect.poll`，不要 `waitForTimeout`。
- 引入守卫时登记的 7 处固定等待已在 2026-11-05 全部改为确定性写法，白名单现在是
  空数组；新增任何固定等待都会直接失败。
- 替代写法：Java 用 Awaitility（`atMost` + `pollInterval` + 有意义的 alias）等待
  真实数据库状态；模拟“慢服务/超时”的夹具改为等待真实事件（客户端断连）或由测试
  显式释放的门闩，不再睡固定毫秒；前端用 `expect.poll` 的条件 + `intervals`。
- 并发用例靠屏障（barrier）释放所有调用方来制造重叠，而不是靠人工延迟窗口。
- 屏障本身不是证据：用例还要记录同时在途的调用数与最大值，并断言最大并发 ≥ 2。
  这样即使断言顺序、UI 可见或重试逻辑退化，测试也会失败。注意在 `TestPage` 这类
  挂载即并发发请求的页面上，移除屏障不会让请求变串行（请求本就独立发出），所以该
  断言真正拦截的是“应用退化成串行请求”，而不是“屏障被删”。可观测证据必须与
  行为断言分开写。
- 并发数只是必要证据，不是充分证据。用例还要记录带时间戳的事件序列，并断言
  “所有 401 都先于第一次 refresh 到达”。单页用例的事件名是 `401:user`、
  `401:login-methods`、`refresh`，断言 refresh 恰好一次；跨标签页用例断言两个
  不同标签的 `401:tab-*` 都先于第一次 refresh。
- 跨标签页**不能**断言“refresh 恰好一次”：`coordinateTokenRefresh` 在 refresh
  启动时才读取 session marker，如果 A 标签的 refresh 在 B 标签读取 marker 之前
  完成，B 会合法地看到新 marker 并再次 refresh。真实契约是“至少一次、每标签最多
  一次”，第三次就是重试风暴。单页用例才有确定性的一次 refresh。
- 断言顺序的场景要有一个确定性负向探针：把“refresh 早于第二个 401”的事件注入
  后必须失败，否则时间戳日志只是装饰。
- 固定端口只允许出现在已经显式配置、且不并行复用的脚本里；新增自动化优先随机端口。

## 4. wire 契约三处同步

改任何 API 响应字段时必须同时改：

1. `contracts/api-wire-contract.json`（跨端唯一事实）
2. 后端 DTO 与 `src/test/java/**/ApiWireContractIntegrationTest.java`
3. 前端 TS 类型与 `frontend/tests/unit/wire-contract.test.ts`

三者任何一处漏改，另一个门禁会失败。`GET /api/user` 的真实 wire 字段是
`userId`、`userName`、`userEmail`，Playwright route mock 必须按这个形状提供。

## 5. 覆盖率棘轮

覆盖率只报告、不设绝对阈值；防止的是“覆盖率悄悄下跌”。

```bash
mvn test                                  # 生成 target/site/jacoco/jacoco.csv
cd frontend && npm run test:unit:coverage # 生成 frontend/coverage/coverage-summary.json
python3 scripts/coverage-ratchet.py        # 对比 config/coverage-baseline.json
```

- 容忍值：后端 1.0pp，前端 2.0pp（页面层主要由浏览器门禁覆盖，允许更宽波动）。
- 只在跌幅超过容忍值时失败，失败信息会给出文件与指标。
- 基线缺 scope 或 `line`/`branch` 指标时直接失败：缺键不再按 0.0 处理，避免删掉
  一条基线就静默关闭棘轮（第十一批修正）。`--update` 对缺失指标是显式迁移，
  输出 `baseline unset ->`。
- 更新基线必须显式：`python3 scripts/coverage-ratchet.py --update`；超过容忍值的
  下调还需要 `--allow-lower`，防止顺手把基线“调低”。
- 接入 `scripts/verify.sh` 阶段 7（后端）和阶段 10（前端），不新增阶段。

## 6. 变异测试（PIT）

作用域固定在三个安全关键类，定义在 `pom.xml` 的 `mutation-testing` profile：

- `TokenValidationService`
- `CanonicalEmailService`
- `Web3SignatureUtils`

固定命令（**必须 JDK 21**）：

```bash
JAVA_HOME=/path/to/jdk-21 \
  mvn -Pmutation-testing test-compile org.pitest:pitest-maven:mutationCoverage
python3 scripts/check-mutation-baseline.py
```

- 预期耗时：本机 PIT 统计约 5–8 秒（含 Maven 启动通常 < 30 秒）；CI 约 2–4 分钟。
- 结果基线：`config/mutation-baseline.json`，当前 150 mutants / 143 killed
  （95.33%），3 个 no-coverage。脚本按类比较 killed 与 no-coverage，并额外校验
  `generated`：删除生产代码导致变异体集合缩小同样失败，只有显式 `--update`
  才能记录经复核的等价调整（第十一批补上）。类级 `generated`/`killed`/`noCoverage`
  全部必填，缺一即失败（第十二批移除默认 0 的静默弱化）；非对象类级条目按
  malformed 失败；顶层指标缺失同样失败。
- 已知等价存活变异（不补断言）：`CanonicalEmailService` L32/L35/L69/L85。
- 低频率 CI：`.github/workflows/mutation-probe.yml` 每周四 04:00 UTC 定时或手动触发，
  与 `verification.yml` 解耦。它不进入 `scripts/verify.sh`，因为主门禁使用本机默认
  JDK，而 PIT 在 JDK 25 会把全部变异体误标为 `NO_COVERAGE`（
  `Unsupported class file major version 69`）。

### 6.1 邮件参考服务的作用域 PIT

`reference/email-service/pom.xml` 的 `mutation-testing` profile 固定三个纯逻辑类：

- `EmailApiKeyFilter`
- `EmailResponseSecurityFilter`
- `EmailRateLimiter`

```bash
cd reference/email-service
JAVA_HOME=/path/to/jdk-21 \
  mvn -Pmutation-testing test-compile org.pitest:pitest-maven:mutationCoverage
python3 scripts/check-mutation-baseline.py
```

- 实测本机 5–6 秒（PIT 本身约 2 秒）。第一次把 `EmailService` 也算进去时是
  145 mutants / 25 killed，改动作用域后稳定在 39 mutants / 31 killed（79.5%）、
  0 no-coverage；数据与取舍见实施记录第 14 节。
- 基线 `reference/email-service/config/mutation-baseline.json` 按类比较 killed 与
  no-coverage；下降即失败。它和根基线一样不进入 `scripts/verify.sh`。
- 曾经把覆盖面广、但主要靠 ApplicationContext E2E 保护的 `EmailService` 拉进来，
  PIT 只看到 22% 行覆盖率和 17% killed；那不是“弱断言”，而是“错误的作用域”，
  因此改为只选真正由单测负责的安全边界类。

## 7. 测试腐化守卫

```bash
python3 scripts/check-test-rot.py          # 门禁模式
python3 scripts/check-test-rot.py --list   # 打印全部命中（file:line + 原因）
python3 scripts/check-test-rot.py --update # 显式更新白名单
bash scripts/test-test-rot-guard.sh        # 守卫自身的正负向自测
```

| 规则 | 拦截的模式 | 为什么危险 |
|------|-----------|-----------|
| `skipped-test` | `.skip`、`@Disabled`、`@Ignore`、`unittest.skip` | CI 变绿但行为无人保护 |
| `focused-test` | `.only` | 静默丢弃其余用例，是最危险的假绿 |
| `constant-assertion` | `assertTrue(true)`、`expect(true).toBe(true)` | 永远不会失败，等于没断言 |
| `no-assertion` | `@Test` 方法体内没有任何断言/verify | 只证明“没抛异常” |
| `empty-test` | 空方法体 / 空回调 | 只增加数字 |
| `fixed-wait` | `Thread.sleep`、`time.sleep`、`waitForTimeout`、`new Promise(setTimeout)` | 与机器速度耦合，负载下假失败或掩盖竞态 |
| `unfinished-marker` | `TODO` / `FIXME` | 断言或覆盖欠账被静默留在门禁里 |

基线 `config/test-rot-baseline.json` 现在是空数组（历史 7 处固定等待已全部改成
确定性写法）；新增命中直接失败，并输出 `path:line` 与原因。守卫接入
`scripts/verify.sh` 阶段 1。

## 8. 顺序独立性抽查

```bash
mvn test -Dsurefire.runOrder=random -Dsurefire.runOrder.random.seed=20261106
PLAYWRIGHT_SHUFFLE_SEED=20261105 PLAYWRIGHT_WORKERS=1 scripts/test-playwright-shuffled.sh
```

修改共享夹具、静态可变状态、数据库初始化或测试清理逻辑时，至少跑一次随机顺序；
发现真实耦合就修夹具，不要靠固定顺序掩盖。

## 9. 门禁与新增文件

- 统一入口是 `scripts/verify.sh`（15 个阶段），会在临时 Git 快照中执行，因此新增
  文件必须被 `git ls-files -co --exclude-standard` 纳入；忽略文件不会参与验证。
- 新增 Shell 脚本由阶段 1 的 `bash -n` 覆盖（含 `scripts/lib/*.sh`）；新增 Python
  脚本由同一阶段的 `compile()` 检查覆盖（遍历 `scripts/*.py` 与
  `python-resource-server/*.py`，第十一批补上该检查）。
- 阶段 1 还会运行 `scripts/check-readiness-parity.py` 与
  `scripts/test-readiness-parity.sh`，保证根与邮件组件的 PostgreSQL 就绪片段不会
  单边漂移（见第 11 节）。
- 阶段 1 同时运行新增守卫的负向自测：`scripts/test-mutation-baseline.sh`
  （11 项；变异基线退化、顶层字段/`classes` 段或类级字段缺失都必须失败）、
  `scripts/test-health-snapshot.sh`（20 项；缺层、覆盖率退化、门禁记录自相
  矛盾、组件记录缺失/过期/被忽略文件不得误判）与
  `scripts/test-coverage-ratchet.sh`（删除 scope/metric 必须失败，不允许按 0.0
  静默放行）。组件 `verify.sh` 阶段 1 还运行
  `reference/email-service/scripts/test-mutation-baseline.sh`（11 项，同构负向覆盖）。
- 新增 Markdown 必须从 `docs/README.md` 或 `docs/drafts/README.md` 可达，门禁会检查
  相对链接。

## 10. 快速反馈入口

日常重构用快速入口，不跑 E2E：

```bash
PYTHON_BIN=python3 scripts/verify-fast.sh
```

它只覆盖四层：

1. 后端 `mvn clean compile test-compile`
2. 后端 `mvn test`（Surefire 单测 + PostgreSQL 集成测试）
3. 前端 `npm run lint` 与 `npx tsc --noEmit`
4. 前端 `npm run test:unit`（Vitest，不收集覆盖率）

实测耗时见 `docs/drafts/TEST_HARDENING_IMPLEMENTATION.md` 第 13 节。它**不能替代**
`scripts/verify.sh`：不启动真实后端/Vite，不跑 Mock 或生产 Playwright、Shell HTTP
E2E、Flyway guard、shared-schema、邮箱登录浏览器 E2E、邮件组件门禁、Python、
`npm ci`/供应链审计、敏感产物扫描、文档链接和覆盖率棘轮。因此它不产生交付证据，
“fast 通过”不等于“可以交付”。

## 11. 共享脚本的就绪一致性守卫

根 `scripts/lib/postgres-readiness.sh` 与
`reference/email-service/scripts/lib/postgres-readiness.sh` 不能合并成一个文件：
组件门禁只快照组件目录，引用仓库根的路径在快照里不存在。两个副本的共享契约用
显式标记界定：

```bash
# === BEGIN SHARED POSTGRES READINESS ===
# === END SHARED POSTGRES READINESS ===
```

```bash
python3 scripts/check-readiness-parity.py   # 规范化后逐行比对，漂移即失败
bash scripts/test-readiness-parity.sh       # 守卫自测：单边改动/缺标记必须失败
```

- 规范化只忽略行尾空白与 CRLF；其余任何差异（含注释和空行）都算漂移。
- 标记内是共享契约；标记外允许差异：两个文件头各解释自己为何存在，组件专有的
  `scripts/lib/application-readiness.sh` 也完全在标记之外。
- 单边修改共享区会打印 unified diff 并失败；想给组件加行为，只能加在标记外，
  或同时改两侧。
- 共享区内定义的每个函数名都是契约的一部分。把同名函数复制到标记外再改，属于
  “守卫看不见的漂移”，会以 `redefined outside the markers` 失败。组件自己的
  辅助函数只要换名字、放在标记外，就不会被误伤。
- 守卫与自测接入 `scripts/verify.sh` 阶段 1，任何一侧单独演化都会让完整门禁失败。

## 12. 就绪预算校准

`wait_for_application_health` 的 90s 与 `wait_for_process_exit` 的 60s 不是拍脑袋，
2026-11-05 在本机（macOS，Apple M4 Pro，14 核，64 GB）用 disposable
`postgres:16.13` 容器实测，每次样本都新建数据库并计完整 Flyway 首次迁移：

| 场景 | 样本 | 中位数 | 最大值 | P95 | 预算 | 相对最大值余量 |
|------|------|--------|--------|-----|------|----------------|
| 顺序冷启动（launch → health 200） | 10 | 3.52s | 4.32s | ≈4.25s | 90s | 20.8× |
| 并发 4 个 JVM × 3 轮（每实例独立库） | 12 | 5.45s | 5.69s | ≈5.67s | 90s | 15.8× |
| 配置守卫启动失败（launch → 进程退出） | 3 | 1.50s | 1.58s | n/a | 60s | 38× |

- 测量口径：毫秒墙钟，`java -jar` 到 `GET /api/email/health` 首次 200；并发组
  同时启动 4 个实例，各自随机端口、独立数据库。
- 完整门禁实际观测到的应用就绪为 3–4s（预算 90s），失败退出为 2s（预算 60s），
  与上表同一量级。
- 局限：P95 来自 10/12 个样本，统计上接近最大值；只在单机、单 Docker 后端测得，
  CI 的 CPU/磁盘争用可能更慢。预算保留了 15× 以上余量，因此即使 CI 比本机慢一个
  数量级也不会误报超时；若未来有意把预算收紧到 10s 量级，必须重新采样。
- 这些预算不是“越快越好”的性能指标：它们只界定“多久没就绪才算坏”，过紧会把
  慢 CI 变成假失败。

## 13. 健康快照

完整门禁只给一个 PASS/FAIL，重构后退化在哪一层看不出来。快照入口在门禁之后运行：

```bash
python3 scripts/health-snapshot.py
```

输出人类可读表格，并把机器可读 JSON 写到 `.local/uniauth/health/snapshot.json`
（该目录被 git 忽略）。它汇总：

| 层 | 证据来源 | 缺失/退化时 |
|----|----------|-------------|
| gate | `verify.sh` 结束时写入的 `last-verify.json` | 无记录、`passed` 与 `exitCode` 不一致、或 STALE → 失败 |
| backend-tests | 门禁快照中的 Surefire XML | 无报告、有 failure/error、或 `tests` 与 `testcase` 记录数不一致 → 失败 |
| frontend-tests | Vitest / Playwright 的 **JSON reporter 运行时报告** | 门禁记录存在时只认记录指标，缺失即 MISSING（不回读工作区报告）；报告缺失、内部总数与记录数不一致、有失败 → 失败 |
| backend/frontend-coverage | 门禁记录的 JaCoCo/前端汇总 + 已提交棘轮 | 记录存在但指标缺失 → 失败；基线缺 scope/metric → 失败；超容忍值下降 → 失败 |
| mutation | `config/mutation-baseline.json` + 最近的 PIT CSV | 基线缺失或必需指标缺失 → 失败；无实时 CSV 标为 N/A（周探针） |
| component-tests | 组件门禁写入的 `email-last-verify.json` | 无记录 → `MISSING`（必需，Overall 降级）；记录 `recordedAt` 超 24h、组件源码 mtime 晚于记录，或按组件门禁同一路径子集重算的 `sourceFingerprint` 不匹配 → STALE；记录自相矛盾、失败或 Surefire 计数不一致 → 失败。mtime 与指纹都只覆盖 `git ls-files -co --exclude-standard` 的同一文件集，被忽略文件不会触发 STALE |
| test-rot | 现场运行 `check-test-rot.py` | 新命中 → 失败 |
| readiness | `readiness.tsv` 中预算为 90s/60s 的真实样本 | 可选；只显示预算时不失败 |

前端测试数不再使用 `vitest list` / `playwright --list` 的声明值。参数化用例只在
运行时展开（旧口径 28，真实运行 29），声明值既不是证据也会掩盖“报告丢失”。
`frontend/vitest.config.ts` 与两份 Playwright config 把 JSON 报告写到
`frontend/reports/`（该目录被 git 忽略），快照只读这些运行时报告；报告缺失或
`numTotalTests` 与其 `assertionResults` 数量不符时失败关闭，不回落到声明值。

**STALE 语义**：`last-verify.json` 满足任一条件即为 STALE：

- 记录时间超过 24 小时；
- `scripts/`、`src/`、`frontend/src`、`frontend/tests`、`config/` 等门禁实际执行的
  源码或脚本的 mtime 晚于记录时间（容差 2s，避免同一秒写入误判）。

STALE 会显式打印在 gate 行，并使快照以 1 退出，而不是仅标注。理由：快照的契约是
“当前树的健康”；记录过期后门禁结论对当前树未知，此时仍输出 HEALTHY 会把“昨天
通过、今天改过”误当成健康。想恢复 HEALTHY 只需重跑完整门禁。修改文件后立即看
快照会看到 STALE，这是预期行为。

退出码：`0` 全部必需层健康，`1` 任一层失败、证据缺失或门禁记录 STALE。自测
`scripts/test-health-snapshot.sh` 20 项覆盖空仓库、覆盖率退化、Surefire 失败与
内部计数不一致、Vitest 报告缺失与内部计数不一致、门禁记录缺前端指标时不回读
工作区、覆盖率基线缺指标、门禁记录自相矛盾、记录过期、源码 mtime 晚于记录、
变异基线缺失、组件记录过期/指纹不匹配（含指纹一致性的 shell 对照）、被忽略的
组件文件不影响判定、受跟踪组件源码 mtime 晚于记录，以及组件记录缺失为
`MISSING` 等“会误判为成功”的路径。
