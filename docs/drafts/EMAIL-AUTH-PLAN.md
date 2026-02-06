# 邮箱注册与验证登录功能规划文档

**文档版本**: 1.5  
**创建日期**: 2026-02-06  
**更新日期**: 2026-02-06  
**作者**: UniAuth 开发团队  
**状态**: 待审核（新增：前端演示项目规划章节）

---

## 1. 功能概述

### 1.1 背景与目标

UniAuth 作为一个通用的多方式认证系统，目前支持以下登录方式：
- 本地用户名/密码登录
- Web3 钱包登录（以太坊）
- OAuth2 社交登录（Google、GitHub、Twitter/X）

本功能的目标是**增加邮箱注册与验证登录方式**，允许用户使用邮箱地址作为用户名进行注册和登录，并通过邮箱验证码完成身份验证。

### 1.2 功能描述

#### 1.2.1 邮箱注册流程

```
用户输入邮箱地址 → 系统验证邮箱格式 → 系统检查邮箱是否已被注册
    ↓
发送验证码邮件 → 用户查收邮件并输入验证码 → 系统验证验证码
    ↓
验证成功 → 完成注册流程
```

#### 1.2.2 邮箱登录流程

```
用户输入邮箱地址和密码 → 系统检测到邮箱格式的用户名
    ↓
查询用户是否存在 → 检查邮箱是否已验证
    ↓
如果未验证 → 提示用户进行邮箱验证
    ↓
发送验证码邮件 → 用户输入验证码 → 验证通过
    ↓
完成登录
```

### 1.3 与现有系统的关系

#### 1.3.1 与用户名/密码登录的关系

本功能**不是完全替换**现有的用户名/密码登录，而是**扩展**其功能：

- **现有流程保持不变**：普通用户名（非邮箱格式）仍然可以直接注册和登录
- **邮箱用户名增强**：当用户名是邮箱格式时，强制要求邮箱验证
- **渐进式验证**：用户可以先完成注册，然后通过邮箱验证来激活账户

#### 1.3.2 require-email-username 配置

通过配置项 `app.email.registration.require-email-username` 控制：

| 配置值 | 行为 | 适用场景 |
|-------|------|---------|
| `false`（默认） | 渐进式策略：普通用户名可直接注册登录，邮箱用户名需验证 | 系统迁移过渡期、兼容现有用户 |
| `true` | 强制邮箱策略：只有邮箱地址可以作为用户名，必须通过邮箱验证 | 新系统上线、强制邮箱验证策略 |

> **重要说明**：此配置**仅影响普通用户名（非邮箱格式）的本地注册/登录**。不影响 Web3、OAuth2 等其他登录方式的使用。

### 1.4 核心业务规则

| 规则编号 | 规则描述 | 优先级 |
|---------|---------|-------|
| BR-001 | 用户名如果是合法邮箱格式，必须通过邮箱验证才能完成注册/登录 | P0 |
| BR-002 | 邮箱验证码有效期为 10 分钟，过期后需要重新发送 | P0 |
| BR-003 | 同一邮箱每天最多发送 10 封验证码邮件 | P1 |
| BR-004 | 验证码输入错误超过 5 次，该验证码立即失效 | P1 |
| BR-005 | 同一邮箱只能绑定一个账户 | P0 |
| BR-006 | 用户注册时 email 字段必须与用户名（邮箱）一致 | P0 |

---

## 2. 技术架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        UniAuth Application                       │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                     Controller Layer                       │  │
│  │  ┌───────────────┐  ┌─────────────────┐  ┌─────────────┐ │  │
│  │  │AuthController │  │EmailAuth        │  │ Other       │ │  │
│  │  │(existing)     │  │Controller(new)  │  │Controllers │ │  │
│  │  └───────────────┘  └─────────────────┘  └─────────────┘ │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                     Service Layer                          │  │
│  │  ┌─────────────────────────────────────────────────────┐  │  │
│  │  │           EmailVerificationService                   │  │  │
│  │  │  ┌───────────────────────────────────────────────┐ │  │  │
│  │  │  │              EmailService (Interface)         │ │  │  │
│  │  │  │  ┌─────────────────────────────────────────┐ │ │  │  │
│  │  │  │  │   RestTemplateEmailServiceImpl         │ │ │  │  │
│  │  │  │  │   (Email Service 微服务客户端)           │ │ │  │  │
│  │  │  │  └─────────────────────────────────────────┘ │ │  │  │
│  │  │  └───────────────────────────────────────────────┘ │  │  │
│  │  └─────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                     Repository Layer                      │  │
│  │  ┌───────────────────────────┐  ┌─────────────────────┐ │  │
│  │  │EmailVerificationCode       │  │ Existing           │ │  │
│  │  │Repository (new)          │  │ Repositories       │ │  │
│  │  └───────────────────────────┘  └─────────────────────┘ │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                     Entity Layer                           │  │
│  │  ┌───────────────────────────┐  ┌─────────────────────┐ │  │
│  │  │EmailVerificationCode     │  │ Existing            │ │  │
│  │  │Entity (new)             │  │ Entities          │ │  │
│  │  └───────────────────────────┘  └─────────────────────┘ │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │               External Services                            │  │
│  │  ┌─────────────────────────────────────────────────────┐  │  │
│  │  │         Email Service (Blacksheep API)             │  │  │
│  │  │         POST /api/email/template                   │  │  │
│  │  └─────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 EmailService 接口设计

为了确保未来可以无缝替换邮件服务实现，我们需要设计一个抽象层（接口）来封装邮件发送服务。

#### 2.2.1 EmailService 接口

```java
package org.dddml.uniauth.service.email;

/**
 * 邮件发送服务接口
 * 提供统一的邮件发送抽象，支持多种实现
 */
public interface EmailService {
    
    /**
     * 发送模板邮件
     * 
     * @param to 收件人邮箱
     * @param subject 邮件主题
     * @param templateName 模板名称（如 "email/email-verify"）
     * @param variables 模板变量
     * @param emailType 邮件类型（用于日志记录）
     * @return 发送结果
     */
    EmailSendResult sendTemplateEmail(
        String to,
        String subject,
        String templateName,
        Map<String, Object> variables,
        String emailType
    );
    
    /**
     * 发送简单邮件（HTML 内容）
     * 
     * @param to 收件人邮箱
     * @param subject 邮件主题
     * @param htmlContent HTML 内容
     * @return 发送结果
     */
    EmailSendResult sendSimpleEmail(
        String to,
        String subject,
        String htmlContent
    );
    
    /**
     * 检查邮件服务是否可用
     * 
     * @return 是否可用
     */
    boolean isAvailable();
}
```

