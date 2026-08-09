# F1 邮箱与身份完整性实施记录

> 状态：Completed
> 启动日期：2026-08-09
> 完成日期：2026-08-09
> 总体进度口径：F1 前 82%；F1 完成后 86%
> 上位范围：[最终加固退出计划](FINAL_HARDENING_EXIT_PLAN.md)

## 1. 边界与退出

本文只执行最终退出计划中的 F1，不新增功能，不创建新的加固批次。

- 本文的五个切片只是 F1 内部的依赖顺序，不是可无限续写的小批次。
- F1 的所有固定范围完成并通过统一硬门槛后，F1 结束；本轮不单独执行连续三轮
  无修改检查。
- F1 完成时曾暂停自动进入 F2-F5；后续用户已明确授权继续最终加固阶段，当前状态以
  `FINAL_HARDENING_EXIT_PLAN.md` 的 F1-F2 completed、F3 next 为准。
- 实施中发现的非阻断优化进入加固后 backlog，不改变 F1-F5 范围和百分比。
- 只有数据损坏、认证绕过、凭据泄露或门禁伪成功等阻断问题可以在当前切片内修复。

## 2. 不可变 pre-F1 基线

唯一 pre-F1 compatibility fixture：

```text
commit:
1094da4096e8002fbc1335aa38eca99825a68e29

tracked inputs:
pom.xml
f57824c521d6ce80f57767579d7303014e259b1a8a75e86158678078d71ccdd4

frontend/package-lock.json
ed5e520afdf8a26df5683a5f04392d89194f69ea57c6ecdc979cc2cb33656c43

python-resource-server/requirements.txt
db3674c3e5a57b8d8f1e43ae53eb9eef3ee30bb40c4eae3965c803a4924e0715

reference/email-service/pom.xml
fd0a6ba6b2cd7dc641d78306a1015b5749a7e45857137b613a15d916c16c8f1c
```

Java 17 构件于 2026-08-09 使用 Temurin `17.0.19+10` 构建。JDK 镜像摘要是
`sha256:d524ce35c15ca528396d986a9190660c02a3d45fd8faa094e531e57ad557daaa`。
Maven 3.9 文件来自本机已有 Maven 镜像；编译和打包 JVM 始终是上述 Java 17。

```text
target/uni-auth-1.0.0.jar
8a9839fca98074b576c6084432477c6cb66b02d115d4a1d32a66198a194129fe

target/uni-auth-1.0.0.jar.original
ad8a6c78298273e67075a82c1ecae5e078c52bb9729605e9281d3125d50bc6e5

reference/email-service/target/email-service-1.0.0.jar
dcb5952da4bddf75b50601d5ffc589218142572010d8b5b5579c1124ce12ddc4

reference/email-service/target/email-service-1.0.0.jar.original
202f793c88f517ba037424f978c213e53436256d2c1b4f42684dc84d3077d0a0
```

构件是 ignored 验证产物，不提交。pre-F1 migration 摘要：

```text
UniAuth V1
df268d7c193fddca0f2d8da7565da2e70c75f3e4d8f368bcf99ef9b0cf70e0ba
UniAuth V2
65787b1797e74765c72c9710837b16b304dc8a936765f183514877df3bb5f3ea
UniAuth V3
c5eaff03540ff88d41a71a352441e4f520b19f7736e8746bad12c2b48ca3fbf6
UniAuth V4
f0e835f3361f6c4f6010f7ecb850ff6c622583abd67fe062a26ac3be330698b3
UniAuth V5
6ddae6c556fbbf637ef47dae3817f3b671172600e96cb3875a0e1341f375afbf

Email service V1
7772f335118d4c9651901e0489ab26a1c82b47ba9b852b32ec38eb525974bef4
Email service V2
e3ae16c15d11b38a963fde5aedff2223ad2f95d7d1afc8c60beb167a0c1f73dc
Email service V3
a677643a6c27d816019a34bb1d48aa9124ada34bc1a0e2ed7e33603e7c33b8f9
```

## 3. 固定实施切片

### S1：canonical identity 与凭据边界

