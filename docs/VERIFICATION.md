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
- 根启动脚本必须保持可启动：默认路径只允许隔离 `dev` SQLite；`test`/`prod`
  必须显式提供数据库参数，`test` 还必须使用明显的 test/demo 数据库名。
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

## 2026-08-07 Phase 0 当前门禁

> 状态：Verified。仅覆盖 H0.1-H0.3，不代表 H1-H8、完整认证正确性或生产就绪。

| 检查 | 结果 | 证据 |
|------|------|------|
| `mvn clean compile test-compile` | 通过 | 55 个 main source、8 个 test source 编译成功 |
| `mvn test` | 通过 | 28 tests，0 failures/errors/skips |
| Python 干净 venv | 通过 | requirements 安装成功，5/5 离线 RSA/JWKS/Flask tests |
| `npx tsc --noEmit` | 通过 | 无 TypeScript 错误 |
| `npm run build` | 通过 | Vite 生产构建成功；主 JS 526.59 kB，保留 chunk warning |
| `npm run test:e2e` | 通过 | 1/1 Chrome-channel Mock Playwright test |
| `start.sh` | 通过 | `dev`、临时 SQLite/key、dummy OAuth、端口 `18085` 启动 |
| `start-with-frontend.sh` | 通过 | 前端 build + 后端在同类隔离配置、端口 `18083` 启动 |
| Shell/Python 静态检查 | 通过 | root/scripts `bash -n` 与 Python compile 检查 |
| 文档链接 | 通过 | 42 个 Markdown 文件的相对链接全部解析 |

隔离 HTTP 验证使用 `dev` profile、临时 SQLite、`SPRING_SESSION_STORE_TYPE=none`、
临时 RSA key、dummy OAuth 配置且关闭 demo data。观察结果：

```text
root=200
jwks=200
check_user=403
auth_user=403
current_user=401
generate_hash=403
create_test_user=403
reset_password=403
validate_google=403
validate_github=403
validate_x=403
introspect_test=404
oauth_validate=404
delete_nonce=403
unknown_auth=403
users=0
key_mode=0600
```

`403` 表示 `/api/auth/**` 的公开 allowlist 在 controller 映射前 fail closed；
`401` 表示 canonical `/api/user` 需要有效认证；
`404` 表示 OAuth2 诊断路由不存在。未执行真实 OAuth provider、邮件或 Web3 外部调用。

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
npx tsc --noEmit
npm run build
npm run test:e2e
npm run lint
```

当前 lint 预期失败；完成 ESLint 配置后，必须把 lint 失败视为门禁失败。

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
- schema 与 entity 在 PostgreSQL/SQLite 上一致。
- 邮箱发送 code 与持久化 code 完全相同。
- 密码重置只生成并发送一个真实 code。
- access/refresh token 的 type、issuer、audience、expiry 验证。
- blacklist/revoke/logout 能阻止旧 token。

### P1

- 四条 SecurityFilterChain 的 matcher 和权限边界。
- cookie Secure/HttpOnly/SameSite 在 profile 间一致。
- 多登录方式唯一性、最后方式保护和 primary 不变量。
- Web3 nonce 一次性、过期、消息绑定、`isNewUser` 和 bind 返回值。
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
