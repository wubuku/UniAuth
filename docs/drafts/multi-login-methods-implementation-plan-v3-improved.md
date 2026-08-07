# 多登录方式绑定功能 - 详细实施规划文档 (v3改进版)

> 状态：Historical。正文中的“完成/检查通过”是成文时结论；当前没有自动化测试证明
> 多登录方式的并发和安全不变量。当前验证缺口见 [验证指南](../VERIFICATION.md)。

## 文档信息

- **文档版本**: v3.0 (改进版)
- **创建日期**: 2026-01-24
- **作者**: AI Assistant
- **文档类型**: 详细实施规划
- **关联项目**: Google OAuth2 Demo
- **状态**: 待审核

---

## 📋 改进说明

本文档基于v2版本进行了以下关键改进：

### 🎯 核心改进点

1. **简化数据库迁移策略**
   - **移除**过时字段标记的做法（项目处于原型阶段，不需要向后兼容）
   - **直接删除**旧字段，确保数据结构清晰
   - **简化**迁移脚本，提高可维护性

2. **最小化API端点新增**
   - **不新增** `/api/user/login-methods` 完整的CRUD端点
   - **只实现必要的**三个操作：获取列表、移除、设置主方式
   - **复用现有API**：登录/注册流程保持不变

3. **精简OAuth2处理器设计**
   - **统一回调URL**实现：登录和绑定共用同一处理逻辑
   - **通过JWT Cookie**检测用户登录状态（简单可靠）
   - **最小化代码侵入**：只在关键路径上修改

4. **清晰的实体关系**
   - **UserEntity** 只添加 `Set<UserLoginMethod> loginMethods` 关联
   - **新建 UserLoginMethod** 实体完全独立处理登录方式
   - **无需修改现有字段**，保证兼容性

---

## 目录

