# 邮箱登录跨服务浏览器 E2E

> 状态：Live
> 核验日期：2026-08-09
> 本套件只使用 disposable PostgreSQL、本地邮件 stub、真实 UniAuth/Vite/Python
> 进程和真实 Chrome，不读取 `.env`、不连接共享数据库，也不发送真实邮件。
> 聚合器只对本次 Vite dev server 临时设置 `VITE_AUTH_DIAGNOSTICS=true`；不会创建
> `.env.local`，普通开发启动和生产构建都不自动启用诊断模式。

## 验证目标

`scripts/test-email-login-browser-e2e.sh` 验证当前项目实际存在的用户链路：

1. 聚合器显式启用 diagnostics，用户访问 React 前端的 `/resource-test` 资源展示页。
2. 未登录用户被引导到 `/login?returnTo=/resource-test`。
3. 用户完成邮箱注册，UniAuth 通过真实 REST client 调用不会投递邮件的本地 stub。
4. Playwright 从权限为 `0600` 的临时捕获文件读取真实模板变量中的验证码。
5. 验证成功后浏览器回到 `/resource-test`。
6. diagnostics 页面读取测试 profile 登录 JSON 响应保存的 access token，并用
   `Authorization: Bearer <token>` 跨 origin 调用真实 Python API。
7. 清除浏览器认证状态后，再使用同一邮箱和密码登录并重复访问受保护资源。

该套件是当前邮箱注册、邮箱加密码登录、前端回跳、JWT、JWKS、CORS 和异构资源访问
之间的跨组件回归保护。它不验证真实 SMTP 最终送达。

## 真实拓扑

```text
Chrome
  |
  | same-origin page/API calls
  v
Vite frontend: http://127.0.0.1:<random>
  |                         |
  | dev proxy               | Authorization: Bearer <access token>
  v                         v
UniAuth: 127.0.0.1:<random>  Python REST API: localhost:<random>
  |             |
  |             +--> no-delivery email REST stub
  v
disposable PostgreSQL 16
```

Python 组件只有 REST API，不提供资源展示页面。当前展示页面是
`frontend/src/pages/ResourceTestPage.tsx`，与 UniAuth 登录页面属于同一个 React
应用；Python API 可以部署在另一个 origin。套件故意让前端使用 `127.0.0.1`、
Python API 使用 `localhost`，从而证明资源请求不能依赖 UniAuth host-only Cookie。

`/resource-test` 和 `/test` 只在 Vite dev server 且
`VITE_AUTH_DIAGNOSTICS=true` 时注册。Vite 生产构建通过虚拟模块排除两个路由、页面和
诊断链接；独立 production Playwright 会检查路由回退和静态 bundle 内容。生产后端
同时固定 `app.auth.transport.expose-access-token=false`。

如果资源展示页面本身也部署在另一个域，它不能读取 UniAuth 域的 localStorage 或
host-only Cookie。当前仓库尚未证明这种独立资源前端的完整登录协议；生产实现应使用
OAuth/OIDC Authorization Code + PKCE，或使用 BFF/服务器端会话完成 token 交换和持有。

## Access Token 边界

本套件的显式 diagnostics 模式采用双重传递：

- 后端把 access token 写入 HttpOnly Cookie。
- `test` profile 的登录和注册 JSON 响应同时返回 access token。
- 前端把 JSON 中的 access token 存入 localStorage，调用 Python API 时读取该值并
  放入 `Authorization` header。

因此，跨 origin Python API 流程依赖的是 JSON/localStorage 中的 Bearer token，
不是 HttpOnly Cookie。HttpOnly Cookie 主要供 UniAuth 自身的同站请求使用。
Access token 可以同时存在于 HttpOnly Cookie，但它不能**只**存在于 HttpOnly
Cookie，否则浏览器 JavaScript 无法构造跨 origin Bearer 请求。

普通前端不会因为处于 Vite dev mode 就自动启用该路径；聚合脚本必须同时显式启动
诊断路由。生产构建不包含诊断页面，也不把 access token 存入 localStorage。该路径
仅是异构资源服务器演示兼容性；XSS 会扩大 token 泄露风险。生产通常采用以下一种
边界：

1. SPA 直接调用跨域 API：SPA 持有 access token，优先只保存在内存；refresh token
   使用 HttpOnly Cookie。
2. BFF：浏览器只持有 HttpOnly session cookie；同域 BFF 在服务器端持有或交换 token，
   并代替浏览器调用资源 API。

本套件明确验证第一种模式的受控 diagnostics 演示。Playwright 同时断言：

- Python 资源 origin 没有名为 `accessToken` 的 Cookie。
- 即使 Python 资源 origin 存在哨兵 Cookie，资源请求也使用
  `credentials: omit`，不会发送 Cookie。
- `/api/protected` 请求包含非空 Bearer header。
- 未携带 Bearer token 的直接请求返回 `401`。

## 脚本分层

