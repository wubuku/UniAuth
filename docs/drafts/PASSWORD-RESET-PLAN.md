# 邮箱登录密码重置功能规划文档

> **文档版本**: 1.1
> **创建日期**: 2026-02-06
> **更新日期**: 2026-02-06
> **功能**: 邮箱登录密码重置
> **依赖服务**: Email Service (Blacksheep-API)

## 1. 功能概述

### 1.1 功能描述

邮箱登录密码重置功能旨在为使用邮箱注册的用户提供安全的密码找回机制。当用户忘记密码时，可以通过以下流程重置密码：

1. 用户输入注册邮箱地址
2. 系统验证邮箱是否存在
3. 系统生成验证码并发送到用户邮箱
4. 用户输入收到的验证码
5. 用户输入新密码
6. 系统验证验证码并更新密码
7. 用户使用新密码登录

### 1.2 与现有系统的关系

本功能建立在以下现有组件基础之上：

| 现有组件 | 复用方式 |
|---------|---------|
| **EmailVerificationCode 实体** | 完全复用，使用 purpose=PASSWORD_RESET |
| **EmailVerificationCodeService** | 复用 sendVerificationCode() 和 verifyCode() |
| **EmailService 接口** | 复用 sendTemplateEmail() 发送密码重置模板 |
| **LoginMethodService** | 复用 findByLocalUsername() 查询用户 |
| **UserRepository** | 复用 findByEmail() 邮箱查询 |
| **PasswordEncoder** | 复用 encode() 密码加密 |

### 1.3 现有组件能力确认

#### 1.3.1 EmailVerificationCode 实体 ✅

```java
@Entity
@Table(name = "email_verification_codes")
public class EmailVerificationCode {
    private String email;
    private String verificationCode;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 50)
    private VerificationPurpose purpose;  // 已包含 PASSWORD_RESET
    
    private Instant expiresAt;
    private Boolean isUsed;
    private Integer retryCount;
}
```

**结论**: ✅ 已有 `purpose` 字段，完全支持 PASSWORD_RESET 用途

#### 1.3.2 EmailVerificationCodeService ✅

```java
public class EmailVerificationCodeService {
    // 发送验证码（支持多种purpose）
    public void sendVerificationCode(String email, VerificationPurpose purpose, Map<String, Object> metadata);
    
    // 验证验证码
    public VerificationResult verifyCode(String email, String code, VerificationPurpose purpose);
    
    // 检查是否可以发送（频率限制）
    public boolean canSend(String email);
    
    // 获取重发冷却时间
    public long getResendCooldown(String email);
}
```

**结论**: ✅ 已有完整验证码管理能力

#### 1.3.3 EmailService 接口 ✅

```java
public interface EmailService {
    EmailSendResult sendTemplateEmail(
        String to,
        String subject,
        String templateName,  // 可指定模板名
        Map<String, Object> variables,
        String emailType
    );
}
```

**结论**: ✅ 支持模板邮件，可发送 password-reset 模板

### 1.3 功能边界

**包含**：
- 忘记密码页面入口
- 请求发送验证码
- 验证验证码
- 重置密码
- 密码重置成功提示

**不包含**：
- 修改已登录用户的密码（Profile页面功能）
- 邮箱绑定/解绑
- 账户锁定和解锁

### 1.4 复用策略总结

本功能采用最大化复用策略，充分利用现有基础设施，避免重复建设：

| 复用层级 | 复用组件 | 复用程度 | 复用方式 |
|---------|---------|---------|---------|
| **实体层** | EmailVerificationCode | 100% | 复用 purpose=PASSWORD_RESET |
| **服务层** | EmailVerificationCodeService | 90% | 复用发送和验证逻辑 |
| **基础设施** | EmailService | 100% | 复用模板邮件能力 |
| **数据层** | UserLoginMethodRepository | 100% | 复用用户查询和密码更新 |
| **配置层** | 应用配置体系 | 100% | 复用现有配置结构 |

**复用收益**：
- 减少约 70% 的代码开发量
- 保持与邮箱注册流程的验证码逻辑一致
- 降低维护成本，修改只需在一处进行
- 降低引入新 bug 的风险