1. 增加唯一 `CanonicalEmailService`，统一 trim、长度、格式和
   `Locale.ROOT` 小写规则。
2. 注册、邮箱 challenge、密码重置、LOCAL email-shaped username、用户和登录方式查询
   全部使用 canonical 值。
3. Flyway 增加明确的 email identity 状态、canonical CHECK/partial unique index 和
   email-shaped LOCAL username 一致性约束；坏数据 preflight 只报告冲突，不自动合并。
4. verified contact 与 OAuth/Web3 synthetic identity 使用明确不同的状态/字段；
   合成值不得参与 canonical email 唯一归属、密码重置或可投递身份判断。
5. 普通 username 注册和 email-as-username 注册都先完成邮箱验证；验证前不创建 user。
6. 增加统一密码策略和 typed DTO，覆盖注册、验证注册、add-local、密码重置和登录。
7. 登录只接受单个 JSON body，不接受 query/form password、重复 JSON 字段、超长字段或
   非 JSON content type。
8. 登录只执行一次 BCrypt 校验；同一事务确认 enabled、更新 `last_used_at`、写安全事件，
   然后调用单一 token issuance facade。
9. 已有低成本 BCrypt 在成功登录后升级；既有 passwordless LOCAL 只兼容读取和报告，
   F1 写路径不得继续产生。

### S2：challenge、验证码和枚举防护

1. challenge 使用不可猜测 handle；verify 按 handle、purpose 和 canonical email 精确定位。
2. 数据库只保存带独立服务端 key 的 HMAC digest 和 key id，不保存明文 code。
3. 删除 password/hash/任意 metadata；注册 DTO 由浏览器在 verify 时提交并再次验证。
4. 同一 canonical `(email,purpose)` 最多一个 active challenge。
5. 删除 `LOGIN` purpose；遗留活动记录由 migration preflight 报告并失效。
6. 删除公开 email status 和只读 check-code oracle；错误 verify 同样原子增加 retry。
7. forgot-password 发送阶段统一外部响应；不存在账户走有界 decoy，不创建可用 challenge。
8. 匿名注册不得绑定或修改既有 LOCAL/OAuth/Web3 账户。
9. `/register` 验证分支、`/verify-email` 和重复 service 建号逻辑调用同一个
   challenge 消费 + user/LOCAL method 创建事务；兼容 endpoint 不保留第二条写路径。

### S3：durable delivery 与邮件幂等契约

1. UniAuth 在本地事务中创建 challenge 和 outbox，状态初始为 `PENDING_DELIVERY`。
2. worker 使用稳定 idempotency key 调用邮件服务；超时或进程退出后可以安全重试。
3. 邮件服务按 idempotency key 只创建一个 queue identity，并返回稳定 queue id/status。
4. 邮件服务提供受现有 API key 保护的最小 delivery status 查询。
5. UniAuth 只有确认邮件服务接受后才把 challenge 转为 `ACTIVE`。
6. reconciler 恢复“邮件已接受、UniAuth 尚未确认”的窗口；最终 delivery 失败使
   challenge 进入不可验证状态。
7. pending、accepted 和 active 均有处理截止时间及总生命周期上限；恢复不能延长总寿命。

### S4：共享限流与 append-only 安全事件

1. PostgreSQL 原子 reservation 实现多实例一致限流，不使用先 count 后 insert。
2. F1 接入密码登录、注册、发送/验证 challenge 和密码重置。
3. key 只含 endpoint、可信来源和 canonical identity 的不可逆摘要。
4. limiter 故障时，高成本或外部调用前稳定返回 `503`；不得永久锁定账户。
5. 最小 security event 只保存事件类型、内部 subject、request id、结果和原因码。
6. 事件表拒绝业务角色 update/delete；关键状态和事件必须同事务提交。

### S5：迁移、兼容切换与完整证据

