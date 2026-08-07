# UniAuth 文档导航

> 当前文档基线：2026-08-07
> 本页是项目文档的主入口。代码、配置与本文冲突时，以当前代码和配置为准。
> `docs/Perplexity/` 和 `docs/drafts/` 中包含大量历史方案，不应直接当作运行手册。

## 首先阅读

| 文档 | 状态 | 适用场景 |
|------|------|----------|
| [项目 README](../README.md) | Needs verification | 项目概览；长篇正文仍含历史描述 |
| [Agent Guide](../AGENTS.md) | Live | 编程代理的安全边界、模块入口和高风险事实 |
| [当前架构](ARCHITECTURE.md) | Live | 模块、认证链、数据流和跨模块影响 |
| [配置基线](CONFIGURATION.md) | Live | 端口、profile、数据库、外部服务和密钥 |
| [开发指南](DEVELOPMENT.md) | Live | 安全构建、启动前检查和日常改动流程 |
| [验证指南](VERIFICATION.md) | Live | 可执行检查、当前基线和未覆盖风险 |
| [加固实施规划](drafts/HARDENING_IMPLEMENTATION_PLAN.md) | Draft | 只修复现有能力、不增加新功能的分阶段计划 |

## 当前关键结论

- 默认 Spring profile 是 `test`，后端端口是 `8081`。
- `test` 与 `dev` profile 启动都会删除全部用户和登录方式。
- Vite 使用 `5173`，Python 资源服务器代码实际使用 `5002`。
- `db/migration/V*.sql` 当前没有 Flyway/Liquibase 执行器。
- SQLite schema 落后于当前实体和 PostgreSQL schema。
- Java 构建通过但没有测试源码；前端 build 通过，lint 缺少配置。

详细证据和操作限制见 [配置基线](CONFIGURATION.md) 与
[验证指南](VERIFICATION.md)。

## 组件文档

| 文档 | 状态 | 说明 |
|------|------|------|
| [前端 README](../frontend/README.md) | Needs verification | React/Vite 使用说明；以 `vite.config.ts` 为端口和构建事实 |
| [Python 资源服务器 README](../python-resource-server/README.md) | Needs verification | Flask 示例说明；以 `app.py` 为端口和 JWT claim 事实 |
| [异构资源服务器验证记录](../VERIFICATION_CHECKLIST.md) | Historical | 2026-01-25 的历史验证快照，不是当前回归证明 |

## 契约与集成材料

| 文档 | 状态 | 说明 |
|------|------|------|
| [前后端契约](FRONTEND_BACKEND_CONTRACT.md) | Needs verification | 大型设计/契约材料，代码示例和端口存在漂移 |
| [GitHub OAuth2 集成规划](GitHub-OAuth2-Integration-Planning.md) | Historical | GitHub 集成前的规划，功能现已部分落地 |
| [草稿与历史索引](drafts/README.md) | Live index | 20 份计划、调查、实施记录的逐项分类 |
| [文档体系计划](drafts/DOCUMENTATION_PLAN.md) | Draft | 本轮文档整理范围、顺序和验收标准 |

## Perplexity 历史资料

`docs/Perplexity/` 是 2026 年 1 月生成的方案集。其结构和概念有参考价值，
但包名、端口、API、数据库和完成度与当前仓库不完全一致。

| 文档 | 状态 | 主题 |
|------|------|------|
| [01-Architecture-Design.md](Perplexity/01-Architecture-Design.md) | Historical | 初始架构设计 |
| [02-Backend-Implementation.md](Perplexity/02-Backend-Implementation.md) | Historical | Spring 后端实现示例 |
| [03-Frontend-Implementation.md](Perplexity/03-Frontend-Implementation.md) | Historical | React 前端实现示例 |
| [04-Database-Setup.md](Perplexity/04-Database-Setup.md) | Historical | SQLite/PostgreSQL 设置 |
| [05-Deployment-Guide.md](Perplexity/05-Deployment-Guide.md) | Historical | 部署和运维方案 |
| [06-Quick-Reference.md](Perplexity/06-Quick-Reference.md) | Historical | 旧版速查 |
| [07-GOOGLE-TOKEN-SUPPLEMENT.md](Perplexity/07-GOOGLE-TOKEN-SUPPLEMENT.md) | Historical | Google token 与刷新方案 |
| [08-X-API-v2-迁移指南.md](Perplexity/08-X-API-v2-迁移指南.md) | Reference | X API v2 迁移背景，使用前核对当前配置 |

## 状态定义

| 状态 | 含义 |
|------|------|
| Live | 当前维护的运行、架构或验证指南 |
| Draft | 正在执行或待评审的计划 |
| Reference | 有参考价值，使用前需核对代码 |
| Historical | 记录过去的设计、实施或验证 |
| Needs verification | 存在已知冲突，不能直接作为操作依据 |

## 维护规则

1. 新的当前事实优先写入本页链接的 live guide。
2. 既有历史文档保持原路径，通过状态和链接说明其角色。
3. 修改端口、profile、schema、JWT claim、cookie 或回调地址时，同步更新相关 live guide。
4. 只有本次实际运行的命令才能标记为已验证。
5. 新增文档后更新本页或 `docs/drafts/README.md`，避免孤立文件。
