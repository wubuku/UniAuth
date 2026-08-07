# UniAuth 验证指南

> 状态：Live
> 最近基线：2026-08-07
> 本页区分静态/构建验证与会启动应用的行为验证。

## 验证层级

| 层级 | 目的 | 是否启动 Spring 应用 |
|------|------|----------------------|
| L0 静态检查 | 语法、格式、链接 | 否 |
| L1 构建检查 | Java 编译、TypeScript/Vite build | 否 |
| L2 自动化测试 | 单元/集成行为 | 取决于测试；当前仓库没有 Java 测试 |
| L3 本地运行验证 | API、cookie、数据库、OAuth2 流程 | 是 |
| L4 外部集成 | OAuth provider、邮件、Web3、远端 JWKS | 是 |

L3/L4 前必须确认 profile、隔离数据库、凭据和网络副作用。

## 2026-08-07 基线

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
mvn clean test
```

增加测试后，应检查 Surefire 输出中的实际 test count，避免空测试仍被误报为通过。

### Frontend

```bash
cd frontend
npm run build
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
- [ ] 已确认初始化器会执行哪些删除。
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

过去的验证记录保留为 Historical，不自动继承为当前版本状态。

## 相关文档

- [开发指南](DEVELOPMENT.md)
- [配置基线](CONFIGURATION.md)
- [历史异构资源服务器验证记录](../VERIFICATION_CHECKLIST.md)
- [加固实施规划](drafts/HARDENING_IMPLEMENTATION_PLAN.md)
