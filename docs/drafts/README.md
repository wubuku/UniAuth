# 草稿、计划与历史材料索引

> 状态：Live。本目录中的文件全部保留原路径。
> 分类基于 2026-08-10 的当前代码和配置，
> 不代表文件名中的 `COMPLETE`、勾选项或正文中的“已完成”仍然成立。
> 当前运行事实请先阅读 [配置基线](../CONFIGURATION.md) 和
> [验证指南](../VERIFICATION.md)。

## 状态定义

| 状态 | 含义 |
|------|------|
| Live | 当前持续维护的索引或计划 |
| Draft | 仍可用于指导后续决策的计划 |
| Reference | 有参考价值，但使用前应核对当前实现 |
| Historical | 记录过去的方案、实施过程或验证结果 |
| Needs verification | 与当前代码存在已知或疑似冲突，不能直接执行 |

## 文档体系计划

| 文档 | 状态 | 用途 |
|------|------|------|
| [DOCUMENTATION_PLAN.md](DOCUMENTATION_PLAN.md) | Live | 文档体系范围、维护规则和当前校准状态 |
| [FINAL_HARDENING_EXIT_PLAN.md](FINAL_HARDENING_EXIT_PLAN.md) | Reference | Completed；F1-F5、统一门禁和阶段退出 3/3 检查记录 |
| [F1_EMAIL_IDENTITY_HARDENING_IMPLEMENTATION.md](F1_EMAIL_IDENTITY_HARDENING_IMPLEMENTATION.md) | Reference | 已完成 F1 的不可变基线、固定切片、迁移规则和验收记录 |
| [F2_TOKEN_SESSION_HARDENING_IMPLEMENTATION.md](F2_TOKEN_SESSION_HARDENING_IMPLEMENTATION.md) | Reference | 已完成 F2 的 token family、浏览器 transport、CSRF、strict introspection 实施与验收记录 |
| [F3_OAUTH_WEB3_CONTRACT_HARDENING_IMPLEMENTATION.md](F3_OAUTH_WEB3_CONTRACT_HARDENING_IMPLEMENTATION.md) | Reference | 已完成 F3 的 OAuth bind intent、Web3 challenge、recent-auth 和 canonical API 实施与验收记录 |
| [F4_SUPPLY_CHAIN_PRODUCTION_OPERATIONS_HARDENING_IMPLEMENTATION.md](F4_SUPPLY_CHAIN_PRODUCTION_OPERATIONS_HARDENING_IMPLEMENTATION.md) | Reference | 已完成 F4 的供应链、生产配置、密钥、readiness 和运维门禁实施证据 |
| [HARDENING_IMPLEMENTATION_PLAN.md](HARDENING_IMPLEMENTATION_PLAN.md) | Reference | 历史风险路线图；加固阶段已结束，不再驱动活动批次 |
| [NEXT_HARDENING_IMPLEMENTATION_PLAN.md](NEXT_HARDENING_IMPLEMENTATION_PLAN.md) | Historical | 既有测试优先批次和验收记录；不再驱动开放循环 |

## 邮箱与密码

| 文档 | 状态 | 说明 |
|------|------|------|
| [用户名初始化管理员与登录后改密实施记录-2026-08-12.md](用户名初始化管理员与登录后改密实施记录-2026-08-12.md) | Reference | 显式初始化用户名/密码管理员、登录后改密、CAS 与 token family 撤销的实施和验收记录 |
| [EMAIL-AUTH-PLAN.md](EMAIL-AUTH-PLAN.md) | Historical | 邮箱注册/验证早期规划；当前 challenge、投递、消费和并发边界见 [F1 实施记录](F1_EMAIL_IDENTITY_HARDENING_IMPLEMENTATION.md) |
| [PASSWORD-RESET-PLAN.md](PASSWORD-RESET-PLAN.md) | Historical | 密码重置早期规划；当前枚举防护、challenge 和 token 撤销边界见 [F1](F1_EMAIL_IDENTITY_HARDENING_IMPLEMENTATION.md)、[F2](F2_TOKEN_SESSION_HARDENING_IMPLEMENTATION.md) 与 [验证指南](../VERIFICATION.md) |

## 多登录方式

