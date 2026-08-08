# UniAuth 验证指南

> 状态：Live
> 最近基线：2026-08-08
> 本页是项目交付验收的权威规则，区分静态/构建验证与会启动应用的行为验证。

## 交付验收硬门槛

以下规则适用于修复、重构和功能变更。没有完成与改动范围匹配的自动化验证，
不得把代码 review、编译成功或人工目测写成“已完成”。

### 后端

- API 端点必须通过后端集成测试验证，测试应尽可能覆盖 HTTP、安全过滤链、
  controller、service 和持久化边界，而不只测试孤立方法。
- 每次修改必须覆盖本次触达的成功路径、拒绝路径和关键失败路径。
- 并发正确性优先使用数据库约束、条件更新、乐观锁或 CAS。不得把悲观锁作为默认方案；
  “优先乐观锁”也不等于必须引入 JPA `@Version` 字段，应按不变量和数据库能力选择机制。
- 后端硬门槛至少包括：

```bash
mvn clean compile test-compile
mvn test
```

- 需要启动应用验证时，必须显式选择 profile 和隔离、可丢弃的数据库；禁止裸跑默认配置。
- 启动脚本被修改时，至少执行 Shell 语法检查，并在隔离配置下验证受影响的启动路径。
- 根启动脚本必须保持可启动：未设置 profile 时只默认选择 `dev`，但不会提供数据库
  回退；它只接受 `dev`、`test` 或 `prod`，并要求完整 PostgreSQL 连接参数。`dev`、
  `test` 还必须使用符合 runtime guard 规则的 dev/test/demo 数据库名。
- 对 OAuth、邮件、Web3 或其他外部服务的真实调用，必须先确认凭据、费用和副作用；
  未经用户允许不得发起高成本或不可逆的真实调用。

### 邮件服务参考实现

`reference/email-service/` 是独立 Maven 组件，不纳入根 Maven reactor。修改邮件服务
代码、HTTP/模板契约、队列、实体或 migration 时，至少执行：

```bash
cd reference/email-service
scripts/verify.sh
```

组件级集成测试必须依赖完整 Spring ApplicationContext 和真实业务 Bean，并尽可能从
真实 HTTP 入口覆盖 Flyway/PostgreSQL、Thymeleaf、队列、异步事件、JavaMailSender
和 SMTP 结果。PostgreSQL 使用 Testcontainers，SMTP 使用进程内 GreenMail；默认门禁
不得读取 `.env`、连接真实供应商或发送真实邮件。所有 profile 都只接受独立
PostgreSQL datasource；H2 仅作为 runtime guard 的负向输入，不是测试数据库。统一
入口还必须执行：

- `scripts/test-runtime-guard.sh`：profile、独立数据库、env 权限、暴露鉴权以及
  STARTTLS/implicit SSL/server identity 配置矩阵。
- `scripts/test-http-e2e.sh`：真实 JAR、真实 HTTP、Flyway/PostgreSQL、API key、
  重复鉴权 header 拒绝、模板渲染、边界拒绝和重启持久化。
- `scripts/test-flyway-baseline-guard.sh`：dirty schema 拒绝、V2 坏数据失败关闭和
  forward-fix；在 V1 migrated 应用启用 API key 后仍拒绝重复同名凭据。

### 前端

前端模块是 `frontend/`。凡改动前端源码、接口契约或后端返回结构，硬门槛至少包括：

```bash
cd frontend
npx tsc --noEmit
npm run build
```

- 核心用户路径应使用 Mock 配置执行浏览器级测试，优先使用 Playwright，并覆盖本次改动。
- 测试配置优先通过命令环境变量注入，不为验收持久化本地环境文件。
- 如果临时创建 `frontend/.env.local` 或类似文件，测试结束后必须删除，并确认未进入提交。
- `GET /api/user` route mock 必须使用真实 wire 字段 `userId`、`userName`、`userEmail`。
  涉及导航的认证测试应等待首页 `checkAuth()` 完成并断言最终稳定状态，不能把验证响应
  写入 localStorage 与当前用户响应覆盖之间的瞬时字段当作通过条件。
- 当前仓库的核心 Mock 浏览器门禁是：

```bash
cd frontend
npm run test:e2e
```

Playwright 通过命令环境变量和请求 route 提供 mock，不依赖持久化 `.env.local`。

### Python 资源服务器

凡修改 `python-resource-server/`、JWT/JWKS/claim 契约或跨语言认证行为，至少执行：

```bash
cd python-resource-server
python3 -m unittest -v test_app.py
```

- 测试必须使用本地生成的临时 RSA key 与 mock JWKS，不得访问历史外部域名。
- 真实 JWKS/认证服务验证属于显式 opt-in 的联调，不能替代离线测试。
- Python 依赖应在干净虚拟环境中可安装；不得依赖工作机已有的隐式包。