## 2. 技术架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        前端 (React)                               │
├─────────────────────────────────────────────────────────────────┤
│  LoginPage.tsx                                                  │
│  ├── 忘记密码链接 → ForgotPasswordModal                         │
│  ├── 输入邮箱请求验证码                                         │
│  ├── 输入验证码和新密码                                         │
│  └── 提交重置请求                                               │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                      后端 (Spring Boot)                          │
├─────────────────────────────────────────────────────────────────┤
│  ForgotPasswordController.java (新增)                           │
│  ├── POST /api/auth/forgot-password                            │
│  └── POST /api/auth/reset-password                             │
│                                                                  │
│  ForgotPasswordService.java (新增)                              │
│  ├── 验证邮箱是否存在                                           │
│  ├── 生成并发送验证码                                           │
│  └── 验证并重置密码                                            │
│                                                                  │
│  EmailVerificationCodeService.java (复用)                       │
│  ├── 生成验证码                                                 │
│  ├── 验证验证码                                                 │
│  └── 标记验证码已使用                                          │
│                                                                  │
│  UserLoginMethodService.java (复用)                             │
│  └── 更新密码                                                   │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Email Service (外部服务)                       │
├─────────────────────────────────────────────────────────────────┤
│  模板: email/password-reset                                      │
│  变量: username, verificationCode, expiryMinutes                │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 模块依赖关系

```
uniauth (主应用)
├── spring-boot-starter-web
├── spring-boot-starter-security
├── spring-authorization-server
├── spring-data-jpa
├── postgresql (运行时)
│
├── Email Service (外部REST API)
│   └── 端口: 8095 (约定)
│   └── 模板: password-reset
│
└── 内部组件 (复用)
    ├── EmailVerificationCode (实体)
    ├── EmailVerificationCodeService (服务)
    └── EmailService (邮件发送接口)
```

### 2.3 邮箱服务集成

#### 2.3.1 EmailService接口（现有）

当前已实现的EmailService接口：

```java
public interface EmailService {
    EmailSendResult sendTemplateEmail(
        String to,
        String subject,
        String templateName,        // 如 "email/password-reset"
        Map<String, Object> variables,  // 模板变量
        String emailType           // 如 "PASSWORD_RESET"
    );
    
    EmailSendResult sendSimpleEmail(
        String to,
        String subject,
        String htmlContent
    );
    
    boolean isAvailable();
}
```

**结论**: ✅ 接口已支持模板邮件，发送密码重置邮件只需指定模板名

#### 2.3.2 密码重置模板变量

根据Email Service文档，password-reset模板需要以下变量：

| 变量名 | 类型 | 必填 | 说明 | 示例 |
|--------|------|------|------|------|
| username | String | 是 | 用户名 | "wubuku" |
| verificationCode | String | 是 | 验证码 | "123456" |
| expiryMinutes | Integer | 是 | 过期时间(分钟) | 10 |

#### 2.3.3 EmailType枚举

现有EmailSendResult.EmailType已包含PASSWORD_RESET：

```java
public enum EmailType {
    WELCOME,
    EMAIL_VERIFY,
    PASSWORD_RESET,
    SIMPLE
}
```

### 2.4 配置项

在application.yml或application-test.yml中添加：

```yaml
app:
  email:
    service:
      url: ${EMAIL_SERVICE_URL:http://localhost:8095}  # Email Service地址
    verification:
      # 密码重置验证码配置
      password-reset:
        code-length: 6                    # 验证码长度
        expiry-minutes: 10                # 验证码有效期
        max-send-per-day: 5              # 每日最大发送次数
        resend-cooldown-seconds: 60      # 重发冷却时间
        max-retry-attempts: 3             # 最大验证失败次数
```

## 3. 业务流程设计

### 3.1 完整流程图

