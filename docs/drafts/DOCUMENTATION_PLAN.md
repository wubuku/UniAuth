# UniAuth 文档体系建设计划

> 状态：执行中
> 基线日期：2026-08-07
> 原则：已有文档不移动；新文档链接已有内容；当前事实以代码、配置和可执行验证为准。

## 目标

建立一套面向开发者和编程代理的文档体系，使读者能够快速判断：

1. 当前系统实际由哪些模块组成。
2. 哪些文档是当前指南，哪些只是历史方案或待核验材料。
3. 启动、数据库、密钥和外部集成有哪些高风险操作。
4. 修改后应执行哪些不会误伤数据的验证。

## 当前库存

审计范围内共有 36 份项目 Markdown：

| 区域 | 数量 | 当前角色 |
|------|------|----------|
| 根目录及 `.gemini/` | 4 | 项目入口、代理上下文、历史验证记录 |
| `docs/` 顶层 | 2 | 大型契约/集成规划，内容混合且存在漂移 |
| `docs/Perplexity/` | 8 | 2026 年 1 月生成的架构与实现参考，整体按历史材料处理 |
| `docs/drafts/` | 20 | 规划、调查、集成指南、进度记录，状态差异较大 |
| 组件 README | 2 | 前端与 Python 示例说明，均存在端口或契约漂移 |

技能包 `.agents/skills/project-docs/` 中的文档不计入项目文档库存。

## 已确认的权威来源

| 主题 | 当前权威来源 |
|------|--------------|
| Java 版本、依赖、构建 | `pom.xml` |
| 后端端口、profile、OAuth2、JWT、CORS | `src/main/resources/application*.yml` |
| 数据库启动行为 | profile 配置、初始化器和 `schema-*.sql` |
| 安全链和认证边界 | `src/main/java/org/dddml/uniauth/config/` |
| API 行为 | controller、service、repository 和 entity |
| 前端端口、代理、构建输出 | `frontend/vite.config.ts`、`frontend/package.json` |
| Python 示例行为 | `python-resource-server/app.py` |
| 可执行验证状态 | 2026-08-07 本地无启动验证结果 |

现有 prose 与这些来源冲突时，不把 prose 视为当前事实。

## 文档架构

### Hub

- `README.md`：人类入口，增加当前状态警告和文档导航。
- `AGENTS.md`：代理长期记忆和安全边界。
- `docs/README.md`：项目文档的唯一主导航。
- `docs/drafts/README.md`：草稿、历史计划和调查材料索引。

### Current Guides

- `docs/ARCHITECTURE.md`：当前模块、认证链、数据流和所有权边界。
- `docs/CONFIGURATION.md`：端口、profile、数据库、外部服务和配置漂移基线。
- `docs/DEVELOPMENT.md`：安全开发、构建和启动前检查。
- `docs/VERIFICATION.md`：验证层级、当前基线和未覆盖风险。

### Reference

既有详细文档全部保留原路径，由 `docs/README.md` 和
`docs/drafts/README.md` 分类链接。新指南只摘要当前事实，不复制大段历史内容。

## 优先级和执行状态

| 优先级 | 工作项 | 状态 |
|--------|--------|------|
| P0 | 创建自包含 `project-docs` 技能包 | 已完成 |
| P0 | 建立文档库存和当前实现事实矩阵 | 已完成 |
| P0 | 创建 `docs/README.md` 与草稿索引 | 已完成 |
| P0 | 创建架构、配置、开发、验证首版指南 | 已完成 |
| P0 | 修复 live 文档、脚本和组件 README 的端口漂移 | 已完成 |
| P0 | 为历史“已完成/生产就绪”声明增加明确状态说明 | 已完成 |
| P1 | 更新根 README、AGENTS 和组件文档入口 | 已完成 |
| P1 | 编写不增加新功能的全面加固实施规划 | 首版已完成，严格审查中 |
| P1 | 修复新增/修改文档的相对链接 | 待执行 |
| P2 | 随代码修复逐步校准详细 API/集成文档 | 延后 |

## 端口和状态漂移处置

当前默认开发拓扑基线：

| 服务 | 当前默认值 |
|------|------------|
| Spring Boot | `8081` |
| Vite | `5173` |
| Python 资源服务器 | `5002` |
| PostgreSQL | `5432` |
| 外部邮件服务 | `8095` |

处置规则：

1. live 指南、启动脚本和组件 README 使用当前默认值。
2. `8082` 仅在明确说明为测试脚本覆盖端口时保留。
3. 部署示例中的域名必须标记为环境配置，不再描述为通用默认值。
4. 历史文档不逐段重写；在文档顶部增加状态提示，并链接当前配置指南。
5. “已完成”“全部通过”“生产级”只能表示有可复现证据的当前状态，否则改为历史记录或待核验。

## 已知事实与待修复项

- 默认 `test` profile 指向 PostgreSQL，并在应用启动时删除全部用户和登录方式。
- `dev` profile 同样会清空 SQLite 用户数据。
- `db/migration/V*.sql` 当前没有 Flyway/Liquibase 执行器。
- SQLite schema 缺少当前实体所需的部分表和列。
- `mvn clean test` 成功，但仓库没有 Java 测试源码。
- 前端 build 成功；lint 因缺少 ESLint 配置失败。
- 邮箱验证码发送值与持久化值可能不同；密码重置模板仍含硬编码验证码。
- token blacklist 尚未接入验证、刷新和登出流程。
- Web3 的 `isNewUser`、bind 返回处理和 SIWE 消息绑定需要测试覆盖。

这些问题的实施顺序和验收门槛将在
`HARDENING_IMPLEMENTATION_PLAN.md` 中定义。

## 验证计划

完成文档工作前执行：

```bash
python3 .agents/skills/project-docs/scripts/check_relative_links.py \
  README.md AGENTS.md docs frontend/README.md python-resource-server/README.md
python3 .agents/skills/project-docs/scripts/check_relative_links.py \
  .agents/skills/project-docs
git diff --check
```

同时人工确认：

- 没有既有 Markdown 被移动、重命名或删除。
- 新文档都能从 `docs/README.md` 到达。
- 所有“已验证”命令都有本次执行结果。
- 未运行会启动 Spring 应用或接触非隔离数据库的命令。

## 延后事项

- 不在本轮重写 20 份草稿和 8 份 Perplexity 历史材料。
- 不在文档整理阶段改变认证架构、数据库 schema 或 token 行为。
- 不把历史测试记录提升为当前发布证明。
- 详细 API 文档随下一阶段加固实现和测试补齐后再校准。
