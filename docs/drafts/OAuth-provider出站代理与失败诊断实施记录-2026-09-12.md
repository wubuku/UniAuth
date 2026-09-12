# OAuth provider 出站代理与失败诊断：实施记录

> 状态：实现、自动化门禁与连续三轮审查完成，待 Circle 隔离栈重启和真实隧道验收
> 日期：2026-09-12
> 范围：authorization-code token 交换、标准 user-info、GitHub/X 补充 profile 请求

## 事故证据

Circle 外网隧道的 Google 登录在 provider 回调后返回
`externalAuthError=EXTERNAL_LOGIN_FAILED`。UniAuth 同期日志只记录：

```text
OAuth2 login failed: errorCode=invalid_token_response
```

进一步核实得到：

- 失败发生在 UniAuth 使用 authorization code 向 Google token endpoint 换取 token
  的阶段，尚未进入 Circle handoff、开户或 session 创建；
- 当时运行中的 UniAuth 未配置 OAuth 专用代理；
- 本机直连 `accounts.google.com` 和 `oauth2.googleapis.com` 均在约 5 秒后连接超时；
- macOS 当前 HTTPS 代理为 `127.0.0.1:1235`，通过该代理访问 Google discovery
  endpoint 返回 `200`，向 token endpoint 发送无凭据请求返回预期的 `400`；
- UniAuth 当前只为 OAuth HTTP client 配置了 connect/read timeout，没有显式代理入口，
  failure handler 也没有区分网络、超时、TLS 和 provider 响应错误。

因此本次直接根因是 UniAuth 到 Google 的服务端出站链路不可用；Circle 的错误回跳只是
稳定降级表现。实现仍需修复两个工程缺口：OAuth 客户端不能稳定选择专用出站代理，日志
也不足以在不泄露 code/token/secret 的前提下定位失败层级。

## 实施边界

- [x] 新增可选、无认证、仅 HTTP CONNECT 语义的 OAuth 专用 proxy URL。
- [x] 默认读取标准 HTTP(S) proxy 环境变量并保留 JDK 系统代理回退，运维无需把机器
  地址写入配置文件；`DIRECT` 仅作为显式排障覆盖。
- [x] 代理只作用于 OAuth token、user-info 和补充 profile HTTP client，不设置 JVM
  全局代理，不影响数据库、邮件、Circle/scenemill 或其他 HTTP client。
- [x] proxy URL 必须是 `http://host:port`，禁止 userinfo、path、query、fragment、
  控制字符和非法端口；未配置时保持现有默认路由。
- [x] connect/read timeout 继续有界，authorization code 和 provider token 不做盲重试。
- [x] failure log 只输出稳定错误码、失败分类和异常类名，不输出异常 message、URL query、
  authorization code、provider token、client secret、Cookie 或响应正文。
- [x] 自动化覆盖默认/JDK 路由、显式代理转发、`DIRECT`、非法配置拒绝、token 解析和
  安全失败分类。
- [x] 更新 `AGENTS.md`、`docs/CONFIGURATION.md`、`docs/DEVELOPMENT.md`、
  `docs/VERIFICATION.md` 与索引。
- [x] 完成 Maven 定向测试、`mvn clean compile test-compile`、完整 `mvn test`、
  `mvn validate`、文档链接和 `git diff --check`。
- [x] 在最后一次行为修复后完成连续三轮固定范围无修改审查。

## 验收原则

真实 Google 登录需要浏览器中的用户授权交互，不由无凭据自动化伪造。自动化先证明
代理选择、HTTP 交换、错误分类和超时边界；随后由 Circle 隔离开发栈通过真实隧道验证。
浏览器证据只使用 DOM、网络请求/响应和断言，不使用截图。

## 当前自动化证据

- `OAuth2HttpClientConfigTest`：18/18；
- `OAuth2SuccessHandlerIntegrationTest`：24/24；
- 合计 42/42，覆盖真实 loopback HTTP proxy socket、token 表单/JSON 解析、默认路由、
  `DIRECT` 覆盖、URL 校验、超时和脱敏失败日志。
- `mvn clean compile test-compile`：通过；
- 完整 `mvn test`：43 个 suite，292/292，0 failures，0 errors，0 skipped；
- `mvn validate`：通过；
- 54 个 Markdown 文档的相对链接检查通过；
- `git diff --check`：通过。

## 收敛审查中的修复

最后一次实现复核发现并修复了两项边界问题，因此连续审查计数已归零：

1. Circle 启动器在调用 shell 已提供标准 `https_proxy` / `http_proxy` 时，仍可能被
   UniAuth `.env` 中遗留的 `DIRECT` 或旧 `HTTP` 地址覆盖。最终优先级明确为：
   本次进程的 OAuth 专用覆盖、标准代理环境变量、应用/`.env` 兼容回退、操作系统/JDK
   代理、直连。自动化已覆盖标准环境变量压过两类遗留配置。
2. provider 提供的 OAuth error code 理论上可能包含控制字符并污染日志。最终只允许
   64 字符以内的字母、数字、下划线、连字符和点；其余统一记录为 `unavailable`，
   集成测试证明伪造换行和后续文本不会进入日志。

## 连续三轮审查结论

最后一次行为修复后，连续三轮均未修改实现或测试，计数达到 `3/3`：

1. **合同与接线**：authorization-code token exchange 使用专用 token client；Google、
   GitHub 标准 user-info、GitHub 邮箱和 X profile 请求共用同一有界 OAuth client。
   代理没有进入邮件、数据库或其他 HTTP client，也没有设置 JVM 全局代理。
2. **失败、恢复与安全边界**：核对 `AUTO`、`HTTP`、`DIRECT`、标准代理环境变量、
   应用兼容回退、JDK 系统代理、非法 URL、connect/read timeout 和安全日志分类；
   Shell 与 Java 定向矩阵全部通过，未发现盲重试、凭据泄漏或日志注入路径。
3. **交付与可运行性**：定向测试 `42/42`、完整 Maven `292/292`、编译/test-compile、
   validate、文档相对链接和 `git diff --check` 均通过；机器专属代理地址、敏感信息、
   生成物和版本字段均未进入提交范围。

本次自动化不重放真实 authorization code，也不打印 provider URL query、OAuth code、
token、client secret、Cookie 或响应正文。真实 provider 可达性和浏览器回跳由 Circle
一键开发栈在 UniAuth 提交后统一验证。
