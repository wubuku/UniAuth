# UniAuth - 统一身份认证系统

> 状态：Needs verification。H0.1-H0.3、PostgreSQL/Flyway H1.1-H1.3、测试基础
> Batch A、登录方式约束 Batch B1、删除/primary 并发保护 Batch B2a 与实体约束/索引
> 对齐 Batch B2b、邮件服务边界与参考实现已完成加固验证，但项目尚无生产就绪证明。
> 仓库不默认激活 Spring profile，所有 profile 只支持显式 PostgreSQL 16，Flyway 是唯一
> schema owner，演示数据默认关闭且不执行全表清理。
> 开始开发或启动前，请先阅读 [文档导航](docs/README.md)、
> [配置基线](docs/CONFIGURATION.md)、[开发指南](docs/DEVELOPMENT.md) 和
> [验证指南](docs/VERIFICATION.md)。真实邮箱注册、登录、资源回跳和跨域 Bearer
> 验证见[邮箱登录浏览器 E2E](docs/EMAIL_LOGIN_BROWSER_E2E.md)。
> [加固阶段最终收尾计划](docs/drafts/FINAL_HARDENING_EXIT_PLAN.md)中的 F1 邮箱与身份
> 完整性批次已经完成；F2-F5 是显式延后的独立批次，不会由当前邮箱任务自动启动。
> 下文保留了较多设计目标、部署示例和历史说明，包括已经退役的 SQLite 路径。
> 当前操作只使用上述 live guides；不要执行下文的 SQLite、手工 schema init 或旧域名示例。

## 当前可执行基线

| 项目 | 当前状态 |
|------|----------|
| 后端 | Spring Boot 3.3.4 / Java 17，默认端口 `8081` |
| 前端 | React 18 / Vite，开发端口 `5173` |
| 资源服务器 | Flask，默认端口 `5002` |
| 邮件发送 | 外部 HTTP 服务，默认端口 `8095`；`reference/email-service/` 提供独立参考实现 |
| 数据库 | PostgreSQL 16-only |
| Migration | Flyway V1 baseline + V2 + V3 + V4 + V5 + V6，history `uniauth_flyway_schema_history` |
| 邮件数据库布局 | 默认独立数据库；显式 `shared-uniauth` 可与 UniAuth 共用 `public` schema，两侧 relation 名无冲突并使用独立 Flyway history |
| Java 验证 | 212 tests，0 failures/errors/skips |
| 邮件参考服务 | 150 tests；另有 Shell runtime 44/44、HTTP 11/11、Flyway guard 15/15、backup/restore 10/10 |
| Shared-schema E2E | 4/4；UniAuth/邮件服务两种启动顺序、独立 history 和 baseline V0 |
| HTTP E2E | 16/16；含四条安全链 CORS 矩阵，正常邮箱流程使用真实参考服务，失败映射矩阵使用受控 stub |
| Flyway baseline guard | 16/16 |
| Playwright | 28 个 Mock 浏览器测试 + 1 个真实邮箱登录跨服务 E2E |
| Python | 18 个资源服务器测试 + 12 个邮件 REST stub 契约测试 |
| 前端 lint/type/build | 通过 |

安全启动、测试和 baseline 操作见 [开发指南](docs/DEVELOPMENT.md) 与
[验证指南](docs/VERIFICATION.md)。`blacksheep_dev` 只完成了只读 rehearsal，
尚未执行 Flyway baseline apply。

## 目录

