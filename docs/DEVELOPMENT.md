# UniAuth 开发指南

> 状态：Live
> 核验日期：2026-08-09
> 本指南优先保护数据和密钥；启动前先阅读 [配置基线](CONFIGURATION.md)。

## 前置条件

- Java 17
- Maven
- Node.js `20.19+`、`22.13+` 或 `24+` / npm
- Python 3（仅 Python 示例和脚本）
- PostgreSQL 16（交互启动）
- Docker（Testcontainers、Shell E2E 和 Flyway rehearsal）

## 安全规则

1. 不要裸跑 `mvn spring-boot:run`。
2. 启动前明确 profile、数据库 URL、端口和数据是否可丢弃。
3. 演示数据默认关闭；启用时必须同时设置 disposable 标志并使用 test/demo 数据库名。
4. 不打印或提交 `.env`、数据库密码、OAuth2 secret 和私钥。
5. 不手改 `src/main/resources/static/`。
6. 不把 `docs/drafts/` 中的代码片段当作当前实现。

## 无启动构建

后端编译与 Maven 测试生命周期：

```bash
mvn clean compile test-compile
mvn test
```

当前已有 Phase 0 配置、危险端点和敏感输出回归测试；必须检查实际 test count。

前端生产构建：

```bash
cd frontend
npm run lint
npx tsc --noEmit
npm run build
npm run test:e2e
```

构建会重建 Spring Boot 静态资源目录。

完整仓库门禁使用一次性 PostgreSQL 16.13、真实参考邮件服务及其独立 PostgreSQL、
真实后端 HTTP E2E、Flyway guard、backup/restore、Mock Playwright、真实五服务邮箱
登录 Playwright、离线 Python 测试和供应链/敏感扫描，并先通过无宽松参数的
`npm ci` 安装前端依赖。根 HTTP E2E 的正常邮箱
流程直接调用
`reference/email-service/`；参考实现无法自然产生的拒绝/限流映射另在同一脚本中
显式切换到受控 loopback stub：

```bash
PYTHON_BIN=python3 scripts/verify.sh
```

`PYTHON_BIN` 只用于创建隔离供应链环境；运行时和 audit 都从带 hash 的
`python-resource-server/requirements.lock`/`requirements-tools.lock` 安装，不能依赖
全局 site-packages。该入口只复制 Git 已跟踪和非忽略的未跟踪源码到进程专属临时
Git 快照，在快照中执行 15 个 Maven、npm、Shell E2E、Playwright、Python、审计和
敏感扫描阶段；不会复制或读取 `.env`，
不会写共享开发库，也不会执行 Flyway baseline apply。原工作区源码若在验证期间
变化，入口会失败并要求基于稳定工作树重跑；并行验证不共享 `target/`、
`node_modules/` 或前端静态生成物。

需要在快照清理后保留测试报告或 Playwright trace 时，使用仓库外的绝对目录：

```bash
VERIFICATION_ARTIFACTS_DIR=/tmp/uniauth-verification-artifacts \
  PYTHON_BIN=python3 scripts/verify.sh
```

入口会为每次运行创建独立子目录，并写入退出码、HEAD 和源码指纹。CI 使用同一机制
上传失败证据；artifact 根目录会解析符号链接并拒绝任何最终落入源码仓库的路径，
不要把该目录放进仓库。根入口会汇总并检查邮件服务独立快照中的 Surefire XML；
任何 artifact 写入失败都会令门槛失败。中断运行固定记录 `SIGINT=130`、
`SIGTERM=143`；成功证据写入后若最终日志输出失败，也会覆写为真实非零状态，
不能把人为中止或日志管道失败误判为成功。

## 前端开发

```bash
cd frontend
npm run dev
```

Vite 使用 `5173`，并把 `/api` 与 `/oauth2` 代理到 `8081`。后端未启动时，
UI 可以加载，但认证 API 会失败。

动态联调端口使用：

```bash
VITE_DEV_PROXY_TARGET=http://127.0.0.1:8081 \
VITE_RESOURCE_SERVER_URL=http://localhost:5002 \
npm run dev
```

proxy 会把 `Origin` 重写为 backend origin，以保持开发代理的同源语义。

## Spring 应用启动

仓库不默认选择 profile。先创建或选择一个非共享的 dev 数据库，再显式加载本地环境：

```bash
createdb -h localhost -U postgres uniauth_local_dev

set -a
source .env
set +a

POSTGRES_DATABASE=uniauth_local_dev \
SPRING_PROFILES_ACTIVE=dev \
./start.sh
```

