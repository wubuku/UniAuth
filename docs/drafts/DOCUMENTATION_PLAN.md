# UniAuth 文档体系建设计划

> 状态：Live maintenance；首版体系已建立，随加固 batch 持续校准
> 首版基线：2026-08-07；最近校准：2026-08-08
> 原则：已有文档不移动；新文档链接已有内容；当前事实以代码、配置和可执行验证为准。

## 目标

建立一套面向开发者和编程代理的文档体系，使读者能够快速判断：

1. 当前系统实际由哪些模块组成。
2. 哪些文档是当前指南，哪些只是历史方案或待核验材料。
3. 启动、数据库、密钥和外部集成有哪些高风险操作。
4. 修改后应执行哪些不会误伤数据的验证。

## 当前库存

当前审计范围内共有 49 份项目 Markdown（不含技能包）：

| 区域 | 数量 | 当前角色 |
|------|------|----------|
| 根目录及 `.gemini/` | 4 | 项目入口、代理上下文、历史验证记录 |
| `docs/` 顶层 | 7 | live guides、导航和大型历史契约 |
| `docs/Perplexity/` | 8 | 2026 年 1 月生成的架构与实现参考，整体按历史材料处理 |
| `docs/drafts/` | 24 | 规划、调查、集成指南、进度记录，状态差异较大 |
| `docs/archive/` | 2 | database/legacy SQL 归档索引 |
| 组件 README/AGENTS | 4 | 前端、Python 与邮件服务参考实现的组件入口 |

技能包 `.agents/skills/project-docs/` 中的文档不计入项目文档库存。

## 已确认的权威来源

| 主题 | 当前权威来源 |
|------|--------------|
| Java 版本、依赖、构建 | `pom.xml` |
| 后端端口、profile、OAuth2、JWT、CORS | `src/main/resources/application*.yml` |
| 数据库启动行为 | profile 配置、Flyway migration、初始化器和 runtime guard |
| 安全链和认证边界 | `src/main/java/org/dddml/uniauth/config/` |
| API 行为 | controller、service、repository 和 entity |
| 前端端口、代理、构建输出 | `frontend/vite.config.ts`、`frontend/package.json` |
| Python 示例行为 | `python-resource-server/app.py` |
| 可执行验证状态 | `scripts/verify.sh` 与 `docs/VERIFICATION.md` 记录的 2026-08-08 完整门禁 |

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
| P1 | 编写不增加新功能的全面加固实施规划 | 已完成首版；持续按实施状态校准 |
| P1 | 编写下一轮测试优先实施切片 | 已完成；随 batch 状态持续更新 |
| P1 | 修复新增/修改文档的相对链接 | 已纳入统一门禁 |
| P1 | 说明邮箱认证对独立邮件服务的依赖、契约和验证边界 | 已完成 |
| P1 | 纳入独立邮件服务参考实现并记录 Flyway、配置和 E2E | 已完成 |
| P1 | 说明参考服务 SMTP 传输模式、生产身份校验和测试边界 | 已完成 |
| P1 | 说明参考服务 SMTP host/port 形状、双重 guard 和 Bean 绑定证据 | 已完成 |
| P1 | 说明持久化队列到最终 SMTP 投递的二次校验信任边界 | 已完成 |
| P1 | 说明 event/recovery 限流 reservation 的 claim 异常释放和投递尝试消费语义 | 已完成 |
| P1 | 说明限流 reservation 的窗口 generation ownership、幂等释放和 E2E 证据 | 已完成 |
| P1 | 说明参考服务敏感邮件 API 的 no-store/no-cache/nosniff 响应基线和验证证据 | 已完成 |
| P1 | 说明邮件服务 API key header 的单值精确匹配要求和重复凭据拒绝证据 | 已完成 |
| P1 | 说明根 HTTP E2E 使用真实参考邮件服务完成正常跨进程闭环，并仅用 stub 覆盖 503/429 失败映射 | 已完成 |
| P1 | 说明参考服务 Flyway schema-owner 配置不可被外部覆盖，并记录 Java/Shell/ApplicationContext/Flyway guard 证据 | 已完成 |
| P1 | 说明参考服务缺失 migration location 与非法 migration 命名必须 fail closed，并记录覆盖拒绝证据 | 已完成 |
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

- `dev`、`test`、`prod` 已统一为显式 PostgreSQL，SQLite runtime 已退役。
- Flyway V1 baseline + V2 + V3 + V4 已接管 8 张认证/Session 表，并加固登录方式
  行形状、primary、集合变更 CAS 以及其余目标实体约束和 email repository 索引；
  旧 SQL 已归档到 runtime classpath 外。