### 前后端联调

- 后端集成测试与前端 Mock 测试分别通过后，通常已经覆盖主要风险。
- 只有跨端契约、cookie、CORS、redirect、代理或运行时配置仍有疑问时，才启动非 Mock
  前后端做联调；联调不能替代两端各自的自动化门禁。
- 实际 HTTP 验证必须记录端口、profile、数据库目标、关键环境覆盖和观察到的状态码/响应契约。
- 不得要求用户先代为完成本可自动化的首轮验收。交付信心必须来自测试证据，而不是 review。

### 收敛检查

三轮实现检查只能在以下基础门槛全部通过后开始：

1. 后端本任务相关集成测试通过。
2. `mvn clean compile test-compile` 与完整 Maven 测试通过。
3. 前端 `tsc`、生产构建和本任务核心 Mock 浏览器测试通过；未触达前端时应明确记为不适用。
4. 需要的 Shell、Python、文档和隔离 HTTP 验证通过。

检查范围在开始前固定，以避免发散式探索。随后执行：

```text
counter = 0
while counter < 3:
    对固定范围执行一轮系统性代码、测试、配置和文档交叉检查
    if 发现实质问题:
        立即修复
        重新运行受影响的验证门槛
        counter = 0
    else:
        输出本轮时间、范围、发现、措施和结果
        counter += 1
```

只有连续三轮未发现实质问题且没有修改任何实现或文档，才允许结束检查。
任何由实质问题触发的代码或文档修改都会把计数器归零；行号漂移、纯格式和实施中自然暴露的
无关紧要细节不触发归零。无问题轮次只记录在当次工作报告中，不为留痕而修改仓库文件。

## 验证层级

| 层级 | 目的 | 是否启动 Spring 应用 |
|------|------|----------------------|
| L0 静态检查 | 语法、格式、链接 | 否 |
| L1 构建检查 | Java 编译、TypeScript/Vite build | 否 |
| L2 自动化测试 | Java 行为、邮件参考服务、前端浏览器、Python JWT/JWKS | 测试按需启动各自 harness |
| L3 本地运行验证 | API、cookie、数据库、OAuth2 流程 | 是 |
| L4 外部集成 | OAuth provider、邮件、Web3、远端 JWKS | 是 |

L3/L4 前必须确认 profile、隔离数据库、凭据和网络副作用。

## 2026-08-08 当前加固门禁

> 状态：Verified。覆盖 H0.1-H0.3、H1.1-H1.3、Batch A、Batch B1、Batch B2a、
> Batch B2b、邮件服务边界、邮箱 challenge 投递接受/原子消费、敏感响应、API key
> 单值鉴权，以及认证 Cookie/浏览器 refresh 存储预备切片；
> 不代表 H1.4-H8、完整认证正确性或生产就绪。

| 检查 | 结果 | 证据 |
|------|------|------|
| `mvn clean compile test-compile` | 通过 | Java main/test 编译成功 |
| `mvn test` | 通过 | 当前工作树完整测试数量以 Surefire 汇总为准，0 failures/errors/skips；Web3 V5 增加完整 SIWE 字段和并发覆盖 |
| `scripts/test-http-e2e.sh` | 通过 | 15/15；真实应用、独立 PostgreSQL、参考邮件服务跨进程模板入队、失败映射 stub、重启、JWT、Web3 字段篡改/并发 replay、email、登录方式 |
| `scripts/test-flyway-baseline-guard.sh` | 通过 | 13/13；exact schema、V2/V4 初始及 apply 前数据预检、V5 history/message 列、非法 email verification state、post-baseline 失败恢复与其他拒绝/清理路径 |
| Flyway integration | 通过 | fresh V1→V5、existing baseline V1→V5、V3→V5、Hibernate validate、Session、checksum/failure recovery |
| 邮件参考服务 | 通过 | 138 tests；22 个 PostgreSQL/GreenMail ApplicationContext E2E、5 个 PostgreSQL repository constraint tests、27 个 Java runtime guard tests、1 个 PostgreSQL-only Spring Context 启动 guard test；Shell runtime 39/39、HTTP 11/11、Flyway guard 15/15、backup/restore rehearsal 10/10；Flyway schema-owner、migration discovery/naming、队列生命周期行形状和非 PostgreSQL datasource 拒绝矩阵通过 |
| `blacksheep_dev` rehearsal | 通过 | 只读；fingerprint `12c67edaba1ca20833c0db634226b2cd3d9c07549cc8c9a390a5ff2df5eadebe` |
| `npm run lint` | 通过 | ESLint 0 warnings/errors |
| `npm ci` | 通过 | 无宽松参数；lockfile 和统一门禁显式使用官方 npm registry |
| `npm audit --audit-level=high` | 通过 | 0 high/critical；2 个 React Router moderate advisories 见下文 |
| `npx tsc --noEmit` | 通过 | 无 TypeScript 错误 |
| `npm run build` | 通过 | Vite 生产构建成功，保留 chunk warning |
| `npm run test:e2e` | 通过 | 21/21 Chrome-channel Mock Playwright tests |
| Python | 通过 | 16/16 离线 RSA/JWKS/Flask tests |
| 邮件 REST stub contract | 通过 | 8/8；API key 单值/重复 header、health、接受、拒绝、限流、坏请求、chunked client 形状和安全响应 header |
| Shell syntax | 通过 | 启动、Flyway、export 和 E2E 脚本 `bash -n` |
| Documentation | 通过 | 根入口、文档树、组件 README 和 skill 包相对链接检查，`git diff --check` |