```
┌──────────────┐     ┌──────────────────────────────────────────────┐
│   用户访问    │     │            忘记密码流程                        │
│ 登录页面     │     └──────────────────────────────────────────────┘
└──────┬───────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────────┐
│  1. 用户点击"忘记密码"链接                                        │
│     → 显示忘记密码弹窗                                            │
└─────────────────────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────────┐
│  2. 用户输入邮箱地址                                              │
│     → 前端验证邮箱格式                                            │
│     → 调用 POST /api/auth/forgot-password                        │
└─────────────────────────────────────────────────────────────────┘
       │
       ▼
       │  ┌───────────────────────────────────────────────────────┐
       │  │ 后端处理:                                              │
       │  │ 1. 验证邮箱格式                                       │
       │  │ 2. 检查邮箱是否已注册 (user_login_methods)             │
       │  │ 3. 检查今日发送次数限制                                │
       │  │ 4. 生成验证码 (6位数字)                                │
       │  │ 5. 保存到 email_verification_codes 表                │
       │  │ 6. 调用Email Service发送邮件                          │
       │  │ 7. 返回结果 (不暴露邮箱是否注册)                       │
       │  └───────────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────────┐
│  3. 系统返回结果                                                  │
│     - 成功: "验证码已发送到邮箱"                                   │
│     - 失败: "请求过于频繁" / "发送失败"                           │
└─────────────────────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────────┐
│  4. 用户输入验证码和新密码                                        │
│     → 前端验证验证码格式 (6位数字)                                │
│     → 前端验证密码复杂度                                         │
│     → 调用 POST /api/auth/reset-password                         │
└─────────────────────────────────────────────────────────────────┘
       │
       ▼
       │  ┌───────────────────────────────────────────────────────┐
       │  │ 后端处理:                                              │
       │  │ 1. 验证请求参数                                        │
       │  │ 2. 查询验证码记录                                      │
       │  │ 3. 检查验证码是否过期                                   │
       │  │ 4. 检查验证码是否已使用                                 │
       │  │ 5. 检查验证失败次数                                     │
       │  │ 6. 验证验证码是否匹配                                  │
       │  │ 7. 标记验证码已使用                                    │
       │  │ 8. 更新用户密码 (BCrypt)                               │
       │  │ 9. 使旧会话失效 (如有)                                 │
       │  │ 10. 返回成功响应                                       │
       │  └───────────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────────────┐
│  5. 密码重置成功                                                  │
│     → 显示成功提示                                                │
│     → 自动跳转到登录页面                                          │
│     → 用户使用新密码登录                                          │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 异常处理流程

#### 3.2.1 验证码发送失败

| 场景 | 处理方式 | 用户提示 |
|-----|---------|---------|
| 邮箱未注册 | 正常处理，不暴露信息 | "如果该邮箱已注册，验证码已发送" |
| 发送次数超限 | 拒绝发送 | "请求过于频繁，请XX分钟后重试" |
| Email Service不可用 | 记录日志，返回错误 | "发送失败，请稍后重试" |
| 邮箱格式无效 | 前端验证 | "请输入有效的邮箱地址" |

#### 3.2.2 验证码验证失败

| 场景 | 处理方式 | 用户提示 |
|-----|---------|---------|
| 验证码过期 | 拒绝，标记过期 | "验证码已过期，请重新获取" |
| 验证码已使用 | 拒绝 | "验证码已使用，请重新获取" |
| 验证码错误 | 记录失败次数 | "验证码错误，剩余X次尝试" |
| 超过最大失败次数 | 锁定，记录日志 | "验证失败次数过多，请重新获取验证码" |

#### 3.2.3 密码重置失败

| 场景 | 处理方式 |
|-----|---------|
| 验证码无效 | 返回错误 |
| 新密码不符合要求 | 返回具体错误 |
| 数据库更新失败 | 回滚事务，返回错误 |
| 并发请求 | 乐观锁处理 |

### 3.3 状态流转

```
验证码状态流转:
┌─────────┐    生成    ┌─────────┐    验证    ┌─────────┐
│  NEW    │ ───────→ │ PENDING │ ───────→ │ USED    │
└─────────┘          └─────────┘          └─────────┘
                            │
                   超时/过期   │
                            ▼
                       ┌─────────┐
                       │ EXPIRED │
                       └─────────┘

