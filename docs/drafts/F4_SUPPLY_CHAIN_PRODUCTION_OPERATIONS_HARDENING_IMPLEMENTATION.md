# F4 供应链、生产配置与运维门禁实施记录

> 状态：Completed；F5 统一门禁已通过
> 规划日期：2026-08-09
> 固定进度口径：F3 完成后约 95%；F4 完成后约 98%
> 上位范围：`FINAL_HARDENING_EXIT_PLAN.md` 的 F4

## 1. 边界

1. 只加固依赖、构建、生产配置、密钥加载、健康探针、代理边界和运维演练，不增加
   provider、认证方式、用户流程或业务功能。
2. 保持 Java 17 和 PostgreSQL 16-only；Spring Boot 只升级到仍支持 Java 17 的
   3.5 维护线，不进入 Java 21、Boot 4 或框架重写。
3. 所有数据库验证使用 disposable PostgreSQL；不连接或写入 `blacksheep_dev`。
4. 不调用真实 OAuth、SMTP 或高成本外部服务；依赖下载可临时使用本机代理，但代理
   地址不写入仓库。
5. 不丢弃、回滚或 stash 其他人的修改；提交时用 `git add -A` 纳入全部非 ignored、
   非敏感、非生成修改。
6. F4 通过定向测试和完整统一门禁验收，不执行单批三轮无修改检查；唯一阶段末三轮
   检查在 F1-F5 全部完成后执行。

## 2. 已验证起点

- Python 示例仍使用未锁 hash 的 `requirements.txt`，当前 `pip-audit` 对 6 个直接
  依赖报告 35 条已知漏洞；全局 Python 环境还存在依赖漂移警告。
- 前端 `react-router-dom 6.30.4` 有 2 个 moderate 公告，修复线为 `7.18.2`；
  high/critical 为 0。
- 两个 Maven 工程仍是 Spring Boot 3.3.4，未配置 Maven Enforcer 或可重复的漏洞审计。
- GitHub Actions 仍使用浮动 major tag；PostgreSQL 测试镜像仍使用浮动 `postgres:16`。
- `AuthorizationServerConfig` 默认暴露固定 `auth-client`、`{noop}auth-secret`、
  localhost redirect、password grant 和无 PKCE client。
- `prod` 仍可继承 placeholder issuer/audience/kid、local HMAC/limiter/introspection
  secret 和工作目录 RSA 自动生成路径。
- OAuth provider user-info 手工请求使用无显式 connect/read timeout 的
  `RestTemplate`。
- 根应用尚无最小 liveness/readiness 门禁；现有邮件 `health` 只证明进程存活。
- Docker Hub 通过当前网络代理访问时出现证书替换错误；本机已验证的 PostgreSQL
  镜像实际版本为 16.8，因此本批先固定明确 patch tag，并把镜像更新作为显式维护动作。

## 3. 固定实施切片

### F4.1 供应链与可重复构建

1. 使用 `requirements.in` 维护 Python 直接依赖，生成带 hash 的精确 lock；统一门禁
   在仓库外创建隔离 venv，以 `--require-hashes` 安装并对同一 lock 执行 `pip-audit`。
2. 升级 Python 直接依赖到无未豁免漏洞的兼容版本，运行完整资源服务器契约。
3. 升级 React Router 到修复版本，运行 lint、TypeScript、生产构建和完整 Playwright。
4. 两个 Maven 工程升级到 Spring Boot 3.5 维护线并增加 Enforcer：最低 Java 17、
   Maven 3.9、重复依赖和 dependency convergence 失败关闭。
5. 两个 Maven 工程固定 OWASP Dependency-Check 版本、CVSS 阈值和报告存在性；
   网络、数据库更新、扫描或报告缺失失败关闭。
6. GitHub Actions 固定 action commit SHA；PostgreSQL 测试镜像固定明确 16.x patch。

### F4.2 生产配置、授权服务器与密钥

1. 默认关闭 Spring Authorization Server 配置，不暴露伪可用 fixed client；若以后
   支持，必须作为独立功能重新定义 client、grant、PKCE、secret 和生命周期。
2. 增加生产配置 guard：Web3 domain/URI、JWT issuer/audience/kid、验证码 HMAC key、
   limiter/introspection secret、frontend/CORS/callback 和启用 provider 凭据必须
   非 placeholder、无 localhost、满足 HTTPS/长度/唯一性约束。
3. `prod` 禁止自动生成 RSA key，禁止仓库工作目录内的私钥路径；必须加载已存在、
   owner-only 的外部 key material。dev/test 保留隔离本地自动生成。
4. 自定义 JWT、JWKS 和 verifier 继续使用同一 active key/kid；增加 key 配置、权限、
   缺失、损坏和 kid 漂移测试。
5. OAuth user-info 与邮件/JWKS 类出站请求使用配置化 connect/read timeout、TLS
   校验和有限边界；黑洞/慢响应不能无限占用线程。