Shell HTTP E2E 使用 `test` profile、UniAuth disposable PostgreSQL、参考邮件服务
disposable PostgreSQL、临时 RSA key 和 dummy OAuth。脚本在测试序列开始前启动真实
参考邮件服务 JAR；第 10/15、11/15 步骤通过真实 HTTP 调用模板端点并直接检查
参考服务的 `email_queue`；第 12/15、13/15 步骤为获得稳定的 `503/429` 失败夹具
而重启应用并切换到受控 loopback 邮件 REST stub。它验证：

- Flyway V1/V2/V3/V4/V5 和自定义 history table。
- 应用重启后的 migration 幂等和用户数据保留。
- `/api/auth/**` allowlist 与资源服务器拒绝边界。
- 本地注册/登录、JWT claims、cookie/header 优先级和持久化。
- refresh rotation 与 access/refresh type confusion。
- 本地签名 Web3 登录、domain/chain/完整 message 字段 tamper、并发 replay、nonce
  upsert 覆盖、原子消费和钱包绑定。
- 登录方式 primary/delete/最后方式拒绝，以及真实并发 mutation 的 `200/409` 和最终
  “至少一个登录方式、恰好一个 primary”不变量。
- 邮箱注册、动态有效期/cooldown、真实参考服务接受后持久化 challenge、同步拒绝/限流
  失败关闭、不支持 purpose 拒绝、重试耗尽和密码重置。
- logout cookie 清理、Flyway history 和最终数据库不变量。

认证 Cookie/浏览器 refresh 存储预备切片还验证：

- local、邮箱、Web3、OAuth2 和 refresh 使用同一个 Cookie writer，写入与清除的
  Path/Secure/SameSite 一致，非默认 JWT TTL 会改变对应 Cookie Max-Age。
- `prod` 中认证 Cookie 或 Session Cookie 的 Secure 最终值被高优先级配置覆盖为
  false 时，ApplicationContext 启动失败。
- 前端启动、local/email/Web3/OAuth2、401 refresh 后都不保留
  `localStorage.refreshToken`；当前 access token localStorage 演示兼容性保持不变。
- Python 资源服务器在签名、kid、issuer、audience 和 expiry 正确时仍拒绝
  refresh token 和缺少 `type` 的 token。

未执行真实 OAuth provider、真实邮件或共享开发库写操作。

UniAuth 主应用的邮件相关门禁验证 ApplicationContext、PostgreSQL 状态机和真实 HTTP
客户端边界：

- `EmailAuthenticationIntegrationTest` 使用完整 Spring ApplicationContext、MockMvc、
  Testcontainers PostgreSQL 和真实 repository/service Bean；只在最外层 `EmailService`
  边界使用可控 mock，覆盖接受/拒绝结果、动态配置、原子消费和 retry-count CAS；
  两个回归场景在原子消费后插入更新 challenge，分别验证 `/verify-email` 和
  `/register` 不会再按 email/purpose 二次消费该记录。
- `RestTemplateEmailServiceIntegrationTest` 使用 Spring context 中的真实邮件 client Bean
  和 loopback HTTP server，覆盖 API key、context path、超时和 429 映射。
- Shell HTTP E2E 的正常邮件路径启动真实参考服务和真实应用，通过生产
  `RestTemplate` 调用链与两个独立 PostgreSQL 验证模板已入参考服务队列；失败/限流
  映射路径再切换到受控 stub，验证数据库中不留下失败 challenge。
- `scripts/test_email_service_stub.py` 独立固定 stub 的 API key 单值/重复 header、
  health、接受、拒绝、限流、坏请求和 chunked request 兼容性。
- 外部服务返回 `success=true` 仍只表示接受或入队，不证明收件箱已收到邮件。
- 外部服务已接受后，本地 challenge 保存事务失败的窗口，以及异步 delivery 失败后
  撤销 challenge，仍需要 outbox 或 delivery/challenge 双状态机解决。

独立参考实现补充了默认无外部副作用的组件级 E2E：