`dev` 只接受 dev/test/demo 命名数据库；`test` 只接受明确 disposable 的 test/demo
数据库。自动化验证优先使用 Testcontainers 或 `scripts/test-http-e2e.sh`，不要手工
创建共享测试库。

`blacksheep_dev` 尚未执行 Flyway baseline apply，当前不能作为普通启动示例。
需要 apply 时使用独立受控流程，不把 apply 放进启动脚本。

## 日常改动路径

### 后端

1. 找到 controller 对应的 service/repository/entity。
2. 检查请求是否落入 `/api/auth/**` 公开链或 `/api/**` 资源服务器链。
3. 检查 cookie、CORS、JWT 和 schema 的跨模块影响。
4. 补测试后运行 [验证指南](VERIFICATION.md) 中的检查。

### 前端

1. 修改 `frontend/src/**`。
2. API 契约同步 `services/authService.ts` 和 `types/index.ts`。
3. 运行 lint、typecheck、生产构建和覆盖改动的 Mock Playwright。

### Python 示例

1. 修改 `python-resource-server/app.py` 或配套脚本。
2. 保持 issuer、audience、claim 和 JWKS 契约与后端一致。
3. 运行 Python 语法检查和 `python3 -m unittest -v test_app.py`。
4. 外部网络集成测试必须使用显式 URL 和有效 TLS。

### 邮箱登录跨服务浏览器 E2E

需要验证真实前后端、邮件 stub、JWT/JWKS 和 Python API 的完整链路时运行：

```bash
PYTHON_BIN=/path/to/python-with-resource-server-dependencies \
  scripts/test-email-login-browser-e2e.sh
```

该入口只使用 disposable PostgreSQL 和本地无投递 stub。服务启动脚本保持独立，
聚合器只管理端口、生命周期和 Playwright。完整拓扑、token 安全边界、底层脚本与
故障排查见[邮箱登录浏览器 E2E](EMAIL_LOGIN_BROWSER_E2E.md)。

### 数据库

entity 或 schema 变更至少核对：

- `src/main/resources/db/migration/postgresql/`
- `application-dev.yml`
- `application-test.yml`
- `application-prod.yml`
- `scripts/export-schema-pg.sh`
- `scripts/flyway-baseline-existing.sh`
- `scripts/test-flyway-baseline-guard.sh`
- `scripts/test-email-shared-schema-e2e.sh`
- Flyway fresh/baseline 集成测试
- schema fingerprint

Flyway 是唯一 schema owner。已发布 migration 不得改写；新增结构修复必须使用 V9+。

## 外部集成

以下操作不是默认验证，运行前必须确认凭据和副作用：

- Google/GitHub/X OAuth2。
- 真实 SMTP/邮件供应商投递。
- Web3 真实钱包签名。
- PostgreSQL schema 导出或 baseline apply。
- Python 从远端 JWKS 拉取密钥。

仓库中的[邮件服务参考实现](../reference/email-service/README.md)有独立默认门禁：

```bash
cd reference/email-service
scripts/verify.sh
```

该门禁包含 Maven/ApplicationContext/GreenMail 测试、runtime guard、真实进程 HTTP
E2E 和 Flyway fail-closed guard；所有 PostgreSQL 都是 disposable，不会连接外部
SMTP 或发送真实邮件。参考服务的真实进程 HTTP E2E 验证其自身 REST/Flyway/队列
边界，根 HTTP E2E 另外验证 UniAuth 调用它的跨进程模板入队契约。入口在进程专属
临时源码快照中运行，避免并行 Maven `clean` 互相删除 `target/`；如果原源码在验证
期间变化，门禁会失败并要求重跑。根 `scripts/verify.sh` 会执行同一入口。
参考服务的 Flyway schema-owner 配置是硬约束，不是部署建议：Java
ApplicationContext guard 和 Shell guard 都拒绝 Flyway、SQL init 或 Hibernate
schema-generation 的外部覆盖；任何这类配置变化都必须重新运行完整参考服务门禁。
其中缺失 migration location 和非法 migration 文件命名也必须 fail closed；对应的
`fail-on-missing-locations` 与 `validate-migration-naming` 覆盖同样会在迁移前被拒绝。

启用邮箱注册验证或密码重置前：