失败次数:
每验证失败一次，retry_count + 1
超过阈值(3次) → 验证码失效
```

## 4. 数据库设计

### 4.1 复用现有表 ✅

#### 4.1.1 email_verification_codes表（现有）

**结论**: ✅ 现有表完全满足需求，无需创建新表！

```sql
CREATE TABLE email_verification_codes (
    id                  VARCHAR(36) PRIMARY KEY,
    email               VARCHAR(255) NOT NULL,
    verification_code   VARCHAR(10) NOT NULL,
    purpose             VARCHAR(50) NOT NULL,  -- PASSWORD_RESET
    metadata            TEXT,
    expires_at          TIMESTAMP NOT NULL,
    retry_count         INTEGER DEFAULT 0,
    is_used             BOOLEAN DEFAULT FALSE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 索引
CREATE INDEX idx_email_purpose ON email_verification_codes(email, purpose);
CREATE INDEX idx_expires_at ON email_verification_codes(expires_at);
```

**复用说明**:
- `purpose` 字段已支持枚举值: REGISTRATION, LOGIN, PASSWORD_RESET
- `email` 字段存储用户邮箱
- `verification_code` 字段存储6位数字验证码
- `expires_at` 字段存储过期时间
- `retry_count` 字段用于记录验证失败次数
- `is_used` 字段用于标记验证码是否已使用

#### 4.1.2 约束条件（现有）

```sql
-- 用途枚举约束 (应用级已在实体中实现)
ALTER TABLE email_verification_codes
ADD CONSTRAINT chk_purpose CHECK (
    purpose IN ('REGISTRATION', 'LOGIN', 'PASSWORD_RESET')
);
```

**结论**: ✅ 数据库约束已存在，无需修改

### 4.2 用户表关系

```
users (主用户表)
    │
    │ user_id (UUID)
    ▼
user_login_methods (登录方式表)
    │
    │ id
    ├── user_id (外键 → users.id)
    ├── auth_provider (LOCAL)
    ├── local_username (邮箱地址)
    └── local_password_hash (BCrypt哈希)

    password_reset流程:
    1. 通过 local_username 找到 user_login_method
    2. 通过 user_id 更新 users 表或 user_login_methods 表的密码
```

## 5. API接口设计

### 5.1 重要说明：与现有API的关系

**现有 API**： `/api/auth/reset-password`
- **状态**：仅开发环境可用 (`@Profile("dev")`)
- **功能**：简单的密码重置，无需验证码
- **影响**：生产环境不可用，无冲突

**新 API**（本功能）：
- `/api/auth/forgot-password` - 请求发送验证码（生产环境）
- `/api/auth/verify-reset-code` - 验证验证码并重置密码（生产环境）

### 5.2 请求密码重置验证码

#### 5.2.1 接口信息

| 项目 | 说明 |
|-----|------|
| 端点 | POST /api/auth/forgot-password |
| Content-Type | application/json |
| 认证 | 无需认证 |

#### 5.1.2 请求体

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "email": {
      "type": "string",
      "format": "email",
      "description": "用户注册邮箱地址",
      "maxLength": 255
    }
  },
  "required": ["email"]
}
```

#### 5.1.3 请求示例

```bash
curl -X POST "http://localhost:8082/api/auth/forgot-password" \
  -H "Content-Type: application/json" \
  -d '{"email": "wubuku@163.com"}'
```

#### 5.1.4 成功响应 (200)

```json
{
  "success": true,
  "message": "验证码已发送到邮箱",
  "resendAfter": 60,
  "expiresIn": 600
}
```

| 字段 | 类型 | 说明 |
|-----|------|------|
| success | boolean | 是否成功 |
| message | string | 用户友好的消息 |
| resendAfter | integer | 多少秒后可重发 (60秒) |
| expiresIn | integer | 验证码多少秒后过期 (600秒=10分钟) |

#### 5.1.5 错误响应 (400/500)

```json
{
  "success": false,
  "message": "发送失败，请稍后重试"
}
```

**注意**: 无论邮箱是否注册成功，都返回相同消息，防止邮箱枚举攻击。

### 5.3 验证验证码并重置密码

#### 5.3.1 接口信息

| 项目 | 说明 |
|-----|------|
| 端点 | POST /api/auth/verify-reset-code |
| Content-Type | application/json |
| 认证 | 无需认证 |

#### 5.2.2 请求体

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "email": {
      "type": "string",
      "format": "email",
      "description": "用户邮箱地址"
    },
    "verificationCode": {
      "type": "string",
      "pattern": "^[0-9]{6}$",
      "description": "6位数字验证码"
    },
    "newPassword": {
      "type": "string",
      "minLength": 8,
      "maxLength": 128,
      "description": "新密码"
    }
  },
  "required": ["email", "verificationCode", "newPassword"]
}
```

#### 5.3.3 请求示例

```bash
curl -X POST "http://localhost:8082/api/auth/verify-reset-code" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "wubuku@163.com",
    "verificationCode": "123456",
    "newPassword": "NewPassword123!"
  }'