- 完整 Spring ApplicationContext 和随机真实 HTTP 端口。
- Flyway V1/V2/V3、独立 history table、PostgreSQL 16 和 Hibernate `validate`。
- 两个必需模板经过真实 service/repository/event Bean、Thymeleaf、队列和 GreenMail 收件。
- API key、输入和分页边界、未知模板拒绝、SMTP 连接失败后的失败日志和可重试状态。
- 配置 API key 时，真实 Tomcat HTTP 入口只接受恰好一个精确匹配的
  `X-Email-Service-Key`；重复正确值、正确/错误和错误/正确组合均返回 `401`，
  单个正确 header 的成功契约保持不变。
- Java/Shell 双重 runtime guard 拒绝 STARTTLS 降级、STARTTLS 与 implicit SSL
  同时启用、生产明文 SMTP 和关闭 server identity verification；PostgreSQL
  ApplicationContext 验证该属性进入真实 `JavaMailSender` Bean。
- Java/Shell 双重 runtime guard 拒绝带 URI 语法/空白的 SMTP host，以及非数字或
  超出 `1..65535` 的 SMTP port；PostgreSQL ApplicationContext 验证实际 host/port
  进入真实 `JavaMailSender` Bean。
- `EmailQueueRepositoryTest` 和 `EmailLogRepositoryTest` 使用 disposable PostgreSQL
  + Flyway + Hibernate `validate`，直接验证 retry bound check constraint 和
  `email_logs.queue_id` 外键拒绝 orphan row，以及队列终态处理时间、claim 后重试调度
  清理和非失败状态错误文本约束；repository 测试不再依赖 H2 `create-drop`。
- `EmailServiceRuntimeGuardTest` 对所有 profile 固定 PostgreSQL-only JDBC URL 约束；
  独立 `ApplicationContextRunner` 测试装配真实 configuration properties 和 guard
  Bean，证明 `test` profile 注入 H2 URL 时 Context 在 Flyway 前失败。
- PostgreSQL/GreenMail ApplicationContext 直接持久化绕过 HTTP 的异常队列行，
  验证 CR/LF subject、过大 HTML 和非法 `emailType` 在 SMTP 前被拒绝，同时保留
  现有失败日志和 retry 状态机。
- UniAuth 客户端 URL/timeout 配置拒绝矩阵，以及 context path、尾斜杠和 API key
  请求契约。
- simple 请求 HTML 与模板渲染后的最终 HTML 都限制为最多 1,000,000 字符。
- 原子队列 claim、stuck `PROCESSING` 恢复和配置化最大重试次数。
- Flyway V3 对 V1/V2 历史队列行做生命周期元数据规范化，并由 PostgreSQL check
  constraint、真实 delivery/recovery Bean 和 Shell Flyway guard 固定四种合法状态。
- 恢复候选按优先级处理；邮件总开关或队列关闭时，存量 pending/stuck 邮件不投递。
- event 与 recovery 并发竞争同一 PostgreSQL 队列记录时，只有一个投递者成功，
  最终只产生一条成功日志和一封 SMTP 邮件。
- event/recovery 的 PostgreSQL claim 抛异常时，真实 `EmailRateLimiter` reservation
  会释放，队列保持 `PENDING` 且不写日志、不进入 SMTP；delivery 一旦开始则按一次
  尝试计数，异常不会错误归还 slot。
- 异步执行器拒绝任务时不向已提交的入队事务传播异常，持久队列可由 recovery 接管。
- API key 配置、实体、事件和 HTTP 请求 DTO 的对象字符串不会包含 API key、
  收件人、验证码或 HTML。
- 独立 Shell 进程门禁验证无 SMTP 副作用的 HTTP/数据库契约、启动保护和 Flyway
  dirty-schema/V2 坏数据失败关闭，以及 V3 历史元数据规范化。
- backup/restore rehearsal 使用 disposable PostgreSQL 16 和同 major 容器客户端，
  验证共享库/版本/目录失败关闭、owner-only 原子 archive、空库恢复、队列/日志与
  Flyway history/约束一致，以及恢复后真实 Spring HTTP 写入和重启。
- 参考服务邮件 API 的真实 HTTP 响应在成功、API key 拒绝、参数拒绝、MVC 路由错误
  和内部失败下均设置 `Cache-Control: no-store`、`Pragma: no-cache` 和
  `X-Content-Type-Options: nosniff`；Shell 和 Python stub contract 均有对应断言。
- 独立 Flyway 集成测试验证 checksum 失配会在 migrate 阶段失败关闭，并保留已有
  migration history 和业务数据。
- 统一入口在进程专属临时源码快照中执行 Maven 和 Shell E2E，已通过两套完整门禁
  并行运行验证；原工作区源码在门禁期间变化时会失败关闭，不能继承旧结果。