| 文档 | 状态 | 说明 |
|------|------|------|
| [multi-login-methods-design-v1.md](multi-login-methods-design-v1.md) | Historical | 第一版设计，保留决策背景 |
| [multi-login-methods-implementation-plan-v2.md](multi-login-methods-implementation-plan-v2.md) | Historical | 第二版实施方案，已被后续实现和 v3 取代 |
| [multi-login-methods-implementation-plan-v3-improved.md](multi-login-methods-implementation-plan-v3-improved.md) | Historical | 与当前实现关系最近，但“完成”声明不等于当前验证结果 |

## Web3

| 文档 | 状态 | 说明 |
|------|------|------|
| [Web3-Auth-API-Documentation.md](Web3-Auth-API-Documentation.md) | Needs verification | API 参考；端点大体对应当前代码，响应语义仍需测试 |
| [Web3-Wallet-Login-Guide.md](Web3-Wallet-Login-Guide.md) | Reference | 通用开发教程，包含 Redis/MySQL 等非本仓库当前架构 |
| [Web3-Wallet-Login-Python-Test-Guide.md](Web3-Wallet-Login-Python-Test-Guide.md) | Needs verification | Python 测试指南，存在端口和 endpoint 漂移 |

## OAuth2 与重定向

| 文档 | 状态 | 说明 |
|------|------|------|
| [Spring_Boot_OAuth2_重定向问题.md](Spring_Boot_OAuth2_重定向问题.md) | Reference | 候选方案集合，不是当前实现说明 |
| [oauth2-redirect-issue-analysis.md](oauth2-redirect-issue-analysis.md) | Historical | 记录已修复的硬编码前端地址问题；当前 redirect 与绑定边界见 [F3 实施记录](F3_OAUTH_WEB3_CONTRACT_HARDENING_IMPLEMENTATION.md) |
| [OAuth绑定与X用户资料回调加固实施记录-2026-08-11.md](OAuth绑定与X用户资料回调加固实施记录-2026-08-11.md) | Historical | OAuth/绑定加固记录；内容已改写为 UniAuth 通用契约，不代表对特定消费方的依赖 |

## 异构资源服务器与微服务

| 文档 | 状态 | 说明 |
|------|------|------|
| [HETEROGENEOUS-RESOURCE-SERVER-INTEGRATION.md](HETEROGENEOUS-RESOURCE-SERVER-INTEGRATION.md) | Historical | Python 资源服务器集成规划 |
| [HETEROGENEOUS_INTEGRATION_COMPLETE.md](HETEROGENEOUS_INTEGRATION_COMPLETE.md) | Historical | 过去的完成记录；当前 Python claim 契约和离线撤销限制见 [验证指南](../VERIFICATION.md) |
| [SpringBoot资源服务器JWT保护实现指引.md](SpringBoot资源服务器JWT保护实现指引.md) | Reference | Java 资源服务器通用实现参考 |
| [MICROSERVICE-INTEGRATION-GUIDE.md](MICROSERVICE-INTEGRATION-GUIDE.md) | Needs verification | 微服务整合指南，域名、端口和部署假设需按环境复核 |
| [INTEGRATION-GUIDE.md](INTEGRATION-GUIDE.md) | Needs verification | 将认证模块复制到其他项目的通用指南，不代表当前仓库运行手册 |
| [INTEGRATION-CHECKLIST.md](INTEGRATION-CHECKLIST.md) | Reference | 通用集成检查表，不能替代当前项目验证 |

## 数据库与 Session

| 文档 | 状态 | 说明 |
|------|------|------|
| [MULTI-DATABASE-SETUP.md](MULTI-DATABASE-SETUP.md) | Historical | 多数据库历史方案；当前只支持 PostgreSQL |
| [SESSION-PERSISTENCE-GUIDE.md](SESSION-PERSISTENCE-GUIDE.md) | Historical | Spring Session JDBC 实施背景；当前表由 Flyway V1 管理 |

## 调查与进度记录

| 文档 | 状态 | 说明 |
|------|------|------|
| [FRONTEND_BACKEND_improvement-20260127.md](FRONTEND_BACKEND_improvement-20260127.md) | Historical | 2026-01-27 的问题调查和修复建议 |
| [project-progress.md](project-progress.md) | Historical | 长期实施日志；端口、域名、密码示例和完成度均不作为当前依据 |

## 维护规则

1. 新规划可以放入本目录，但必须在此索引登记状态和用途。
2. 已完成或失效的文档仍保留原路径，只把状态改为 Historical。
3. 当前事实进入 `docs/` 顶层 live guide；详细历史继续链接到原文件。
4. 任何代码修复落地后，同时更新受影响的 live guide 和本索引状态。