1. UniAuth 只新增 V6 及后续 forward migration；邮件服务只新增 V4 及后续 migration。
2. fresh、V5/V3 upgrade、坏数据 preflight、重复启动和 shared-schema 双启动顺序全覆盖。
3. 更新双方 peer-history inventory、Java/Shell guard、backup inventory 和 schema 断言。
4. 用 pre-F1 JAR 验证 expand 阶段兼容；退役明文 code/metadata 是 no-return cutover。
5. 更新 Java、Shell HTTP E2E、Playwright 和必要的 Python/契约测试。
6. 按 F1 当时的冻结计划，完整统一门禁通过后记录 F1 验收证据并计划进入 F2，
   连续三轮无修改检查延后到 F1-F5 全部完成后的阶段末执行。后续用户已明确恢复并
   启动该冻结顺序。

## 4. 数据迁移和切换规则

- pre-F1 已提交的 UniAuth V1-V5 和邮件 V1-V3 migration 不修改；F1 只新增
  UniAuth V6 和邮件 V4，后续继续使用 forward-only migration。
- canonical 冲突、重复 active challenge、LOGIN 活动记录、非 canonical email、
  passwordless LOCAL 和含凭据 metadata 均由只读 preflight 输出稳定报告。
- 迁移不得自动选择账户、合并 identity、生成密码或把 LOGIN 转成其他 purpose。
- expand 阶段允许 pre-F1 应用只读/启动验证时，不得让旧应用写入新的不安全状态。
- contract 前停止旧写流量，运行 preflight，执行 forward migration，部署 F1 应用并
  清理旧客户端 challenge 状态。
- no-return cutover 后不回滚旧应用；故障处理是停止流量、保留非凭据数据、部署
  forward-fix 并要求重新发起 challenge。
- 所有 rehearsal 只使用 disposable PostgreSQL 16；不得写 `blacksheep_dev`。

## 5. 固定测试矩阵

### PostgreSQL / Java

- canonical 大小写、首尾空白、Unicode/控制字符、超长和重复归属。
- 两类注册都在验证前不创建 user；匿名验证既有账户失败关闭。
- 密码策略一致、旧 hash 登录后 rehash、passwordless LOCAL 写入拒绝。
- JSON login、query/form/重复字段/content type/超长输入拒绝和单次 BCrypt。
- 唯一 active challenge、HMAC key id、常量时间验证、retry CAS 和并发消费。
- outbox 提交前后故障、重复 worker、accepted 恢复、最终失败和过期。
- limiter 多线程/多实例竞争、故障和清理。
- security event 同事务、失败回滚及数据库不可变性。

### Shell HTTP E2E

- 真实 UniAuth、真实参考邮件服务、两个 disposable PostgreSQL 16。
- 注册和密码重置跨进程成功、重启、worker 重试和 delivery status reconciliation。
- 统一 forgot-password 外部语义、oracle endpoint 关闭和 JSON-only 登录。
- Flyway fresh/upgrade/preflight、shared schema、backup inventory 和 baseline guard。

### Playwright

- 现有邮箱注册和密码重置 UX 使用 challenge handle。
- 注册 DTO 只保留在页面内存，刷新/失败后不持久保存 password。
- 未注册和已注册邮箱的外部失败语义一致。
- 不新增邮箱验证码登录或新页面。

## 6. 固定验收顺序

1. 先补充会失败的 PostgreSQL 集成测试、Shell E2E、Playwright 和 migration guard。
2. 按 S1-S4 实现，S5 收敛迁移和全链路证据。
3. 运行定向测试并执行 `PYTHON_BIN=python3 scripts/verify.sh`。
4. 按当时规则，硬门槛全部通过后即确认 F1 验收完成，不在 F1 内执行连续三轮
   无修改检查。
5. 更新 live 文档和本记录，提交所有非 ignored、非敏感修改并推送；原计划由 F5 在
   F1-F5 全部完成后统一执行阶段末三轮检查。
6. 原计划将 F1 进度更新为 86% 后进入 F2，并禁止因非阻断发现新增 F1 子批或第六
   加固批次；F2-F5 现已停止自动启动。

## 7. 实施与验收证据

F1 已完成 S1-S5 的固定范围：

- UniAuth V6 增加 canonical contact identity、challenge digest/delivery/usage
  状态、唯一 active challenge、transactional outbox、PostgreSQL 认证限流和
  append-only security event；只读 V6 preflight 在 rehearsal 与 apply 前均执行。