该 E2E 证明参考实现的本地协议链和 TLS 配置 fail-closed 规则，不执行真实 TLS 握手，
也不证明真实供应商鉴权、证书链、主机名、退信或外部收件。
参考服务是至少一次而非恰好一次投递；SMTP 已接受后的数据库提交/进程失败窗口和
stuck reclaim 仍可能重复发送。真实邮箱能力仍需要隔离账户的显式 opt-in 测试，
不进入默认无副作用门禁。

## 2026-08-08 SMTP transport 加固增量

> 状态：Verified。严格布尔值解析修复后，邮件组件门禁和根统一门禁均于
> 2026-08-08 重新执行并通过。

本轮固定范围只涉及参考邮件服务的 SMTP 配置、Java/Shell runtime guard、测试夹具、
`.env.example` 和文档，不改变 UniAuth 邮箱 HTTP 契约、challenge、队列或投递业务语义。
生产参考配置现在只允许强制 STARTTLS 或 implicit SSL，并要求
`SMTP_SSL_CHECK_SERVER_IDENTITY=true`；隔离的 dev/test 夹具仍允许 loopback 明文 SMTP。

最终验证结果：

- 邮件参考服务 Maven：101 tests，0 failures/errors/skips；其中 Java runtime guard
  17 tests。
- 邮件 Shell runtime guard 21/21、HTTP/PostgreSQL E2E 8/8、Flyway guard 8/8。
- 根统一门禁：Java 98 tests、HTTP E2E 14/14、Flyway guard 12/12、
  Mock Playwright 19/19、Python 14/14，前端 lint/type/build 通过。
- 文档相对链接和 patch hygiene 检查通过。

## 2026-08-08 SMTP endpoint 配置加固增量

> 状态：Verified。邮件组件门禁和根统一门禁均已通过。

本轮只补齐参考邮件服务有效 SMTP endpoint 的 fail-closed 保护，不改变模板、队列、
重试、Flyway schema、HTTP 契约或 UniAuth 邮箱业务语义：

- `SMTP_HOST` 必须是最长 255 字符、无 URI 语法、空白或控制字符的 host/IP token。
- `SMTP_PORT` 必须是 `1..65535` 的十进制整数。
- Shell 与 Java runtime guard 使用同一拒绝语义；该早期切片曾由 H2 和
  PostgreSQL/GreenMail ApplicationContext 断言真实 `JavaMailSender` 的 host/port。
  当前 H2 测试后端已移除，现行集成覆盖统一使用 PostgreSQL。

邮件组件验证结果：

- Maven：108 tests，0 failures/errors/skips；其中 14 个完整 ApplicationContext E2E、
  24 个 Java runtime guard tests。
- Shell runtime guard 27/27、HTTP/PostgreSQL E2E 8/8、Flyway guard 8/8。
- 根统一门禁：Java 98 tests、HTTP E2E 14/14、Flyway guard 12/12、
  Mock Playwright 19/19、Python 14/14，前端 lint/type/build 通过。
- 文档相对链接、Shell 语法和 patch hygiene 检查通过。
- 不连接真实 SMTP，不验证外部 DNS、网络可达性、TLS 握手或供应商鉴权。

## 2026-08-08 持久化队列投递边界加固增量

> 状态：Verified。完整邮件组件门禁和根统一门禁均于 2026-08-08 通过。

本轮只在最终 SMTP 投递前重新验证当前已存在的入队契约，不改变合法邮件内容、
模板、REST 响应、队列状态、retry 次数、Flyway schema 或 UniAuth 邮箱业务语义：

- recipient、subject 和 HTML 复用现有校验，防止历史数据、手工 SQL 或异常写入
  绕过 HTTP DTO/service 边界。
- `emailType` 和内部 `sendMethod` 在进入 `X-Email-Type`、`X-Send-Method` 前必须是
  有界 ASCII token；缺失或空白的历史 `emailType` 按既有默认语义使用 `GENERAL`。
- 非法持久化行生成通用失败日志，不复制恶意 recipient、subject、HTML 或 header
  token；非法 `sendMethod` 记录为 `UNKNOWN`，并继续使用现有 retry 状态机。
- PostgreSQL/GreenMail 集成测试覆盖 CR/LF subject、1,000,001 字符 HTML、CR/LF
  `emailType`、超长注入型 `sendMethod`，以及 `NULL`/blank 历史 `emailType`
  按 `GENERAL` 成功投递；Shell HTTP E2E 同步覆盖 `emailType` header injection 拒绝。

邮件组件验证结果：

- Maven：110 tests，0 failures/errors/skips；其中 16 个 PostgreSQL/GreenMail
  ApplicationContext E2E、24 个 Java runtime guard tests。