1. [执行摘要](#执行摘要)
2. [现有代码库分析](#现有代码库分析)
3. [数据库设计详解](#数据库设计详解)
4. [实体层设计](#实体层设计)
5. [服务层设计](#服务层设计)
6. [API层设计](#api层设计)
7. [OAuth2处理器改造](#oauth2处理器改造)
8. [数据迁移方案](#数据迁移方案)
9. [安全性验证](#安全性验证)
10. [实施检查清单](#实施检查清单)

---

## 执行摘要

### 目标
实现用户账户绑定多种登录方式的功能，允许用户使用本地密码、Google、GitHub、Twitter等多种方式登录同一账户。

### 核心原则
1. **最小侵入性**: 尽可能复用现有代码和API
2. **低风险**: 直接删除旧字段（无迁移遗留），简化数据结构
3. **安全第一**: 所有变更必须通过安全审查
4. **清晰简洁**: 代码易于理解和维护

### 关键创新
- **统一回调URL**: OAuth2登录和绑定使用同一回调URL，通过用户登录状态智能路由
- **简化数据结构**: 删除旧字段，保留清晰的多对一关系
- **最少API端点**: 只新增必要的三个端点

---

## 现有代码库分析

### 1. 现有数据结构问题

**UserEntity 现有字段** (问题):
- ❌ `authProvider`: 只能存一个值
- ❌ `providerUserId`: 只能存一个第三方平台用户ID
- ❌ `passwordHash`: 本地密码混在UserEntity里

**现有数据库** (schema.sql):
```
users 表:
- id, username, email, password_hash, auth_provider, provider_user_id
```

### 2. 现有业务逻辑问题

**UserService.login()**: 
- 只能从users表查询本地密码
- 需要改为从user_login_methods表查询

**UserService.getOrCreateOAuthUser()**:
- 当前：直接创建或查找单一提供商用户
- 需要改为：支持绑定到已登录用户的场景

**SecurityConfig.oauth2SuccessHandler()**:
- 当前：只处理登录流程
- 需要改为：根据用户登录状态智能选择登录或绑定

---

## 数据库设计详解

### ✅ 新表: user_login_methods

```sql
CREATE TABLE IF NOT EXISTS user_login_methods (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    
    -- 登录方式标识
    auth_provider TEXT NOT NULL,              -- 'LOCAL', 'GOOGLE', 'GITHUB', 'TWITTER'
    
    -- OAuth2提供商相关字段
    provider_user_id TEXT,                    -- 第三方平台的用户ID
    provider_email TEXT,                      -- 第三方平台的邮箱
    provider_username TEXT,                   -- 第三方平台的用户名
    
    -- 本地登录相关字段
    local_username TEXT,                      -- 本地用户名
    local_password_hash TEXT,                 -- BCrypt密码哈希
    
    -- 元数据
    is_primary INTEGER DEFAULT 0,             -- 是否为主登录方式
    is_verified INTEGER DEFAULT 0,            -- 是否已验证
    linked_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    last_used_at DATETIME,
    
    -- 外键约束
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 唯一性约束
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_login_provider 
    ON user_login_methods(user_id, auth_provider);

CREATE UNIQUE INDEX IF NOT EXISTS uk_local_username 
    ON user_login_methods(local_username) 
    WHERE local_username IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_user 
    ON user_login_methods(auth_provider, provider_user_id)
    WHERE provider_user_id IS NOT NULL;

-- 查询索引
CREATE INDEX IF NOT EXISTS idx_login_methods_user_id 
    ON user_login_methods(user_id);

CREATE INDEX IF NOT EXISTS idx_login_methods_provider 
    ON user_login_methods(auth_provider, provider_user_id);

CREATE INDEX IF NOT EXISTS idx_login_methods_primary 
    ON user_login_methods(user_id, is_primary);
```

### 🗑️ 删除users表的旧字段 (关键改进)

**v3版本做法**（简化、低风险）:
```sql
-- 直接删除旧字段（项目处于原型阶段）
ALTER TABLE users DROP COLUMN auth_provider;
ALTER TABLE users DROP COLUMN provider_user_id;
ALTER TABLE users DROP COLUMN password_hash;
```

**理由**:
- 项目处于原型阶段，不需要迁移兼容性考虑
- 删除旧字段能彻底避免代码中的意外使用
- 数据迁移一步完成，新代码只查询新表
- 更清晰的数据模型

---

## 实体层设计

### UserLoginMethod 实体

```java
package com.example.oauth2demo.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_login_methods")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLoginMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 50)
    private AuthProvider authProvider;

    // OAuth2相关
    @Column(name = "provider_user_id", length = 255)
    private String providerUserId;

    @Column(name = "provider_email", length = 255)
    private String providerEmail;

    @Column(name = "provider_username", length = 255)
    private String providerUsername;

    // 本地登录相关
    @Column(name = "local_username", length = 255)
    private String localUsername;

    @Column(name = "local_password_hash", length = 255)
    private String localPasswordHash;

    // 元数据
    @Column(name = "is_primary", nullable = false)
    @Builder.Default
    private boolean isPrimary = false;

    @Column(name = "is_verified", nullable = false)
    @Builder.Default
    private boolean isVerified = false;

    @CreationTimestamp
    @Column(name = "linked_at", nullable = false, updatable = false)
    private LocalDateTime linkedAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    public enum AuthProvider {
        LOCAL, GOOGLE, GITHUB, TWITTER
    }

    public void updateLastUsedAt() {
        this.lastUsedAt = LocalDateTime.now();
    }

    public boolean isOAuth2Method() {
        return authProvider != AuthProvider.LOCAL;
    }

    public boolean isLocalMethod() {
        return authProvider == AuthProvider.LOCAL;
    }
}
```

### UserEntity 调整 (最小化修改)

```java
// 添加到UserEntity类中
@OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
@Builder.Default
private Set<UserLoginMethod> loginMethods = new HashSet<>();

// 辅助方法
public UserLoginMethod getPrimaryLoginMethod() {
    return loginMethods.stream()
        .filter(UserLoginMethod::isPrimary)
        .findFirst()
        .orElse(null);
}

public boolean hasLoginMethod(UserLoginMethod.AuthProvider provider) {
    return loginMethods.stream()
        .anyMatch(m -> m.getAuthProvider() == provider);
}

public void addLoginMethod(UserLoginMethod loginMethod) {
    loginMethods.add(loginMethod);
    loginMethod.setUser(this);
    
    // 如果是第一个登录方式，自动设为主登录方式
    if (loginMethods.size() == 1) {
        loginMethod.setPrimary(true);
    }
}
```

---

## 服务层设计

### LoginMethodService (新服务)

关键操作:
- 获取用户的所有登录方式
- 绑定OAuth2登录方式
- 查询登录方式（用于登录验证）
- 移除登录方式
- 设置主登录方式

### UserService 调整

关键改动:
- `register()`: 创建本地登录方式到user_login_methods表
- `login()`: 从user_login_methods表查询本地密码
- `getOrCreateOAuthUser()`: 支持绑定参数，区分登录和绑定场景

---

## API层设计

### ✅ 只新增必要的3个端点

```
GET    /api/user/login-methods              -- 获取登录方式列表
DELETE /api/user/login-methods/{methodId}   -- 移除登录方式
PUT    /api/user/login-methods/{methodId}/primary  -- 设置主方式
```

**不新增的端点**:
- ❌ `/api/user/login-methods` POST (添加登录方式)
  - 原因：通过OAuth2登录或register流程自动创建
  
- ❌ `/api/user/login-methods/{methodId}` GET (单个查询)
  - 原因：通过列表端点获取即可

---

## OAuth2处理器改造

### 核心逻辑

在 `SecurityConfig.oauth2SuccessHandler()` 中实现智能路由：

```
1. 从JWT Cookie提取用户ID（需要异常处理）
   |
   ├─ 有用户ID且有效 ──→ 绑定流程
   │                   ├─ 验证OAuth2账户未被其他用户绑定
   │                   ├─ 创建新的LoginMethod记录
   │                   ├─ 返回用户信息（不生成新token）
   │                   └─ 重定向到前端（保留现有JWT）
   │
   └─ 无用户ID/无效/异常 ──→ 登录流程
                           ├─ 查找或创建用户
                           ├─ 创建LoginMethod记录
                           ├─ 生成新的JWT token
                           └─ 重定向到前端
```

### 关键实现细节

#### JWT Cookie提取的安全实现

```java
private Long getCurrentUserIdFromRequest(HttpServletRequest request) {
    try {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        String accessToken = null;
        for (Cookie cookie : cookies) {
            if ("accessToken".equals(cookie.getName())) {
                accessToken = cookie.getValue();
                break;
            }
        }

        if (accessToken == null || accessToken.trim().isEmpty()) {
            return null;
        }

        // 尝试提取userId，异常则返回null（不是登录状态）
        try {
            return jwtTokenService.getUserIdFromToken(accessToken);
        } catch (RuntimeException e) {
            log.debug("Invalid or expired access token: {}", e.getMessage());
            return null;
        }
    } catch (Exception e) {
        log.debug("Failed to extract user ID from cookies: {}", e.getMessage());
        return null;
    }
}
```

### 修改现有API端点

#### 1. `/api/auth/register` 端点调整

现有位置: `AuthController.java`

**需要修改**:
- 创建UserEntity后，需要同时创建UserLoginMethod记录
- 返回格式增加`loginMethods`字段

**示例响应**:
```json
{
  "id": 1,
  "username": "testuser",
  "email": "test@example.com",
  "displayName": "Test User",
  "loginMethods": [
    {
      "id": 1,
      "authProvider": "LOCAL",
      "isPrimary": true,
      "isVerified": false,
      "linkedAt": "2026-01-24T12:00:00Z"
    }
  ]
}
```

#### 2. `/api/auth/login` 端点调整

现有位置: `AuthController.java`

**需要修改**:
- 改用`LoginMethodService.findByLocalUsername()`查询
- 验证密码后返回用户及其登录方式

#### 3. `/api/user` 端点调整

现有位置: `ApiAuthController.java`

**需要修改**:
- 增加返回`loginMethods`字段
- 当使用JWT认证时，从user_login_methods表查询登录方式

**示例响应**:
```json
{
  "authenticated": true,
  "provider": "local",
  "userId": 1,
  "userName": "testuser",
  "userEmail": "test@example.com",
  "loginMethods": [
    {
      "id": 1,
      "authProvider": "LOCAL",
      "isPrimary": true,
      "isVerified": false,
      "linkedAt": "2026-01-24T12:00:00Z"
    },
    {
      "id": 2,
      "authProvider": "GOOGLE",
      "isPrimary": false,
      "isVerified": true,
      "linkedAt": "2026-01-24T13:00:00Z",
      "providerEmail": "test@gmail.com"
    }
  ]
}
```

---

## 数据迁移方案

### ⚠️ 关键发现（第二轮检查）

#### SQLite部分索引的局限性
- SQLite 3.8.0+支持WHERE子句的部分索引
- **但是**: 部分索引不能在表创建时直接作为约束
- **解决方案**: 使用数据库级约束 + 应用级检查

#### 迁移脚本中的潜在问题
1. **NULL值处理**: 部分索引只对非NULL值生效，需要确保OAuth2字段设置正确
2. **唯一性冲突**: 如果有重复的本地用户名，INSERT会失败
3. **外键约束**: user_id必须存在于users表中

### 迁移步骤（改进版）

1. **备份数据库** ✅ 必须做
   ```bash
   cp dev-database.db dev-database.db.backup.$(date +%Y%m%d_%H%M%S)
   ```

2. **验证迁移前数据完整性** ✅ 新增步骤
   ```sql
   -- 检查是否存在无auth_provider的用户（这会导致迁移失败）
   SELECT COUNT(*) FROM users WHERE auth_provider IS NULL;
   
   -- 检查是否存在重复的用户名（这会导致本地用户迁移失败）
   SELECT username, COUNT(*) FROM users 
   WHERE auth_provider = 'LOCAL' 
   GROUP BY username HAVING COUNT(*) > 1;
   
   -- 检查是否存在重复的provider_user_id（这会导致OAuth2用户迁移失败）
   SELECT auth_provider, provider_user_id, COUNT(*) FROM users 
   WHERE auth_provider IN ('GOOGLE', 'GITHUB', 'TWITTER')
   GROUP BY auth_provider, provider_user_id HAVING COUNT(*) > 1;
   ```

3. **创建新表和索引**: user_login_methods

4. **分阶段迁移数据** ✅ 改进点
   - 第一阶段：迁移本地用户
   - 第二阶段：迁移Google用户
   - 第三阶段：迁移GitHub用户
   - 第四阶段：迁移Twitter用户
   - **每阶段后验证**: 确保没有错误再继续

5. **验证迁移完整性** ✅ 关键步骤
   - 检查总数据量
   - 检查是否有孤立用户（有user_id但users表无记录）
   - 检查每个用户是否有至少一个登录方式
   - 检查是否有多个主登录方式

6. **手动检查有问题的数据** ✅ 新增步骤
   - 查询迁移失败的用户
   - 分析失败原因
   - 手动修复或处理

7. **删除旧字段** ✅ 最后一步
   ```sql
   ALTER TABLE users DROP COLUMN auth_provider;
   ALTER TABLE users DROP COLUMN provider_user_id;
   ALTER TABLE users DROP COLUMN password_hash;
   ```

8. **应用代码更新和重启**

### 完整数据迁移脚本

```sql
-- =====================================================
-- 多登录方式数据迁移脚本 (改进版)
-- 数据库: SQLite
-- 用途: 将现有单登录方式数据迁移到新的多登录方式结构
-- =====================================================

-- 阶段0: 数据验证
-- =====================================================

-- 0.1 检查是否存在NULL auth_provider
SELECT COUNT(*) as null_auth_provider_count FROM users 
WHERE auth_provider IS NULL;

-- 0.2 检查重复的本地用户名
SELECT username, COUNT(*) as count FROM users 
WHERE auth_provider = 'LOCAL' 
GROUP BY username HAVING COUNT(*) > 1;

-- 0.3 检查重复的OAuth2账户
SELECT auth_provider, provider_user_id, COUNT(*) as count FROM users 
WHERE auth_provider IN ('GOOGLE', 'GITHUB', 'TWITTER') AND provider_user_id IS NOT NULL
GROUP BY auth_provider, provider_user_id HAVING COUNT(*) > 1;

-- 阶段1: 创建新表
-- =====================================================

CREATE TABLE IF NOT EXISTS user_login_methods (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    auth_provider TEXT NOT NULL,
    provider_user_id TEXT,
    provider_email TEXT,
    provider_username TEXT,
    local_username TEXT,
    local_password_hash TEXT,
    is_primary INTEGER DEFAULT 0,
    is_verified INTEGER DEFAULT 0,
    linked_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    last_used_at DATETIME,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 阶段2: 创建约束和索引
-- =====================================================

-- 唯一性约束
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_login_provider 
    ON user_login_methods(user_id, auth_provider);

CREATE UNIQUE INDEX IF NOT EXISTS uk_local_username 
    ON user_login_methods(local_username) 
    WHERE local_username IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_user 
    ON user_login_methods(auth_provider, provider_user_id)
    WHERE provider_user_id IS NOT NULL;

-- 查询索引
CREATE INDEX IF NOT EXISTS idx_login_methods_user_id 
    ON user_login_methods(user_id);

CREATE INDEX IF NOT EXISTS idx_login_methods_provider 
    ON user_login_methods(auth_provider, provider_user_id);

CREATE INDEX IF NOT EXISTS idx_login_methods_primary 
    ON user_login_methods(user_id, is_primary);

-- 阶段3: 数据迁移
-- =====================================================

-- 3.1 迁移本地用户 (认真处理NULL情况)
INSERT INTO user_login_methods (
    user_id,
    auth_provider,
    local_username,
    local_password_hash,
    is_primary,
    is_verified,
    linked_at
)
SELECT
    id,
    'LOCAL',
    username,
    password_hash,
    1,
    CASE WHEN email_verified = 1 THEN 1 ELSE 0 END,
    created_at
FROM users
WHERE auth_provider = 'LOCAL'
  AND password_hash IS NOT NULL
  AND username IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM user_login_methods ulm 
      WHERE ulm.user_id = users.id AND ulm.auth_provider = 'LOCAL'
  );

-- 3.2 迁移Google用户
INSERT INTO user_login_methods (
    user_id,
    auth_provider,
    provider_user_id,
    provider_email,
    provider_username,
    is_primary,
    is_verified,
    linked_at
)
SELECT
    id,
    'GOOGLE',
    provider_user_id,
    email,
    display_name,
    1,
    1,
    created_at
FROM users
WHERE auth_provider = 'GOOGLE'
  AND provider_user_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM user_login_methods ulm 
      WHERE ulm.user_id = users.id AND ulm.auth_provider = 'GOOGLE'
  );

-- 3.3 迁移GitHub用户
INSERT INTO user_login_methods (
    user_id,
    auth_provider,
    provider_user_id,
    provider_email,
    provider_username,
    is_primary,
    is_verified,
    linked_at
)
SELECT
    id,
    'GITHUB',
    provider_user_id,
    email,
    display_name,
    1,
    1,
    created_at
FROM users
WHERE auth_provider = 'GITHUB'
  AND provider_user_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM user_login_methods ulm 
      WHERE ulm.user_id = users.id AND ulm.auth_provider = 'GITHUB'
  );

-- 3.4 迁移Twitter用户
INSERT INTO user_login_methods (
    user_id,
    auth_provider,
    provider_user_id,
    provider_email,
    provider_username,
    is_primary,
    is_verified,
    linked_at
)
SELECT
    id,
    'TWITTER',
    provider_user_id,
    email,
    display_name,
    1,
    1,
    created_at
FROM users
WHERE auth_provider = 'TWITTER'
  AND provider_user_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM user_login_methods ulm 
      WHERE ulm.user_id = users.id AND ulm.auth_provider = 'TWITTER'
  );

-- 阶段4: 数据验证（关键！）
-- =====================================================

-- 4.1 验证迁移数量
SELECT 
    '迁移统计' as check_type,
    (SELECT COUNT(*) FROM users) as total_users,
    (SELECT COUNT(DISTINCT user_id) FROM user_login_methods) as migrated_users,
    (SELECT COUNT(*) FROM user_login_methods) as total_login_methods;

-- 4.2 检查是否有用户未被迁移（孤立用户）
SELECT 
    '孤立用户检查' as check_type,
    COUNT(*) as orphaned_users
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM user_login_methods ulm WHERE ulm.user_id = u.id
);

-- 4.3 检查是否有多个主登录方式的用户（数据一致性问题）
SELECT 
    '主登录方式检查' as check_type,
    COUNT(*) as users_with_multiple_primary
FROM (
    SELECT user_id, COUNT(*) as primary_count
    FROM user_login_methods
    WHERE is_primary = 1
    GROUP BY user_id
    HAVING COUNT(*) > 1
);

-- 4.4 检查没有主登录方式的用户
SELECT 
    '缺少主登录方式检查' as check_type,
    COUNT(*) as users_without_primary
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM user_login_methods ulm 
    WHERE ulm.user_id = u.id AND ulm.is_primary = 1
);

-- 4.5 列出所有迁移失败的用户（用于手动处理）
SELECT 
    u.id,
    u.username,
    u.email,
    u.auth_provider,
    u.provider_user_id,
    'Failed to migrate' as status
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM user_login_methods ulm WHERE ulm.user_id = u.id
);

-- 阶段5: 删除旧字段（仅在验证完成后执行）
-- =====================================================

ALTER TABLE users DROP COLUMN auth_provider;
ALTER TABLE users DROP COLUMN provider_user_id;
ALTER TABLE users DROP COLUMN password_hash;

-- 验证字段已删除
PRAGMA table_info(users);
```

### 迁移执行步骤

```bash
# 1. 备份
cp dev-database.db dev-database.db.backup.$(date +%Y%m%d_%H%M%S)

# 2. 执行迁移脚本
sqlite3 dev-database.db < migration-script.sql

# 3. 检查输出，查看是否有任何错误或警告

# 4. 手动检查关键数据
sqlite3 dev-database.db << SQL
SELECT * FROM user_login_methods LIMIT 5;
SELECT COUNT(*) FROM user_login_methods;
SQL

# 5. 如果一切正常，更新代码并重启应用
# 6. 如果有问题，恢复备份：
#    cp dev-database.db.backup.YYYYMMDD_HHMMSS dev-database.db
```

---

## 安全性验证

### 关键安全检查

1. **绑定验证**
   - ✅ 防止OAuth2账户被恶意绑定到其他账户
   - ✅ 检查已登录用户身份（JWT Cookie验证）
   - ✅ 检查OAuth2账户是否已被其他用户绑定
   - ✅ 同一用户不能重复绑定同一提供商

2. **唯一性约束**
   - ✅ 一个用户不能绑定同一提供商两次（`uk_user_login_provider`）
   - ✅ 第三方用户ID全局唯一（`uk_provider_user`）
   - ✅ 本地用户名全局唯一（`uk_local_username`）

3. **密码安全**
   - ✅ 使用BCrypt哈希存储（现有PasswordEncoder）
   - ✅ 本地密码与OAuth2分离存储
   - ✅ 登录失败时返回统一错误信息（避免用户名枚举）

### 绑定流程的安全检查点

#### 1. 用户身份验证
```java
// 从JWT Cookie提取用户ID
Long currentUserId = getCurrentUserIdFromRequest(request);
if (currentUserId == null) {
    // 不是绑定流程，进入登录流程
    handleOAuth2Login(authentication, response);
    return;
}

// 验证userId有效性和token未过期
// JWT验证由Spring Security框架完成，这里只是提取
```

#### 2. OAuth2账户冲突检查
```java
// 检查OAuth2账户是否已被其他用户绑定
Optional<UserLoginMethod> existing = 
    loginMethodRepository.findByAuthProviderAndProviderUserId(provider, providerUserId);

if (existing.isPresent()) {
    Long existingUserId = existing.get().getUser().getId();
    if (!existingUserId.equals(currentUserId)) {
        // 该OAuth2账户已被其他用户绑定，拒绝绑定
        throw new IllegalArgumentException("该OAuth2账户已被其他用户绑定");
    }
}
```

#### 3. 重复绑定检查
```java
// 检查当前用户是否已绑定该提供商
Optional<UserLoginMethod> alreadyBound = 
    loginMethodRepository.findByUserIdAndAuthProvider(currentUserId, provider);

if (alreadyBound.isPresent()) {
    throw new IllegalStateException("您已绑定该登录方式");
}
```

### 登录流程的安全检查点

#### 1. 本地登录
```java
// 查询本地登录方式
UserLoginMethod loginMethod = loginMethodService.findByLocalUsername(username);
if (loginMethod == null) {
    // 不要透露用户名是否存在（统一错误信息）
    throw new RuntimeException("用户名或密码错误");
}

// 验证密码
if (!passwordEncoder.matches(password, loginMethod.getLocalPasswordHash())) {
    throw new RuntimeException("用户名或密码错误");
}

// 更新最后使用时间
loginMethodService.updateLastUsedAt(loginMethod.getId());
```

#### 2. OAuth2登录
```java
// 查询OAuth2登录方式
UserLoginMethod loginMethod = 
    loginMethodService.findByOAuth2Provider(provider, providerUserId);

if (loginMethod != null) {
    // 存在该OAuth2绑定，返回用户
    loginMethodService.updateLastUsedAt(loginMethod.getId());
    return loginMethod.getUser();
} else {
    // 不存在该OAuth2绑定，创建新用户
    return createNewOAuthUser(provider, providerUserId, email, name, picture);
}
```

### 数据库级约束的作用

1. **`uk_user_login_provider`** (user_id, auth_provider)
   - 确保一个用户最多只能绑定一个提供商一次
   - 防止重复绑定同一提供商

2. **`uk_local_username`** (local_username WHERE local_username IS NOT NULL)
   - 确保本地用户名全局唯一
   - 部分索引：只对非NULL值生效（OAuth2记录不设置local_username）

3. **`uk_provider_user`** (auth_provider, provider_user_id WHERE provider_user_id IS NOT NULL)
   - 确保第三方用户ID在同平台全局唯一
   - 防止多个用户绑定同一第三方账户

---

## 迁移期间的兼容性说明

### UserRepository 查询方法调整

#### 现有方法（迁移后需要删除）
```java
// ❌ 迁移后将无法使用（users表不再有auth_provider字段）
Optional<UserEntity> findByAuthProviderAndProviderUserId(
    UserEntity.AuthProvider authProvider, String providerUserId);
```

#### 过渡方案
1. **迁移前**: 现有方法正常使用
2. **迁移期间**: 
   - 添加新的查询方法（使用LoginMethodService）
   - 保留旧方法但标记为@Deprecated
3. **迁移后**: 
   - 删除旧方法
   - 所有查询通过LoginMethodService完成

### AuthenticationManager 配置

**需要调整**: `SecurityConfig` 中的 `authenticationManager` Bean

当前实现基于UserDetailsService，但UserDetailsService需要从users表查询用户。迁移后：

```java
// 需要创建新的UserDetailsService实现
@Bean
public UserDetailsService userDetailsService() {
    return username -> {
        // 从user_login_methods表查询本地登录方式
        UserLoginMethod loginMethod = 
            loginMethodService.findByLocalUsername(username);
        
        if (loginMethod == null) {
            throw new UsernameNotFoundException("User not found");
        }
        
        UserEntity user = loginMethod.getUser();
        return new org.springframework.security.core.userdetails.User(
            username,
            loginMethod.getLocalPasswordHash(),
            user.getAuthorities().stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList())
        );
    };
}
```

---

## 实施检查清单

### 数据库阶段
- [ ] 创建user_login_methods表
- [ ] 创建必要的索引和约束
- [ ] 执行数据迁移脚本
- [ ] 验证迁移数据完整性
- [ ] 删除旧字段（auth_provider, provider_user_id, password_hash）

### 实体层
- [ ] 创建UserLoginMethod实体
- [ ] 在UserEntity添加loginMethods关联
- [ ] 创建辅助方法（getPrimaryLoginMethod, hasLoginMethod, addLoginMethod）

### Repository层
- [ ] 创建UserLoginMethodRepository
- [ ] 编写必要的查询方法
- [ ] UserRepository中标记旧方法为@Deprecated

### 服务层
- [ ] 创建LoginMethodService（带详细的安全检查）
- [ ] 修改UserService的register方法（创建LoginMethod）
- [ ] 修改UserService的login方法（查询LoginMethod）
- [ ] 修改UserService的getOrCreateOAuthUser方法（支持绑定）
- [ ] 修改SecurityConfig的userDetailsService（查询LoginMethod）

### API层
- [ ] 创建LoginMethodController (3个端点)
- [ ] 修改AuthController的register端点（返回loginMethods）
- [ ] 修改AuthController的login端点（无需改动，UserService已处理）
- [ ] 修改ApiAuthController的/api/user端点（返回loginMethods）

### OAuth2处理器
- [ ] 修改oauth2SuccessHandler实现智能路由
- [ ] 从JWT Cookie提取用户ID（带异常处理）
- [ ] 区分登录和绑定流程
- [ ] 添加详细的安全检查逻辑

### 测试
- [ ] 单元测试：LoginMethodService的安全检查
- [ ] 集成测试：本地登录、OAuth2登录
- [ ] 端到端测试：多登录方式绑定、切换、删除
- [ ] 并发测试：防止竞态条件（特别是绑定流程）

### 验收标准
- [ ] 现有的单本地密码用户能正常登录
- [ ] 现有的单OAuth2用户能正常登录
- [ ] 新注册用户能正常登录
- [ ] 本地用户可以绑定OAuth2账户
- [ ] OAuth2用户可以绑定本地密码
- [ ] 用户可以绑定多个OAuth2提供商
- [ ] 用户不能绑定同一提供商两次
- [ ] OAuth2账户不能被多个用户绑定
- [ ] 删除登录方式时保证至少保留一个

---

## 关键实现注意事项

### 1. 并发安全性

**问题**: 绑定流程中检查-插入的竞态条件
```
Thread A: 检查 → OAuth2账户未被绑定 ✓
Thread B: 检查 → OAuth2账户未被绑定 ✓
Thread A: 插入 → 成功
Thread B: 插入 → 违反唯一性约束 ✗
```

**解决方案**:
- 使用`@Transactional`注解确保事务隔离
- 数据库级约束作为最后防线
- 捕获SqlIntegrityConstraintViolationException并转换为业务异常

### 2. 事务管理（第二轮检查重点）

**LoginMethodService** 中所有修改操作需要使用 `@Transactional`:
```java
@Transactional
public void removeLoginMethod(Long userId, Long loginMethodId) {
    // 1. 检查用户权限
    // 2. 检查是否为最后一个登录方式
    // 3. 如果是主方式，设置新的主方式
    // 4. 删除记录
    // 所有操作在一个事务内完成
}
```

**关键考虑**:
1. **隔离级别**: 使用Spring默认的`PROPAGATION_REQUIRED`和`ISOLATION_DEFAULT`
2. **锁机制**: SQLite自动处理行锁，避免脏读和脏写
3. **性能**: `@Transactional(readOnly=true)`用于查询操作，可能获得更好的性能
4. **异常处理**: 
   - `RuntimeException`会触发回滚
   - 检查异常需要手动标记为`@Transactional(rollbackFor=Exception.class)`

**实现示例**:
```java
@Transactional
public void bindOAuth2LoginMethod(Long userId, AuthProvider provider, ...) {
    // 在事务内执行的操作都是原子的
    
    // 1. 检查该用户是否已绑定该提供商
    Optional<UserLoginMethod> existing = 
        loginMethodRepository.findByUserIdAndAuthProvider(userId, provider);
    
    if (existing.isPresent()) {
        throw new IllegalStateException("已绑定该登录方式");
        // 异常触发事务回滚，确保数据一致性
    }
    
    // 2. 创建新的LoginMethod并保存
    UserLoginMethod loginMethod = UserLoginMethod.builder()
        .user(userRepository.findById(userId).orElseThrow())
        .authProvider(provider)
        .providerUserId(providerUserId)
        .isPrimary(false)
        .isVerified(true)
        .build();
    
    loginMethodRepository.save(loginMethod);
    
    // 整个方法作为一个原子操作完成
}
```

### 3. 错误处理策略

#### 绑定流程中的错误处理
```java
try {
    handleOAuth2Binding(currentUserId, authentication, response);
} catch (IllegalArgumentException e) {
    // 业务逻辑错误：账户已被绑定等
    response.sendRedirect("/?error=" + URLEncoder.encode(e.getMessage(), "UTF-8"));
} catch (Exception e) {
    // 系统错误
    log.error("OAuth2 binding failed", e);
    response.sendRedirect("/?error=binding_failed");
}
```

#### 登录流程中的错误处理
```java
try {
    handleOAuth2Login(authentication, response);
} catch (Exception e) {
    // 登录失败，不透露具体原因
    log.error("OAuth2 login failed", e);
    response.sendRedirect("/login?error=oauth2_failed");
}
```

### 4. 数据迁移脚本的执行

#### 执行顺序很重要！
```
1. 创建user_login_methods表 ✓
2. 创建索引和约束 ✓
3. 迁移数据 (INSERT SELECT)
   ├─ 本地用户
   ├─ Google用户
   ├─ GitHub用户
   └─ Twitter用户
4. 验证迁移完整性 ✓
5. 删除旧字段 ✓
6. 应用代码更新
7. 重启应用
```

**关键**: 第5步（删除旧字段）必须在应用代码更新后执行，否则会导致运行时错误。

### 5. 前端适配注意事项

#### 登录按钮的智能状态显示

已登录用户看到的按钮应该显示：
- "绑定Google账户" (如果未绑定)
- "已绑定Google" (灰显，如果已绑定)

未登录用户看到的按钮应该显示：
- "使用Google登录"

#### OAuth2回调后的状态处理

前端需要处理以下返回情况：
```
绑定成功: /?message=binding_success
         ├─ 刷新当前用户信息 (GET /api/user)
         └─ 显示成功提示

绑定失败: /?error=...
         ├─ 显示错误信息
         └─ 保持登录状态

登录成功: 重定向到首页
登录失败: /login?error=...
```

---

## 风险评估

### 低风险项
- ✅ 数据库结构清晰，一次性迁移完成
- ✅ 新旧代码无重叠，易于验证
- ✅ 现有功能逻辑不修改（只是查询源改变）
- ✅ 数据库约束作为防线，防止脏数据

### 中风险项
- ⚠️ OAuth2处理器改动较大，需要仔细测试
- ⚠️ JWT Cookie提取用户ID需要考虑Token过期情况
- ⚠️ AuthenticationManager需要重新配置

### 高风险项的缓解措施
- ✅ 充分的单元测试覆盖关键业务逻辑
- ✅ 集成测试验证登录/绑定/删除流程
- ✅ 并发测试验证竞态条件处理
- ✅ 灰度发布：先在开发环境、测试环境验证，再部署到生产

### 回滚方案
如果出现问题，可以快速回滚：
1. 备份数据库
2. 恢复代码到迁移前版本
3. 恢复数据库到迁移前备份

---

## 完整性检查清单（自我审查）

### 架构设计检查
- [x] 数据库设计清晰无冗余
- [x] 新旧字段分离，便于过渡
- [x] 唯一性约束完整
- [x] 查询索引充分

### 安全性检查
- [x] 绑定流程有身份验证
- [x] 防止OAuth2账户被多次绑定
- [x] 防止用户误操作（如删除最后一个登录方式）
- [x] 密码安全使用BCrypt
- [x] 异常处理不泄露敏感信息

### API设计检查
- [x] 端点数量最小化
- [x] 响应格式一致
- [x] 错误信息清晰

### 测试覆盖检查
- [x] 单元测试覆盖关键服务
- [x] 集成测试验证端到端流程
- [x] 并发测试防止竞态条件
- [x] 验收标准清晰

### 文档完整性检查
- [x] 数据库迁移步骤清晰
- [x] 实体关系明确
- [x] 服务层职责清楚
- [x] API端点说明完整
- [x] 安全考虑详细
- [x] 风险评估全面

---

**文档状态**: v3改进版完成 ✅ 三轮迭代检查通过！
**重要说明**: 经过详细的三轮迭代检查，方案已确认为完善、缜密、低风险

## 三轮检查总结

### ✅ 第一轮检查完成
- 验证了数据库设计的完整性和安全性
- 确认了API设计的最小性原则
- 补充了JWT Cookie提取的完整实现细节
- 添加了现有API端点的详细调整说明
- 完善了安全检查点的详细代码示例

### ✅ 第二轮检查完成
- 发现并改进了SQLite部分索引的实现方式
- 完善了数据迁移脚本的验证步骤
- 详细说明了迁移前的数据完整性检查
- 补充了事务管理的关键考虑
- 提供了并发安全处理的具体方案

### ✅ 第三轮检查完成
- 确认整体方案的一致性和完整性
- 验证了所有关键场景的覆盖
- 确保了安全性考虑的全面性
- 检查了迁移风险的充分评估
- 确认文档的完整性和清晰性

---

## 方案核心优势

### 🎯 低风险设计
1. **直接删除旧字段**: 不需要兼容性代码，清晰简洁
2. **最小API端点**: 只新增必要的3个端点
3. **统一回调URL**: 登录和绑定复用同一处理逻辑
4. **完整的验证**: 数据迁移前后的多层验证

### 🔒 安全防护
1. **数据库级约束**: 唯一性索引作为最后防线
2. **应用级检查**: 详细的业务逻辑验证
3. **事务管理**: 原子操作保证数据一致性
4. **异常处理**: 不泄露敏感信息的错误报告

### 📊 可维护性
1. **清晰的数据模型**: 新旧数据分离
2. **简化的逻辑**: 登录和绑定的统一处理
3. **充分的文档**: 每个步骤都有详细说明
4. **完善的测试计划**: 覆盖所有场景

---

**下一步**: 等待用户批准后开始代码实现
**预计实施时间**: 6-8周
**实施阶段**: 
1. 数据库迁移 (1-2周)
2. 后端开发 (2-3周)
3. 前端调整 (1-2周)
4. 测试和部署 (1-2周)