#### 2.2.2 EmailSendResult 枚举

```java
package org.dddml.uniauth.service.email;

/**
 * 邮件发送结果枚举
 */
public enum EmailSendResult {
    SUCCESS,      // 发送成功
    QUEUED,       // 已入队，等待发送
    FAILED,       // 发送失败
    RATE_LIMITED, // 被限流
    INVALID_EMAIL // 邮箱地址无效
}
```

#### 2.2.3 RestTemplateEmailServiceImpl 实现

使用 Spring 的 `RestTemplate` 调用外部 Email Service 微服务（项目已在 `SecurityConfig` 中配置了 `RestTemplate` bean）：

```java
package org.dddml.uniauth.service.email.impl;

import org.dddml.uniauth.service.email.EmailService;
import org.dddml.uniauth.service.email.EmailSendResult;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于 RestTemplate 的 Email Service 实现
 * 集成 Blacksheep Email Service 微服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestTemplateEmailServiceImpl implements EmailService {
    
    @Value("${app.email.service.url:http://localhost:8095}")
    private String emailServiceUrl;
    
    private final RestTemplate restTemplate;  // 使用 SecurityConfig 中配置的 RestTemplate
    
    @Override
    public EmailSendResult sendTemplateEmail(
            String to,
            String subject,
            String templateName,
            Map<String, Object> variables,
            String emailType) {
        
        String url = emailServiceUrl + "/api/email/template";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        Map<String, Object> body = new HashMap<>();
        body.put("to", to);
        body.put("subject", subject);
        body.put("templateName", templateName);
        body.put("variables", variables);
        body.put("emailType", emailType);
        
        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> responseBody = response.getBody();
                if (Boolean.TRUE.equals(responseBody.get("success"))) {
                    log.info("Email sent successfully to: {}, queueId: {}", 
                        to, responseBody.get("queueId"));
                    return EmailSendResult.QUEUED;
                }
            }
            
            log.error("Failed to send email to: {}, response: {}", to, response.getBody());
            return EmailSendResult.FAILED;
            
        } catch (Exception e) {
            log.error("Exception while sending email to: {}", to, e);
            return EmailSendResult.FAILED;
        }
    }
    
    @Override
    public boolean isAvailable() {
        try {
            String healthUrl = emailServiceUrl + "/api/email/health";
            ResponseEntity<Map> response = restTemplate.getForEntity(healthUrl, Map.class);
            return response.getStatusCode().is2xxSuccessful() 
                && "UP".equals(response.getBody().get("status"));
        } catch (Exception e) {
            log.warn("Email service health check failed", e);
            return false;
        }
    }
}
```

#### 2.2.4 邮件发送日志服务