- Shell runtime guard 27/27、HTTP/PostgreSQL E2E 8/8、Flyway guard 8/8。
- 根统一门禁：Java 98 tests、HTTP E2E 14/14、Flyway guard 12/12、
  Mock Playwright 19/19、Python 14/14，前端 lint/type/build 通过。

root Flyway baseline guard 的临时配置并发隔离也在本批修复：

- macOS/BSD `mktemp` 只替换末尾的 `XXXXXX`；配置模板不再在占位符后追加
  `.conf`，避免并发 rehearsal 共享并互删同一路径。
- guard 的 exact-schema 场景断言 5 次 Flyway 调用使用 5 个唯一配置文件，且调用
  结束后全部删除；内部输出不匹配时会先打印诊断再失败。
- 两套完整 root Flyway guard 已并行通过，各 `12/12`；随后当前组合工作树的完整
  根统一门禁也已通过。

Flyway baseline guard 使用 disposable PostgreSQL 16。错误 major 测试通过离线
`psql` fixture 注入 PostgreSQL 15 版本号，不要求下载或支持 `postgres:15` 镜像。
apply 竞态 fixture 会在 rehearsal 后改变 disposable 源数据，确认二次预检在创建
Flyway history 前失败关闭。第二个 fixture 在 baseline 已创建后注入不兼容数据，
确认 V2 拒绝迁移，并且脚本只在受管 schema 未变、history 为 baseline-only 时移除
不完整 history，恢复为可重新 rehearsal 的状态。V4 fixture 另行确认实体契约坏数据
会在创建 history 前被只读 preflight 拒绝。

## 2026-08-08 限流 reservation 异常路径加固增量

> 状态：Verified。邮件组件和根统一门禁均于 2026-08-08 通过。

本轮只修复 event/recovery 在 PostgreSQL claim 抛异常时泄漏单进程限流 slot 的
问题，不改变正常投递、失败 retry、REST 契约、Flyway schema 或 UniAuth 邮箱流程：

- reservation 在 claim 返回 false、claim 抛异常或 delivery 返回 `SKIPPED` 时释放。
- 一旦调用 delivery bean 就按一次投递尝试计数；后续 SMTP/数据库失败或异常不归还
  slot，避免未知投递结果绕过限流。
- 两个 PostgreSQL/ApplicationContext E2E 使用真实 `EmailRateLimiter`、listener/
  processor、repository 和事务 Bean，只对 claim 方法注入异常；断言队列仍为
  `PENDING`、无 `email_logs`、无 GreenMail 邮件且 slot 可立即复用。
- 四个行为测试分别固定 event/recovery 的 claim 异常释放和 delivery 异常消费语义。

邮件组件验证结果：

- Maven：116 tests，0 failures/errors/skips；其中 18 个 PostgreSQL/GreenMail
  ApplicationContext E2E、24 个 Java runtime guard tests。
- Shell runtime guard 27/27、HTTP/PostgreSQL E2E 8/8、Flyway guard 8/8。
- 根统一门禁：Java 98 tests、HTTP E2E 14/14、Flyway guard 12/12、
  Mock Playwright 19/19、Python 14/14，前端 lint/type/build、文档链接和
  patch hygiene 通过。

## 2026-08-08 限流 reservation 窗口 ownership 与附加 E2E 增量

> 状态：Verified。邮件组件和根统一门禁均于 2026-08-08 通过。

本轮延续上一切片，不改变邮件内容、队列状态机、retry、REST schema 或 Flyway
migration：

- `EmailRateLimiter.tryAcquire()` 返回绑定当前窗口 generation 的 reservation；
  release 幂等，只能归还同一窗口额度。
- 旧窗口 reservation 在新窗口开始后迟到释放不会扣减新窗口计数；取得额度后临时
  关闭限流也不影响原 reservation 的正确释放。
- event/recovery 的 PostgreSQL/ApplicationContext E2E 在 claim 内滚动窗口并取得
  新窗口唯一额度，随后让旧 reservation 释放，确认新额度仍保持占用。
- Shell HTTP E2E 新增 queue detail 披露边界断言：响应不包含渲染 HTML/metadata，
  且当前夹具的验证码值不出现在响应中。endpoint 仍返回 subject，不能据此推导任意
  敏感值都不可能经允许字段返回。
- Shell Flyway guard 新增 checksum drift 场景，确认启动失败关闭、不改变业务数据
  和成功 history 行数，也不自动改写漂移 checksum；显式恢复原 checksum 后可正常
  启动。Java PostgreSQL migration 集成测试覆盖同一保持和恢复路径。

邮件组件验证结果：

- Maven：124 tests，0 failures/errors/skips；其中 20 个 PostgreSQL/GreenMail
  ApplicationContext E2E、24 个 Java runtime guard tests。
