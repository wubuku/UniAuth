# UniAuth 开发指南

> 状态：Live
> 核验日期：2026-08-07
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

完整仓库门禁使用一次性 PostgreSQL、真实后端 HTTP E2E、Flyway guard、Mock
Playwright 和离线 Python 测试，并先通过无宽松参数的 `npm ci` 安装前端依赖：

```bash
PYTHON_BIN=python3 scripts/verify.sh
```

`PYTHON_BIN` 可指向已安装 `python-resource-server/requirements.txt` 依赖的解释器。
该入口不会读取 `.env`，不会写共享开发库，也不会执行 Flyway baseline apply。

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

Flyway 是唯一 schema owner。已发布 migration 不得改写；新增结构修复必须使用 V2+。

## 外部集成

以下操作不是默认验证，运行前必须确认凭据和副作用：

- Google/GitHub/X OAuth2。
- 邮件服务。
- Web3 真实钱包签名。
- PostgreSQL schema 导出或 baseline apply。
- Python 从远端 JWKS 拉取密钥。

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
