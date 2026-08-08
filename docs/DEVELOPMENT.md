# UniAuth 开发指南

> 状态：Live
> 核验日期：2026-08-08
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

完整仓库门禁使用一次性 PostgreSQL、真实参考邮件服务及其独立 PostgreSQL、真实后端
HTTP E2E、Flyway guard、Mock Playwright 和离线 Python 测试，并先通过无宽松参数的
`npm ci` 安装前端依赖。根 HTTP E2E 的正常邮箱流程直接调用
`reference/email-service/`；参考实现无法自然产生的拒绝/限流映射另在同一脚本中
显式切换到受控 loopback stub：

```bash
PYTHON_BIN=python3 scripts/verify.sh
```

`PYTHON_BIN` 可指向已安装 `python-resource-server/requirements.txt` 依赖的解释器。
该入口只复制 Git 已跟踪和非忽略的未跟踪源码到进程专属临时 Git 快照，在快照中
执行 Maven、npm、Shell E2E、Playwright 和 Python 阶段；不会复制或读取 `.env`，
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

### 数据库

entity 或 schema 变更至少核对：

- `src/main/resources/db/migration/postgresql/`
- `application-dev.yml`
- `application-test.yml`
- `application-prod.yml`
- `scripts/export-schema-pg.sh`
- `scripts/flyway-baseline-existing.sh`
- `scripts/test-flyway-baseline-guard.sh`
- Flyway fresh/baseline 集成测试
- schema fingerprint

Flyway 是唯一 schema owner。已发布 migration 不得改写；新增结构修复必须使用 V5+。

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

启用邮箱注册验证或密码重置前：

1. 单独启动[参考实现](../reference/email-service/README.md)或其他满足
   [邮件服务契约](CONFIGURATION.md#邮件服务依赖)的独立服务。
2. 为该服务配置模板、队列和 SMTP/邮件供应商凭据；生产 SMTP 使用强制 STARTTLS
   或 implicit SSL，并启用证书/主机身份校验。其他供应商协议应提供等价保护。
3. 通过参考组件的 `start.sh` 或等价受保护入口启动，确认使用独立邮件数据库；
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
- [验证指南](VERIFICATION.md)
- [前后端契约历史材料](FRONTEND_BACKEND_CONTRACT.md)
