# Circle OAuth 绑定与 X 登录加固实施记录

> 日期：2026-08-11
> 关联应用：Circle compatibility external Cookie 集成
> UniAuth 分支：`main`

## 1. 范围

本记录覆盖 Circle 手动联调期间发现的 UniAuth 边界问题：

- 已登录 Circle 用户绑定社交账号时，绑定 session 轮换后 CSRF token 仍属于旧
  `JSESSIONID`；
- 绑定一个已经属于其他用户的 provider identity 时，错误被前端看成普通登录失败；
- recent-auth 过期时，绑定入口可能落入 Spring Security 的 Whitelabel 500；
- X OAuth 的授权组合需要与 `/2/users/me` 当前 endpoint 契约一致；Circle 只读取用户资料，
  因此实现收敛为 `users.read`，不申请推文权限。此前还存在
  user-info 处理依赖硬编码 URL、保留过多 profile 字段的问题。

本次不改变 UniAuth 的身份数据模型：一个 provider subject 只能绑定一个用户；绑定
冲突不自动解绑、不合并、不迁移。

## 2. 实施结论

### 2.1 绑定 session 与 CSRF

绑定启动如果 UniAuth 返回新的 `JSESSIONID`，scenemill compatibility client 会
使用新 session 重新请求 `/api/auth/csrf`，然后才把浏览器带入 provider 授权页。
后续绑定请求使用新 session 对应的 CSRF token。UniAuth 返回 `403` 时，scenemill
将其解释为需要重新认证，而不是“统一身份服务暂时无法启动账号绑定”。

### 2.2 provider identity 冲突

OAuth binding intent 只允许显式 `/oauth2/bind/{provider}` 路径创建。回调失败时，
UniAuth 识别 `LoginMethodConflictException` 并通过受控错误码：

```text
oauth2_binding_conflict
```

回跳给 compatibility handoff。Circle 将其显示为：

```text
这个社交账号已经绑定到其他 Circle 账号，不能重复绑定。
```

所有社交账号的绑定状态仍以 UniAuth 读取结果为准；读取失败时 Circle 保留已知状态，
不把未知状态误画成全部“绑定”，并提供“重新读取登录方式”入口。

### 2.3 recent-auth 过期

绑定入口保留 recent-auth 安全门禁。门禁失败返回：

```json
{
  "success": false,
  "error": "RECENT_AUTH_REQUIRED",
  "message": "Recent authentication is required"
}
```

Circle 将其转换为可理解的重新登录提示，并保留回到登录方式区域的路径。

### 2.4 X OAuth 最小权限和最小 principal

X 当前配置：

```text
users.read
```

Circle/UniAuth 不调用推文接口。X user-info URL 从 `ClientRegistration` 获取，并通过
URI builder 添加最小字段：

```text
id,username,profile_image_url
```

请求使用 OAuth access token 的 `Authorization: Bearer ...` header，OAuth principal
只保留上述字段，不把 description、entities、location、metrics 等多余资料写入
session 或身份对象。

### 2.5 绑定 marker 生命周期

`UNIAUTH_OAUTH2_BINDING_PROVIDER` 只允许由显式
`/oauth2/bind/{provider}` 授权请求写入 session。普通
`/oauth2/authorization/{provider}` 登录启动时会主动清除残留 marker，避免用户在
绑定页停留、取消 provider 授权或切回普通登录后，后续回调仍被误判为绑定。

绑定回调无论成功、业务冲突还是 provider 失败，都会清除 marker。provider 失败会返回
`oauth2_binding_failed`，普通登录失败仍返回 `oauth2_failed`，两者不会串线。

### 2.6 Review 收敛与 Circle 兼容性

后续 review 发现并修复了四个边界，并额外收敛了 X 的授权范围：

1. disposable reset 现在在 preview 和 apply 事务中都验证 UniAuth Flyway history
   必须精确为成功的 V1-V8，并验证关键表、级联外键和 provider 唯一索引；
2. `--apply` 清空 disposable 数据库中的全部 Spring Session，避免被删除用户的旧
   序列化 SecurityContext 继续认证；
3. `uk_provider_user` 唯一约束在并发绑定裁决路径统一翻译为
   `OAuth2BindingConflictException`，因此 Circle 始终收到 `oauth2_binding_conflict`；
4. OAuth failure handler 在限流 429/503 提前返回路径也通过 `finally` 清理
   `UNIAUTH_OAUTH2_BINDING_PROVIDER`；
5. X scope 额外收敛为 `users.read`，与 Circle 只读取 `/2/users/me` 的实际用途一致。

这些修改不改变一个 provider subject 只能绑定一个用户的模型，也不改变 Circle 正常
登录、首次使用确认、邮箱登录或社交账号解绑语义。唯一预期的测试环境行为变化是：
直接执行 UniAuth reset apply 后，整个 disposable UniAuth 的浏览器会话都需要重新登录；
Circle 联调应使用包装清理入口，以同步清理 Circle onboarding 数据。

## 3. 验收证据

```text
mvn -q -Dtest=SecurityConfigXOAuth2UserServiceTest,OAuth2SuccessHandlerIntegrationTest test  PASS
mvn -q -Dtest=ExternalIdentityControllerTest,UniAuthCompatibilityClientTest test              PASS
mvn test -q                                                                                   262/262 PASS
```

完整套件第一次复跑时，`ProductionHttpBoundaryIntegrationTest` 曾出现 4 个未复现的
HTTP 边界失败；该类隔离运行 `4/4` 通过，随后完整套件重新运行并从 Surefire 报告
核算为 `262` tests、`0` failures、`0` errors、`0` skipped。此次新增的 Circle
兼容性集成测试也包含在该数字内；没有为一次性运行异常修改生产逻辑或测试门槛。

X 定向测试覆盖：

- 精确 user-info URI；
- Bearer header；
- `id`、`username` 缺失；
- `data` 不是对象；
- 多余 profile 字段不进入 principal；
- registration scope 精确为 `users.read`；
- registration user-info URI 和扁平化后的 principal name attribute 与运行实现一致。

OAuth 集成测试覆盖：

- 绑定冲突使用 `oauth2_binding_conflict`；
- 过期 recent-auth 返回 `RECENT_AUTH_REQUIRED`；
- 绑定 provider 失败使用 `oauth2_binding_failed` 并清除 marker；
- 普通登录启动会清除残留绑定 marker；
- 限流器返回 429/503 时仍会清除残留绑定 marker；
- 并发 provider subject 唯一约束失败保留 `OAuth2BindingConflictException` 类型；
- 删除社交登录方式会撤销旧 access token，但保留的邮箱/LOCAL 登录方式仍可重新登录，
  并能重新读取只剩 LOCAL 的登录方式列表；
- 正常登录与正常绑定仍可完成。

Circle 侧兼容契约定向回归：

```text
cd seedance-research-circle-app/api-server
mvn -q -Dtest=UniAuthCompatibilityClientTest,ExternalIdentityControllerTest test  PASS
30/30
```

该回归确认 Circle 当前调用的登录方式读取、Bearer 透传、解绑响应、绑定启动、
CSRF、provider session 以及 OAuth handoff 错误映射仍与 UniAuth 契约一致。

## 4. provider 配置边界

真实 provider 后台仍只登记 Circle 当前公网 canonical callback：

```text
https://api.u2511175.nyat.app:55139/oauth2/callback
```

X 控制台只需允许 `users.read`。如果 X 应用已有旧 scope 配置，重新授权时应以当前
UniAuth `application.yml` 的最小 scope 为准。