- 登录、注册、验证码发送/验证和密码重置共用 canonical email、统一密码策略、
  typed JSON DTO、credential authentication、token issuance facade 与共享限流。
- 验证码数据库列改为带 key id 的 HMAC digest，旧明文 code 和 credential metadata
  在 no-return migration 中退役；公开 status/check-code oracle 已关闭。
- 邮件服务 V4 增加 idempotency key/request fingerprint；UniAuth outbox 可通过稳定
  key 重试并查询 delivery status，accepted、恢复、终态失败和重启路径均有自动化覆盖。
- shared-schema 双方 peer inventory、schema fingerprint、backup/restore inventory
  和 Flyway grouped migration guard 已同步到 UniAuth V1-V6、邮件 V1-V4。

2026-08-09 验收证据：

- `PYTHON_BIN=python3 TESTCONTAINERS_RYUK_DISABLED=true scripts/verify.sh`：
  完整 `12/12` 阶段通过，并以
  `PASS: complete repository verification gate` 结束。
- UniAuth Maven `212/212`；邮件组件 Maven `150/150`。
- shared-schema E2E `4/4`；根 HTTP/PostgreSQL/Flyway/Web3/email E2E `16/16`；
  根 Flyway baseline guard `16/16`。
- 邮件 runtime guard `44/44`、HTTP/PostgreSQL E2E `11/11`、Flyway guard
  `15/15`、backup/restore rehearsal `10/10`。
- 前端 ESLint、TypeScript 和生产构建通过；Mock Playwright `28/28`，真实邮箱注册/
  登录跨服务 Playwright `1/1`。
- 邮件 REST stub contract `12/12`；Python 资源服务器 `18/18`；53 个 Markdown
  文件的相对链接检查与 `git diff --check` 通过。
- 完整门禁首次运行发现邮件 Flyway guard 在隔离快照中依赖根 POM。测试基础设施已
  最小修复为邮件组件自包含 Flyway Maven 插件并在组件目录执行；受影响 guard
  `15/15` 通过后，从头重跑完整统一门禁并通过。

本轮没有连接共享 `blacksheep_dev`、真实 SMTP/OAuth provider 或高成本外部服务。
React Router 仍有 2 个 moderate 公告，自动修复要求 breaking major upgrade；该
依赖升级保持在已冻结 F4 范围，不阻断 F1 验收。

F1 按阶段规则只以自动化验收收口，没有执行单轮连续三轮无修改检查。后续用户已明确
启动并完成 F2，F1-F5 的唯一一次连续三轮检查继续延后到五批全部完成之后统一执行。

### 2026-08-09 Post-F1 邮件参考服务 V5 收尾

该并行收尾不改写上面的 F1 V4 验收历史。邮件服务新增 forward-only V5，
清空历史 `email_logs.email_content`，将历史 `COMPLETED`/`FAILED` 队列 HTML
替换为 `<redacted/>` 并清空 metadata，再用 PostgreSQL check constraint 固定后续
写入。运行时在成功或永久失败状态转换时执行同样脱敏；`PENDING`/`PROCESSING`
仍保留真实 HTML，以支持 SMTP 投递和重试。共享 schema peer inventory、Shell
Flyway/HTTP E2E、ApplicationContext/PostgreSQL/GreenMail 测试和选择性
backup/restore inventory 同步到邮件 V1-V5。

本次完整统一门禁已经通过：根 Maven `212/212`、邮件 Maven `154/154`，其余
shared-schema、HTTP、Flyway、Playwright、Python、前端和文档门槛均通过。该结果是
V5 独立收尾时的历史证据；V5 与 F2 合并后仍必须重跑组合门禁，不能继承两边各自的
成功状态。按当前阶段规则，V5 不单独启动三轮检查计数器。

## 8. 检查记录

本文实施记录只保存 F1 和 post-F1 V5 收尾的执行与验收证据，不建立单批检查计数器。
F1-F5 全部完成并通过统一阶段门禁后，才对整体实现执行唯一一次连续三轮无修改检查；
无问题轮次不为留痕修改仓库文件。