1. 单独启动[参考实现](../reference/email-service/README.md)或其他满足
   [邮件服务契约](CONFIGURATION.md#邮件服务依赖)的独立服务。
2. 为该服务配置模板、队列和 SMTP/邮件供应商凭据；生产 SMTP 使用强制 STARTTLS
   或 implicit SSL，并启用证书/主机身份校验。其他供应商协议应提供等价保护。
3. 通过参考组件的 `start.sh` 或等价受保护入口启动，默认使用独立邮件数据库；
   如显式采用 `shared-uniauth`，先确认同 schema 前置条件和整库灾备责任边界。
   `SMTP_HOST` 填写裸 host/IP 而不是 URL，`SMTP_PORT` 使用 `1..65535`；不得通过
   关闭 `SMTP_SSL_CHECK_SERVER_IDENTITY` 绕过证书错误。
4. 设置 `EMAIL_SERVICE_URL` 和 `EMAIL_SERVICE_TIMEOUT_MS`；URL 必须是带 host、
   无 userinfo/query/fragment 的绝对 HTTP/HTTPS 地址，timeout 必须在
   `100..600000ms`。若服务要求共享密钥，两端设置相同的
   `EMAIL_SERVICE_API_KEY`；该值最长 1024 字符且不能包含 CR/LF。
5. 确认后端进程能够携带所需 header 访问 `/api/email/health`。
6. 明确认知 health 和 `success=true` 只证明服务存活或请求入队，不证明最终送达。
7. 使用隔离账户执行显式 opt-in 的真实收件测试；不要把它加入默认门禁。

邮件服务未启动不会阻止 Spring Boot 启动，也不会影响已验证账户的邮箱加密码登录。
注册/重置请求遇到同步拒绝、限流、超时或网络异常时会失败关闭且不保存 challenge。
但“接口返回成功”仍只证明外部服务同步接受了请求：它不证明最终送达，也不能消除
外部已接受后本地 challenge 事务失败的窗口。

参考服务的恢复扫描间隔必须在 `1..10080` 分钟。关闭邮件总开关、队列或 recovery
任一项后，恢复任务不得发送既有 pending/stuck 队列；重新启用前应先确认积压和真实
投递副作用。

参考服务的 SMTP runtime guard 同时存在于 Shell 启动入口和 Spring
ApplicationContext。`prod` 的合法组合及 implicit SSL 切换方式见
[参考实现配置说明](../reference/email-service/README.md#配置)。默认无副作用门禁
验证 endpoint、TLS 和 identity 配置进入真实 `JavaMailSender` Bean，并使用本地
明文 GreenMail 完成协议链；它不证明真实供应商证书、TLS 握手或网络路径正确。

live 测试脚本默认使用当前端口和一次性数据库；历史脚本/文档仍可能包含旧目标，
运行前先检查生命周期状态。

## Disposable 社交身份重置

真实 Google、GitHub、X 账号在本地 disposable 数据库中已经各自完成过首次登录后，
后续“一个用户绑定多种登录方式”回归可能被 provider subject 唯一约束阻止。不要修改
唯一约束，也不要手工删除任意 `user_login_methods` 行。使用受保护脚本：

```bash
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DATABASE=uniauth_demo
export POSTGRES_USER=...
export POSTGRES_PASSWORD=...
export APP_DEMO_DATA_DISPOSABLE=true

# 默认只读预览
scripts/reset-disposable-social-identities.sh \
  --providers google,github

# 确认预览后显式写入
scripts/reset-disposable-social-identities.sh \
  --providers google,github \
  --apply
```

安全边界：

- 数据库名必须符合 `test/demo` disposable 命名规则；
- 必须显式设置 `APP_DEMO_DATA_DISPOSABLE=true`；
- `testlocal`、`testsso`、`testboth` 三个受管演示账号永远不进入目标集合；
- 只删除“恰好只有一个登录方式，且该方式属于所选 provider”的非受管用户；
- 如果所选 provider 已属于一个多登录方式用户，脚本拒绝执行，必须通过已认证产品
  UI 管理，不能用数据库脚本拆分；
- `token_blacklist` 显式清理，其余 authorities、token families、binding intents 和
  login methods 依靠 `users` 外键级联删除；
- 执行 `--apply` 前后都会验证 `uniauth_flyway_schema_history` 恰好是成功的
  V1-V8，并检查 UniAuth 关键表、级联外键和 provider 唯一索引；schema 不匹配时
  fail closed；
- `--apply` 会清空 disposable 数据库中的全部 `spring_session`，因为 Spring
  Session 的序列化 `SecurityContext` 没有可靠的 users 外键映射；执行后所有 UniAuth
  浏览器会话都必须重新登录；
- 默认模式只展示目标用户；只有 `--apply` 写库。

该脚本只适用于可丢弃的本地/测试认证数据，不是生产账号合并、解绑或删除工具。

### Circle 影响评估

这五项加固不会改变 Circle 正常登录、首次使用确认、绑定多个 provider 或邮箱登录
的业务语义：

- schema guard 只影响 `reset-social` 这一破坏性测试辅助命令；正常 UniAuth/Circle
  启动和 OAuth 回调不受影响；
- 并发 provider subject 冲突现在稳定返回 `oauth2_binding_conflict`。Circle 会继续
  显示“这个社交账号已经绑定到其他 Circle 账号，不能重复绑定”，不再把数据库竞争
  的失败方误显示为普通 OAuth 处理失败；
- OAuth 授权失败、429 或限流器 503 都会清除 binding marker，避免下一次普通登录
  被误判成绑定流程；
- 直接运行 UniAuth reset 的 `--apply` 会使整个 disposable UniAuth 数据库的浏览器
  Session 失效。Circle 联调应优先使用 `dev-circle.sh reset-social`，由 Circle 包装
  流程同步清理对应的 disposable onboarding 数据；清理后重新用邮箱登录再继续绑定
  测试；
- X 只申请 `users.read`，Circle 不需要也不会获得推文读取能力。X 控制台需要允许
  的 scope 与 UniAuth 配置保持一致。

Circle 手动验收最小闭环：

1. 用 Google/GitHub/X 任一 provider 首次登录，完成 Circle 首次使用确认；
2. 在登录方式页面保留 `LOCAL`，依次绑定其他 provider；
3. 尝试绑定已属于另一 Circle 用户的 provider，确认显示绑定冲突，而不是通用失败；
4. 退出并用邮箱登录，逐个解绑社交方式，确认每次解绑后旧会话失效；
5. 使用 `dev-circle.sh reset-social <providers> --apply` 清理后，重新确认首次登录和
   多方式绑定流程。

UniAuth 的 PostgreSQL 集成测试还固定验证一个 Circle 关键闭环：用户同时保留
邮箱/`LOCAL` 与社交登录方式时，删除社交方式会撤销当前旧 access token，但不会删除
`LOCAL`；用户可以重新用邮箱和密码登录，并重新读取剩余登录方式。这是“有限社交账号
循环测试”依赖的服务端安全与可恢复性契约。

Circle 外部认证开发环境还提供了一个包装清理入口：
`seedance-research-circle-app/api-server/dev-circle.sh reset-social <providers> [--apply]`。
它会先调用本脚本的 UniAuth 预览/写入，再由 Circle 侧脚本检查并清理已经完成首次使用
的、仅含初始化数据的 Circle tenant。若社交用户已经完成 Circle 首次使用，必须使用
Circle 包装入口，否则只删除 UniAuth 用户会留下孤立的 Circle user/tenant；若流程停在
`/welcome/external` 尚未确认，Circle 侧没有 tenant，UniAuth 通用脚本即可完成清理。

为了循环使用有限社交账号，优先在目标 UniAuth 用户中保留 `LOCAL` 登录方式。Circle
账号页只对 Google/GitHub/X 等社交方式显示解绑；只要 `LOCAL` 仍然存在，就可以把
所有社交方式逐个解绑，最后保留 `LOCAL`，而 UniAuth 仍会拒绝删除最后一种方式。邮箱
资料字段本身不等于 `LOCAL` 登录方式，测试前应在 login-methods 列表中确认 `LOCAL`
存在。每次解绑都会撤销旧登录会话，因此 Circle 会清除 provider session 并要求重新用
邮箱登录后才能继续管理下一种登录方式；这是预期的安全边界，不是解绑失败。

## 生成物与本地文件

不要提交：

- `target/`
- `node_modules/`
- `src/main/resources/static/` 的生成文件
- `*.db`
- `__pycache__/`
- `.env`
- 测试报告和临时日志

默认本地 RSA key 写入 ignored 的 `.local/uniauth/rsa-keys.ser`。历史根目录 key
已从当前索引移除且必须视为已暴露；真实环境应通过 `JWT_RSA_KEY_FILE` 使用外部管理路径。

## 文档工作流

1. 当前事实更新对应 live guide。
2. 详细历史材料保留原路径。
3. 新计划登记到 [草稿索引](drafts/README.md)。
4. 修改链接后运行技能包的离线链接检查。

## 相关文档

- [当前架构](ARCHITECTURE.md)
- [配置基线](CONFIGURATION.md)
- [运维基线](OPERATIONS.md)
- [验证指南](VERIFICATION.md)
- [邮箱登录浏览器 E2E](EMAIL_LOGIN_BROWSER_E2E.md)
- [前后端契约历史材料](FRONTEND_BACKEND_CONTRACT.md)