```

#### 5.3.4 成功响应 (200)

```json
{
  "success": true,
  "message": "密码重置成功，请使用新密码登录"
}
```

#### 5.3.5 错误响应 (400)

```json
{
  "success": false,
  "message": "验证码错误，请检查后重试",
  "remainingAttempts": 2,
  "expiresIn": 300
}
```

| 字段 | 类型 | 说明 |
|-----|------|------|
| success | boolean | 是否成功 |
| message | string | 错误描述 |
| remainingAttempts | integer | 剩余尝试次数 (可选) |
| expiresIn | integer | 验证码剩余有效秒数 (可选) |

#### 5.3.6 错误码定义

| 错误码 | HTTP状态 | 说明 |
|-------|---------|------|
| INVALID_CODE | 400 | 验证码错误 |
| CODE_EXPIRED | 400 | 验证码已过期 |
| CODE_ALREADY_USED | 400 | 验证码已使用 |
| MAX_ATTEMPTS_EXCEEDED | 400 | 超过最大验证失败次数 |
| EMAIL_NOT_FOUND | 400 | 邮箱未注册 |
| PASSWORD_WEAK | 400 | 密码不符合要求 |
| INTERNAL_ERROR | 500 | 服务器内部错误 |

### 5.3 对现有API的修改

#### 5.3.1 POST /api/auth/register (可能需要修改)

**现状**: 用户名是邮箱时，email字段必须为null或相同

**修改**: 无需修改，邮箱注册流程保持不变

#### 5.3.2 POST /api/auth/login (无需修改)

**现状**: 支持用户名/密码登录

**修改**: 无需修改，密码重置后用户可正常登录

## 6. 安全考虑

### 6.1 验证码安全

#### 6.1.1 生成算法

```java
// 使用SecureRandom生成密码学安全的验证码
public String generateVerificationCode(int length) {
    SecureRandom random = new SecureRandom();
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
        sb.append(random.nextInt(10));  // 0-9
    }
    return sb.toString();
}
```

**安全特性**:
- 使用SecureRandom而非Random (密码学安全)
- 6位数字 (1,000,000种可能)
- 默认10分钟有效期

#### 6.1.2 有效期设置

| 场景 | 默认值 | 可配置项 |
|-----|-------|---------|
| 验证码有效期 | 10分钟 | app.email.verification.password-reset.expiry-minutes |
| 重发冷却时间 | 60秒 | app.email.verification.password-reset.resend-cooldown-seconds |
| 每日发送限制 | 5次 | app.email.verification.password-reset.max-send-per-day |
| 最大验证失败次数 | 3次 | app.email.verification.password-reset.max-retry-attempts |

### 6.2 防止恶意请求

#### 6.2.1 频率限制

```java
@Service
public class RateLimitService {
    
    // 每日发送次数限制
    public boolean canSendToday(String email) {
        int todayCount = emailVerificationCodeRepository
            .countByEmailAndPurposeAndCreatedAtAfter(
                email, 
                PASSWORD_RESET, 
                LocalDateTime.now().minusDays(1)
            );
        return todayCount < MAX_SEND_PER_DAY;
    }
    
    // 重发冷却检查
    public boolean canResend(String email) {
        Optional<EmailVerificationCode> latest = 
            emailVerificationCodeRepository
                .findLatestByEmailAndPurpose(email, PASSWORD_RESET);
        if (latest.isEmpty()) return true;
        return latest.get().getCreatedAt()
            .isBefore(LocalDateTime.now().minusSeconds(COOLDOWN_SECONDS));
    }
}
```

#### 6.2.2 防止邮箱枚举

**策略**: 不论邮箱是否注册成功，都返回相同的成功消息

```java
public ResponseEntity<?> forgotPassword(ForgotPasswordRequest request) {
    // 1. 即使邮箱不存在，也继续处理
    // 2. 生成验证码并尝试发送
    // 3. 返回相同的成功消息
    
    // 这样攻击者无法区分"邮箱不存在"和"发送成功"
    return ResponseEntity.ok(Map.of(
        "success", true,
        "message", "验证码已发送到邮箱",
        "resendAfter", 60,
        "expiresIn", 600
    ));
}
```

#### 6.2.3 并发控制

使用数据库乐观锁防止并发重置：

```java
@Entity
public class EmailVerificationCode {
    @Version
    private Long version;
    
