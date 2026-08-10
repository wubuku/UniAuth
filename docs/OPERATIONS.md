# UniAuth 运维基线

> 状态：Live
> 核验日期：2026-08-09
> 范围：现有 PostgreSQL/Flyway、生产配置、健康探针、备份恢复、签名密钥和供应链门禁。

## 运维边界

- 当前运行时只支持 PostgreSQL 16；自动化固定使用 `postgres:16.13`。
- Flyway V1-V8 是 UniAuth schema 的唯一 owner，history table 是
  `uniauth_flyway_schema_history`。已发布 migration 不得改写。
- 不对 `blacksheep_dev` 执行自动 migration、restore 或 baseline apply。该库仍只允许
  已授权的只读 rehearsal；写入需要用户单独授权和精确 confirmation token。
- 默认门禁不调用真实 OAuth provider、SMTP 或高成本外部服务。
- 本页命令只使用 disposable PostgreSQL 或只读源码扫描，不隐式读取 `.env`。

## 生产启动前检查

生产 profile 的 `ProductionConfigurationGuard` 会在启动阶段拒绝：

- localhost、保留域名、非 HTTPS frontend/CORS/email/provider callback/JWT issuer。
- placeholder 或过短的 JWT audience/kid、provider client 和内部 client 标识。
- 小于 32 字符、重复或 placeholder 的数据库、限流、introspection、邮件 API 和
  验证码 HMAC secret。
- `jwt.rsa.generate-if-missing=true`、相对 key path、工作目录内 key path。
- diagnostics、access-token JSON 暴露、Swagger/OpenAPI、forwarded-header 信任。
- 非 `16KB` header 上限或非 `1MB` form/swallow 上限。

生产 RSA key 文件必须已经存在，使用仓库外绝对路径，并在 POSIX 文件系统上保持
owner read/write。不要把真实路径、key、secret 或 `.env` 内容写入日志和文档。

当前生产 forwarded-header 策略是 `none`。应用直接忽略
`Forwarded`/`X-Forwarded-*`，redirect 使用显式 callback/frontend 配置，限流使用真实
直连来源。若部署在反向代理后，由边缘负责清除外部 forwarded header、固定 canonical
host/proto 并限制后端只接受受控网络连接；不要通过把 Spring 策略改为 `framework`
来临时信任任意上游。

## 健康探针

公开探针：

```text
GET /actuator/health/liveness
GET /actuator/health/readiness
```

- liveness 只表示 Spring 进程状态。
- readiness 同时要求 readiness state、数据库、Flyway 无 pending migration 和当前
  signing key/kid 可用。
- 响应只公开聚合 `UP`/`DOWN`，不返回 JDBC URL、异常、组件清单或密钥信息。
- `/actuator/health` 是唯一公开的 Actuator 类别；生产 Swagger、OpenAPI 和
  diagnostics 路由关闭。

## Schema 与恢复

标准验证：

```bash
scripts/test-flyway-baseline-guard.sh
scripts/test-auth-backup-restore-rehearsal.sh
```

认证数据恢复演练在 disposable PostgreSQL 16.13 中：

1. 运行 Flyway V1-V8 并写入合成 user、login method、Session 和 token family。
2. 创建 `0600` custom archive/checksum，先验证 archive 可读取。
3. 拒绝损坏 archive。
4. 恢复到独立空数据库并比较 migration、identity、Session attribute 和 token metadata。
5. 使用恢复库前删除全部 Session、revoke 未撤销 token family，并递增用户
   `token_security_version`。

该脚本证明恢复步骤可执行，不是生产备份产品。真实备份仍需在仓库外提供加密、访问
控制、保留、销毁、异地副本和恢复审批。不得用 `pg_restore --clean` 覆盖未知现有库。

已发布 migration 不提供 Flyway down。失败处理使用停止流量、保留现场、从已验证备份
恢复非凭据数据、使旧 Session/token/key 失效并部署 forward-fix。

