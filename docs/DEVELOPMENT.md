# UniAuth 开发指南

> 状态：Live
> 核验日期：2026-08-07
> 本指南优先保护数据和密钥；启动前先阅读 [配置基线](CONFIGURATION.md)。

## 前置条件

- Java 17
- Maven
- Node.js/npm
- Python 3（仅 Python 示例和脚本）
- PostgreSQL（仅显式使用 `test`/`prod` 或数据库脚本时）

## 安全规则

1. 不要裸跑 `mvn spring-boot:run`。
2. 启动前明确 profile、数据库 URL、端口和数据是否可丢弃。
3. `dev` 和 `test` 都会清空用户与登录方式。
4. 不打印或提交 `.env`、数据库密码、OAuth2 secret 和私钥。
5. 不手改 `src/main/resources/static/`。
6. 不把 `docs/drafts/` 中的代码片段当作当前实现。

## 无启动构建

后端编译与 Maven 测试生命周期：

```bash
mvn clean test
```

当前没有 Java 测试源码，因此成功只证明编译和测试生命周期可完成。

前端生产构建：

```bash
cd frontend
npm run build
```

构建会重建 Spring Boot 静态资源目录。

前端 lint 命令存在，但当前缺少 ESLint 配置：

```bash
cd frontend
npm run lint
```

在配置补齐前，该命令预期失败，不能报告 lint 通过。

## 前端开发

```bash
cd frontend
npm run dev
```

Vite 使用 `5173`，并把 `/api` 与 `/oauth2` 代理到 `8081`。后端未启动时，
UI 可以加载，但认证 API 会失败。

## Spring 应用启动

仓库当前没有“不会清数据”的本地 profile。

若只使用可丢弃 SQLite 数据进行开发：

1. 确认 `dev-database.db` 可被清空。
2. 确认没有把重要数据库映射到该路径。
3. 显式启动：

```bash
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
```

不要把 `test` profile 指向共享、生产或不可恢复的 PostgreSQL。必须使用 PostgreSQL
集成验证时，先创建隔离数据库并显式覆盖所有连接变量。

## 日常改动路径

### 后端

1. 找到 controller 对应的 service/repository/entity。
2. 检查请求是否落入 `/api/auth/**` 公开链或 `/api/**` 资源服务器链。
3. 检查 cookie、CORS、JWT 和 schema 的跨模块影响。
4. 补测试后运行 [验证指南](VERIFICATION.md) 中的检查。

### 前端

1. 修改 `frontend/src/**`。
2. API 契约同步 `services/authService.ts` 和 `types/index.ts`。
3. 运行 build；lint 配置补齐后再把 lint 设为门禁。

### Python 示例

1. 修改 `python-resource-server/app.py` 或配套脚本。
2. 保持 issuer、audience、claim 和 JWKS 契约与后端一致。
3. 运行 Python 语法检查。
4. 外部网络集成测试必须使用显式 URL 和有效 TLS。

### 数据库

entity 或 schema 变更至少核对：

- `schema-postgresql.sql`
- `schema-sqlite.sql`
- `application-dev.yml`
- `application-test.yml`
- `application-prod.yml`
- `scripts/export-schema-pg.sh`
- 生产迁移机制

当前 `db/migration/` 不会自动执行，不能把它当作完成步骤。

## 外部集成

以下操作不是默认验证，运行前必须确认凭据和副作用：

- Google/GitHub/X OAuth2。
- 邮件服务。
- Web3 真实钱包签名。
- PostgreSQL schema 导出。
- Python 从远端 JWKS 拉取密钥。

仓库内测试脚本包含历史端口、数据库默认值和部署域名。运行前先读脚本，并通过
环境变量覆盖目标。

## 生成物与本地文件

不要提交：

- `target/`
- `node_modules/`
- `src/main/resources/static/` 的生成文件
- `*.db`
- `__pycache__/`
- `.env`
- 测试报告和临时日志

`rsa-keys.ser` 当前已被跟踪，处理方式将在加固计划中单独设计；不要在普通开发改动中轮换。

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