    // ... 其他字段
}
```

### 6.3 密码安全

#### 6.3.1 密码复杂度要求

```java
public class PasswordValidator {
    
    public static boolean isValid(String password) {
        if (password == null || password.length() < 8) {
            return false;
        }
        // 至少8个字符，包含字母和数字
        boolean hasLetter = password.matches(".*[a-zA-Z].*");
        boolean hasDigit = password.matches(".*[0-9].*");
        return hasLetter && hasDigit;
    }
}
```

#### 6.3.2 密码存储

```java
// 使用BCrypt加密新密码
public void resetPassword(String email, String newPassword) {
    String hashedPassword = passwordEncoder.encode(newPassword);
    
    // 更新 user_login_methods 表
    userLoginMethodRepository.updatePasswordByEmail(
        email, 
        hashedPassword
    );
}
```

### 6.4 邮件内容安全

#### 6.4.1 使用内置模板

使用Email Service提供的password-reset模板：

```java
Map<String, Object> variables = new HashMap<>();
variables.put("username", getUsernameFromEmail(email));
variables.put("verificationCode", code);
variables.put("expiryMinutes", 10);

emailService.sendPasswordResetEmail(
    email,
    variables
);
```

#### 6.4.2 邮件安全提示

模板中应包含：
- "如果这不是您的请求，请忽略此邮件"
- "验证码有效期10分钟"
- "不要向任何人透露验证码"

## 7. 实施步骤

### 7.1 分阶段计划

#### Phase 1: 后端实现 (1天)

**目标**: 完成所有后端API和业务逻辑

**任务清单**:
```
□ 1.1 创建 ForgotPasswordController.java
       - POST /api/auth/forgot-password
       - POST /api/auth/verify-reset-code
```       
□ 1.2 扩展 LoginMethodService.java
       - 添加 updatePassword(String username, String newPassword) 方法
       - 职责：查询用户登录方式，更新密码哈希
       
□ 1.3 创建 ForgotPasswordService.java
       - 验证邮箱是否存在（调用 LoginMethodService）
       - 调用 EmailVerificationCodeService 发送验证码（purpose=PASSWORD_RESET）
       - 调用 LoginMethodService 更新密码
       
□ 1.4 复用现有组件
       ✅ EmailVerificationCode 实体 (purpose=PASSWORD_RESET)
       ✅ EmailVerificationCodeService.sendVerificationCode()
       ✅ EmailVerificationCodeService.verifyCode()
       ✅ EmailService.sendTemplateEmail() - 使用 password-reset 模板
       ✅ LoginMethodService.findByLocalUsername()
       
□ 1.5 编写单元测试
       - Service层测试
       - Controller层测试
       
□ 1.6 编译验证
       - mvn compile
```

**里程碑**: 后端API可独立测试

**预估工作量**: 由于复用策略，预计减少 70% 代码量

#### Phase 2: 前端实现 (0.5-1天)

**目标**: 完成忘记密码UI组件

**任务清单**:
```
□ 2.1 添加忘记密码链接
       - LoginPage.tsx 添加链接
       
□ 2.2 创建 ForgotPasswordModal.tsx 组件
       - 步骤1: 输入邮箱
       - 步骤2: 输入验证码和新密码
       - 步骤3: 成功提示
       
□ 2.3 修改 authService.ts
       - 添加 forgotPassword(email) 方法
       - 添加 resetPassword(data) 方法
       
□ 2.4 样式集成
       - 保持与现有UI风格一致
       
□ 2.5 编译验证
       - npm run build
```

**里程碑**: 前端可完整演示流程

#### Phase 3: 集成测试 (0.5天)

**目标**: 端到端测试

