# UniAuth 验证指南

> 状态：Live
> 最近基线：2026-08-07
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
| L2 自动化测试 | Java 行为、前端浏览器、Python JWT/JWKS | 测试按需启动各自 harness |
| L3 本地运行验证 | API、cookie、数据库、OAuth2 流程 | 是 |
| L4 外部集成 | OAuth provider、邮件、Web3、远端 JWKS | 是 |

L3/L4 前必须确认 profile、隔离数据库、凭据和网络副作用。

## 2026-08-07 当前加固门禁

> 状态：Verified。覆盖 H0.1-H0.3、H1.1-H1.3 和本轮修复触达路径；
> 不代表 H1.4-H8、完整认证正确性或生产就绪。

| 检查 | 结果 | 证据 |
|------|------|------|
| `mvn clean compile test-compile` | 通过 | Java main/test 编译成功 |
| `mvn test` | 通过 | 63 tests，0 failures/errors/skips |
| `scripts/test-http-e2e.sh` | 通过 | 13/13；真实应用、PostgreSQL、重启、JWT、Web3、email、登录方式 |
| `scripts/test-flyway-baseline-guard.sh` | 通过 | 7/7；exact schema 与六类拒绝/清理路径 |
| Flyway integration | 通过 | fresh V1、existing-schema baseline、Hibernate validate、Session、checksum/failure recovery |
| `blacksheep_dev` rehearsal | 通过 | 只读；fingerprint `12c67edaba1ca20833c0db634226b2cd3d9c07549cc8c9a390a5ff2df5eadebe` |
| `npm run lint` | 通过 | ESLint 0 warnings/errors |
| `npm ci` | 通过 | 无宽松参数；lockfile 和统一门禁显式使用官方 npm registry |
| `npm audit --audit-level=high` | 通过 | 0 high/critical；2 个 React Router moderate advisories 见下文 |
| `npx tsc --noEmit` | 通过 | 无 TypeScript 错误 |
| `npm run build` | 通过 | Vite 生产构建成功，保留 chunk warning |
| `npm run test:e2e` | 通过 | 18/18 Chrome-channel Mock Playwright tests |
| Python | 通过 | 9/9 离线 RSA/JWKS/Flask tests |
| Shell syntax | 通过 | 启动、Flyway、export 和 E2E 脚本 `bash -n` |
| Documentation | 通过 | 根入口、文档树、组件 README 和 skill 包相对链接检查，`git diff --check` |

Shell HTTP E2E 使用 `test` profile、disposable PostgreSQL、临时 RSA key、dummy OAuth
和不可达邮件服务地址。它验证：

- Flyway V1 和自定义 history table。
- 应用重启后的 migration 幂等和用户数据保留。
- `/api/auth/**` allowlist 与资源服务器拒绝边界。
- 本地注册/登录、JWT claims、cookie/header 优先级和持久化。
- refresh rotation 与 access/refresh type confusion。
- 本地签名 Web3 登录、message tamper、replay 拒绝和钱包绑定。
- 登录方式 primary/delete/最后方式拒绝。
- 邮箱注册、持久化验证码、重试耗尽和密码重置。
- logout cookie 清理、Flyway history 和最终数据库不变量。

未执行真实 OAuth provider、真实邮件或共享开发库写操作。

Flyway baseline guard 使用 disposable PostgreSQL 16。错误 major 测试通过离线
`psql` fixture 注入 PostgreSQL 15 版本号，不要求下载或支持 `postgres:15` 镜像。

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
bash -n build-frontend.sh start.sh start-with-frontend.sh scripts/*.sh
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
- 多登录方式唯一性、最后方式保护和 primary 并发不变量。
- Web3 nonce 一次性、过期、消息绑定和覆盖语义。
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