- Shell runtime guard 27/27、HTTP/PostgreSQL E2E 9/9、Flyway guard 9/9。
- 根统一门禁：Java 98 tests、HTTP E2E 14/14、Flyway guard 12/12、
  Mock Playwright 19/19、Python 14/14，前端 lint/type/build、文档链接和
  patch hygiene 通过。
- 完整日志：`/tmp/uniauth-email-verify-checksum-preservation-20260808.log`，该路径是本机
  临时证据，不属于仓库交付物。
- 根统一门禁日志：`/tmp/uniauth-verify-checksum-preservation-20260808.log`，
  该路径同样只作为本机临时证据。

## 2026-08-08 根统一门禁源码快照隔离

> 状态：Implemented。最终提交门槛固定使用下述仓库外 artifact 目录并保存完整日志。

根 `scripts/verify.sh` 现在先固定当前 HEAD、tracked diff 和非忽略 untracked 文件
指纹，再把全部非忽略源码复制到进程专属临时 Git 快照中执行 11 个阶段。这样并行
门禁不会共享根 `target/`、前端 `node_modules/` 或静态构建输出，也不会因另一进程
执行 `mvn clean` 导致测试运行中 `.class` 消失。门禁结束前会在原工作区执行
`git diff --check` 并重新核对源码指纹；`rsync` 完成后也会在编译前立即复核一次，
避免对写入中的中间态执行昂贵测试。验证期间发生任何源码或计划文档变化都失败关闭，
不能继承快照结果。邮件参考服务的独立快照入口执行同样的复制后复核。

快照无论成功或失败都会在显式的 `VERIFICATION_ARTIFACTS_DIR` 下创建运行专属目录，
保留可用的主服务 Surefire 报告、邮件参考服务独立快照回传的 Surefire 报告、
Playwright `test-results`/report/blob report，并写入退出码、HEAD 和源码指纹。
该目录必须是仓库外的绝对路径，且符号链接解析后的目标仍必须位于仓库外。根门槛在
邮件阶段结束后立即断言子门槛 `verification-status.txt` 为 `exit_code=0` 且至少
存在一个 `TEST-*.xml`；任意 artifact 复制或状态写入失败都会反向令门槛失败。
`SIGINT`/`SIGTERM` 分别固定记录 `130`/`143`，不能留下 `exit_code=0` 的伪成功。
成功证据已写入后若最终 `PASS` 输出失败，EXIT 清理仍会以真实非零状态重新保存，
不能让较早的 `exit_code=0` 掩盖进程失败。
CI 从 runner 临时目录上传失败证据；不能从原工作区 `frontend/test-results`
取文件，因为测试实际在快照中执行。

最终提交门槛：

```bash
VERIFICATION_ARTIFACTS_DIR=/tmp/uniauth-verification-artifacts-20260808 \
  PYTHON_BIN=python3 scripts/verify.sh \
  2>&1 | tee /tmp/uniauth-verify-root-snapshot-20260808.log
```

只有日志以 `PASS: complete repository verification gate` 结束，且 artifact
根运行和邮件子运行的 `verification-status.txt` 都记录 `exit_code=0`，邮件目录
存在 Surefire XML，才能继承本轮完整门槛结果。路径与信号守卫由
`scripts/test-verification-artifacts-guard.sh` 的 `8/8` 测试固定。

前端依赖已把 Axios、Ethers、Vite、Rollup、PostCSS 及相关传递依赖升级到修复版本。
审计仍报告 2 个 React Router moderate advisories；当前代码只使用客户端
`BrowserRouter/Routes`，导航 pathname 均为固定同源值；OAuth provider 错误仅进入
`encodeURIComponent` 编码后的 `/login` query 参数，不成为目标 URL。不使用 RSC、
SSR data router 或 `deserializeErrors`。门禁阻止 high/critical；若外部输入开始决定
导航目标 URL，必须先重新评估并升级/替换路由依赖。

## 2026-08-07 实施前基线

> 状态：Historical。该表记录 Phase 0 加固开始前的基线，不代表当前工作树。

| 检查 | 结果 | 结论 |
|------|------|------|
| `mvn clean test` | 通过 | 55 个 Java 源文件编译成功；没有测试源码 |
| `npm run build` | 通过 | TypeScript/Vite build 成功 |
| 前端 chunk | 警告 | 主 JS 约 531.82 kB，超过 500 kB 提示线 |
| `npm run lint` | 失败 | ESLint 找不到配置文件 |
| Shell `bash -n` | 通过 | 根启动/构建脚本和 `scripts/*.sh` 语法通过 |
| Python `compileall` | 通过 | Python 示例和脚本语法通过 |
| Spring 应用启动 | 未执行 | 避免触发默认 `test` 数据清空 |
| OAuth2/邮件/Web3 | 未执行 | 需要外部服务、凭据和隔离环境 |

“构建通过”不等于认证、迁移、登出撤销或跨服务集成正确。