**任务清单**:
```
□ 3.1 准备测试邮箱
       - 配置Email Service
       - 使用真实邮箱测试
       
□ 3.2 端到端流程测试
       - 请求验证码
       - 接收邮件
       - 输入验证码
       - 重置密码
       - 登录验证
       
□ 3.3 异常场景测试
       - 验证码过期
       - 错误验证码
       - 频繁请求
```

**里程碑**: 所有测试通过

### 7.2 新增/修改文件清单

#### 7.2.1 后端新增

| 文件路径 | 说明 | 复用程度 |
|---------|------|---------|
| controller/ForgotPasswordController.java | 控制器 | 新增 |
| service/ForgotPasswordService.java | 服务层 | 新增 |
| dto/ForgotPasswordRequest.java | 请求DTO | 新增 |
| dto/ResetPasswordRequest.java | 请求DTO | 新增 |

#### 7.2.2 后端复用和新增

| 组件 | 复用/新增 | 说明 |
|-----|---------|------|
| EmailVerificationCode 实体 | 复用 | 使用 purpose=PASSWORD_RESET |
| EmailVerificationCodeService | 复用 | 发送和验证验证码 |
| EmailService | 复用 | 模板邮件能力 |
| LoginMethodService | 复用 | findByLocalUsername() 查询用户 |
| LoginMethodRepository | 复用 | findByLocalUsername() |
| UserRepository | 复用 | findByEmail() |
| PasswordEncoder | 复用 | encode() 加密密码 |
| **LoginMethodService.updatePassword()** | **新增** | 需要添加密码更新方法 |

**重要**: ✅ **无需创建新数据库表**，复用现有 email_verification_codes 表

#### 7.2.3 前端新增

| 文件路径 | 说明 | 影响范围 |
|---------|------|---------|
| components/ForgotPasswordModal.tsx | 组件 | 新增 |
| 需修改: pages/LoginPage.tsx | 添加链接和状态 | 修改 |
| 需修改: services/authService.ts | 添加API方法 | 修改 |

#### 7.2.4 配置文件修改

| 文件 | 修改内容 |
|-----|---------|
| application.yml | 可能需要添加用途特定的配置项 |

### 7.3 回滚策略

| 问题级别 | 恢复命令 | 影响 |
|---------|---------|------|
| 编译错误 | git checkout | 恢复所有变更 |
| API错误 | git checkout -- controller/ | 仅恢复控制器 |
| 业务错误 | git checkout -- service/ | 仅恢复服务层 |
| 严重Bug | git revert commit | 创建新提交回退 |

## 8. 测试策略

### 8.1 单元测试

#### 8.1.1 ForgotPasswordService测试

```java
@ExtendWith(MockitoExtension.class)
class ForgotPasswordServiceTest {
    
    @Mock
    private EmailVerificationCodeRepository codeRepository;
    
    @Mock
    private UserLoginMethodRepository loginMethodRepository;
    
    @Mock
    private EmailService emailService;
    
    @InjectMocks
    private ForgotPasswordService service;
    
    @Test
    void testSendVerificationCode_Success() {
        // 场景: 邮箱存在，发送成功
    }
    
    @Test
    void testSendVerificationCode_EmailNotFound() {
        // 场景: 邮箱不存在，返回相同响应
    }
    
    @Test
    void testSendVerificationCode_RateLimited() {
        // 场景: 超过发送频率限制
    }
    
    @Test
    void testResetPassword_Success() {
        // 场景: 验证码正确，密码重置成功
    }
    
    @Test
    void testResetPassword_CodeExpired() {
        // 场景: 验证码已过期
    }
    
    @Test
    void testResetPassword_WrongCode() {
        // 场景: 验证码错误
    }
}
```

#### 8.1.2 Controller测试

```java
@WebMvcTest(ForgotPasswordController.class)
class ForgotPasswordControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    void testForgotPassword_ValidRequest() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"test@example.com\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }
    
    @Test
    void testForgotPassword_InvalidEmail() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"invalid\"}"))
            .andExpect(status().isBadRequest());
    }
}
```

### 8.2 集成测试

#### 8.2.1 API集成测试

```java
@SpringBootTest
@AutoConfigureMockMvc
class ForgotPasswordIntegrationTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    void testCompletePasswordResetFlow() throws Exception {
        // 1. 发送验证码
        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"test@example.com\"}"))
            .andExpect(status().isOk());
        
        // 2. 获取验证码 (从数据库或邮件)
        String code = getVerificationCode("test@example.com");
        
        // 3. 重置密码
        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "test@example.com",
                        "verificationCode": "%s",
                        "newPassword": "NewPassword123!"
                    }
                    """.formatted(code)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }
}
```