每个服务都有独立的前台启动脚本；聚合器只负责动态端口、生命周期、就绪检查和
Playwright 调用。

| 层 | 文件 | 职责 |
|----|------|------|
| PostgreSQL | `scripts/email-login-e2e/start-postgres.sh` | 启动并清理一个 PostgreSQL 16 disposable container |
| 邮件 stub | `scripts/email-login-e2e/start-email-stub.sh` | 启动 REST stub，将已接受模板写入临时 JSONL |
| UniAuth | `scripts/email-login-e2e/start-uniauth-backend.sh` | 使用 `test` profile 和 runtime guard 启动真实 Spring Context |
| 前端 | `scripts/email-login-e2e/start-frontend.sh` | 启动真实 Vite dev server 和动态代理 |
| 资源 API | `scripts/email-login-e2e/start-resource-server.sh` | 启动真实 Flask/JWKS 资源服务器 |
| 浏览器 | `frontend/playwright.email-login.config.ts` | 独立 live Playwright 配置，不启动 Mock web server |
| 测试 | `frontend/tests/email-login-e2e/email-login-resource.spec.ts` | 注册、验证码、回跳、跨域 Bearer 和再次登录断言 |
| 聚合 | `scripts/test-email-login-browser-e2e.sh` | 组合以上层次并保证失败清理 |

底层脚本要求调用者显式提供端口、数据库、URL 和临时文件位置。日常开发优先运行聚合
入口。聚合器在启动前端进程时临时注入 `VITE_AUTH_DIAGNOSTICS=true`，不生成或修改
`.env.local`；不要把机器专用值或测试开关写入仓库配置。

## 运行

前置条件：

- Docker 和 PostgreSQL client (`pg_isready`)。
- Java 17+、Maven。
- Node.js/npm，且 `frontend/node_modules` 已安装。
- Chrome，与现有 Playwright 配置一致。
- 安装了 `python-resource-server/requirements.txt` 的 Python。

推荐在仓库外创建隔离虚拟环境：

```bash
python3 -m venv /tmp/uniauth-resource-venv
/tmp/uniauth-resource-venv/bin/pip install \
  -r python-resource-server/requirements.txt
PYTHON_BIN=/tmp/uniauth-resource-venv/bin/python \
  scripts/test-email-login-browser-e2e.sh
```

网络受限时，只对依赖安装命令临时设置本机代理；本地服务访问应保持
`NO_PROXY=localhost,127.0.0.1,::1`。聚合器会自动补充该回环例外。

统一门禁也会运行该套件：

```bash
PYTHON_BIN=/path/to/python-with-resource-server-dependencies scripts/verify.sh
```

## 数据与秘密

- PostgreSQL database 名固定包含 `_test`，容器随测试清理。
- OAuth client 值、数据库密码、邮件 API key、用户邮箱和密码都按进程生成，只用于
  disposable 测试。
- RSA key 和邮件捕获文件位于进程专属临时目录。
- 捕获文件创建权限为 `0600`；邮件 stub 响应和控制台日志不返回验证码。
- 聚合器成功或失败后删除临时目录和容器。
- Playwright 失败 trace 位于 ignored 的 `frontend/test-results/`。

## 故障排查

### 注册返回 403

Vite dev proxy 必须把浏览器 origin 重写为实际 backend origin。否则随机前端端口会被
Spring CORS 在 controller 之前拒绝。检查 `VITE_DEV_PROXY_TARGET` 和
`frontend/vite.config.ts` 的 proxy `Origin` header，不要通过扩大生产 allowlist
接受任意随机端口。

### 没有验证码

检查邮件 stub 是否通过 `/api/email/health`，以及 UniAuth 的
`EMAIL_SERVICE_URL`/`EMAIL_SERVICE_API_KEY` 是否匹配。捕获文件只记录外部服务已经
接受的模板请求；同步拒绝不会生成 challenge 或捕获记录。

### Python 启动时缺少模块

`PYTHON_BIN` 必须指向安装了组件 requirements 的解释器。不要依赖工作机全局
site-packages，也不要把 venv 提交到仓库。

### 资源请求被 CORS 拒绝

Python 的 `CORS_ALLOWED_ORIGINS` 必须包含精确的 Vite origin。Bearer token 验证还
要求 `AUTH_SERVER_URL`/`JWKS_URL`、issuer、audience 和 `kid` 与 UniAuth 一致。

## 更新触发条件

修改以下任一边界时必须更新并重跑本套件：

- 邮箱注册、验证码或邮箱加密码登录。
- access token 的 JSON、Cookie、localStorage 或内存传递策略。
- `VITE_AUTH_DIAGNOSTICS`、生产诊断 bundle 排除或后端
  `app.auth.transport.expose-access-token` 策略。
- `/resource-test` 登录引导或 `returnTo` 校验。
- Vite proxy、CORS、Python API URL。
- JWT issuer、audience、claims、JWKS 或资源服务器校验。
- 邮件 REST 请求、模板变量、API key 或 stub 捕获格式。