- [项目概述](#项目概述)
- [核心特性](#核心特性)
- [技术架构](#技术架构)
- [快速开始](#快速开始)
- [环境配置](#环境配置)
- [核心功能详解](#核心功能详解)
- [API参考](#api参考)
- [部署指南](#部署指南)
- [生产环境配置](#生产环境配置)
- [安全指南](#安全指南)
- [监控与运维](#监控与运维)
- [故障排查](#故障排查)
- [版本历史](#版本历史)

---

## 项目概述

UniAuth 是一个正在加固的统一身份认证项目，包含本地认证、Google/GitHub/X
OAuth2、多登录方式、自定义 JWT、邮箱验证、Web3 和异构资源服务器示例。
系统采用 Spring Boot 3.3.4 + React 18，`dev`、`test`、`prod` 均使用 PostgreSQL 16。
schema 由 Flyway 管理，SQLite runtime 已退役。

邮箱地址注册验证和密码重置依赖一个独立邮件发送服务。UniAuth 主应用只包含该服务的
HTTP 客户端适配器，不直接连接 SMTP 或邮件供应商；仓库中的
[邮件服务参考实现](reference/email-service/README.md) 是独立 Maven 组件，不由根应用
自动构建或启动。已建立账户后的邮箱加密码登录不发送邮件；当前也没有受支持的
“每次登录发送验证码”无密码登录接口。完整依赖契约见
[配置基线](docs/CONFIGURATION.md#邮件服务依赖)。该契约包括固定 health/template
端点、模板名和变量、`success=true` 入队语义、可选 `X-Email-Service-Key` 以及
connect/read timeout，不只是一个服务 URL；URL 本身也必须是带 host、无
userinfo/query/fragment 的绝对 HTTP/HTTPS 地址。参考实现自身还要求生产 SMTP 使用
强制 STARTTLS 或 implicit SSL，并保持 server identity verification 开启；其他兼容
实现如果不使用 SMTP，也必须为其下游供应商连接提供等价的防降级和身份校验保护。
配置共享密钥时，服务只能接受恰好一个 `X-Email-Service-Key` 且整值精确匹配；
缺失、错误或重复同名鉴权 header 都必须返回 `401`，不能选择首值或末值继续处理。
参考实现的 `SMTP_HOST` 只能是无 URI 语法或空白字符的 host/IP token，
`SMTP_PORT` 必须在 `1..65535`；所有邮件 API 响应统一设置 no-store/no-cache 和
nosniff 安全 header，避免队列、日志或错误响应被缓存或 MIME 嗅探。

UniAuth 会在同一事务中创建 opaque email challenge 和 transactional outbox。数据库
只保存带服务端 key id 的 HMAC digest，不保存明文验证码或注册密码 metadata。
worker 使用稳定 idempotency key 调用邮件服务，并通过 delivery status 查询恢复
响应丢失或进程重启窗口；challenge 只有在邮件请求被确认接受后才进入 `ACTIVE`，
终态投递失败会使其不可验证。同一 canonical `(email,purpose)` 最多一个 active
challenge，错误尝试和并发消费均走 PostgreSQL 条件更新与共享限流。

根目录 `scripts/test-http-e2e.sh` 会为正常邮箱注册和密码重置流程启动
`reference/email-service/` 的真实 JAR、独立 PostgreSQL 和真实 HTTP 端口，并直接
断言参考服务的 `email_queue` 已持久化模板邮件；随后仅为参考实现不会主动返回的
拒绝/限流响应重启 UniAuth 并切换到受控 loopback stub，覆盖客户端的失败映射。
因此该门禁验证的是 UniAuth 到兼容邮件服务的真实跨进程闭环，同时保留失败语义的
可重复测试；它不连接真实 SMTP，也不证明最终收件。

`scripts/test-email-login-browser-e2e.sh` 进一步启动真实 Vite、UniAuth、Python
资源 API、disposable PostgreSQL 和无投递邮件 stub，由 Chrome 从 `/resource-test`
完成邮箱注册、验证、回跳、跨 hostname Bearer 访问和邮箱密码再次登录。当前跨 origin
资源请求依赖登录 JSON 返回并由前端暂存于 localStorage 的 access token，不依赖
HttpOnly Cookie；该演示 transport 的生产安全边界见
[专项 E2E 指南](docs/EMAIL_LOGIN_BROWSER_E2E.md#access-token-边界)。

| 属性 | 说明 |
|------|------|
| **项目名称** | UniAuth |
| **版本号** | 1.0.0 |
| **Java 版本** | 17+ |
| **Spring Boot** | 3.3.4 |
| **前端框架** | React 18 + TypeScript |
| **数据库** | PostgreSQL |
| **构建工具** | Maven |
| **许可证** | MIT |

### 设计目标

本项目的设计目标包括多种 OAuth2 登录方式的统一接入、JWT 令牌管理、会话持久化和跨语言资源服务器验证。当前代码同时包含 Spring Authorization Server 配置与自定义 JWT 签发流程，两者尚未形成经完整测试证明的统一授权服务器实现；剩余加固范围按[加固阶段最终收尾计划](docs/drafts/FINAL_HARDENING_EXIT_PLAN.md)执行，完整风险背景保留在[加固实施规划](docs/drafts/HARDENING_IMPLEMENTATION_PLAN.md)中。

### 适用场景

目标场景包括企业内部认证入口、第三方社交登录 Web 应用和微服务认证集成。当前仓库没有多租户模型、容量测试或高并发稳定性证据，这些场景只能作为设计方向，不能视为当前支持承诺。

---

## 核心特性

### 多提供商统一认证

代码中配置了 Google、GitHub 和 Twitter/X OAuth2 Client，并将登录方式映射到统一用户模型。外部 provider 流程尚未在本次基线上复验；当前实现遇到已被其他身份使用的邮箱时会拒绝自动创建，不会仅凭同邮箱自动关联账户，绑定必须经过已登录流程。

| 提供商 | 认证协议 | 令牌类型 | 用户信息端点 |
|--------|----------|----------|--------------|
| Google | OpenID Connect | ID Token | `https://openidconnect.googleapis.com/v1/userinfo` |
| GitHub | OAuth 2.0 | Access Token | `https://api.github.com/user` |
| Twitter/X | OAuth 2.0 | Access Token | `https://api.twitter.com/2/users/me` |

### JWT 令牌管理

自定义 `JwtTokenService` 使用 RSA-2048/RS256 签发 access token 和 refresh token，
并通过 JWKS 暴露公钥。当前 access token 验证时间、issuer、audience、type、
`kid`、`jti`、用户身份和 enabled 状态；refresh token 轮换以 PostgreSQL 唯一 `jti`
写入实现单次消费，logout 会持久撤销当前可验证的 access/refresh token，Resource
Server 与 introspection 共用 blacklist 结论。当前尚无 token family/security version，
也仍在 JSON 中返回 refresh token。默认密钥文件位于 ignored 的
`.local/uniauth/rsa-keys.ser`，仍不是生产密钥库。

### 会话持久化

项目启用了 Spring Session JDBC，默认会话超时为 30 分钟。Session 表由 Flyway V1
创建，当前 schema 迁移到 V5，集成测试已覆盖 create/read/delete；多实例和负载均衡
行为仍无发布级证据。

### 细粒度权限控制

当前权限存储为用户的 authority 集合，安全链对 `ROLE_ADMIN`、`ROLE_MANAGER` 等值执行路径授权。仓库没有独立的角色/权限实体层级，也没有覆盖所有安全链 matcher 的自动化测试。

### 多登录方式统一管理

后端提供登录方式查询、设置 primary、解绑和添加本地登录方式的接口，OAuth2 成功
处理器包含绑定分支。真实 HTTP Shell E2E 与 Mock Playwright 已分别覆盖后端生命周期
和前端操作；非 Mock 浏览器到真实后端的联调仍未进入默认门禁。当前代码目标包括：

| 功能 | 说明 |
|------|------|
| **绑定新登录方式** | 用户可以通过 OAuth2 流程绑定新的登录提供商 |
| **查看已绑定方式** | 展示所有已绑定的登录方式及绑定时间 |
| **设置主登录方式** | 用户可以切换主登录方式，影响默认显示的提供商信息 |
| **解绑登录方式** | 用户可以解除与某个提供商的绑定（需保留至少一种登录方式） |
| **添加本地账户** | SSO 用户可以为自己的账户添加本地用户名和密码 |

---

## 技术架构

### 系统架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              客户端层                                    │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐    │
│  │   React SPA  │  │  第三方应用  │  │  移动端应用  │  │  REST API   │    │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘    │
└─────────┼────────────────┼────────────────┼────────────────┼───────────┘
          │                │                │                │
          └────────────────┴────────────────┴────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           网关与安全层                                    │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Spring Security Filter Chain                  │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────────────┐    │   │
│  │  │  CORS   │  │  CSRF   │  │ Session │  │  OAuth2 Client  │    │   │
│  │  └─────────┘  └─────────┘  └─────────┘  └─────────────────┘    │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │               Spring Authorization Server (JWT 签发)             │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                            应用服务层                                    │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐    │
│  │  认证服务    │  │  用户服务    │  │ 令牌服务     │  │ 登录方式服务 │    │
│  │AuthService  │  │UserService  │  │JwtTokenSvc  │  │LoginMethod  │    │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘    │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐    │
│  │ 验证服务    │  │ 会话服务     │  │ 刷新服务     │  │ 异常处理    │    │
│  │JwtValidation│  │ SessionSvc  │  │TokenRefresh │  │Exception    │    │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                            数据持久层                                    │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                        PostgreSQL / SQLite                       │   │
│  │  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌─────────────────┐ │   │
│  │  │   users   │ │user_login ││user_author││ token_blacklist │ │   │
│  │  │           │ │  methods  ││  ities    ││                 │ │   │
│  │  └───────────┘ └───────────┘ └───────────┘ └─────────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Spring Session (JDBC)                        │   │
│  │  ┌─────────────────┐  ┌─────────────────────┐                  │   │
│  │  │ spring_session  │  │spring_session_attrs │                  │   │
│  │  └─────────────────┘  └─────────────────────┘                  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           外部服务集成                                    │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────────────┐   │
│  │  Google   │  │  GitHub   │  │ Twitter   │  │   文件系统        │   │
│  │ OAuth2    │  │  OAuth2   │  │  OAuth2   │  │  (RSA 密钥存储)   │   │
│  └───────────┘  └───────────┘  └───────────┘  └───────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

### 技术栈详情

#### 后端技术栈

| 组件 | 版本 | 用途说明 |
|------|------|----------|
| Spring Boot | 3.3.4 | 应用框架核心 |
| Spring Security | 6.1.13 | 安全认证与授权 |
| Spring Authorization Server | 1.3.0 | OAuth2 认证服务器 |
| Spring OAuth2 Client | 6.1.13 | OAuth2 客户端支持 |
| Spring OAuth2 Resource Server | 6.1.13 | JWT 资源服务器 |
| Hibernate | 6.5.3 | ORM 框架 |
| JJWT | 0.12.x | JWT 令牌处理 |
| Lombok | 1.18.30 | 代码简化 |
| SQLite | 3.45.0.0 | 开发环境数据库 |
| PostgreSQL | 42.7.4 | 生产环境数据库 |
| H2 | 2.23.224 | 测试环境数据库 |

#### 前端技术栈

| 组件 | 版本 | 用途说明 |
|------|------|----------|
| React | 18.x | UI 框架 |
| TypeScript | 5.x | 类型安全 |
| Vite | 7.x | 构建工具 |
| Tailwind CSS | 3.x | 样式框架 |
| Axios | 1.x | HTTP 客户端 |
| React Router | 6.30.4 | 路由管理 |

### 项目结构

```
uni-auth/
├── src/main/java/org/dddml/uniauth/
│   ├── UniAuthApplication.java           # 应用启动类
│   ├── config/                           # 配置类
│   │   ├── SecurityConfig.java           # Spring Security 配置
│   │   ├── AuthorizationServerConfig.java # OAuth2 认证服务器配置
│   │   ├── ResourceServerConfig.java     # 资源服务器配置
│   │   ├── CorsConfig.java               # 跨域配置
│   │   ├── WebConfig.java                # Web MVC 配置
│   │   ├── GlobalExceptionHandler.java   # 全局异常处理
│   │   ├── DemoDataConfiguration.java    # 显式演示数据开关
│   │   └── DemoDataInitializer.java      # 受管演示账户 upsert
│   ├── controller/                       # 控制器层
│   │   ├── AuthController.java           # 认证控制器
│   │   ├── OAuth2TokenController.java    # OAuth2 令牌端点
│   │   ├── TokenController.java          # 令牌管理
│   │   ├── LoginMethodController.java    # 登录方式管理
│   │   ├── ApiAuthController.java        # API 认证
│   │   └── SpaController.java            # SPA 路由控制器
│   ├── service/                          # 服务层
│   │   ├── JwtTokenService.java          # JWT 令牌服务
│   │   ├── UserService.java              # 用户服务
│   │   ├── LoginMethodService.java       # 登录方式服务
│   │   ├── CustomUserDetailsService.java # 用户详情服务
│   │   └── TokenRefreshService.java      # 令牌刷新服务
│   ├── repository/                       # 数据访问层
│   │   ├── UserRepository.java           # 用户仓储
│   │   ├── UserLoginMethodRepository.java # 登录方式仓储
│   │   └── TokenBlacklistRepository.java # Token 黑名单仓储
│   ├── entity/                           # 实体类
│   │   ├── UserEntity.java               # 用户实体
│   │   ├── UserLoginMethod.java          # 登录方式实体
│   │   └── TokenBlacklistEntity.java     # Token 黑名单实体
│   └── dto/                              # 数据传输对象
│       ├── UserDto.java                  # 用户 DTO
│       ├── RegisterRequest.java          # 注册请求
│       └── ErrorResponse.java            # 错误响应
├── src/main/resources/
│   ├── application.yml                   # 主配置文件
│   ├── application-dev.yml               # 开发环境配置
│   ├── application-test.yml              # 测试环境配置
│   ├── application-prod.yml              # 生产环境配置
│   ├── schema-postgresql.sql             # PostgreSQL Schema
│   ├── schema-sqlite.sql                 # SQLite Schema
│   ├── data-postgresql.sql               # PostgreSQL 初始数据
│   ├── data-sqlite.sql                   # SQLite 初始数据
│   └── static/                           # 静态资源
│       ├── index.html                    # SPA 入口
│       └── assets/                       # 构建产物
├── src/test/java/                        # 测试代码
├── pom.xml                               # Maven 配置
└── README.md                             # 项目文档
```

### 数据库 Schema

系统使用以下数据库表存储用户认证和会话数据：

| 表名 | 说明 | Schema owner |
|------|------|--------------|
| `users` | 用户基本信息表 | Flyway |
| `user_login_methods` | 用户登录方式表（支持多登录方式） | Flyway |
| `user_authorities` | 用户权限关联表 | Flyway |
| `token_blacklist` | JWT 令牌黑名单表 | Flyway |
| `spring_session` | Spring Session 会话表 | Flyway |
| `spring_session_attributes` | Spring Session 属性表 | Flyway |

**注意**：
- 当前仅支持 PostgreSQL 16；Flyway
  `db/migration/postgresql/V1__baseline_uniauth_auth_schema.sql` 到 V5 是唯一 runtime
  schema 写入链。
- Hibernate 使用 `validate`，SQL init 和 Spring Session 自动建表均关闭。
- 旧 `schema-postgresql.sql` 等脚本只作为历史材料保存在 `docs/archive/database/`，
  不得用于当前环境初始化。

**历史遗留表说明**：
- `jwt_keys` 表：早期设计遗留，当前版本未使用，可安全忽略或删除

---

## 快速开始

> 本节以下长篇步骤保留历史背景，其中 SQLite、手工 schema init 和旧环境变量示例
> 已失效。当前启动命令以 [开发指南](docs/DEVELOPMENT.md) 为准。

### 环境准备

在开始之前，请确保开发环境满足以下要求。所有列出的工具版本均为最低要求，生产环境建议使用 LTS 版本以确保稳定性和安全性。

| 工具 | 最低版本 | 推荐版本 | 说明 |
|------|----------|----------|------|
| Java JDK | 17 | 17 LTS / 21 LTS | Spring Boot 3.x 要求 |
| Maven | 3.6 | 3.9.x | 构建工具 |
| Node.js | 16 | 20 LTS | 前端构建（可选） |
| Git | 2.0 | 最新版本 | 版本控制 |
| PostgreSQL | 12 | 15/16 | 生产环境数据库 |
| SQLite | - | 3.40+ | 开发环境内置 |

### 步骤一：克隆项目

```bash
# 克隆项目仓库
git clone <repository-url>
cd uni-auth

# 查看所有分支（生产代码在 main 分支）
git branch -a
```

### 步骤二：配置环境变量

项目使用 `.env` 文件管理敏感配置。在项目根目录创建或修改 `.env` 文件，填入各 OAuth2 提供商的凭据信息。

```bash
# 复制示例配置文件
cp .env.example .env

# 编辑配置文件
vim .env
```

以下为必需的环境变量配置说明：

```bash
# PostgreSQL 数据库连接配置
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DATABASE=uni_auth
POSTGRES_USER=postgres
POSTGRES_PASSWORD=your_secure_password

# Google OAuth2 配置
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret

# GitHub OAuth2 配置
GITHUB_CLIENT_ID=your-github-client-id
GITHUB_CLIENT_SECRET=your-github-client-secret

# Twitter/X OAuth2 配置
TWITTER_CLIENT_ID=your-twitter-client-id
TWITTER_CLIENT_SECRET=your-twitter-client-secret

# OAuth2 callback、前端回跳与 CORS
OAUTH2_CALLBACK_URI=http://localhost:8081/oauth2/callback
APP_FRONTEND_URL=http://localhost:5173
CORS_ALLOWED_ORIGINS=http://localhost:5173
# 可选：逗号分隔的额外受信 OAuth2 错误回跳 origin
# APP_FRONTEND_ALLOWED_REDIRECT_ORIGINS=https://console.example.com

# JWT 密钥配置（生产环境必须修改）
JWT_SECRET=your-base64-encoded-secret-key
```

`APP_FRONTEND_URL` 可以包含部署 context path；OAuth2 成功和错误回跳都会保留该
base path。`CORS_ALLOWED_ORIGINS` 与额外 redirect allowlist 则必须填写精确 origin，
不能包含 path。

**重要安全提示**：生产环境必须修改所有默认密钥和密码，不要将包含真实凭据的 `.env` 文件提交到版本控制系统。

### 步骤三：获取 OAuth2 凭据

#### Google Cloud Console 配置

1. 访问 [Google Cloud Console](https://console.cloud.google.com/) 并创建新项目或选择现有项目
2. 导航至「API 和服务」→「凭据」页面
3. 点击「创建凭据」下拉菜单，选择「OAuth 客户端 ID」
4. 选择「Web 应用」作为应用类型
5. 在「授权重定向 URI」中添加：`https://your-domain.com/oauth2/callback`
6. 创建完成后，复制显示的 **Client ID** 和 **Client Secret**

#### GitHub OAuth App 配置

1. 登录 GitHub，访问「Settings」→「Developer settings」→「OAuth apps」
2. 点击「New OAuth App」创建新应用
3. 填写应用信息：
   - Homepage URL：`http://localhost:8081`（本地开发）
   - Authorization callback URL：`https://your-domain.com/oauth2/callback`
4. 创建成功后，复制 **Client ID** 并生成 **Client Secret**

#### X (原 Twitter) Developer 配置

1. 访问 [X Developer Portal](https://developer.twitter.com/en/portal/dashboard)
2. 创建新项目和应用，选择「Authentication with X」能力
3. 在「OAuth 2.0 Settings」中启用 2.0 授权
4. 设置回调 URL：`https://your-domain.com/oauth2/callback`
5. 复制 **Client ID** 和 **Client Secret**

### 步骤四：启动应用

#### 开发环境启动（使用显式 PostgreSQL）

```bash
POSTGRES_HOST=localhost \
POSTGRES_PORT=5432 \
POSTGRES_DATABASE=uniauth_dev \
POSTGRES_USER=postgres \
POSTGRES_PASSWORD='set-explicitly' \
SPRING_PROFILES_ACTIVE=dev \
./start.sh
```

应用启动后访问 `http://localhost:8081`。演示数据默认关闭；如需创建三个受管演示账户，
必须同时设置 `APP_DEMO_DATA_ENABLED=true` 和 `APP_DEMO_DATA_DISPOSABLE=true`，
并使用 test/demo 命名的数据库。

#### 测试环境启动（使用 PostgreSQL）

`test` profile 只允许指向隔离且可丢弃的 PostgreSQL，并且必须提供完整的
`POSTGRES_*` 变量。Flyway 是唯一 schema owner，Hibernate 只执行 `validate`：

```bash
POSTGRES_HOST=localhost \
POSTGRES_PORT=5432 \
POSTGRES_DATABASE=uniauth_test \
POSTGRES_USER=uniauth_test \
POSTGRES_PASSWORD='set-explicitly' \
SPRING_PROFILES_ACTIVE=test \
mvn spring-boot:run
```

测试环境会执行 Flyway V1-V6；SQL init 和 Spring Session 自动建表均关闭，演示账户
仍保持默认关闭。

---

更多测试说明：

```bash
# ----------------------------------------------------------------------
# **给 AI 编程助手的重要提示**：
# - 不要试图一次执行过长的 shell 命令！避免生成的错误的命令导致自己挂起。
# - 宁愿每次执行一个相对简单的命令、多步执行！
# ----------------------------------------------------------------------

# 1. 构建前端（自动集成到Spring Boot）
# cd repo-root
./build-frontend.sh


# 2. 使用安全启动脚本（默认 dev profile，但必须显式提供 PostgreSQL）
./start.sh

# 设置环境变量运行测试环境
# SPRING_PROFILES_ACTIVE=test POSTGRES_...=... ./start.sh

# 如果确认可以停止占用默认后端端口的进程
# lsof -ti tcp:8081 | xargs kill

# 如果使用环境变量文件，可以使用以下命令：
# export $(cat .env | grep -v '^#' | xargs) && mvn spring-boot:run

# 显式指定当前默认后端端口：
# export $(cat .env | grep -v '^#' | xargs) && mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
# 也可以通过 Spring Boot 环境变量指定端口：
# export SERVER_PORT=8081

# ----------------------------------------------------------------------
# **给 AI 助手的话**：
# 你应该使用后台运行的方式启动服务，避免自己挂起：
# cd /PATH/TO/UniAuth && nohup bash -c 'export $(cat .env 2>/dev/null | grep -v "^#" | xargs) && mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"' > /tmp/spring-boot.log 2>&1 & echo "PID: $!"
#
# 在必要的时候，使用 psql 检查数据库。数据库连接信息见：`src/main/resources/application-test.yml`
# 示例查询：
# PGPASSWORD="$POSTGRES_PASSWORD" psql -h "${POSTGRES_HOST:-localhost}" -p "${POSTGRES_PORT:-5432}" -U "$POSTGRES_USER" -d "$POSTGRES_DATABASE" -t -c "SELECT count(*) FROM users;"
# ----------------------------------------------------------------------

# **提示**：
# - prod 必须显式提供 OAUTH2_CALLBACK_URI、APP_FRONTEND_URL 和 CORS_ALLOWED_ORIGINS。
# - dev/test 使用 loopback 默认值；部署时仍应与 provider 控制台和实际前端 origin 对齐。
```

### 步骤五：验证安装

应用启动成功后，通过以下方式验证系统正常运行：

1. **健康检查端点**：访问 `GET /actuator/health`（如已配置）
2. **API 基础验证**：访问 `GET /api/user`，未登录时应返回 401
3. **登录流程测试**：访问首页，点击任意 OAuth2 登录按钮完成认证流程
4. **JWT 验证测试**：登录后使用 Token 验证功能确认令牌有效

---

## 环境配置

### 配置文件层次结构

Spring Boot 配置按以下优先级加载（从高到低）：

1. 命令行参数
2. 环境变量
3. `application-{profile}.yml` 特定配置文件
4. `application.yml` 主配置文件
5. 默认值

### 开发环境配置（SQLite）

开发环境使用嵌入式 SQLite 数据库，适合快速迭代和本地测试。配置文件位于 `application-dev.yml`。

```yaml
# src/main/resources/application-dev.yml
spring:
  datasource:
    url: jdbc:sqlite:./dev-database.db
    driver-class-name: org.sqlite.JDBC
    username:
    password:

  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect
    hibernate:
      ddl-auto: none  # 手动管理 Schema
    show-sql: true
    properties:
      hibernate:
        format_sql: true

  sql:
    init:
      mode: always
      schema-locations: classpath:schema-sqlite.sql
      data-locations: classpath:data-sqlite.sql

  session:
    store-type: jdbc
    jdbc:
      initialize-schema: always  # 自动创建 Session 表

server:
  port: 8081

logging:
  level:
    com.example.oauth2demo: DEBUG
    org.springframework.security: DEBUG
```

### 测试环境配置（PostgreSQL）

测试环境使用 PostgreSQL 数据库，模拟生产环境配置。配置文件位于 `application-test.yml`。

```yaml
# src/main/resources/application-test.yml
spring:
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DATABASE:uni_auth}
    driver-class-name: org.postgresql.Driver
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:password}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 20000

  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: none  # 使用 Schema 脚本
    show-sql: true
    properties:
      hibernate:
        format_sql: true

  sql:
    init:
      mode: always
      schema-locations: classpath:schema-postgresql.sql

  session:
    store-type: jdbc
    jdbc:
      initialize-schema: always

logging:
  level:
    com.example.oauth2demo: INFO
    org.springframework.security: INFO
```

### 生产环境配置（PostgreSQL）

生产环境配置强调安全性、高可用性和性能优化。配置文件位于 `application-prod.yml`。

```yaml
# src/main/resources/application-prod.yml
spring:
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DATABASE}
    driver-class-name: org.postgresql.Driver
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000

  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: validate  # 生产环境只验证，不修改
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        jdbc:
          batch_size: 50
          fetch_size: 50
        order_inserts: true
        order_updates: true

  sql:
    init:
      mode: never  # 生产环境手动管理

  session:
    store-type: jdbc
    jdbc:
      initialize-schema: never  # 生产环境手动创建 Session 表
    timeout: 1800
    cookie:
      secure: true
      http-only: true
      same-site: Strict

server:
  port: ${SERVER_PORT:8081}

logging:
  level:
    com.example.oauth2demo: WARN
    org.springframework.security: WARN
```

### 前端模式配置

系统支持两种前端实现模式，通过 `app.frontend.type` 配置项切换：

| 模式 | 配置值 | 说明 |
|------|--------|------|
| Thymeleaf | `thymeleaf` | 服务端渲染，无需额外构建 |
| React | `react` | SPA 应用，支持 React Router 路由 |

```yaml
# application.yml
app:
  frontend:
    type: react  # 或 thymeleaf
```

---

## 核心功能详解

### OAuth2 认证流程

系统实现标准 OAuth2 授权码流程，支持 PKCE 增强安全性。以下为完整认证流程时序图：

```
┌────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│用户 │     │ 前端应用  │     │ UniAuth  │     │ OAuth2   │     │  用户信息 │
│    │     │          │     │  服务器   │     │ 提供商   │     │   API    │
└─┬──┘     └────┬─────┘     └────┬─────┘     └────┬─────┘     └────┬─────┘
  │              │               │               │               │
  │ 1. 点击登录   │               │               │               │
  │─────────────>│               │               │               │
  │              │               │               │               │
  │              │ 2. 重定向到授权端点               │               │
  │              │───────────────>│               │               │
  │              │               │               │               │
  │              │               │ 3. 重定向到OAuth2提供商             │
  │              │               │───────────────>│               │
  │              │               │               │               │
  │              │               │               │ 4. 用户登录并授权            │
  │              │               │               │<─────────────│
  │              │               │               │               │
  │              │               │               │ 5. 返回授权码               │
  │              │               │<──────────────│               │
  │              │               │               │               │
  │              │               │ 6. 用授权码交换令牌                │
  │              │               │───────────────>               │
  │              │               │               │               │
  │              │               │               │ 7. 返回 Access Token        │
  │              │               │<──────────────│               │
  │              │               │               │               │
  │              │               │ 8. 获取用户信息                   │
  │              │               │───────────────>               │
  │              │               │               │               │
  │              │               │               │ 9. 返回用户信息              │
  │              │               │<──────────────│               │
  │              │               │               │               │
  │              │               │ 10. 创建会话，签发 JWT             │
  │              │               │               │               │
  │              │ 11. 设置 Cookie，重定向到首页               │
  │              │<───────────────               │               │
  │              │               │               │               │
  │ 12. 登录成功  │               │               │               │
  │<─────────────│               │               │               │
```

### JWT 令牌管理

#### 令牌签发

系统使用 RSA-2048 密钥对签发 JWT 令牌，遵循以下配置：

```java
// JwtTokenService.java 核心配置
@Service
public class JwtTokenService {
    private static final int RSA_KEY_SIZE = 2048;
    private static final SignatureAlgorithm ALGORITHM = SignatureAlgorithm.RS256;
    
    // 访问令牌过期时间：1小时
    private static final long ACCESS_TOKEN_EXPIRY = 3600000L;
    // 刷新令牌过期时间：7天
    private static final long REFRESH_TOKEN_EXPIRY = 604800000L;
}
```

#### 令牌声明

签发的 JWT 令牌包含以下声明：

| 声明 | 类型 | 说明 |
|------|------|------|
| `iss` | String | 令牌签发者，通常为认证服务器 URL |
| `aud` | String | 令牌受众，通常为资源服务器标识 |
| `sub` | String | 主题，通常为用户 ID |
| `userId` | String | 用户唯一标识 |
| `username` | String | 用户名 |
| `email` | String | 用户邮箱 |
| `authorities` | Array | 用户权限列表 |
| `jti` | String | 令牌唯一 ID，用于令牌吊销 |
| `iat` | Long | 令牌签发时间 |
| `exp` | Long | 令牌过期时间 |

#### JWKS 端点

系统提供 JWKS（JSON Web Key Set）端点供第三方验证 JWT 签名：

```
GET /oauth2/jwks
```

响应示例：

```json
{
  "keys": [
    {
      "kty": "RSA",
      "alg": "RS256",
      "kid": "key-1",
      "use": "sig",
      "n": "0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx4cbbfAAtVT86zwu1RK7aPFFxuhDR1L6tSoc_BJECPebWKRXjBZCiFV4n3oknjhMstn64tZ_2W-5JsGY4Hc5n9yBXArwl93lqt7_RN5w6Cf0h4QyQ5v-65YGjQR0_FDW2QvzqY368QQMicAtaSqzs8KJZgnYb9c7d0zgdAZHzu6qMQvRL5hajrn1n91CbOpbISD08qNLyrdkt-bFTWhAI4vMQFh6WeZu0fM4lFd2NcRwr3XPksINHaQ-G_xBniIqbw0Ls1jF44-csFCur-kEgU8awapJzKnqDKgw",
      "e": "AQAB"
    }
  ]
}
```

### 用户登录方式管理

系统支持同一用户绑定多个登录方式，实现统一身份管理。核心数据结构如下：

```sql
-- 用户登录方式表
CREATE TABLE user_login_methods (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    auth_provider TEXT NOT NULL,           -- LOCAL, GOOGLE, GITHUB, TWITTER
    provider_user_id TEXT,
    provider_email TEXT,
    provider_username TEXT,
    local_username TEXT,
    local_password_hash TEXT,
    is_primary BOOLEAN DEFAULT FALSE,
    is_verified BOOLEAN DEFAULT FALSE,
    linked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP
);

-- 唯一性约束
CREATE UNIQUE INDEX uk_user_login_provider 
    ON user_login_methods(user_id, auth_provider);
```

### 会话管理

#### 会话超时配置

会话超时时间通过 `spring.session.timeout` 配置，单位为秒：

```yaml
spring:
  session:
    timeout: 1800  # 30 分钟
```

#### 会话安全配置

生产环境必须配置以下会话安全选项：

```yaml
spring:
  session:
    store-type: jdbc
    jdbc:
      initialize-schema: never  # 手动创建表
    timeout: 1800
    cookie:
      name: SESSIONID
      secure: true      # HTTPS 传输
      http-only: true   # 防止 XSS
      same-site: Strict # CSRF 防护
      max-age: 1800     # 与会话超时一致
```

---

## API 参考

### 认证端点

#### OAuth2 授权端点

```
GET /oauth2/authorization/{provider}
```

启动指定提供商的 OAuth2 授权流程。`provider` 可选值：`google`、`github`、`x`。

**响应**：重定向至 OAuth2 提供商的授权页面。

#### OAuth2 回调端点

```
GET /oauth2/callback
```

OAuth2 提供商授权成功后的回调端点。处理令牌交换、用户信息获取和会话创建后，重定向至首页或原始请求页面。

#### JWKS 端点

```
GET /oauth2/jwks
```

获取用于验证 JWT 签名的公钥集合。

**响应状态码**：
- `200 OK`：成功返回 JWKS

**响应示例**：
```json
{
  "keys": [
    {
      "kty": "RSA",
      "alg": "RS256",
      "kid": "key-1",
      "use": "sig",
      "n": "...",
      "e": "AQAB"
    }
  ]
}
```

### REST API

#### 用户注册

```
POST /api/auth/register
```

注册新用户账户。

**请求头**：
```
Content-Type: application/json
```

**请求体**：
```json
{
  "username": "newuser",
  "email": "newuser@example.com",
  "password": "securePassword123",
  "displayName": "新用户"
}
```

**响应示例**：
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440001",
  "username": "newuser",
  "email": "newuser@example.com",
  "displayName": "新用户",
  "enabled": true,
  "emailVerified": false,
  "authorities": ["ROLE_USER"],
  "loginMethods": ["LOCAL"]
}
```

#### 用户登录（本地账户）

```
POST /api/auth/login
```

使用本地账户（用户名/密码）登录。

**请求头**：
```
Content-Type: application/x-www-form-urlencoded
```

**请求体（表单编码）**：
```
username=user@example.com&password=password123
```

**响应示例**：
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIs...",
  "refreshToken": "eyJhbGciOiJSUzI1NiIs...",
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "username": "user@example.com",
    "email": "user@example.com",
    "displayName": "张三",
    "authorities": ["ROLE_USER"]
  }
}
```

#### 获取当前用户信息

```
GET /api/user
```

获取当前已登录用户的完整信息。

**请求头**：
```
Authorization: Bearer <access_token>
```

**响应示例**：
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "username": "user@example.com",
  "email": "user@example.com",
  "displayName": "张三",
  "avatarUrl": "https://avatar.example.com/123",
  "authorities": ["ROLE_USER"],
  "loginMethods": ["GOOGLE", "GITHUB"]
}
```

#### 用户登出

```
POST /api/auth/logout
```

清除当前会话和令牌，使认证失效。

**请求头**：
```
Authorization: Bearer <access_token>
Cookie: JSESSIONID=<session_id>
```

**响应**：成功登出后返回 `200 OK`。

#### 刷新访问令牌

```
POST /api/auth/refresh
```

使用刷新令牌获取新的访问令牌。

**请求头**：
```
Cookie: refreshToken=<refresh_token>
```

**响应示例**：
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIs...",
  "refreshToken": "eyJhbGciOiJSUzI1NiIs..."  // 新的刷新令牌
}
```

#### 获取用户登录方式

```
GET /api/user/login-methods
```

获取当前用户绑定的所有登录方式。

**请求头**：
```
Authorization: Bearer <access_token>
```

**响应示例**：
```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440001",
    "authProvider": "GOOGLE",
    "providerUserId": "123456789",
    "providerEmail": "user@gmail.com",
    "providerUsername": "user",
    "isPrimary": true,
    "isVerified": true,
    "linkedAt": "2024-01-10T10:00:00Z",
    "lastUsedAt": "2024-01-15T14:30:00Z"
  },
  {
    "id": "550e8400-e29b-41d4-a716-446655440002",
    "authProvider": "LOCAL",
    "localUsername": "user@example.com",
    "isPrimary": false,
    "isVerified": true,
    "linkedAt": "2024-01-05T08:00:00Z"
  }
]
```

#### 设置主登录方式

```
PUT /api/user/login-methods/{methodId}/primary
```

将指定的登录方式设置为主登录方式。

**请求头**：
```
Authorization: Bearer <access_token>
```

**路径参数**：
- `methodId`：登录方式 ID

**响应**：成功返回 `200 OK`。

#### 删除登录方式

```
DELETE /api/user/login-methods/{methodId}
```

解除用户与指定登录方式的绑定。

**请求头**：
```
Authorization: Bearer <access_token>
```

**路径参数**：
- `methodId`：登录方式 ID

**响应**：成功返回 `200 OK`。

**注意**：如果用户只有一种登录方式且为 LOCAL（本地账户），则无法删除。

#### 为 SSO 用户添加本地登录方式

```
POST /api/user/login-methods/add-local-login
```

为通过 OAuth2/SSO 注册的用户绑定本地账户（设置用户名和密码）。

**请求头**：
```
Authorization: Bearer <access_token>
Content-Type: application/json
```

**请求体**：
```json
{
  "username": "user@example.com",
  "password": "newSecurePassword123",
  "passwordConfirm": "newSecurePassword123"
}
```

**响应**：成功返回 `200 OK`。

Provider token 诊断端点已移除。OAuth2 登录结果通过当前用户接口和服务端认证状态验证，
不再向浏览器暴露独立的 provider token 校验入口。

### 错误响应格式

所有 API 错误响应遵循统一格式：

```json
{
  "error": "error_code",
  "message": "Human readable error message",
  "path": "/api/endpoint",
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 401
}
```

**常见错误码**：

| 错误码 | HTTP 状态码 | 说明 |
|--------|-------------|------|
| `unauthorized` | 401 | 未认证或令牌无效 |
| `forbidden` | 403 | 已认证但权限不足 |
| `not_found` | 404 | 资源不存在 |
| `method_not_allowed` | 405 | 请求方法不允许 |
| `internal_server_error` | 500 | 服务器内部错误 |
| `validation_error` | 400 | 请求参数验证失败 |

---

## 部署指南

### 部署架构建议

生产环境推荐以下部署架构：

```
                    ┌─────────────────────────────────────────────────────┐
                    │                    负载均衡器                        │
                    │                  (Nginx / ALB)                       │
                    └─────────────────────────┬───────────────────────────┘
                                              │
                          ┌───────────────────┼───────────────────┐
                          │                   │                   │
                          ▼                   ▼                   ▼
              ┌───────────────────┐ ┌───────────────────┐ ┌───────────────────┐
              │   UniAuth 实例 1   │ │   UniAuth 实例 2   │ │   UniAuth 实例 N   │
              │   (Spring Boot)   │ │   (Spring Boot)   │ │   (Spring Boot)   │
              └─────────┬─────────┘ └─────────┬─────────┘ └─────────┬─────────┘
                        │                     │                     │
                        └─────────────────────┼─────────────────────┘
                                              │
                          ┌───────────────────┼───────────────────┐
                          │                   │                   │
                          ▼                   ▼                   ▼
              ┌───────────────────┐ ┌───────────────────┐ ┌───────────────────┐
              │  PostgreSQL 主库   │ │                   │ │   Redis 缓存      │
              │   (会话存储)       │ │                   │ │  (可选，性能优化)  │
              └───────────────────┘ └───────────────────┘ └───────────────────┘
```

### Docker 部署（推荐）

#### Dockerfile

```dockerfile
# 构建阶段
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests -Pprod

# 运行阶段
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 创建非 root 用户运行
RUN addgroup -S app && adduser -S app -G app
USER app:app

# 复制构建产物
COPY --from=builder /app/target/uni-auth-1.0.0.jar app.jar

# 暴露端口
EXPOSE 8081

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8081/actuator/health || exit 1

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]
```

#### docker-compose.yml

```yaml
version: '3.8'

services:
  uniauth:
    build: .
    ports:
      - "8081:8081"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - POSTGRES_HOST=postgres
      - POSTGRES_PORT=5432
      - POSTGRES_DATABASE=uni_auth
      - POSTGRES_USER=${POSTGRES_USER}
      - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
    depends_on:
      postgres:
        condition: service_healthy
    restart: unless-stopped

  postgres:
    image: postgres:16-alpine
    environment:
      - POSTGRES_DB=uni_auth
      - POSTGRES_USER=${POSTGRES_USER}
      - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./schema-postgresql.sql:/docker-entrypoint-initdb.d/schema.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d uni_auth"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

volumes:
  postgres_data:
```

### 手动部署

#### 步骤一：构建应用

```bash
# 克隆代码
git clone <repository-url>
cd uni-auth

# 构建生产版本（跳过测试）
mvn clean package -DskipTests -Pprod

# 构建产物位于
ls target/uni-auth-1.0.0.jar
```

#### 步骤二：初始化数据库

```bash
# 连接到 PostgreSQL
psql -h <host> -U <user> -d <database> -f schema-postgresql.sql
```

#### 步骤三：部署应用

```bash
# 方式一：直接运行
java -jar target/uni-auth-1.0.0.jar --spring.profiles.active=prod

# 方式二：使用 systemd 管理
# 创建服务文件 /etc/systemd/system/uniauth.service
[Unit]
Description=UniAuth OAuth2 Server
After=network.target postgresql.service

[Service]
Type=simple
User=uniauth
Group=uniauth
WorkingDirectory=/opt/uniauth
ExecStart=/usr/bin/java -Xmx512m -jar /opt/uniauth/uniauth.jar
Environment="SPRING_PROFILES_ACTIVE=prod"
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

### 部署清单

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 数据库 Schema 已初始化 | ☐ | 执行 `schema-postgresql.sql` |
| 环境变量已配置 | ☐ | 确保 OAuth2 凭据、JWT 密钥已设置 |
| SSL/TLS 已配置 | ☐ | 生产环境必须启用 HTTPS |
| 防火墙规则已配置 | ☐ | 只开放必要端口 |
| 监控告警已配置 | ☐ | CPU、内存、磁盘、错误率 |
| 日志已配置 | ☐ | 日志轮转、聚合 |
| 备份策略已配置 | ☐ | 数据库定期备份 |
| 密钥已轮换 | ☐ | 生产 JWT 密钥已替换默认值 |

---

## 生产环境配置

### 数据库配置

#### PostgreSQL 优化配置

```sql
-- postgresql.conf 关键参数
shared_buffers = 256MB
effective_cache_size = 1GB
work_mem = 16MB
maintenance_work_mem = 256MB
max_connections = 100
random_page_cost = 1.1

-- 连接池配置
pool_mode = transaction
max_pool_size = 20
```

#### 会话表初始化

生产环境需要手动创建 Spring Session 表：

```sql
CREATE TABLE IF NOT EXISTS spring_session (
    primary_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INTEGER NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS spring_session_ix1 ON spring_session(session_id);
CREATE INDEX IF NOT EXISTS spring_session_ix2 ON spring_session(expiry_time);
CREATE INDEX IF NOT EXISTS spring_session_ix3 ON spring_session(principal_name);

CREATE TABLE IF NOT EXISTS spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES spring_session(primary_id) ON DELETE CASCADE
);
```

### 性能配置

#### 连接池配置

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      pool-name: UniAuthHikariPool
```

#### JVM 参数

```bash
# JVM 启动参数
java -Xms512m -Xmx1024m \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/var/log/uniauth/heapdump.hprof \
    -Djava.security.egd=file:/dev/./urandom \
    -jar uniauth.jar
```

### 安全配置

#### 必需的安全响应头

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .xssProtection(xss -> xss.enable())
                .contentTypeOptions(contentType -> {})
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000)
                )
            )
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringRequestMatchers("/api/**")
            );

        return http.build();
    }
}
```

#### CORS 配置

UniAuth 的四条 Spring Security filter chain 共用 `CorsConfig` 创建的唯一
`CorsConfigurationSource`。origin 通过环境变量提供，带凭据时不能使用 wildcard：

```bash
CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com
```

methods、headers、exposed headers、credentials 和 max-age 的基线位于
`application.yml` 的 `app.cors`；origin 必须是不带 path/query/fragment/userinfo 的
精确 HTTP(S) origin。

---

## 安全指南

### OAuth2 安全最佳实践

#### 1. 使用 PKCE 增强授权码流程

系统默认启用 PKCE（Proof Key for Code Exchange），防止授权码被拦截攻击。客户端在发起授权请求时需要生成 `code_verifier` 和 `code_challenge`：

```
# 授权请求
GET /oauth2/authorize?
    client_id=<client_id>&
    redirect_uri=<redirect_uri>&
    response_type=code&
    scope=openid%20profile%20email&
    state=<state>&
    code_challenge=<code_challenge>&
    code_challenge_method=S256
```

#### 2. 安全配置检查清单

| 配置项 | 推荐值 | 说明 |
|--------|--------|------|
| `oauth2-login` 重定向 URI | 精确匹配 | 避免使用通配符 |
| 授权码过期时间 | 5-10 分钟 | 缩短授权码有效期 |
| 令牌过期时间 | 访问令牌 1 小时，刷新令牌 7 天 | 遵循最小权限原则 |
| HTTPS | 强制启用 | 生产环境必须 |
| Scope 最小化 | 只请求必要权限 | 减少信息泄露风险 |

### 令牌安全

#### 令牌存储

- **访问令牌**：存储在 JavaScript 不可访问的内存中，或短期 Cookie（HttpOnly）
- **刷新令牌**：存储在服务器端会话或安全的 HTTP Only Cookie 中
- **ID Token**：验证后立即使用，不长期存储在客户端

#### 令牌吊销

系统实现令牌黑名单机制，支持主动吊销令牌：

```java
@Service
public class TokenBlacklistService {

    public void blacklistToken(String jti, Date expiration) {
        TokenBlacklistEntity entity = new TokenBlacklistEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setJti(jti);
        entity.setExpiresAt(expiration);
        entity.setBlacklistedAt(new Date());
        entity.setReason("user_logout");
        repository.save(entity);
    }

    public boolean isBlacklisted(String jti) {
        return repository.existsByJtiAndExpiresAtAfter(jti, new Date());
    }
}
```

### 依赖安全

#### 依赖扫描

定期使用 OWASP Dependency-Check 扫描依赖漏洞：

```bash
# Maven 插件
mvn org.owasp:dependency-check-maven:check

# 或使用 CLI 工具
dependency-check.sh --project "uni-auth" --scan . --format HTML
```

#### 已知安全依赖版本

| 依赖 | 最低安全版本 | 说明 |
|------|--------------|------|
| Spring Boot | 3.3.4 | 包含安全修复 |
| Spring Security | 6.1.13 | 包含安全修复 |
| PostgreSQL JDBC | 42.7.4 | 修复 JDBC 注入漏洞 |
| JJWT | 0.12.x | 修复密钥混淆漏洞 |

---

## 监控与运维

### 健康检查端点

集成 Spring Boot Actuator 提供监控端点：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: when_authorized
```

| 端点 | 说明 |
|------|------|
| `GET /actuator/health` | 应用健康状态 |
| `GET /actuator/info` | 应用信息 |
| `GET /actuator/metrics` | 性能指标 |
| `GET /actuator/metrics/http.server.requests` | HTTP 请求指标 |

### 日志配置

```xml
<!-- logback-spring.xml -->
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/uniauth.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/uniauth.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE" />
        <appender-ref ref="FILE" />
    </root>

    <logger name="org.springframework.security" level="WARN" />
    <logger name="org.hibernate.SQL" level="DEBUG" />
</configuration>
```

### 性能监控指标

| 指标 | 阈值 | 告警级别 |
|------|------|----------|
| CPU 使用率 | > 80% 持续 5 分钟 | 警告 |
| 内存使用率 | > 85% | 警告 |
| JVM 堆内存 | > 90% | 严重 |
| 请求延迟 P95 | > 2000ms | 警告 |
| 错误率 | > 1% | 警告 |
| 数据库连接池使用率 | > 80% | 警告 |

### 备份与恢复

#### 数据库备份

```bash
# PostgreSQL 每日备份脚本
#!/bin/bash
BACKUP_DIR="/backup/postgresql"
DATE=$(date +%Y%m%d_%H%M%S)
pg_dump -h localhost -U postgres -Fc uni_auth > $BACKUP_DIR/uni_auth_$DATE.dump

# 保留最近 30 天备份
find $BACKUP_DIR -name "*.dump" -mtime +30 -delete
```

#### 恢复命令

```bash
# 从备份恢复
pg_restore -h localhost -U postgres -d uni_auth -c /backup/postgresql/uni_auth_20240115.dump
```

---

## 故障排查

### 常见问题

#### 1. OAuth2 重定向 URI 不匹配

**症状**：OAuth2 授权后返回 `redirect_uri_mismatch` 错误。

**排查步骤**：
1. 检查 OAuth2 提供商控制台中的重定向 URI 配置
2. 确认应用配置中的 `redirect-uri` 与控制台配置完全一致
3. 检查是否有代理或负载均衡器修改了请求 URI

**解决方案**：
```bash
OAUTH2_CALLBACK_URI=https://your-domain.com/oauth2/callback
APP_FRONTEND_URL=https://your-domain.com
```

#### 2. JWT 签名验证失败

**症状**：API 请求返回 401，错误信息为 `JwtException: Invalid signature`。

**排查步骤**：
1. 检查 JWKS 端点是否可访问
2. 确认 JWT 的 `kid` 头与 JWKS 中的 `kid` 匹配
3. 检查 JWT 是否过期

**解决方案**：
```bash
# 验证 JWT header 和 payload（不验证签名）
jwt decode --header --print <token>
```

#### 3. 会话不持久化

**症状**：多实例部署时，用户在一个实例登录后，在其他实例上会话丢失。

**排查步骤**：
1. 确认已配置 `spring.session.store-type=jdbc`
2. 检查 `spring_session` 和 `spring_session_attributes` 表是否存在
3. 验证所有实例使用相同的数据库连接

**解决方案**：
```yaml
spring:
  session:
    store-type: jdbc
    jdbc:
      initialize-schema: never  # 生产环境确保表已创建
```

#### 4. 令牌刷新失败

**症状**：访问令牌过期后，刷新令牌请求返回 401。

**排查步骤**：
1. 检查刷新令牌是否过期
2. 确认用户未被禁用或删除
3. 检查令牌黑名单中是否存在该令牌

#### 5. 数据库连接池耗尽

**症状**：应用响应缓慢或超时，查看日志发现连接获取超时。

**排查步骤**：
1. 检查连接池配置是否合理
2. 分析慢查询，添加必要索引
3. 检查是否存在连接泄露

**解决方案**：
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      leak-detection-threshold: 20000  # 20秒检测连接泄露
```

### 日志级别调试

| 场景 | 推荐日志级别 | 启用方式 |
|------|--------------|----------|
| OAuth2 流程调试 | DEBUG | `org.springframework.security` |
| JWT 调试 | DEBUG | `org.springframework.security.oauth2` |
| SQL 执行 | DEBUG | `org.hibernate.SQL` |
| 会话问题 | DEBUG | `org.springframework.session` |
| 性能问题 | TRACE | `org.hibernate.stat` |

---

## 版本历史

| 版本 | 日期 | 更新内容 |
|------|------|----------|
| 1.0.0 | 2024-01 | 初始生产版本发布 |

---

## 贡献指南

### 开发环境设置

1. Fork 本仓库
2. 创建功能分支：`git checkout -b feature/your-feature`
3. 提交更改：`git commit -am 'Add your feature'`
4. 推送分支：`git push origin feature/your-feature`
5. 创建 Pull Request

### 代码规范

- Java 代码遵循 Google Java Style Guide
- 提交信息遵循 Conventional Commits 规范
- 所有新功能需添加单元测试
- PR 需要通过所有 CI 检查

### 测试覆盖

- 单元测试：核心业务逻辑
- 集成测试：API 端点和数据库交互
- 安全测试：认证流程和权限控制

---

## 许可证

本项目采用 MIT 许可证开源。

---

## 联系方式

- **项目仓库**：[GitHub Repository URL]
- **问题反馈**：[GitHub Issues](https://github.com/your-org/uniauth/issues)
- **文档反馈**：欢迎通过 Pull Request 或 Issues 提交文档改进建议