### 8.3 安全测试要点

| 测试项 | 说明 | 预期结果 |
|-------|------|---------|
| 邮箱枚举攻击 | 快速请求多个邮箱 | 都返回"发送成功" |
| 验证码暴力破解 | 快速提交错误验证码 | 3次后锁定 |
| 并发重置 | 两个请求同时验证同一验证码 | 只有一个成功 |
| CSRF攻击 | 跨站请求 | 需要验证Referer |
| XSS注入 | 邮箱/密码包含特殊字符 | 被正确转义 |

### 8.4 性能测试考虑

| 指标 | 目标值 |
|-----|-------|
| API响应时间 | < 500ms |
| 验证码发送吞吐量 | > 10/秒 |
| 并发重置请求 | > 100/秒 |

## 9. 风险评估与应对措施

### 9.1 技术风险

| 风险 | 概率 | 影响 | 应对措施 |
|-----|------|------|---------|
| Email Service不可用 | 中 | 高 | 添加熔断机制，返回友好错误 |
| 验证码生成性能问题 | 低 | 低 | 使用SecureRandom，轻量操作 |
| 数据库连接池耗尽 | 低 | 高 | 添加连接超时，限制并发 |
| 乐观锁冲突 | 低 | 低 | 重试机制 |

### 9.2 业务风险

| 风险 | 影响 | 应对措施 |
|-----|------|---------|
| 用户收不到邮件 | 高 | 提供重发机制，提示检查垃圾箱 |
| 邮件进入垃圾箱 | 中 | 优化发件人信誉，添加SPF/DKIM |
| 用户忘记注册邮箱 | 低 | 提供备用验证方式(可选) |

### 9.3 安全风险

| 风险 | 影响 | 应对措施 |
|-----|------|---------|
| 恶意用户频繁请求 | 中 | 频率限制，IP限流 |
| 验证码暴力破解 | 高 | 失败次数限制，验证码复杂度 |
| CSRF攻击 | 中 | CSRF Token验证 |
| 密码重置链接泄露 | 高 | 一次性使用，HTTPS |

### 9.4 缓解策略

```
风险缓解优先级:
1. 设计阶段: 架构设计避免已知风险
2. 开发阶段: 代码审查发现潜在问题
3. 测试阶段: 安全测试验证
4. 运维阶段: 监控告警及时响应
```

## 10. 验收标准

### 10.1 功能验收

- [ ] POST /api/auth/forgot-password 正常工作
- [ ] POST /api/auth/reset-password 正常工作
- [ ] 验证码发送和验证流程正常
- [ ] 密码重置后旧密码失效
- [ ] 前端UI交互正常

### 10.2 安全验收

- [ ] 验证码无法暴力破解 (3次锁定)
- [ ] 频率限制生效
- [ ] 邮箱枚举攻击无效
- [ ] 验证码一次性使用
- [ ] 密码复杂度验证

### 10.3 性能验收

- [ ] API响应时间 < 500ms
- [ ] 验证码发送 < 2秒
- [ ] 无内存泄漏

### 10.4 兼容性验收

- [ ] 现有登录功能不受影响
- [ ] 邮箱注册功能不受影响
- [ ] OAuth2登录不受影响

## 11. 附录

### 11.1 相关文档

- Email Service README: /Users/yangjiefeng/Documents/wubuku/Blacksheep-API/src/email-service/README.md
- 邮箱注册规划文档: docs/drafts/EMAIL-REGISTRATION-PLAN.md
- 项目进度文档: docs/drafts/project-progress.md

### 11.2 参考代码

- 现有邮箱验证码实现: src/main/java/.../service/EmailVerificationCodeService.java
- 现有EmailService接口: src/main/java/.../service/email/EmailService.java
- Web3 Nonce实现: src/main/java/.../service/Web3NonceService.java

### 11.3 修订历史

| 版本 | 日期 | 作者 | 说明 |
|-----|------|-----|------|
| 1.0 | 2026-02-06 | AI | 初稿 |