## 基础命令

### Java

```bash
mvn clean compile test-compile
mvn test

cd reference/email-service
scripts/verify.sh
```

应检查 Surefire 输出中的实际 test count，避免测试被过滤或空执行仍被误报为通过。

### Frontend

```bash
cd frontend
npm run lint
npx tsc --noEmit
npm run build
npm run test:e2e
```

任一 lint warning/error、类型错误、构建错误或 Playwright 失败均为门禁失败。

### Shell

```bash
bash -n build-frontend.sh start.sh start-with-frontend.sh scripts/*.sh \
  reference/email-service/start.sh reference/email-service/scripts/*.sh
```

### Python

```bash
python3 -m compileall -q python-resource-server scripts
(cd python-resource-server && python3 -m unittest -v test_app.py)
```

该命令会产生 `__pycache__/`，验证后不要提交。

### Documentation

```bash
python3 .agents/skills/project-docs/scripts/check_relative_links.py \
  README.md AGENTS.md docs frontend/README.md python-resource-server/README.md
git diff --check
```

### Unified Gate

```bash
PYTHON_BIN=python3 scripts/verify.sh
```

该命令串行执行 Shell syntax、严格 `npm ci`、high/critical 依赖审计、Java compile/tests、
HTTP E2E、Flyway baseline guard、frontend lint/type/build/Playwright、Python
tests、文档链接和 patch hygiene。统一入口通过 `NPM_REGISTRY` 固定 npm registry，
默认使用 `https://registry.npmjs.org/`，避免继承用户级镜像后因缺少 audit API 而误失败。
网络受限时可同时设置本机代理；脚本会把本地回环地址加入 `NO_PROXY`。
`.github/workflows/verification.yml` 使用同一入口，避免本地与 CI 漂移。

## 行为验证前置条件

启动 Spring 前逐项确认：

- [ ] `SPRING_PROFILES_ACTIVE` 已显式设置。
- [ ] 数据库是隔离且可丢弃的。
- [ ] 演示数据开关默认关闭；若启用，已确认 disposable 标志和数据库名保护。
- [ ] 端口没有与已有服务冲突。
- [ ] OAuth2 callback 和前端 URL 与测试环境一致。
- [ ] 不会打印或提交 secret。
- [ ] 测试后有明确清理方案。

## 必须补齐的自动化覆盖

### P0

- 初始化器在未显式授权时不能清空数据库。
- PostgreSQL schema 与 entity 一致，SQLite 运行与测试入口保持退役。
- Flyway checksum、缺表、未知 auth 漂移和 baseline guard 失败矩阵继续保持覆盖。
- access/refresh token 的 type、issuer、audience、expiry 和 header/cookie 冲突。
- blacklist/revoke/logout 能阻止旧 token。
- OAuth2 登录/绑定、redirect allowlist 和 provider subject mock 集成测试。
- 邮箱发送失败、频控、重试和并发 challenge。
- Web3 完整 SIWE message 篡改与并发 replay。

### P1

- 四条 SecurityFilterChain 的 matcher 和权限边界。
- cookie Secure/HttpOnly/SameSite 在 profile 间一致。
- 多登录方式 bind/set-primary 并发不变量保持覆盖；补齐删除与 set-primary 等组合
  并发下的“至少一个登录方式且恰好一个 primary”保护。
- Web3 V5 nonce 一次性、过期清理、完整 message 绑定和覆盖语义已验证；后续继续
  关注账户创建与绑定的跨请求并发。
- `/api/user` 的 provider 和 claim 映射。
- Python 资源服务器的 `sub`/`username` 契约。

### P2

- 前端 service/type 与后端 JSON 契约。
- OAuth2 callback、错误重定向和允许域名。
- 真实邮件、provider 和 Python JWKS 的可选环境测试。

## 验证证据规则

只有满足以下条件才能写“已完成”或“通过”：

1. 命令、环境和日期明确。
2. 能看到实际执行的测试数量。
3. 外部依赖和数据库目标明确。
4. 失败路径也有覆盖。
5. 结果可由其他开发者在隔离环境复现。
6. 与改动范围对应的后端集成测试、前端构建/浏览器测试或跨端验证没有被静默跳过。
7. 三轮收敛检查是在基础验证门槛通过后执行，并且确实连续三轮无修改。

过去的验证记录保留为 Historical，不自动继承为当前版本状态。

## 相关文档

- [开发指南](DEVELOPMENT.md)
- [配置基线](CONFIGURATION.md)
- [历史异构资源服务器验证记录](../VERIFICATION_CHECKLIST.md)
- [加固实施规划](drafts/HARDENING_IMPLEMENTATION_PLAN.md)
- [下一轮加固实施计划](drafts/NEXT_HARDENING_IMPLEMENTATION_PLAN.md)