记录每次邮件发送的详细信息，用于统计和限流（复用 `email_verification_codes` 表）：

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailSendLogService {
    
    private final EmailVerificationCodeRepository emailVerificationCodeRepository;
    
    /**
     * 检查今日发送次数
     * 通过查询 email_verification_codes 表实现
     */
    @Transactional(readOnly = true)
    public long countTodaySends(String email) {
        LocalDate today = LocalDate.now();
        Instant startOfDay = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        return emailVerificationCodeRepository.countByEmailAndCreatedAtAfter(email, startOfDay);
    }
    
    /**
     * 检查最近一小时的发送次数
     */
    @Transactional(readOnly = true)
    public long countRecentSends(String email, int minutes) {
        Instant since = Instant.now().minus(minutes, ChronoUnit.MINUTES);
        return emailVerificationCodeRepository.countByEmailAndCreatedAtAfter(email, since);
    }
}
```

**注意**：不再需要单独的 `EmailSendLog` 实体和表。

### 2.3 配置项设计

在 `application.yml` 中添加邮件服务配置：

```yaml
app:
  email:
    service:
      url: ${EMAIL_SERVICE_URL:http://localhost:8095}  # Email Service 微服务地址
      timeout: 5000  # HTTP 超时时间（毫秒）
    verification:
      code-length: 6  # 验证码长度
      expiry-minutes: 10  # 验证码有效期（分钟）
      max-send-per-day: 10  # 每邮箱每天最大发送次数
      max-retry-attempts: 5  # 最大验证失败次数
      resend-cooldown-seconds: 60  # 重新发送冷却时间（秒）
    # 注册配置
    registration:
      # 是否强制使用邮箱作为用户名
      # true: 只有邮箱地址可以作为用户名，必须通过邮箱验证
      # false: 允许普通用户名（非邮箱格式）注册和登录
      require-email-username: false
```

---

## 3. 业务流程设计

### 3.1 邮箱注册完整流程

#### 3.1.1 流程图

```
┌─────────────┐     ┌───────────────────────┐     ┌─────────────────────┐
│   User       │     │   Backend             │     │   Email Service     │
└──────┬──────┘     └───────────┬───────────┘     └──────────┬──────────┘
       │                          │                           │
       │  1. POST /api/auth/register                          │
       │  (username=邮箱, password=密码, ...)               │
       ├──────────────────────────────────────────────────────>│
       │                          │                           │
       │                          │  2. 验证邮箱格式          │
       │                          │  检查用户名是否已存在    │
       │                          │                           │
       │                          │  3. POST /api/email/send-verification
       │                          ├──────────────────────────>│
       │                          │                           │
       │                          │  4. 发送验证码邮件        │
       │                          │  (使用 email-verify 模板) │
       │                          │                           │
       │                          │  5. 返回发送结果         │
       │                          │<──────────────────────────┤
       │                          │                           │
       │                          │  6. 创建验证码记录        │
       │                          │  (包含注册元数据)        │
       │                          │                           │
       │  7. 返回待验证状态        │                           │
       │<─────────────────────────┤                           │
       │                          │                           │
       │  8. POST /api/auth/verify-email                      │
       │  (verificationCode=xxx)   │                           │
       ├──────────────────────────────────────────────────────>│
       │                          │                           │
       │                          │  9. 验证验证码            │
       │                          │                           │
       │                          │  10. 从 metadata 提取注册信息
       │                          │      创建用户            │
       │                          │                           │
       │  11. 返回注册成功         │                           │
       │<─────────────────────────┤                           │
       │                          │                           │
```

#### 3.1.2 详细步骤说明

| 步骤 | 操作 | 说明 |
|-----|------|-----|
| 1 | 用户提交注册信息 | 包含邮箱地址作为用户名、密码、显示名等 |
| 2 | 后端验证 | 检查邮箱格式、检查用户名是否已存在 |
| 3 | 发送验证码 | 调用邮件服务发送验证码 |
| 4 | 邮件发送 | Email Service 使用 email-verify 模板发送验证码 |
| 5 | 返回结果 | 告知用户验证码已发送，请查收邮件 |
| 6 | 创建验证码记录 | 在 `email_verification_codes` 表中创建记录，包含注册元数据（密码哈希、显示名） |
| 7 | 响应用户 | 返回 success + 待验证状态 |
| 8 | 用户提交验证码 | 用户输入收到的验证码 |
| 9 | 验证验证码 | 检查验证码是否正确且未过期 |
| 10 | 创建用户/绑定邮箱 | 验证通过后：<br>- 如果已有用户：绑定邮箱登录方式<br>- 如果新用户：创建 User + LOCAL 登录方式 |
| 11 | 完成注册 | 返回用户信息和 JWT Token |

### 3.1.3 设计理由：验证通过前不创建用户记录

**核心原则：邮箱验证通过之前，不创建 User 记录，不创建 UserLoginMethod 记录。**

**原因分析：**

1. **避免与已有用户产生复杂关联**
   - 如果邮箱没有验证通过，但已创建 User 记录并与其他登录方式（如 SSO）关联，可能产生安全漏洞
   - 例如：恶意用户可能尝试绑定他人已拥有的邮箱

2. **避免阻塞合法用户**
   - 如果提前创建用户记录，可能阻止邮箱的合法拥有者创建/绑定用户账号
   - 验证码记录是临时的、可过期的，不会产生持久占用

3. **简化边界条件处理**
   - 不需要处理"未验证邮箱与已验证邮箱的优先级"
   - 不需要处理"邮箱与用户的多种关联状态"
   - 不需要处理"临时用户的清理策略"

**最终设计：**

```
┌─────────────────────────────────────────────────────────────────┐
│                     邮箱注册流程最终设计                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   1. 用户提交邮箱注册              2. 发送验证码（仅验证码记录）    │
│      ┌──────────────┐               ┌──────────────────┐       │
│      │ 验证邮箱格式  │               │ email_verific   │       │
│      │ 检查重复      │──────────────▶│ ation_codes 表  │       │
│      └──────────────┘   不创建       │ + metadata       │       │
│                          User        └──────────────────┘       │
│                                                                 │
│   3. 用户输入验证码              4. 验证通过后创建记录            │
│      ┌──────────────┐               ┌──────────────────┐       │
│      │ 验证验证码    │               │ 已存在用户        │       │
│      │ 检查过期      │──────────────▶│ → 绑定邮箱登录方式│       │
│      └──────────────┘               │                   │       │
│                          新用户     │ 新用户            │       │
│                          ─────────▶│ → 创建 User       │       │
│                                     │ → 创建 LOCAL 登录  │       │
│                                     └──────────────────┘       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**与已有用户的处理：**

如果用户已通过 SSO 等方式注册，邮箱验证通过后，只给该用户**绑定**邮箱登录方式（`local_username = email`，`local_password_hash = null`），而不是创建新用户。

### 3.2 与现有用户名/密码注册的交互

#### 3.2.1 统一的注册入口

```java
@PostMapping("/register")
public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
    boolean isEmailUsername = isValidEmail(request.getUsername());
    boolean requireEmailUsername = appConfig.getRequireEmailUsername();
    
    // 根据配置决定处理逻辑
    if (isEmailUsername) {
        // 邮箱用户名：进入邮箱注册流程
        return handleEmailRegistration(request);
    } else {
        // 非邮箱用户名
        if (requireEmailUsername) {
            // 强制邮箱模式：禁止普通用户名注册
            return ResponseEntity.badRequest()
                .body(Map.of(
                    "error", "REGISTRATION_NOT_ALLOWED",
                    "message", "Only email addresses are allowed for registration",
                    "requireEmailUsername", true
                ));
        } else {
            // 渐进式模式：允许普通用户名注册
            return handleNormalRegistration(request);
        }
    }
}
```

#### 3.2.2 统一的登录入口

```java
@PostMapping("/login")
public ResponseEntity<?> login(@RequestBody LoginRequest request) {
    boolean isEmailUsername = isValidEmail(request.getUsername());
    boolean requireEmailUsername = appConfig.getRequireEmailUsername();
    
    if (isEmailUsername) {
        // 邮箱用户名：检查是否需要验证
        return handleEmailLogin(request);
    } else {
        // 非邮箱用户名
        if (requireEmailUsername) {
            // 强制邮箱模式：禁止普通用户名登录
            return ResponseEntity.badRequest()
                .body(Map.of(
                    "error", "LOGIN_NOT_ALLOWED",
                    "message", "Only email addresses are allowed for login",
                    "requireEmailUsername", true
                ));
        } else {
            // 渐进式模式：允许普通用户名登录
            return handleNormalLogin(request);
        }
    }
}
```

#### 3.2.3 业务规则与配置联动

| 场景 | require-email-username | 行为 |
|-----|---------------------|------|
| 用户名=普通字符串 | false | 允许注册/登录 |
| 用户名=普通字符串 | true | 拒绝注册/登录 |
| 用户名=邮箱格式 | false | 需要邮箱验证 |
| 用户名=邮箱格式 | true | 需要邮箱验证 |

> **注意**：此配置**仅影响普通用户名的本地注册/登录**。Web3 钱包登录、OAuth2 社交登录等不受此配置影响。

#### 3.2.4 邮箱注册流程

```java
private ResponseEntity<?> handleEmailRegistration(RegisterRequest request) {
    // 1. 检查邮箱是否已被注册
    if (userRepository.findByEmail(request.getEmail()).isPresent()) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "Email already registered"));
    }
    
    // 2. 检查用户名（邮箱）是否已被使用
    if (loginMethodRepository.existsByLocalUsername(request.getUsername())) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "Username already exists"));
    }
    
    // 3. 生成验证码
    String verificationCode = generateVerificationCode();
    
    // 4. 准备注册元数据（存储在验证码记录中，验证成功后创建用户）
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("password", passwordEncoder.encode(request.getPassword()));
    metadata.put("displayName", request.getDisplayName());
    
    // 5. 创建验证码记录（包含注册元数据）
    EmailVerificationCode codeRecord = EmailVerificationCode.builder()
        .id(UUID.randomUUID().toString())
        .email(request.getUsername())
        .verificationCode(verificationCode)
        .purpose(VerificationPurpose.REGISTRATION)
        .metadata(objectMapper.writeValueAsString(metadata))
        .expiresAt(Instant.now().plusSeconds(VERIFICATION_EXPIRY_SECONDS))
        .isUsed(false)
        .retryCount(0)
        .build();
    
    emailVerificationCodeRepository.save(codeRecord);
    
    // 6. 发送验证码邮件
    emailVerificationCodeService.sendVerificationCode(
        request.getUsername(), 
        verificationCode
    );
    
    // 7. 返回成功响应
    return ResponseEntity.ok(Map.of(
        "message", "Verification code sent",
        "username", request.getUsername(),
        "expiresIn", VERIFICATION_EXPIRY_SECONDS
    ));
}
```

**验证成功后创建用户：**

```java
private ResponseEntity<?> handleEmailVerificationComplete(String email, String code) {
    // 1. 获取验证码记录
    EmailVerificationCode codeRecord = emailVerificationCodeRepository
        .findByEmailAndPurposeAndIsUsedFalse(email, VerificationPurpose.REGISTRATION)
        .orElseThrow(() -> new IllegalArgumentException("Verification code not found"));
    
    // 2. 验证验证码
    if (!codeRecord.getVerificationCode().equals(code)) {
        codeRecord.incrementRetryCount();
        emailVerificationCodeRepository.save(codeRecord);
        throw new IllegalArgumentException("Invalid verification code");
    }
    
    // 3. 验证过期
    if (codeRecord.isExpired()) {
        throw new IllegalArgumentException("Verification code expired");
    }
    
    // 4. 检查是否已有用户使用此邮箱（可能通过SSO等方式注册）
    Optional<UserEntity> existingUser = userRepository.findByEmail(email);
    
    if (existingUser.isPresent()) {
        // 4.1 邮箱已被使用：给已有用户绑定邮箱登录方式
        UserEntity user = existingUser.get();
        bindEmailLoginMethod(user, email);
        
        // 4.3 标记验证码已使用
        codeRecord.setIsUsed(true);
        emailVerificationCodeRepository.save(codeRecord);
        
        // 4.4 返回用户信息和Token
        return createLoginResponse(user);
        
    } else {
        // 4.2 新用户：创建User和UserLoginMethod
        UserEntity user = createUserWithEmailLogin(email, codeRecord.getMetadata());
        
        // 标记验证码已使用
        codeRecord.setIsUsed(true);
        emailVerificationCodeRepository.save(codeRecord);
        
        // 返回用户信息和Token
        return createLoginResponse(user);
    }
}

/**
 * 给已有用户绑定邮箱登录方式
 */
private void bindEmailLoginMethod(UserEntity user, String email) {
    // 检查是否已经绑定了该邮箱的登录方式
    boolean alreadyBound = user.getLoginMethods().stream()
        .anyMatch(lm -> lm.getAuthProvider() == AuthProvider.LOCAL 
                     && email.equalsIgnoreCase(lm.getLocalUsername()));
    
    if (alreadyBound) {
        log.info("User {} already has email login method bound: {}", user.getId(), email);
        return;
    }
    
    // 检查邮箱是否已被其他用户用作 username
    if (loginMethodRepository.existsByLocalUsername(email)) {
        throw new IllegalArgumentException("Email already registered as username by another user");
    }
    
    // 直接创建邮箱登录方式（不使用 addLocalLoginMethod，因为该方法不允许重复添加 LOCAL 方式）
    UserLoginMethod emailLoginMethod = UserLoginMethod.builder()
        .id(UUID.randomUUID().toString())
        .user(user)
        .authProvider(AuthProvider.LOCAL)
        .localUsername(email)
        .localPasswordHash(null)  // 邮箱登录不需要密码（验证码已验证身份）
        .isPrimary(false)  // 保持原有的主登录方式不变
        .isVerified(true)  // 邮箱已验证
        .build();
    
    user.addLoginMethod(emailLoginMethod);
    userRepository.save(user);
    
    log.info("Bound email login method to existing user: userId={}, email={}", user.getId(), email);
}

/**
 * 创建新用户（仅邮箱登录方式）
 */
private UserEntity createUserWithEmailLogin(String email, String metadataJson) {
    // 从metadata提取注册信息
    Map<String, Object> metadata = objectMapper.readValue(
        metadataJson != null ? metadataJson : "{}", 
        new TypeReference<Map<String, Object>>() {}
    );
    
    String displayName = (String) metadata.getOrDefault("displayName", extractDisplayNameFromEmail(email));
    String passwordHash = (String) metadata.get("password");
    
    // 创建User
    UserEntity user = new UserEntity();
    user.setId(UUID.randomUUID().toString());
    user.setUsername(email);
    user.setEmail(email);
    user.setDisplayName(displayName);
    user.setPasswordHash(passwordHash);
    user.setEnabled(true);
    user.setEmailVerified(true);
    user.setAuthorities(Set.of("ROLE_USER"));
    
    // 创建LOCAL登录方式
    UserLoginMethod loginMethod = UserLoginMethod.builder()
        .id(UUID.randomUUID().toString())
        .user(user)
        .authProvider(AuthProvider.LOCAL)
        .localUsername(email)
        .localPasswordHash(passwordHash)
        .isPrimary(true)
        .isVerified(true)
        .build();
    
    user.addLoginMethod(loginMethod);
    userRepository.save(user);
    
    log.info("Created new user with email login: userId={}, email={}", user.getId(), email);
    return user;
}
```

### 3.3 异常处理流程

#### 3.3.1 验证码过期

```
用户输入验证码 → 系统检查验证码记录
    ↓
验证码已过期（expires_at < 当前时间）
    ↓
返回错误："Verification code expired, please request a new one"
    ↓
用户可重新发送验证码
```

#### 3.3.2 验证码错误

```
用户输入验证码 → 系统检查验证码记录
    ↓
验证码不匹配
    ↓
增加失败尝试次数
    ↓
失败次数 < 5：返回错误提示，告知剩余尝试次数
    ↓
失败次数 >= 5：使验证码失效，返回错误
```

#### 3.3.3 邮件发送失败

```
系统调用 Email Service
    ↓
返回失败或异常
    ↓
记录错误日志
    ↓
返回用户："Failed to send verification email, please try again"
    ↓
提供重试机制（限制重试频率）
```

---

## 4. 数据库设计

### 4.1 数据表设计

#### 4.1.1 邮箱验证码表（email_verification_codes）

此表用于存储所有类型的验证码，包括：
- 注册验证（REGISTRATION）
- 登录验证（LOGIN）
- 密码重置（PASSWORD_RESET）

通过 `purpose` 字段区分不同类型。

**注意**：此表是**临时性**的，验证码过期后会被清理。User 记录和 UserLoginMethod 记录只在验证码验证**通过后**才创建。

```sql
-- 邮箱验证码存储表
CREATE TABLE IF NOT EXISTS email_verification_codes (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    verification_code VARCHAR(10) NOT NULL,
    purpose VARCHAR(50) NOT NULL,           -- REGISTRATION, LOGIN, PASSWORD_RESET
    metadata TEXT,                          -- JSON格式，存储注册元数据（仅REGISTRATION类型使用）
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    retry_count INTEGER DEFAULT 0,         -- 验证失败次数
    is_used BOOLEAN DEFAULT FALSE,        -- 是否已使用
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 邮箱索引（用于快速查找和统计发送次数）
CREATE INDEX IF NOT EXISTS idx_email_verification_codes_email 
    ON email_verification_codes(email);

-- 过期时间索引（用于清理过期记录）
CREATE INDEX IF NOT EXISTS idx_email_verification_codes_expires_at 
    ON email_verification_codes(expires_at);

-- 验证码索引（用于快速验证）
CREATE INDEX IF NOT EXISTS idx_email_verification_codes_code 
    ON email_verification_codes(verification_code);

-- 唯一索引：未使用的验证码（按邮箱+用途）
CREATE UNIQUE INDEX IF NOT EXISTS idx_email_verification_codes_unused 
    ON email_verification_codes(email, purpose) 
    WHERE is_used = FALSE;

-- 创建时间索引（用于统计发送次数）
CREATE INDEX IF NOT EXISTS idx_email_verification_codes_created_at 
    ON email_verification_codes(created_at);
```

**metadata 字段说明（仅 REGISTRATION 类型使用）：**

| 字段 | 类型 | 说明 |
|-----|------|-----|
| password | String | 密码哈希（BCrypt） |
| displayName | String | 用户显示名 |
| locale | String | 用户偏好语言 |

示例：
```json
{
  "password": "$2a$10$N9qo8uLOickgx2ZMRZoMye...",
  "displayName": "张三",
  "locale": "zh-CN"
}
```

#### 4.1.2 邮件发送日志（简化方案）

**删除单独的 `email_send_logs` 表**，原因：
1. `email_verification_codes` 表的 `created_at` 字段可用于统计发送次数
2. 底层邮件服务已有详细日志
3. 发送失败时验证码不会创建，应用日志已足够

**发送次数统计**：通过查询 `email_verification_codes` 表实现

### 4.2 实体类设计

#### 4.2.1 EmailVerificationCode 实体

```java
package org.dddml.uniauth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_verification_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailVerificationCode {
    
    @Id
    @Column(length = 36)
    private String id;
    
    @Column(nullable = false, length = 255)
    private String email;
    
    @Column(name = "verification_code", nullable = false, length = 10)
    private String verificationCode;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 50)
    private VerificationPurpose purpose;
    
    @Column(columnDefinition = "TEXT")
    private String metadata;  // JSON格式，存储注册元数据
    
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    
    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;
    
    @Column(name = "is_used")
    @Builder.Default
    private Boolean isUsed = false;
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    
    @PrePersist
    protected void onCreate() {
        if (id == null || id.trim().isEmpty()) {
            id = UUID.randomUUID().toString();
        }
    }
    
    /**
     * 验证码用途枚举
     */
    public enum VerificationPurpose {
        REGISTRATION,    // 注册验证
        LOGIN,          // 登录验证
        PASSWORD_RESET  // 密码重置
    }
    
    /**
     * 检查验证码是否过期
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
    
    /**
     * 检查验证码是否可用
     */
    public boolean isUsable() {
        return !Boolean.TRUE.equals(isUsed) && !isExpired();
    }
    
    /**
     * 增加失败尝试次数
     */
    public void incrementRetryCount() {
        this.retryCount = (this.retryCount == null ? 0 : this.retryCount) + 1;
    }
}
```

---

## 5. API接口设计

### 5.1 新增接口列表

| 方法 | 路径 | 描述 | 认证 |
|-----|------|-----|-----|
| POST | `/api/auth/send-verification-code` | 发送邮箱验证码 | 否 |
| POST | `/api/auth/verify-email` | 验证邮箱验证码 | 否 |
| POST | `/api/auth/register-with-email` | 邮箱注册（简化版） | 否 |
| POST | `/api/auth/login-with-email` | 邮箱登录 | 否 |
| GET | `/api/auth/email/status/{email}` | 检查邮箱验证状态 | 否 |

### 5.2 详细接口规范

#### 5.2.1 发送验证码

```
POST /api/auth/send-verification-code
```

**请求体：**

```json
{
  "email": "user@example.com",
  "purpose": "REGISTRATION",  // REGISTRATION | LOGIN | PASSWORD_RESET
  "captcha": "abc123"  // 可选：验证码防止恶意请求
}
```

**成功响应 (200)：**

```json
{
  "success": true,
  "message": "Verification code sent successfully",
  "expiresIn": 600,  // 有效期（秒）
  "resendAfter": 60  // 多少秒后可重新发送
}
```

**错误响应 (400）：**

```json
{
  "success": false,
  "error": "EMAIL_ALREADY_VERIFIED",
  "message": "This email has already been verified",
  "errorCode": "EMAIL_ALREADY_VERIFIED"
}
```

**429 Too Many Requests (限流)：**

```json
{
  "success": false,
  "error": "RATE_LIMITED",
  "message": "Too many requests, please try again later",
  "retryAfter": 60
}
```

#### 5.2.2 验证验证码

```
POST /api/auth/verify-email
```

**请求体：**

```json
{
  "email": "user@example.com",
  "verificationCode": "123456"
}
```

**成功响应 (200)：**

```json
{
  "success": true,
  "message": "Email verified successfully",
  "userId": "uuid-string",
  "username": "user@example.com"
}
```

**错误响应 (400)：**

```json
{
  "success": false,
  "error": "INVALID_CODE",
  "message": "Invalid or expired verification code",
  "remainingAttempts": 3
}
```

---

## 6. 安全考虑

### 6.1 验证码生成算法

使用安全的随机数生成器，生成6位数字验证码。

### 6.2 验证码有效期设置

| 场景 | 有效期 |
|-----|-------|
| 注册验证 | 10 分钟 |
| 登录验证 | 5 分钟 |
| 密码重置 | 15 分钟 |
| 重新发送 | 60 秒 |

### 6.3 防止恶意请求措施

- 发送频率限制（每日10次/邮箱）
- 验证失败次数限制（5次后锁定）
- 重新发送冷却时间（60秒）

### 6.4 require-email-username 安全配置

#### 6.4.1 配置说明

```java
@Configuration
@ConfigurationProperties(prefix = "app.email.registration")
public class EmailRegistrationProperties {
    
    /**
     * 是否强制使用邮箱作为用户名
     */
    private boolean requireEmailUsername = false;
    
    /**
     * 是否在响应中暴露配置
     */
    private boolean exposeConfigToClient = true;
    
    // Getters and Setters
    public boolean isRequireEmailUsername() {
        return requireEmailUsername;
    }
    
    public void setRequireEmailUsername(boolean requireEmailUsername) {
        this.requireEmailUsername = requireEmailUsername;
    }
    
    public boolean isExposeConfigToClient() {
        return exposeConfigToClient;
    }
    
    public void setExposeConfigToClient(boolean exposeConfigToClient) {
        this.exposeConfigToClient = exposeConfigToClient;
    }
}
```

#### 6.4.2 安全影响分析

| 配置场景 | 安全影响 | 建议 |
|---------|---------|------|
| require-email-username = false | 普通用户名可能与邮箱用户名混淆 | 默认配置，适合迁移期 |
| require-email-username = true | 统一使用邮箱，增强安全性 | 生产环境推荐 |
| 配置变更时 | 从 false 切换到 true 时，需处理现有普通用户名用户 | 提供迁移宽限期 |

#### 6.4.3 配置变更安全措施

1. **审计日志**：记录所有被拒绝的普通用户名注册/登录尝试
2. **监控告警**：当普通用户名被拒绝时发送告警
3. **配置变更告警**：当配置变更时发送安全告警

---

## 7. 实施步骤

### 7.1 阶段一：基础设施建设

- [ ] 创建 `email_verification_codes` 表
- [ ] 创建 `email_send_logs` 表
- [ ] 创建 `EmailVerificationCode` 实体类
- [ ] 创建 `EmailSendLog` 实体类
- [ ] 创建 Repository 接口

### 7.2 阶段二：邮件服务集成

- [ ] 创建 `EmailService` 接口
- [ ] 创建 `RestTemplateEmailServiceImpl` 实现
- [ ] 创建 `EmailVerificationCodeService` 服务
- [ ] 在 `application.yml` 中添加配置

### 7.3 阶段三：API 接口开发

- [ ] 创建 `EmailAuthController` 控制器
- [ ] 实现 `POST /api/auth/send-verification-code`
- [ ] 实现 `POST /api/auth/verify-email`
- [ ] 修改现有 `AuthController` 支持邮箱格式用户名

### 7.4 阶段四：测试

- [ ] 单元测试
- [ ] 集成测试
- [ ] 安全测试

---

## 8. 测试策略

### 8.1 单元测试

- 验证码生成测试
- 验证码验证测试
- 频率限制测试

### 8.2 集成测试

- 全流程测试（注册 → 发送验证码 → 验证 → 登录）
- 异常场景测试（验证码过期、错误次数限制等）

### 8.3 安全测试

- 验证码暴力破解防护测试
- 频率限制测试
- 配置变更影响测试

---

## 9. 风险评估与应对措施

### 9.1 技术风险

| 风险 | 影响 | 应对措施 |
|-----|------|---------|
| 邮件服务不可用 | 无法发送验证码 | 实现降级策略、缓存验证码 |
| 数据库性能问题 | 注册/登录响应慢 | 合理索引、定期清理过期数据 |
| 并发安全问题 | 验证码验证不正确 | 使用事务、乐观锁 |

### 9.2 业务风险

| 风险 | 影响 | 应对措施 |
|-----|------|---------|
| 垃圾邮件注册 | 系统资源浪费 | 发送频率限制、验证码机制 |
| 用户无法收到邮件 | 无法完成注册 | 提供重新发送功能、详细帮助文档 |

---

## 10. Repository 接口设计

### 10.1 EmailVerificationCodeRepository

```java
@Repository
public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, String> {
    
    Optional<EmailVerificationCode> findByEmailAndPurposeAndIsUsedFalse(
        String email, 
        VerificationPurpose purpose
    );
    
    List<EmailVerificationCode> findByEmail(String email);
    
    List<EmailVerificationCode> findByExpiresAtBeforeAndIsUsedFalse(Instant now);
    
    @Modifying
    @Query("DELETE FROM EmailVerificationCode e WHERE e.expiresAt < :now")
    int deleteExpiredCodes(@Param("now") Instant now);
    
    boolean existsByEmailAndPurposeAndIsUsedFalse(String email, VerificationPurpose purpose);
    
    /**
     * 统计指定时间后该邮箱的发送次数
     */
    long countByEmailAndCreatedAtAfter(String email, Instant since);
}
```

**注意**：不再需要 `EmailSendLogRepository`，发送统计通过查询 `EmailVerificationCodeRepository` 实现。

---

## 11. 前端演示项目规划

### 11.1 设计原则

前端演示项目（frontend）采用**最小化、保守的修改**策略：

| 原则 | 说明 |
|-----|------|
| 最小改动 | 只在必要的位置添加邮箱验证相关功能 |
| 渐进增强 | 现有功能保持不变，只增加新行为的检测和响应 |
| 复用组件 | 利用现有的 `LoginPage`、`useAuth` hook、`authService` |
| 风格一致 | 保持与现有代码相同的样式和交互模式 |

### 11.2 前端项目结构

```
frontend/
├── src/
│   ├── pages/
│   │   └── LoginPage.tsx      # 主要修改：邮箱验证UI
│   ├── services/
│   │   └── authService.ts     # 新增：邮箱验证API方法
│   ├── hooks/
│   │   └── useAuth.ts         # 修改：支持邮箱验证流程
│   └── types/
│       └── index.ts           # 新增：邮箱验证相关类型
```

### 11.3 新增类型定义

```typescript
// frontend/src/types/email.ts (新增文件)

export interface EmailVerificationStatus {
  email: string;
  hasPendingVerification: boolean;
  expiresAt?: string;  // ISO 8601 格式
}

export interface SendVerificationCodeRequest {
  email: string;
  purpose: 'REGISTRATION' | 'LOGIN' | 'PASSWORD_RESET';
}

export interface SendVerificationCodeResponse {
  success: boolean;
  message: string;
  expiresIn: number;   // 有效期（秒）
  resendAfter: number; // 多少秒后可重新发送
}

export interface VerifyEmailRequest {
  email: string;
  verificationCode: string;
}

export interface VerifyEmailResponse {
  success: boolean;
  message: string;
  userId?: string;
  username?: string;
}

// 修改 existing types/index.ts
export interface User {
  // ... 现有字段
  emailVerified?: boolean;  // 新增：邮箱是否已验证
}
```

### 11.4 新增API服务方法

```typescript
// frontend/src/services/authService.ts 新增方法

/**
 * 检查邮箱验证状态
 * 用于在输入邮箱后显示是否有待验证的验证码
 */
static async getEmailStatus(email: string): Promise<EmailVerificationStatus> {
  try {
    const response = await axios.get(`${API_BASE_URL}/api/auth/email/status/${encodeURIComponent(email)}`);
    return response.data;
  } catch (error) {
    console.error('Get email status error:', error);
    // 如果API未实现，返回默认状态
    return { email, hasPendingVerification: false };
  }
}

/**
 * 发送邮箱验证码
 */
static async sendVerificationCode(request: SendVerificationCodeRequest): Promise<SendVerificationCodeResponse> {
  try {
    const response = await axios.post(`${API_BASE_URL}/api/auth/send-verification-code`, request);
    return response.data;
  } catch (error) {
    console.error('Send verification code error:', error);
    throw this.handleApiError(error, '发送验证码失败');
  }
}

/**
 * 验证邮箱验证码
 */
static async verifyEmail(request: VerifyEmailRequest): Promise<VerifyEmailResponse> {
  try {
    const response = await axios.post(`${API_BASE_URL}/api/auth/verify-email`, request);
    return response.data;
  } catch (error) {
    console.error('Verify email error:', error);
    throw this.handleApiError(error, '验证码验证失败');
  }
}
```

### 11.5 LoginPage.tsx 修改方案

#### 11.5.1 核心修改点

```typescript
// frontend/src/pages/LoginPage.tsx

import { useState, useEffect, useCallback } from 'react';
import { useAuth } from '../hooks/useAuth';
import { AuthService } from '../services/authService';

interface LoginPageState {
  // ... 现有状态
  // 新增状态
  showEmailVerification: boolean;        // 显示邮箱验证UI
  verificationEmail: string;             // 正在验证的邮箱
  verificationCode: string;                // 用户输入的验证码
  verificationLoading: boolean;            // 验证码操作loading
  verificationCountdown: number;          // 重新发送倒计时
  verificationError: string | null;       // 验证码错误信息
}

export default function LoginPage() {
  const { user, oauthLogin, localLogin, register, loading, error } = useAuth();
  const [state, setState] = useState<LoginPageState>({
    // ... 初始化
    showEmailVerification: false,
    verificationEmail: '',
    verificationCode: '',
    verificationLoading: false,
    verificationCountdown: 0,
    verificationError: null,
  });

  // 邮箱格式检测
  const isEmailFormat = useCallback((username: string): boolean => {
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    return emailRegex.test(username);
  }, []);

  // 输入变化时检测是否需要邮箱验证
  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    
    if (name === 'username') {
      // 检测是否是邮箱格式
      if (isEmailFormat(value)) {
        // 检查是否有待验证的验证码
        checkPendingVerification(value);
      }
    }
    
    setFormData({ ...formData, [name]: value });
  };

  // 检查是否有待验证的验证码
  const checkPendingVerification = async (email: string) => {
    try {
      const status = await AuthService.getEmailStatus(email);
      if (status.hasPendingVerification) {
        // 显示验证码输入UI
        setState(prev => ({
          ...prev,
          showEmailVerification: true,
          verificationEmail: email,
        }));
      }
    } catch (err) {
      // 静默处理，API可能未实现
    }
  };

  // 发送验证码
  const handleSendVerificationCode = async () => {
    if (!state.verificationEmail) return;
    
    setState(prev => ({ ...prev, verificationLoading: true, verificationError: null }));
    
    try {
      const response = await AuthService.sendVerificationCode({
        email: state.verificationEmail,
        purpose: isRegisterMode ? 'REGISTRATION' : 'LOGIN',
      });
      
      // 启动倒计时
      startCountdown(response.resendAfter);
      
      // 显示成功提示
      setSuccessMessage(`验证码已发送到 ${state.verificationEmail}，请查收邮件`);
      
    } catch (err) {
      const message = err instanceof Error ? err.message : '发送验证码失败';
      setState(prev => ({ ...prev, verificationError: message }));
    } finally {
      setState(prev => ({ ...prev, verificationLoading: false }));
    }
  };

  // 验证码倒计时
  const startCountdown = (seconds: number) => {
    setState(prev => ({ ...prev, verificationCountdown: seconds }));
    
    const timer = setInterval(() => {
      setState(prev => {
        if (prev.verificationCountdown <= 1) {
          clearInterval(timer);
          return { ...prev, verificationCountdown: 0 };
        }
        return { ...prev, verificationCountdown: prev.verificationCountdown - 1 };
      });
    }, 1000);
  };

  // 提交验证码
  const handleVerifyEmail = async (e: React.FormEvent) => {
    e.preventDefault();
    
    if (!state.verificationCode || state.verificationCode.length !== 6) {
      setState(prev => ({ ...prev, verificationError: '请输入6位验证码' }));
      return;
    }
    
    setState(prev => ({ ...prev, verificationLoading: true, verificationError: null }));
    
    try {
      const response = await AuthService.verifyEmail({
        email: state.verificationEmail,
        verificationCode: state.verificationCode,
      });
      
      if (response.success) {
        setState(prev => ({
          ...prev,
          showEmailVerification: false,
          verificationCode: '',
        }));
        
        setSuccessMessage('邮箱验证成功！');
        
        // 如果是注册流程，自动登录
        if (isRegisterMode) {
          await localLogin(state.verificationEmail, formData.password);
        }
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : '验证码验证失败';
      setState(prev => ({ ...prev, verificationError: message }));
    } finally {
      setState(prev => ({ ...prev, verificationLoading: false }));
    }
  };

  return (
    <div style={...}>
      {/* ... 现有UI保持不变 ... */}

      {/* 新增：邮箱验证UI */}
      {state.showEmailVerification && (
        <div style={verificationModalStyle}>
          <h3>邮箱验证</h3>
          <p>请输入发送到 {state.verificationEmail} 的6位验证码</p>
          
          <form onSubmit={handleVerifyEmail}>
            <input
              type="text"
              value={state.verificationCode}
              onChange={(e) => setState(prev => ({ 
                ...prev, 
                verificationCode: e.target.value.replace(/\D/g, '').slice(0, 6)
              }))}
              placeholder="请输入6位验证码"
              maxLength={6}
              style={verificationInputStyle}
            />
            
            {state.verificationError && (
              <div style={{ color: 'red', fontSize: '14px' }}>
                ❌ {state.verificationError}
              </div>
            )}
            
            <button
              type="submit"
              disabled={state.verificationLoading || state.verificationCode.length !== 6}
              style={{
                ...submitButtonStyle,
                opacity: (state.verificationLoading || state.verificationCode.length !== 6) ? 0.6 : 1,
              }}
            >
              {state.verificationLoading ? '验证中...' : '确 定'}
            </button>
          </form>
          
          <div style={{ marginTop: '15px' }}>
            {state.verificationCountdown > 0 ? (
              <span style={{ color: '#666' }}>
                {state.verificationCountdown} 秒后可重新发送
              </span>
            ) : (
              <button
                onClick={handleSendVerificationCode}
                style={{ background: 'none', border: 'none', color: '#007bff', cursor: 'pointer' }}
              >
                重新发送验证码
              </button>
            )}
          </div>
          
          <button
            onClick={() => setState(prev => ({ ...prev, showEmailVerification: false }))}
            style={{ marginTop: '15px', background: 'none', border: 'none', color: '#666', cursor: 'pointer' }}
          >
            取消
          </button>
        </div>
      )}
    </div>
  );
}
```

#### 11.5.2 样式定义

```typescript
// 复用现有样式，添加验证码相关的样式

const verificationModalStyle: React.CSSProperties = {
  position: 'fixed',
  top: '50%',
  left: '50%',
  transform: 'translate(-50%, -50%)',
  background: 'white',
  padding: '30px',
  borderRadius: '12px',
  boxShadow: '0 20px 60px rgba(0,0,0,0.3)',
  zIndex: 1000,
  minWidth: '350px',
  textAlign: 'center',
};

const verificationInputStyle: React.CSSProperties = {
  width: '100%',
  padding: '12px',
  fontSize: '24px',
  textAlign: 'center',
  letterSpacing: '8px',
  border: '2px solid #007bff',
  borderRadius: '8px',
  outline: 'none',
  marginBottom: '15px',
};

const submitButtonStyle: React.CSSProperties = {
  width: '100%',
  padding: '12px',
  background: '#28a745',
  color: 'white',
  border: 'none',
  borderRadius: '8px',
  fontSize: '16px',
  fontWeight: 'bold',
  cursor: 'pointer',
};
```

### 11.6 useAuth.ts 修改

```typescript
// frontend/src/hooks/useAuth.ts 修改

// 新增邮箱验证相关的返回状态和方法
export function useAuth() {
  // ... 现有代码
  
  // 新增：邮箱验证状态
  const [emailVerificationRequired, setEmailVerificationRequired] = useState(false);
  const [pendingEmail, setPendingEmail] = useState<string | null>(null);

  // 修改：注册方法，支持邮箱验证流程
  const register = async (data: {
    username: string;
    email: string;
    password: string;
    displayName: string;
  }) => {
    try {
      setLoading(true);
      setError(null);

      const response = await AuthService.register(data);
      console.log('Registration successful:', response);

      // 检查响应是否需要邮箱验证
      if (response.requireEmailVerification) {
        setPendingEmail(data.username);  // username 就是邮箱
        setEmailVerificationRequired(true);
        setLoading(false);
        return response;
      }

      // 不需要验证，直接登录
      await localLogin(data.username, data.password);
      return response;
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Registration failed';
      setError(message);
      throw err;
    } finally {
      setLoading(false);
    }
  };

  return {
    // ... 现有返回值
    // 新增
    emailVerificationRequired,
    setEmailVerificationRequired,
    pendingEmail,
  };
}
```

### 11.7 修改清单

| 文件 | 修改类型 | 说明 |
|-----|---------|------|
| `src/types/index.ts` | 修改 | 添加 `emailVerified` 字段 |
| `src/types/email.ts` | 新增 | 邮箱验证相关类型定义 |
| `src/services/authService.ts` | 修改 | 添加 `getEmailStatus`、`sendVerificationCode`、`verifyEmail` 方法 |
| `src/pages/LoginPage.tsx` | 修改 | 添加邮箱验证UI和交互逻辑 |
| `src/hooks/useAuth.ts` | 修改 | 支持邮箱验证流程状态 |

### 11.8 UI/UX 改进点

#### 11.8.1 邮箱输入后的即时反馈

```
用户输入邮箱格式的用户名
    ↓
系统检测到邮箱格式
    ↓
后台查询是否有待验证的验证码
    ↓
如果有 → 显示验证码输入界面
如果没有 → 正常进入注册/登录流程
```

#### 11.8.2 验证码发送后的用户体验

| 场景 | UI表现 |
|-----|-------|
| 发送成功 | 显示成功提示"验证码已发送到 xxx@xxx.com"，启动60秒倒计时 |
| 发送失败 | 显示错误提示"发送失败，请重试" |
| 验证码错误 | 显示"验证码错误，还剩 X 次尝试机会" |
| 验证码过期 | 显示"验证码已过期，请重新发送" |
| 验证成功 | 显示"验证成功！"并自动登录 |

#### 11.8.3 渐进式增强策略

1. **基础行为**（所有用户）：
   - 邮箱格式检测
   - 显示验证UI

2. **增强行为**（新功能上线后）：
   - 后端API调用
   - 验证码发送和验证
   - 自动登录

### 11.9 后端API配合

前端需要后端提供以下API：

| 方法 | 路径 | 说明 |
|-----|------|------|
| GET | `/api/auth/email/status/{email}` | 查询邮箱验证状态 |
| POST | `/api/auth/send-verification-code` | 发送验证码 |
| POST | `/api/auth/verify-email` | 验证验证码 |

如果后端API未实现，前端应优雅降级：
- `getEmailStatus` 返回默认状态（`hasPendingVerification: false`）
- 验证码相关操作显示"功能开发中"提示

---

**文档结束**