- Java 已有 PostgreSQL/Testcontainers 集成测试；当前完整门禁数量以最近一次
  `mvn test` Surefire 汇总为准，Web3 V5 slice 另增字段绑定、并发 upsert/consume
  覆盖。
- HTTP Shell E2E 当前 15/15，Flyway baseline guard 13/13，
  Mock Playwright 21 tests，Python 资源服务器 16 tests，邮件 REST stub contract
  8 tests。
- 前端严格 `npm ci`、high/critical 依赖审计、lint、typecheck 和生产构建通过；
  `scripts/verify.sh` 与 GitHub Actions 使用统一验证入口。
- npm audit 仍有 2 个 React Router moderate advisories；当前客户端路由 pathname
  固定为同源值，OAuth 错误仅进入编码后的 query，不触达公告中的
  RSC/SSR data-router/外部输入决定目标 URL 路径；后续版本升级继续跟踪。
- UniAuth 主应用只包含外部邮件服务 HTTP 适配器；仓库已纳入独立参考实现。
  live guides 已明确邮箱注册/重置的运行依赖、端点和模板契约、普通密码登录边界，
  API key/超时、参考组件自己的 Flyway V1/V2、独立数据库、运行保护、完整
  ApplicationContext E2E 和 Shell 进程门禁；URL 结构、恢复开关和敏感对象字符串
  约束也已纳入当前指南。生产 SMTP 的强制 STARTTLS/implicit SSL 二选一、
  server identity verification、host/port 形状和默认门禁不执行真实 TLS 握手的
  边界也已说明；最终投递对持久化 recipient/subject/HTML/header token 的二次校验
  以及 claim 异常不泄漏限流 reservation、旧窗口迟到释放不影响新窗口额度的语义
  也已纳入 live 配置和验证文档。参考服务邮件 API 的 no-store/no-cache/nosniff
  响应基线也已纳入组件指南、真实 Spring HTTP E2E、Shell HTTP/Flyway guard 和
  Python stub contract。配置 API key 时必须只接受恰好一个精确匹配的 header，
  不能以首值或末值选择消解重复同名凭据；该要求已由真实 Spring HTTP、Shell、
  Flyway migrated app 和 Python stub contract 固定。邮件 Shell HTTP/Flyway guard
  当前分别为 10/10 和 11/11，覆盖 queue detail 披露边界、响应安全 header、重复
  鉴权 header，以及 checksum drift 失败关闭、原样保持与显式恢复。
- 参考服务当前组合门禁为 Maven 131 tests、Java runtime guard 26/26、Shell
  runtime 39/39、HTTP 11/11、Flyway guard 14/14；Flyway 唯一 schema owner、缺失
  location 和 migration 命名校验的
  固定配置和外部覆盖拒绝已由 Java/Shell/ApplicationContext/Flyway 层共同验证。
- 根 `scripts/test-http-e2e.sh` 的正常邮箱注册/重置路径已使用真实
  `reference/email-service` JAR、独立 PostgreSQL 和真实 HTTP；仅失败/限流映射路径
  使用受控 stub。参考服务队列中的模板持久化已成为根 E2E 的直接断言。
- 邮箱验证码发送值已与持久化值一致；同步拒绝/限流/异常不保存 challenge，动态
  有效期/cooldown、按 challenge id 的正确验证码原子消费、禁止 controller 二次
  email/purpose 消费和错误重试 CAS 已有 PostgreSQL、HTTP、Playwright 与 Python
  stub 契约覆盖。外部接受后本地事务失败、异步 delivery 失败、单一 pending
  challenge、canonical email 和可靠 outbox 状态机仍待修复。
- token blacklist 尚未接入验证、刷新和登出流程。
- Web3 的 `isNewUser`、bind 返回处理和 EIP-191 字节长度已修复；
  SIWE 字段绑定、nonce 原子消费和并发重放仍待修复。
- `blacksheep_dev` 只读 baseline rehearsal 已通过，但 baseline apply 尚未执行。

这些问题的总路线图见 `HARDENING_IMPLEMENTATION_PLAN.md`，下一轮实际顺序见
`NEXT_HARDENING_IMPLEMENTATION_PLAN.md`。

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

- 不批量重写 `docs/drafts/` 中的 24 个文件和 8 份 Perplexity 历史材料。
- 不在文档整理阶段改变认证架构、数据库 schema 或 token 行为。
- 不把历史测试记录提升为当前发布证明。
- 详细 API 文档随下一阶段加固实现和测试补齐后再校准。
- 不把根 E2E 的受控 stub 描述成完整邮件服务实现；它只负责参考服务不自然产生的
  `503/429` 失败响应矩阵。
