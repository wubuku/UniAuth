# F3 OAuth2、Web3 与 Canonical API 契约实施记录

> 状态：Completed
> 规划日期：2026-08-09
> 固定进度口径：F2 完成后约 91%；F3 完成后约 95%
> 上位范围：`FINAL_HARDENING_EXIT_PLAN.md` 的 F3

## 1. 边界

1. 只加固现有 OAuth2、Web3、登录方式管理和 `/api/user`，不增加 provider、链、
   钱包或用户流程。
2. 只支持 PostgreSQL 16；V8 是 forward-only migration，不修改 V1-V7。
3. 不调用真实 OAuth provider，不写共享开发库，不调用真实 SMTP 或高成本模型。
4. 并发一致性优先唯一约束、条件更新和 CAS；不引入 JPA `@Version`，不把悲观锁
   作为默认方案。
5. 不丢弃、回滚或 stash 其他人的修改；提交时使用 `git add -A` 纳入全部非 ignored、
   非敏感、非生成修改。
6. F3 只通过本批测试和完整统一门禁验收，不执行单批连续三轮无修改检查；唯一一次
   三轮检查在 F1-F5 全部完成后执行。

## 2. 已验证风险

- OAuth callback 仍根据 access Cookie 是否存在自动选择绑定，普通登录可能意外绑定。
- provider 有属性名猜测后备路径；OAuth subject、email、name 和 picture 缺少统一边界。
- OAuth 新用户 username/email 可从 provider subject 或 email 派生，且 provider email
  被默认视为已验证。
- OAuth bind 没有绑定 state、浏览器 Session、provider、当前用户和 security version
  的一次性服务端 intent。
- Web3 challenge 仍按 wallet upsert，另一个浏览器申请 nonce 会覆盖未完成 challenge；
  公开 status endpoint 泄露 wallet 绑定状态。
- Web3 建号/绑定存在跨 repository 事务空窗；绑定仍包含 `findAll()` 扫描。
- Web3 请求缺少 challenge handle 和严格长度边界；active challenge 没有 source/global
  容量上限。
- add-local 使用裸 `Map`；敏感登录方式写操作没有统一 recent-auth 和限流守卫。
- `/api/user` 对 JWT principal 把 provider 固定为 `local`。
- X scope 包含当前登录不需要的长期/写侧能力；authorized client 没有显式最短生命周期。

## 3. 固定实施切片

### F3.1 PostgreSQL V8 与持久化边界

1. 新增 `oauth2_binding_intents`，保存 state/session 摘要、provider、user、
   security version、auth time、过期和消费状态。
2. callback 在业务事务内以 state + Session + provider 条件更新原子消费 intent；
   过期、重放、跨 provider、跨 Session、跨用户或 security version 漂移统一失败。
3. 扩展 `web3_nonces` 为 challenge-handle 精确关联；禁止 wallet upsert，保留单 wallet
   一个 active challenge 的唯一约束。
4. 新增 Web3 active challenge source/global counter；使用条件更新/CAS 预留和释放容量，
   challenge 插入失败时同事务回滚，过期清理同步释放。
5. 同步 Flyway inventory、schema fingerprint、shared-schema peer guard、fresh/upgrade/
   failure recovery 和 baseline guard 到 V1-V8。

### F3.2 统一守卫

1. 新增配置化 recent-auth 窗口；直接登录写入当前 auth time，refresh 只继承原值。
2. add-local、remove、set-primary、OAuth2 bind 和 Web3 bind 共用 recent-auth 判断。
3. OAuth authorization/failure、Web3 challenge/verify 和登录方式写操作接入共享
   PostgreSQL 限流，只信任 servlet remote address，不信任客户端 forwarded header。
4. recent-auth、限流不可用和容量耗尽使用稳定的 `403`、`429`、`503` 外部语义。

### F3.3 OAuth2 契约

1. `/oauth2/authorization/{provider}` 只表示登录；
   `/oauth2/bind/{provider}` 只表示显式绑定。
2. bind resolver 先验证当前 access token 和 recent-auth，再创建绑定 authorization
   request 与一次性 intent；callback 没有 intent 时只能执行普通登录。
3. provider 只取 `OAuth2AuthenticationToken.registrationId`；`x` 映射业务
   `TWITTER`，未知 registration id 失败。
4. subject 必须是非空、单值、无控制字符且长度不超过数据库边界。
5. Google 只信任 `email_verified=true`；GitHub 只信任 verified primary email；
   X 不提供可信 email。未验证 email 使用 `UNVERIFIED_CONTACT`，缺失 email 使用
   `SYNTHETIC`，均不得触发自动合并。
6. OAuth/Web3 新用户使用随机 opaque username 和 synthetic identity，不从 provider
   subject、wallet、email 或 display name 派生。
7. 首次 callback、重复 callback、并发创建和不同用户绑定依赖数据库唯一约束并映射
   稳定业务冲突；disabled/security-version 漂移在写 metadata、消费 intent 和签发
   token 前失败关闭。
