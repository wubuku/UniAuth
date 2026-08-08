# UniAuth 文档导航

> 当前文档基线：2026-08-07
> 本页是项目文档的主入口。代码、配置与本文冲突时，以当前代码和配置为准。
> `docs/Perplexity/` 和 `docs/drafts/` 中包含大量历史方案，不应直接当作运行手册。

## 首先阅读

| 文档 | 状态 | 适用场景 |
|------|------|----------|
| [项目 README](../README.md) | Needs verification | 顶部为当前概览；长篇正文仍保留历史描述 |
| [Agent Guide](../AGENTS.md) | Live | 编程代理的安全边界、模块入口和高风险事实 |
| [当前架构](ARCHITECTURE.md) | Live | 模块、认证链、数据流和跨模块影响 |
| [配置基线](CONFIGURATION.md) | Live | 端口、profile、数据库、外部服务和密钥 |
| [开发指南](DEVELOPMENT.md) | Live | 安全构建、启动前检查和日常改动流程 |
| [验证指南](VERIFICATION.md) | Live | 可执行检查、当前基线和未覆盖风险 |
| [加固实施规划](drafts/HARDENING_IMPLEMENTATION_PLAN.md) | In progress | H0.1-H0.3、H1.1-H1.3、H1.4 Batch B1/B2a/B2b 已验证；其余 H1.4-H8 待实施 |
| [下一轮实施计划](drafts/NEXT_HARDENING_IMPLEMENTATION_PLAN.md) | Draft | Batch B2b 已完成；继续按测试优先切片执行后续加固 |

## 当前关键结论

- 默认不激活 Spring profile，后端端口是 `8081`。
- 演示数据默认关闭且不再全表清理；显式启用仍只允许 test/demo 命名的 disposable 数据库。
- Vite 使用 `5173`，Python 资源服务器代码实际使用 `5002`。
- `dev`、`test`、`prod` 只支持显式 PostgreSQL；SQLite runtime 已退役。
- Flyway 已接管 schema：V1 来自实际 dev PostgreSQL 的 8 表结构，V2 加固登录方式
  行形状/primary 不变量，V3 增加登录方式集合 revision CAS，V4 对齐其余既有实体
  约束并补齐 email repository 索引。
- Hibernate 只执行 `validate`；SQL init 和 Spring Session 自动建表均关闭。
- 邮箱注册验证和密码重置依赖独立邮件服务；UniAuth 主应用只提供 HTTP 客户端适配器，
  仓库另有不纳入根构建的参考实现。依赖契约包括端点、模板、响应语义、可选 API key
  和客户端 URL/超时约束；参考实现还对生产 SMTP 加密模式和 server identity
  verification 做失败关闭保护。普通邮箱加密码登录不需要每次发信。
- 已建立 PostgreSQL Java 集成测试、真实 HTTP Shell E2E、Mock Playwright 和
  Python 离线 JWT/JWKS 测试；ESLint 与统一验证入口已纳入门禁。
- `blacksheep_dev` 已通过只读 baseline rehearsal，但尚未执行 baseline apply。

详细证据和操作限制见 [配置基线](CONFIGURATION.md) 与
[验证指南](VERIFICATION.md)。

## 组件文档

| 文档 | 状态 | 说明 |
|------|------|------|
| [前端 README](../frontend/README.md) | Needs verification | React/Vite 使用说明；以 `vite.config.ts` 为端口和构建事实 |
| [Python 资源服务器 README](../python-resource-server/README.md) | Needs verification | Flask 示例说明；以 `app.py` 为端口和 JWT claim 事实 |
| [邮件服务参考实现](../reference/email-service/README.md) | Reference | 独立 Spring Boot REST/SMTP 组件；Flyway V1/V2、SMTP endpoint/transport guard、PostgreSQL/GreenMail 与 Shell E2E |
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
6. 外部 REST 依赖不能只记录 URL/端口；还要记录责任边界、接口和数据契约、鉴权、
   成功语义、超时/重试以及默认门禁是否覆盖真实外部行为。