邮件参考服务有独立的选择性备份工具和 rehearsal，见
[邮件服务参考实现](../reference/email-service/README.md)。shared-uniauth 布局的邮件
组件 archive 不包含 UniAuth 用户/认证表，不能替代整库备份。

## 签名密钥处置

当前实现使用一个 active RSA key/kid。紧急轮换演练由
`scripts/test-http-e2e.sh` 第 16/17 步覆盖：

1. 停止应用并保留待销毁的旧 key 作为受控演练证据。
2. 配置新的外部 key path 和新的 `JWT_KID`。
3. 启动应用，确认 JWKS 只发布新 kid。
4. 确认旧 key 签发的 access token 和 introspection 立即失效。
5. 使用新 key 重新认证并确认新 token 可用。
6. 销毁演练中的 retired key。

这是紧急 revoke/cutover，不是双 key 无感 rollover。当前没有新旧公钥并行发布窗口；
轮换会要求旧会话重新认证。需要零停机兼容窗口时，应作为加固后的独立密钥管理需求
设计和实施，不能恢复已暴露 key 或放宽 kid 校验来模拟兼容。

## 供应链与敏感扫描

统一入口：

```bash
PYTHON_BIN=python3 scripts/verify.sh
```

它在仓库外源码快照中串行执行 15 个阶段，包含：

- Shell/supply-chain/sensitive-scan fail-closed 自测。
- 前端严格 `npm ci`、moderate 级 audit、lint、typecheck、build 和两组 Playwright。
- Python hash lock 安装、`pip-audit` 和资源服务器契约。
- 两个 Maven 工程的 Enforcer、OWASP Dependency-Check、测试和 E2E。
- PostgreSQL shared-schema、HTTP、Flyway、backup/restore。
- 源码、Java class、邮件 class 和 `src/main/resources/static/` 前端候选构建的
  敏感信息扫描。
- 文档相对链接、源码指纹和 patch hygiene。

Maven dependency suppression 必须有 owner、理由和 UTC 到期日；到期、报告缺失、
扫描网络失败或审计命令未执行都会失败关闭。当前唯一 suppression 针对未打包的
Tomcat examples-only CVE，到期日为 2026-09-01；到期前应升级到发布的修复版本并删除
suppression。

Python audit 当前只允许 `cryptography 48.0.1` 的 `PYSEC-2026-3552`、
`PYSEC-2026-3553` 和 `PYSEC-2026-3554`。当前资源服务器不使用对应
PKCS#7/S/MIME 解密或 X.509 chain/name-constraint 路径；例外必须继续精确匹配
包版本与 advisory，并于 2026-10-01 UTC 前升级、替换或重新评估。

敏感扫描例外位于 `config/sensitive-scan-exceptions.json`。例外必须按 finding
fingerprint 精确匹配并包含 owner、充分理由和未来到期日；当前没有例外。

Swagger UI 的当前 Maven WebJar 仍可带来未达到 CVSS 7 阻断线的 DOMPurify medium
公告。生产已关闭 Swagger/OpenAPI，统一门禁仍保留该风险可见性；可用的兼容修复版本
发布后应升级，不得通过无期限 suppression 隐藏。

## 变更后的验证

| 变更 | 至少执行 |
|------|----------|
| migration/schema | Flyway guard、PostgreSQL integration、shared-schema E2E |
| prod 配置/代理边界 | production guard、production HTTP boundary、readiness |
| RSA key/kid/JWT | key-file tests、JWT integration、HTTP key rotation、Python contracts |
| 备份恢复 | `scripts/test-auth-backup-restore-rehearsal.sh` |
| 依赖/lock/CI | supply-chain self-tests 和完整 `scripts/verify.sh` |
| 文档链接 | project-docs relative-link checker 和 `git diff --check` |

详细交付标准见[验证指南](VERIFICATION.md)，配置字段见[配置基线](CONFIGURATION.md)。