8. callback 使用完 provider profile 后删除 authorized client；X scope 收敛为
   `/2/users/me` 官方要求的 `tweet.read` 与 `users.read`，不增加离线、写入、关注或
   点赞权限。

### F3.4 Web3 与 API 契约

1. nonce 响应和 verify/bind 请求增加 opaque challenge handle；wallet、message、
   signature、nonce、handle 和 chainId 在签名恢复前完成 typed validation。
2. chainId 必须等于服务端配置并同时出现在服务端保存的 SIWE message；不静默忽略。
3. verify + challenge consume + find/create user，以及 bind + consume + login-method
   insert + session replacement，分别位于完整事务中。
4. 删除公开 wallet status endpoint 和 `findAll()` 绑定扫描。
5. `Web3SignatureUtils` 用 EIP-191 UTF-8 byte length 和标准签名恢复覆盖 ASCII、中文、
   emoji、v/r/s 与错误地址。
6. add-local 改为 typed DTO；登录方式列表使用 typed response DTO。
7. `/api/user` 从数据库 primary login method 返回 `local/google/github/x/web3`。
8. OAuth2/Web3 bind、primary change 和稳定冲突写入不含 provider subject、wallet、
   token 或原始请求载荷的最小安全事件。

## 4. 验收矩阵

### PostgreSQL / Java

- V1-V8 fresh、V7 upgrade、重复启动、Hibernate `validate`、schema inventory 和坏数据
  preflight。
- OAuth login/bind intent 的缺失、成功、过期、重放、跨 Session、跨 provider、
  security version 漂移和并发消费。
- Google/GitHub/X subject 与 email trust；opaque identity；disabled user；并发首次
  callback 和唯一冲突回滚。
- Web3 challenge handle、wallet/source/global 上限、过期清理、原子消费、并发建号/
  绑定、事务回滚和最终登录方式不变量。
- recent-auth 在直接登录后成功、refresh 后不延长、过期/缺失失败；所有敏感写入口共用。
- `/api/user` primary provider、typed DTO 边界、status oracle 404 和安全事件最小载荷。

### Shell / Playwright / Python

- Shell 使用真实 PostgreSQL 和应用覆盖普通 OAuth login 不绑定、显式 bind intent
  一次消费、Web3 handle、status oracle 关闭、recent-auth、限流、重启和最终 schema。
- Mock Playwright 覆盖登录 URL 与绑定 URL 分离、Web3 handle 透传、provider 展示和
  生产诊断路由仍不可达。
- Python JWT 合约不变化；仍运行完整离线资源服务器回归，确认 F3 未破坏 F2 claims。

## 5. 退出门槛

1. 本任务相关 PostgreSQL/Java、Shell/Flyway、Playwright 和 Python 测试通过。
2. `mvn clean compile test-compile`、完整 Maven、前端 lint/type/build 和
   `scripts/verify.sh` 完整门禁通过。
3. 更新 live 文档和本记录，使用 `git add -A` 提交并推送。
4. 不执行 F3 单轮三轮无修改检查；通过后进入冻结的 F4。

## 6. 实施结果

2026-08-09 已完成固定范围：

- PostgreSQL V8 增加 OAuth2 binding intent、Web3 challenge handle 与 capacity
  counter；双方 shared-schema peer inventory、baseline guard、fresh/upgrade 和
  Hibernate `validate` 已同步到 V1-V8。
- 普通 OAuth2 登录不再隐式绑定；`/oauth2/bind/{provider}` 建立一次性、绑定用户、
  provider、Session、state 和 security version 的服务端 intent。
- Google/GitHub/X provider profile、subject/email trust、opaque identity、
  authorized-client 清理和唯一冲突语义已固定。
- Web3 nonce/verify/bind 使用 typed handle/chain contract、完整事务、容量 CAS 和
  稳定冲突映射；公开 wallet status oracle 与 `findAll()` 扫描已移除。
- login-method 写操作共用 recent-auth 与限流守卫；typed DTO 和 `/api/user` primary
  provider 契约已覆盖前后端。

验收证据：

- F3 定向 PostgreSQL/Java 63/63；migration/shared-schema 根项目 15/15，邮件 peer
  guard 8/8。
- shared-schema process E2E 4/4；HTTP/PostgreSQL/Flyway/Web3/email E2E 16/16。
- 相关 Mock Playwright 15/15，显式 OAuth bind 1/1；完整 Mock Playwright 29/29，
  生产 Playwright 2/2，真实邮箱登录浏览器 E2E 1/1。
- 完整 `scripts/verify.sh` 12/12：根 Maven 222/222、邮件 Maven 154/154、Python
  邮件 stub 12/12、资源服务器 20/20、文档链接和 `git diff --check` 全部通过。
- F3 未执行单批三轮无修改检查；唯一阶段末三轮检查继续延后到 F1-F5 全部完成后。