### F4.3 Readiness、代理边界与运维演练

1. 增加最小 liveness/readiness；readiness 只有在 Context、Flyway、数据库和 signing
   key 可用时成功，公开响应不显示数据库地址、异常或密钥信息。
2. 固定生产 forwarded-header/trusted proxy 行为、请求/header/cookie 上限和 Swagger/
   诊断路由关闭；伪造 header 不能改变 redirect、Secure Cookie、限流来源或审计来源。
3. 在 disposable PostgreSQL 执行 fresh/upgrade、backup/restore、forward-fix、
   pre-F1 compatibility fixture 和 key rotation/revoke rehearsal；不实现 Flyway down。
4. 增加 secret/private-key/full-token scan，并验证报告缺失、扫描失败和过期例外
   都会使统一门禁失败。

## 4. 验收矩阵

- Python hash lock 安装、lock 漂移、hash 篡改、漏洞发现和审计网络失败。
- Maven Enforcer、两个工程漏洞扫描、报告存在性和依赖升级回归。
- npm 全级别 audit、lint、typecheck、production build、Mock/production Playwright。
- prod ApplicationContext 配置拒绝矩阵、Authorization Server 默认关闭、RSA
  缺失/权限/路径/kid 和出站 timeout。
- liveness/readiness、trusted proxy/直连、诊断路由、secret scan 和 disposable
  PostgreSQL 恢复/轮换演练。
- 完整 `PYTHON_BIN=python3 scripts/verify.sh` 通过，且新增供应链阶段没有静默 skip。

## 5. 退出门槛

1. F4.1-F4.3 固定范围已实现，定向门槛通过。
2. F5 已对组合工作树运行完整统一门禁并核验供应链报告。
3. live 文档和本记录已进入 F5 收敛更新；提交推送在阶段末检查后统一执行。
4. F4 不执行单批三轮无修改检查；F5 已完成，当前进入阶段末统一检查。

## 6. 2026-08-09 实施证据

- 两个 Maven 工程升级到 Spring Boot 3.5.16，Maven Enforcer 和 OWASP
  Dependency-Check 12.2.2 已接入；根项目 94 个、邮件项目 54 个 dependency
  evidence 完成扫描。CVSS 7 阻断，报告缺失、扫描失败和 suppression 过期均
  fail closed。
- 唯一 Maven suppression 精确约束 Tomcat 10.1.57 的 examples-only
  `CVE-2026-66299`，UTC 到期日为 2026-09-01；应用和测试证明未打包 examples。
- Python 使用人类维护 input 与带 hash 的 runtime/tools lock；隔离 venv 安装、
  lock/hash/audit 失败矩阵和资源服务器契约纳入统一入口。
- Python `cryptography 48.0.1` 当前有 3 个精确、限时例外：
  `PYSEC-2026-3552`、`PYSEC-2026-3553`、`PYSEC-2026-3554`。对应 PKCS#7/S/MIME
  解密和 X.509 chain/name-constraint 路径不被当前 JWT/JWKS 示例使用；固定版本、
  owner、不可达证据和 2026-10-01 UTC 到期日由统一门禁失败关闭校验。
- React Router 固定到 7.18.2；前端 audit、lint、typecheck、production build、
  Mock/production Playwright 纳入门禁。
- CI action 使用精确 commit SHA，所有 PostgreSQL 自动化固定到
  `postgres:16.13`；不支持 PostgreSQL 15。
- 生产配置 guard、RSA 外部路径/权限、出站 OAuth timeout、readiness、forwarded
  header、header/cookie/form 上限和 Swagger/diagnostics 关闭均有 Java 集成测试。
  OAuth timeout 覆盖 authorization-code token endpoint、标准 user-info 和 GitHub/X
  补充 profile 请求，并验证成功 token 解析与慢响应失败边界。
- `AuthorizationServerConfig` 不再注册内存 client，也不匹配整个 `/oauth2/**`；
  JWKS/introspection 保留，未支持的 AS endpoint deny all，OAuth Client 的
  `/oauth2/authorization/{provider}` 继续进入正确安全链。
- 敏感扫描 fail-closed 自测 8/8；源码、候选 Java class 和前端静态构建使用同一
  规则，当前无例外。
- `scripts/test-auth-backup-restore-rehearsal.sh` 6/6 通过，覆盖 V1-V8、owner-only
  archive/checksum、损坏 archive 拒绝、隔离恢复和恢复后 Session/token 失效。
- `scripts/test-http-e2e.sh` 17/17 通过，新增单 active key 的紧急 rotation/revoke
  演练；旧 token 失效，新 kid 经 JWKS 发布，新 token 可用。
- 当前只实现紧急单 key cutover，不实现双 key 无感兼容窗口；该发布能力进入加固后
  backlog，不通过恢复 retired key 或放宽 kid 校验规避。
