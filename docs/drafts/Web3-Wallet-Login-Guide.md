# Web3 钱包登录完整开发指南

> 状态：Reference。本文是包含 Redis/MySQL 等假设的通用教程，不是当前仓库实现说明。
> 当前 Web3 所有权边界见 [当前架构](../ARCHITECTURE.md#代码所有权)。

> **面向对象**: Spring Boot 后端开发人员（Web3 小白）  
> **目标**: 为现有 Web2 项目添加 Web3 钱包登录功能  
> **支持钱包**: MetaMask、Coinbase Wallet、WalletConnect 等 300+ 主流钱包

---

## 📚 目录

1. [前置知识](#1-前置知识)
2. [技术架构总览](#2-技术架构总览)
3. [环境准备](#3-环境准备)
4. [后端开发步骤](#4-后端开发步骤)
5. [前端集成步骤](#5-前端集成步骤)
6. [测试验证](#6-测试验证)
7. [生产部署](#7-生产部署)
8. [常见问题](#8-常见问题)

---

## 1. 前置知识

### 1.1 什么是 Web3 钱包登录？

**传统 Web2 登录**:
```
用户输入用户名密码 → 服务器验证 → 发放 Session/JWT
```

**Web3 钱包登录**:
```
用户连接钱包（如 MetaMask）→ 签名消息 → 后端验证签名 → 发放 JWT
```

### 1.2 核心概念（3 分钟理解）

| 概念 | 通俗解释 | 技术细节 |
|------|---------|---------|
| **钱包地址** | 类似用户名，唯一标识 | 0x 开头的 42 位十六进制字符串 |
| **私钥** | 类似密码，用户不会告诉你 | 存储在用户钱包中，永远不发送给服务器 |
| **签名** | 用户用私钥"盖章"证明身份 | 后端可验证签名但无法伪造 |
| **Nonce** | 一次性随机数，防重放攻击 | 每次登录生成新的，用后作废 |
| **SIWE** | 标准化的签名消息格式 | 类似 OAuth 2.0 的地位 |

### 1.3 为什么这样设计安全？

```
✅ 用户永远不发送私钥给服务器（私钥保存在钱包中）
✅ 每次签名的消息包含 nonce（无法重放）
✅ 签名可数学验证但无法伪造（椭圆曲线加密）
✅ 后端只存储钱包地址（类似 email，公开信息）
```

---

## 2. 技术架构总览

### 2.1 整体流程图

```
┌─────────────┐         ┌──────────────┐         ┌─────────────┐
│   用户浏览器   │         │  Spring Boot  │         │   数据库     │
│  + MetaMask  │         │     后端       │         │  (MySQL)    │
└──────┬──────┘         └───────┬──────┘         └──────┬──────┘
       │                        │                       │
       │  1. 请求 nonce          │                       │
       ├───────────────────────>│                       │
       │                        │  2. 生成 nonce        │
       │                        ├──────────────────────>│
       │                        │  3. 存储 nonce(5分钟) │
       │  4. 返回 nonce         │<──────────────────────┤
       │<───────────────────────┤                       │
       │                        │                       │
       │  5. 用户签名消息         │                       │
       │  (MetaMask 弹窗)        │                       │
       │                        │                       │
       │  6. 提交签名验证         │                       │
       ├───────────────────────>│                       │
       │                        │  7. 验证签名有效性     │
       │                        │  8. 验证 nonce        │
       │                        ├──────────────────────>│
       │                        │  9. 创建/更新用户      │
       │                        ├──────────────────────>│
       │  10. 返回 JWT          │                       │
       │<───────────────────────┤                       │
       │                        │                       │
       │  11. 后续请求带 JWT     │                       │
       ├───────────────────────>│  12. 验证 JWT        │
       │                        │                       │
```

### 2.2 技术栈选型

| 层级 | 技术 | 版本 | 作用 |
|------|------|------|------|
| **后端框架** | Spring Boot | 3.2+ | 基础框架 |
| **Web3 库** | Web3j | 4.11.0 | 验证以太坊签名 |
| **JWT** | jjwt | 0.12.5 | 生成和验证 token |
| **缓存** | Spring Data Redis | 3.2+ | 存储 nonce |
| **数据库** | MySQL | 8.0+ | 存储用户数据 |
| **前端** | React | 18+ | 用户界面 |
| **钱包连接** | ethers.js | 6.x | 与钱包交互 |

---

## 3. 环境准备

### 3.1 后端依赖配置

**步骤 1**: 打开项目的 `pom.xml`，添加以下依赖：

```xml
<dependencies>
    <!-- Web3j: 验证以太坊签名 -->
    <dependency>
        <groupId>org.web3j</groupId>
        <artifactId>core</artifactId>
        <version>4.11.0</version>
    </dependency>

    <!-- JWT: 生成和解析 token -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.5</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.12.5</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.12.5</version>
        <scope>runtime</scope>
    </dependency>

    <!-- Redis: 存储 nonce -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>

    <!-- Spring Security: 统一认证授权 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <!-- Lombok: 简化代码 -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

**步骤 2**: 点击 IDE 的 "Reload Maven" 按钮，下载依赖包。

### 3.2 配置文件

**步骤 3**: 编辑 `src/main/resources/application.yml`：

```yaml
spring:
  # Redis 配置（用于存储 nonce）
  redis:
    host: localhost
    port: 6379
    password: # 如果有密码则填写
    database: 0
    timeout: 5000ms

  # 数据库配置
  datasource:
    url: jdbc:mysql://localhost:3306/your_database?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: your_password
    driver-class-name: com.mysql.cj.jdbc.Driver

  # JPA 配置
  jpa:
    hibernate:
      ddl-auto: update # 生产环境改为 validate
    show-sql: true

# JWT 配置
jwt:
  secret: your-super-secret-key-minimum-256-bits-long-for-HS512 # 生产环境用环境变量
  access-token-expiration: 900000      # 15分钟（毫秒）
  refresh-token-expiration: 604800000  # 7天（毫秒）

# Web3 登录配置
web3:
  nonce-expiration: 300 # nonce 有效期（秒）5分钟
  domain: example.com   # 你的域名
```

**⚠️ 安全提示**:
- `jwt.secret` 在生产环境必须用环境变量，不要写死在配置文件！
- 建议使用 `openssl rand -base64 64` 生成随机密钥

### 3.3 数据库表设计

**步骤 4**: 执行以下 SQL 创建用户表：

```sql
-- 用户表
CREATE TABLE `users` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `wallet_address` VARCHAR(42) NOT NULL COMMENT '钱包地址（小写）',
  `chain_id` INT NOT NULL DEFAULT 1 COMMENT '链 ID（1=以太坊主网）',
  `nickname` VARCHAR(100) COMMENT '用户昵称',
  `avatar_url` VARCHAR(500) COMMENT '头像 URL',
  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `last_login_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后登录时间',
  `status` TINYINT DEFAULT 1 COMMENT '状态（1=正常 0=禁用）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wallet_address` (`wallet_address`),
  KEY `idx_last_login` (`last_login_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- JWT 黑名单表（用于实现登出功能）
CREATE TABLE `jwt_blacklist` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `token` VARCHAR(1000) NOT NULL COMMENT 'JWT token',
  `expiration` TIMESTAMP NOT NULL COMMENT 'token 过期时间',
  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_token` (`token`(255)),
  KEY `idx_expiration` (`expiration`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='JWT 黑名单';
```

---

## 4. 后端开发步骤

### 4.1 项目结构

```
src/main/java/com/yourcompany/project/
├── config/                    # 配置类
│   ├── SecurityConfig.java    # Spring Security 配置
│   ├── RedisConfig.java       # Redis 配置
│   └── JwtProperties.java     # JWT 配置属性
├── controller/                # 控制器
│   └── Web3AuthController.java
├── service/                   # 服务层
│   ├── Web3AuthService.java
│   ├── JwtService.java
│   └── UserService.java
├── repository/                # 数据访问层
│   └── UserRepository.java
├── entity/                    # 实体类
│   └── User.java
├── dto/                       # 数据传输对象
│   ├── NonceResponse.java
│   ├── Web3LoginRequest.java
│   └── AuthResponse.java
├── security/                  # 安全相关
│   ├── JwtAuthenticationFilter.java
│   └── JwtAuthenticationEntryPoint.java
└── util/                      # 工具类
    └── SignatureUtils.java    # 签名验证工具
```

### 4.2 实体类开发

#### 步骤 5: 创建 User 实体类

**文件**: `src/main/java/com/yourcompany/project/entity/User.java`

```java
package com.yourcompany.project.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "users")
public class User {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "wallet_address", unique = true, nullable = false, length = 42)
    private String walletAddress;  // 统一小写存储
    
    @Column(name = "chain_id", nullable = false)
    private Integer chainId = 1;   // 默认以太坊主网
    
    @Column(name = "nickname", length = 100)
    private String nickname;
    
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;
    
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;
    
    @Column(name = "status")
    private Integer status = 1;    // 1=正常 0=禁用
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        lastLoginAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        lastLoginAt = LocalDateTime.now();
    }
}
```

#### 步骤 6: 创建 DTO 类

**文件**: `src/main/java/com/yourcompany/project/dto/NonceResponse.java`

```java
package com.yourcompany.project.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class NonceResponse {
    private String nonce;
    private String message;  // 完整的待签名消息
}
```

**文件**: `src/main/java/com/yourcompany/project/dto/Web3LoginRequest.java`

```java
package com.yourcompany.project.dto;

import lombok.Data;

@Data
public class Web3LoginRequest {
    private String walletAddress;  // 钱包地址
    private String message;        // 签名的原始消息
    private String signature;      // 签名结果
    private String nonce;          // nonce 值
}
```

**文件**: `src/main/java/com/yourcompany/project/dto/AuthResponse.java`

```java
package com.yourcompany.project.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private Long expiresIn;        // 过期时间（秒）
    private String walletAddress;
    
    public AuthResponse(String accessToken, String refreshToken, Long expiresIn, String walletAddress) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresIn = expiresIn;
        this.walletAddress = walletAddress;
    }
}
```

### 4.3 核心工具类开发

#### 步骤 7: 签名验证工具类（核心！）

**文件**: `src/main/java/com/yourcompany/project/util/SignatureUtils.java`

```java
package com.yourcompany.project.util;

import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 以太坊签名验证工具类
 * 核心功能：从签名中恢复钱包地址
 */
public class SignatureUtils {
    
    /**
     * 验证签名是否正确
     * 
     * @param message 原始消息
     * @param signature 签名（0x 开头）
     * @param expectedAddress 期望的钱包地址
     * @return 验证结果
     */
    public static boolean verifySignature(String message, String signature, String expectedAddress) {
        try {
            String recoveredAddress = recoverAddress(message, signature);
            return expectedAddress.equalsIgnoreCase(recoveredAddress);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 从签名中恢复钱包地址（核心方法）
     * 
     * @param message 原始消息
     * @param signature 签名
     * @return 钱包地址
     */
    public static String recoverAddress(String message, String signature) throws Exception {
        // 1. 解析签名（去除 0x 前缀）
        byte[] signatureBytes = Numeric.hexStringToByteArray(signature);
        
        // 2. 提取 r, s, v 值
        // 签名格式: [r(32字节)][s(32字节)][v(1字节)]
        byte v = signatureBytes[64];
        if (v < 27) {
            v += 27; // 兼容某些钱包的 v 值格式
        }
        
        byte[] r = Arrays.copyOfRange(signatureBytes, 0, 32);
        byte[] s = Arrays.copyOfRange(signatureBytes, 32, 64);
        
        // 3. 构造签名对象
        Sign.SignatureData signatureData = new Sign.SignatureData(
            v,
            r,
            s
        );
        
        // 4. 计算消息哈希（以太坊特殊格式）
        // 以太坊签名会在消息前加前缀: "\x19Ethereum Signed Message:\n" + 消息长度
        String prefix = "\u0019Ethereum Signed Message:\n" + message.length();
        byte[] msgHash = Hash.sha3((prefix + message).getBytes(StandardCharsets.UTF_8));
        
        // 5. 从签名中恢复公钥
        int recId = v - 27;
        BigInteger publicKey = Sign.recoverFromSignature(
            recId,
            new Sign.SignatureData(signatureData.getV(), signatureData.getR(), signatureData.getS()),
            msgHash
        );
        
        if (publicKey == null) {
            throw new Exception("无法恢复公钥");
        }
        
        // 6. 从公钥计算地址
        String address = "0x" + Keys.getAddress(publicKey);
        return address.toLowerCase(); // 统一小写
    }
}
```

**💡 代码解释**:
- **第 31-35 行**: 解析签名字节，提取 r, s, v 参数
- **第 46-47 行**: 以太坊签名特殊规则，消息前要加前缀
- **第 50-55 行**: 椭圆曲线加密算法，从签名恢复公钥
- **第 61 行**: 从公钥计算出钱包地址（Keccak256 哈希的后 20 字节）

### 4.4 配置类开发

#### 步骤 8: JWT 配置属性

**文件**: `src/main/java/com/yourcompany/project/config/JwtProperties.java`

```java
package com.yourcompany.project.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
    private String secret;
    private Long accessTokenExpiration;
    private Long refreshTokenExpiration;
}
```

#### 步骤 9: Redis 配置

**文件**: `src/main/java/com/yourcompany/project/config/RedisConfig.java`

```java
package com.yourcompany.project.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {
    
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        
        // 使用 String 序列化
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        
        template.afterPropertiesSet();
        return template;
    }
}
```

### 4.5 服务层开发

#### 步骤 10: JWT 服务

**文件**: `src/main/java/com/yourcompany/project/service/JwtService.java`

```java
package com.yourcompany.project.service;

import com.yourcompany.project.config.JwtProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {
    
    private final JwtProperties jwtProperties;
    
    /**
     * 生成 Access Token
     */
    public String generateAccessToken(String walletAddress) {
        return generateToken(walletAddress, jwtProperties.getAccessTokenExpiration());
    }
    
    /**
     * 生成 Refresh Token
     */
    public String generateRefreshToken(String walletAddress) {
        return generateToken(walletAddress, jwtProperties.getRefreshTokenExpiration());
    }
    
    /**
     * 生成 JWT Token
     */
    private String generateToken(String walletAddress, Long expiration) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);
        
        SecretKey key = Keys.hmacShaKeyFor(
            jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8)
        );
        
        return Jwts.builder()
            .setSubject(walletAddress.toLowerCase()) // 主体：钱包地址
            .setIssuedAt(now)                        // 签发时间
            .setExpiration(expiryDate)               // 过期时间
            .signWith(key, SignatureAlgorithm.HS512) // 签名算法
            .compact();
    }
    
    /**
     * 从 Token 中提取钱包地址
     */
    public String getWalletAddressFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getSubject();
    }
    
    /**
     * 验证 Token 是否有效
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.error("JWT token 已过期");
        } catch (UnsupportedJwtException e) {
            log.error("不支持的 JWT token");
        } catch (MalformedJwtException e) {
            log.error("无效的 JWT token");
        } catch (SignatureException e) {
            log.error("JWT 签名验证失败");
        } catch (IllegalArgumentException e) {
            log.error("JWT token 为空");
        }
        return false;
    }
    
    /**
     * 解析 Token
     */
    private Claims parseToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(
            jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8)
        );
        
        return Jwts.parserBuilder()
            .setSigningKey(key)
            .build()
            .parseClaimsJws(token)
            .getBody();
    }
    
    /**
     * 获取 Token 剩余有效时间（毫秒）
     */
    public Long getExpirationTime(String token) {
        Claims claims = parseToken(token);
        Date expiration = claims.getExpiration();
        return expiration.getTime() - System.currentTimeMillis();
    }
}
```

#### 步骤 11: Web3 认证服务（核心！）

**文件**: `src/main/java/com/yourcompany/project/service/Web3AuthService.java`

```java
package com.yourcompany.project.service;

import com.yourcompany.project.dto.NonceResponse;
import com.yourcompany.project.util.SignatureUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class Web3AuthService {
    
    private final RedisTemplate<String, String> redisTemplate;
    
    @Value("${web3.nonce-expiration:300}")
    private Long nonceExpiration; // 默认 5 分钟
    
    @Value("${web3.domain:example.com}")
    private String domain;
    
    private static final String NONCE_PREFIX = "web3:nonce:";
    
    /**
     * 生成 nonce 和待签名消息
     */
    public NonceResponse generateNonce(String walletAddress) {
        // 1. 生成随机 nonce
        String nonce = UUID.randomUUID().toString().replace("-", "");
        
        // 2. 构造 SIWE 标准消息
        String message = buildSiweMessage(walletAddress, nonce);
        
        // 3. 存储 nonce 到 Redis（5 分钟过期）
        String redisKey = NONCE_PREFIX + walletAddress.toLowerCase();
        redisTemplate.opsForValue().set(redisKey, nonce, nonceExpiration, TimeUnit.SECONDS);
        
        log.info("为地址 {} 生成 nonce: {}", walletAddress, nonce);
        
        return new NonceResponse(nonce, message);
    }
    
    /**
     * 构造 SIWE 标准消息（EIP-4361）
     */
    private String buildSiweMessage(String walletAddress, String nonce) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(nonceExpiration);
        
        return String.format(
            "%s wants you to sign in with your Ethereum account:\n" +
            "%s\n\n" +
            "By signing, you agree to authenticate with your wallet.\n\n" +
            "URI: https://%s\n" +
            "Version: 1\n" +
            "Chain ID: 1\n" +
            "Nonce: %s\n" +
            "Issued At: %s\n" +
            "Expiration Time: %s",
            domain,
            walletAddress,
            domain,
            nonce,
            now.toString(),
            expiry.toString()
        );
    }
    
    /**
     * 验证签名
     */
    public boolean verifySignature(String walletAddress, String message, String signature, String nonce) {
        try {
            // 1. 验证 nonce 是否有效
            if (!validateNonce(walletAddress, nonce)) {
                log.error("Nonce 无效或已过期: {}", nonce);
                return false;
            }
            
            // 2. 验证签名
            boolean isValid = SignatureUtils.verifySignature(message, signature, walletAddress);
            
            if (isValid) {
                // 3. 验证通过后删除 nonce（一次性使用）
                deleteNonce(walletAddress);
                log.info("签名验证成功: {}", walletAddress);
            } else {
                log.error("签名验证失败: {}", walletAddress);
            }
            
            return isValid;
        } catch (Exception e) {
            log.error("验证签名时发生异常", e);
            return false;
        }
    }
    
    /**
     * 验证 nonce 是否有效
     */
    public boolean validateNonce(String walletAddress, String nonce) {
        String redisKey = NONCE_PREFIX + walletAddress.toLowerCase();
        String storedNonce = redisTemplate.opsForValue().get(redisKey);
        return nonce != null && nonce.equals(storedNonce);
    }
    
    /**
     * 删除 nonce
     */
    private void deleteNonce(String walletAddress) {
        String redisKey = NONCE_PREFIX + walletAddress.toLowerCase();
        redisTemplate.delete(redisKey);
    }
}
```

**💡 代码解释**:
- **第 35-42 行**: 生成 nonce 并存入 Redis，5 分钟后自动过期
- **第 48-69 行**: 构造符合 SIWE (EIP-4361) 标准的消息格式
- **第 75-96 行**: 验证流程三步：检查 nonce → 验证签名 → 删除 nonce

#### 步骤 12: 用户服务

**文件**: `src/main/java/com/yourcompany/project/repository/UserRepository.java`

```java
package com.yourcompany.project.repository;

import com.yourcompany.project.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByWalletAddress(String walletAddress);
    boolean existsByWalletAddress(String walletAddress);
}
```

**文件**: `src/main/java/com/yourcompany/project/service/UserService.java`

```java
package com.yourcompany.project.service;

import com.yourcompany.project.entity.User;
import com.yourcompany.project.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {
    
    private final UserRepository userRepository;
    
    /**
     * 创建或更新用户（登录时调用）
     */
    @Transactional
    public User createOrUpdateUser(String walletAddress) {
        String normalizedAddress = walletAddress.toLowerCase();
        
        return userRepository.findByWalletAddress(normalizedAddress)
            .map(user -> {
                // 更新最后登录时间
                user.setLastLoginAt(LocalDateTime.now());
                log.info("更新用户登录时间: {}", normalizedAddress);
                return userRepository.save(user);
            })
            .orElseGet(() -> {
                // 创建新用户
                User newUser = new User();
                newUser.setWalletAddress(normalizedAddress);
                newUser.setChainId(1); // 默认以太坊主网
                log.info("创建新用户: {}", normalizedAddress);
                return userRepository.save(newUser);
            });
    }
    
    /**
     * 根据钱包地址查询用户
     */
    public User getUserByWalletAddress(String walletAddress) {
        return userRepository.findByWalletAddress(walletAddress.toLowerCase())
            .orElse(null);
    }
}
```

### 4.6 控制器开发

#### 步骤 13: Web3 认证控制器

**文件**: `src/main/java/com/yourcompany/project/controller/Web3AuthController.java`

```java
package com.yourcompany.project.controller;

import com.yourcompany.project.dto.AuthResponse;
import com.yourcompany.project.dto.NonceResponse;
import com.yourcompany.project.dto.Web3LoginRequest;
import com.yourcompany.project.entity.User;
import com.yourcompany.project.service.JwtService;
import com.yourcompany.project.service.UserService;
import com.yourcompany.project.service.Web3AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth/web3")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // 生产环境需要指定具体域名
public class Web3AuthController {
    
    private final Web3AuthService web3AuthService;
    private final JwtService jwtService;
    private final UserService userService;
    
    /**
     * 步骤 1: 获取 nonce
     * GET /api/auth/web3/nonce/{walletAddress}
     */
    @GetMapping("/nonce/{walletAddress}")
    public ResponseEntity<NonceResponse> getNonce(@PathVariable String walletAddress) {
        try {
            // 验证地址格式
            if (!isValidAddress(walletAddress)) {
                return ResponseEntity.badRequest().build();
            }
            
            NonceResponse response = web3AuthService.generateNonce(walletAddress);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("生成 nonce 失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * 步骤 2: 验证签名并登录
     * POST /api/auth/web3/verify
     */
    @PostMapping("/verify")
    public ResponseEntity<?> verifyAndLogin(@RequestBody Web3LoginRequest request) {
        try {
            // 1. 验证请求参数
            if (request.getWalletAddress() == null || 
                request.getMessage() == null || 
                request.getSignature() == null || 
                request.getNonce() == null) {
                return ResponseEntity.badRequest().body("缺少必要参数");
            }
            
            // 2. 验证签名
            boolean isValid = web3AuthService.verifySignature(
                request.getWalletAddress(),
                request.getMessage(),
                request.getSignature(),
                request.getNonce()
            );
            
            if (!isValid) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("签名验证失败");
            }
            
            // 3. 创建或更新用户
            User user = userService.createOrUpdateUser(request.getWalletAddress());
            
            // 4. 生成 JWT
            String accessToken = jwtService.generateAccessToken(user.getWalletAddress());
            String refreshToken = jwtService.generateRefreshToken(user.getWalletAddress());
            Long expiresIn = jwtService.getExpirationTime(accessToken) / 1000; // 转换为秒
            
            // 5. 返回认证信息
            AuthResponse response = new AuthResponse(
                accessToken,
                refreshToken,
                expiresIn,
                user.getWalletAddress()
            );
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("登录验证失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("登录失败");
        }
    }
    
    /**
     * 刷新 Token
     * POST /api/auth/web3/refresh
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestHeader("Authorization") String authHeader) {
        try {
            // 1. 提取 Refresh Token
            String refreshToken = authHeader.replace("Bearer ", "");
            
            // 2. 验证 Refresh Token
            if (!jwtService.validateToken(refreshToken)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Token 无效或已过期");
            }
            
            // 3. 提取钱包地址
            String walletAddress = jwtService.getWalletAddressFromToken(refreshToken);
            
            // 4. 生成新的 Access Token
            String newAccessToken = jwtService.generateAccessToken(walletAddress);
            Long expiresIn = jwtService.getExpirationTime(newAccessToken) / 1000;
            
            // 5. 返回新 Token
            AuthResponse response = new AuthResponse(
                newAccessToken,
                refreshToken, // Refresh Token 不变
                expiresIn,
                walletAddress
            );
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("刷新 Token 失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("刷新失败");
        }
    }
    
    /**
     * 验证钱包地址格式
     */
    private boolean isValidAddress(String address) {
        return address != null && 
               address.matches("^0x[a-fA-F0-9]{40}$");
    }
}
```

### 4.7 Spring Security 配置

#### 步骤 14: JWT 认证过滤器

**文件**: `src/main/java/com/yourcompany/project/security/JwtAuthenticationFilter.java`

```java
package com.yourcompany.project.security;

import com.yourcompany.project.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    private final JwtService jwtService;
    
    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        
        try {
            // 1. 从请求头提取 JWT
            String jwt = extractJwtFromRequest(request);
            
            // 2. 验证 JWT 并设置认证信息
            if (jwt != null && jwtService.validateToken(jwt)) {
                String walletAddress = jwtService.getWalletAddressFromToken(jwt);
                
                // 3. 创建认证对象
                UsernamePasswordAuthenticationToken authentication = 
                    new UsernamePasswordAuthenticationToken(
                        walletAddress,
                        null,
                        new ArrayList<>() // 可以添加角色权限
                    );
                
                authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request)
                );
                
                // 4. 设置到 Security Context
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception e) {
            logger.error("设置用户认证失败", e);
        }
        
        filterChain.doFilter(request, response);
    }
    
    /**
     * 从请求头提取 JWT
     */
    private String extractJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
```

#### 步骤 15: Security 配置类

**文件**: `src/main/java/com/yourcompany/project/config/SecurityConfig.java`

```java
package com.yourcompany.project.config;

import com.yourcompany.project.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF（使用 JWT 不需要）
            .csrf(csrf -> csrf.disable())
            
            // 配置路径权限
            .authorizeHttpRequests(auth -> auth
                // Web3 认证接口无需登录
                .requestMatchers("/api/auth/web3/**").permitAll()
                // 其他 API 需要认证
                .requestMatchers("/api/**").authenticated()
                // 其他请求允许访问
                .anyRequest().permitAll()
            )
            
            // 无状态 Session 管理
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            
            // 添加 JWT 过滤器
            .addFilterBefore(
                jwtAuthenticationFilter, 
                UsernamePasswordAuthenticationFilter.class
            );
        
        return http.build();
    }
}
```

---

## 5. 前端集成步骤

### 5.1 前端项目准备

#### 步骤 16: 安装依赖

```bash
# 使用 npm
npm install ethers

# 或使用 yarn
yarn add ethers
```

### 5.2 创建钱包连接工具

#### 步骤 17: 创建 `src/utils/web3Auth.js`

```javascript
import { BrowserProvider } from 'ethers';

/**
 * Web3 认证工具类
 */
class Web3Auth {
    constructor(backendUrl) {
        this.backendUrl = backendUrl || 'http://localhost:8080';
        this.provider = null;
        this.signer = null;
    }

    /**
     * 检测 MetaMask 是否安装
     */
    isMetaMaskInstalled() {
        return typeof window.ethereum !== 'undefined';
    }

    /**
     * 连接钱包
     */
    async connectWallet() {
        if (!this.isMetaMaskInstalled()) {
            throw new Error('请先安装 MetaMask 钱包插件');
        }

        try {
            // 1. 请求用户授权连接钱包
            this.provider = new BrowserProvider(window.ethereum);
            const accounts = await this.provider.send('eth_requestAccounts', []);
            
            if (accounts.length === 0) {
                throw new Error('未检测到钱包账户');
            }

            // 2. 获取 signer
            this.signer = await this.provider.getSigner();
            const walletAddress = await this.signer.getAddress();

            console.log('✅ 钱包连接成功:', walletAddress);
            return walletAddress;
        } catch (error) {
            console.error('❌ 连接钱包失败:', error);
            throw error;
        }
    }

    /**
     * 完整的登录流程
     */
    async login() {
        try {
            // 1. 连接钱包
            const walletAddress = await this.connectWallet();

            // 2. 获取 nonce
            const { nonce, message } = await this.getNonce(walletAddress);

            // 3. 签名消息
            const signature = await this.signMessage(message);

            // 4. 验证签名并获取 JWT
            const authData = await this.verifySignature({
                walletAddress,
                message,
                signature,
                nonce
            });

            // 5. 保存认证信息
            this.saveAuthData(authData);

            console.log('✅ 登录成功!');
            return authData;
        } catch (error) {
            console.error('❌ 登录失败:', error);
            throw error;
        }
    }

    /**
     * 步骤 1: 获取 nonce
     */
    async getNonce(walletAddress) {
        const response = await fetch(
            `${this.backendUrl}/api/auth/web3/nonce/${walletAddress}`
        );

        if (!response.ok) {
            throw new Error('获取 nonce 失败');
        }

        const data = await response.json();
        console.log('📝 获取 nonce:', data.nonce);
        return data;
    }

    /**
     * 步骤 2: 签名消息
     */
    async signMessage(message) {
        if (!this.signer) {
            throw new Error('请先连接钱包');
        }

        console.log('✍️ 请在 MetaMask 中签名...');
        const signature = await this.signer.signMessage(message);
        console.log('✅ 签名完成');
        return signature;
    }

    /**
     * 步骤 3: 验证签名
     */
    async verifySignature(loginData) {
        const response = await fetch(
            `${this.backendUrl}/api/auth/web3/verify`,
            {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(loginData)
            }
        );

        if (!response.ok) {
            const error = await response.text();
            throw new Error(error || '验证签名失败');
        }

        return await response.json();
    }

    /**
     * 保存认证数据到 localStorage
     */
    saveAuthData(authData) {
        localStorage.setItem('accessToken', authData.accessToken);
        localStorage.setItem('refreshToken', authData.refreshToken);
        localStorage.setItem('walletAddress', authData.walletAddress);
    }

    /**
     * 获取保存的 Access Token
     */
    getAccessToken() {
        return localStorage.getItem('accessToken');
    }

    /**
     * 刷新 Token
     */
    async refreshToken() {
        const refreshToken = localStorage.getItem('refreshToken');
        if (!refreshToken) {
            throw new Error('Refresh Token 不存在');
        }

        const response = await fetch(
            `${this.backendUrl}/api/auth/web3/refresh`,
            {
                method: 'POST',
                headers: {
                    'Authorization': `Bearer ${refreshToken}`
                }
            }
        );

        if (!response.ok) {
            throw new Error('刷新 Token 失败');
        }

        const authData = await response.json();
        this.saveAuthData(authData);
        return authData;
    }

    /**
     * 登出
     */
    logout() {
        localStorage.removeItem('accessToken');
        localStorage.removeItem('refreshToken');
        localStorage.removeItem('walletAddress');
        console.log('✅ 已登出');
    }

    /**
     * 检查是否已登录
     */
    isAuthenticated() {
        return !!this.getAccessToken();
    }

    /**
     * 获取当前登录的钱包地址
     */
    getCurrentWalletAddress() {
        return localStorage.getItem('walletAddress');
    }
}

export default Web3Auth;
```

### 5.3 React 组件示例

#### 步骤 18: 创建登录组件

**文件**: `src/components/Web3LoginButton.jsx`

```javascript
import React, { useState } from 'react';
import Web3Auth from '../utils/web3Auth';

const Web3LoginButton = () => {
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState('');
    const [walletAddress, setWalletAddress] = useState('');

    // 初始化 Web3Auth
    const web3Auth = new Web3Auth('http://localhost:8080');

    // 处理登录
    const handleLogin = async () => {
        setLoading(true);
        setError('');

        try {
            const authData = await web3Auth.login();
            setWalletAddress(authData.walletAddress);
            alert('登录成功！');
        } catch (err) {
            setError(err.message);
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    // 处理登出
    const handleLogout = () => {
        web3Auth.logout();
        setWalletAddress('');
        alert('已登出');
    };

    // 检查是否已登录
    const isLoggedIn = web3Auth.isAuthenticated();

    return (
        <div style={{ padding: '20px', border: '1px solid #ccc', borderRadius: '8px' }}>
            <h2>Web3 钱包登录</h2>

            {error && (
                <div style={{ color: 'red', marginBottom: '10px' }}>
                    ❌ {error}
                </div>
            )}

            {!isLoggedIn ? (
                <button 
                    onClick={handleLogin} 
                    disabled={loading}
                    style={{
                        padding: '10px 20px',
                        fontSize: '16px',
                        cursor: loading ? 'not-allowed' : 'pointer',
                        backgroundColor: '#4CAF50',
                        color: 'white',
                        border: 'none',
                        borderRadius: '5px'
                    }}
                >
                    {loading ? '连接中...' : '🦊 Connect Wallet'}
                </button>
            ) : (
                <div>
                    <p>✅ 已连接: {walletAddress || web3Auth.getCurrentWalletAddress()}</p>
                    <button 
                        onClick={handleLogout}
                        style={{
                            padding: '10px 20px',
                            fontSize: '16px',
                            cursor: 'pointer',
                            backgroundColor: '#f44336',
                            color: 'white',
                            border: 'none',
                            borderRadius: '5px'
                        }}
                    >
                        登出
                    </button>
                </div>
            )}

            <div style={{ marginTop: '20px', fontSize: '14px', color: '#666' }}>
                <p>💡 提示:</p>
                <ul>
                    <li>请确保已安装 MetaMask 浏览器插件</li>
                    <li>点击按钮后会弹出 MetaMask 签名窗口</li>
                    <li>签名不会消耗 Gas 费用</li>
                </ul>
            </div>
        </div>
    );
};

export default Web3LoginButton;
```

#### 步骤 19: 使用组件

**文件**: `src/App.jsx`

```javascript
import React from 'react';
import Web3LoginButton from './components/Web3LoginButton';

function App() {
    return (
        <div style={{ padding: '50px', maxWidth: '600px', margin: '0 auto' }}>
            <h1>我的应用</h1>
            <Web3LoginButton />
        </div>
    );
}

export default App;
```

### 5.4 API 请求拦截器（带 JWT）

#### 步骤 20: 创建 Axios 拦截器

**文件**: `src/utils/apiClient.js`

```javascript
import axios from 'axios';
import Web3Auth from './web3Auth';

// 创建 axios 实例
const apiClient = axios.create({
    baseURL: 'http://localhost:8080/api',
    timeout: 10000
});

const web3Auth = new Web3Auth('http://localhost:8080');

// 请求拦截器：添加 JWT
apiClient.interceptors.request.use(
    (config) => {
        const token = web3Auth.getAccessToken();
        if (token) {
            config.headers['Authorization'] = `Bearer ${token}`;
        }
        return config;
    },
    (error) => {
        return Promise.reject(error);
    }
);

// 响应拦截器：处理 Token 过期
apiClient.interceptors.response.use(
    (response) => response,
    async (error) => {
        const originalRequest = error.config;

        // 如果是 401 错误且还没重试过
        if (error.response?.status === 401 && !originalRequest._retry) {
            originalRequest._retry = true;

            try {
                // 尝试刷新 Token
                await web3Auth.refreshToken();
                
                // 重新发送原请求
                const token = web3Auth.getAccessToken();
                originalRequest.headers['Authorization'] = `Bearer ${token}`;
                return apiClient(originalRequest);
            } catch (refreshError) {
                // 刷新失败，跳转到登录
                web3Auth.logout();
                window.location.href = '/login';
                return Promise.reject(refreshError);
            }
        }

        return Promise.reject(error);
    }
);

export default apiClient;
```

#### 使用示例

```javascript
import apiClient from './utils/apiClient';

// 调用需要认证的 API
async function getUserProfile() {
    try {
        const response = await apiClient.get('/user/profile');
        console.log('用户信息:', response.data);
    } catch (error) {
        console.error('获取用户信息失败:', error);
    }
}
```

---

## 6. 测试验证

### 6.1 后端单元测试

#### 步骤 21: 测试签名验证

**文件**: `src/test/java/com/yourcompany/project/util/SignatureUtilsTest.java`

```java
package com.yourcompany.project.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SignatureUtilsTest {
    
    @Test
    void testVerifySignature() {
        // 测试数据（来自真实 MetaMask 签名）
        String message = "example.com wants you to sign in...";
        String signature = "0x..."; // 实际签名
        String walletAddress = "0x..."; // 实际地址
        
        boolean isValid = SignatureUtils.verifySignature(message, signature, walletAddress);
        assertTrue(isValid, "签名验证应该成功");
    }
    
    @Test
    void testInvalidSignature() {
        String message = "test message";
        String signature = "0xinvalid";
        String walletAddress = "0x1234567890123456789012345678901234567890";
        
        boolean isValid = SignatureUtils.verifySignature(message, signature, walletAddress);
        assertFalse(isValid, "无效签名应该验证失败");
    }
}
```

### 6.2 手动测试流程

#### 步骤 22: 使用 Postman 测试

**测试 1: 获取 nonce**

```
GET http://localhost:8080/api/auth/web3/nonce/0xYourAddress

Response:
{
    "nonce": "abc123...",
    "message": "example.com wants you to sign in..."
}
```

**测试 2: 验证签名（需要 MetaMask 签名）**

```
POST http://localhost:8080/api/auth/web3/verify
Content-Type: application/json

{
    "walletAddress": "0xYourAddress",
    "message": "example.com wants you to sign in...",
    "signature": "0x...",
    "nonce": "abc123..."
}

Response:
{
    "accessToken": "eyJhbGci...",
    "refreshToken": "eyJhbGci...",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "walletAddress": "0xyouraddress"
}
```

**测试 3: 访问受保护的 API**

```
GET http://localhost:8080/api/user/profile
Authorization: Bearer eyJhbGci...
```

### 6.3 前端测试

#### 步骤 23: 浏览器测试

1. **启动后端**: `mvn spring-boot:run`
2. **启动前端**: `npm start`
3. **打开浏览器**: http://localhost:3000
4. **打开控制台**: F12 查看日志
5. **点击 "Connect Wallet"**
6. **MetaMask 弹窗**: 连接账户
7. **签名弹窗**: 点击签名
8. **查看结果**: 控制台显示 "✅ 登录成功!"

---

## 7. 生产部署

### 7.1 安全配置检查清单

- [ ] **JWT Secret**: 使用环境变量，至少 64 字节随机字符串
- [ ] **CORS 配置**: 限制为具体域名，不使用 `*`
- [ ] **HTTPS**: 强制使用 HTTPS，禁止 HTTP
- [ ] **Rate Limiting**: 限制 nonce 生成频率（防止 DDoS）
- [ ] **Redis 密码**: 生产环境 Redis 必须设置密码
- [ ] **数据库连接池**: 配置合理的连接池大小
- [ ] **日志脱敏**: 不记录完整签名和 token

### 7.2 环境变量配置

**文件**: `application-prod.yml`

```yaml
spring:
  redis:
    host: ${REDIS_HOST}
    port: ${REDIS_PORT}
    password: ${REDIS_PASSWORD}
  
  datasource:
    url: ${DATABASE_URL}
    username: ${DATABASE_USERNAME}
    password: ${DATABASE_PASSWORD}

jwt:
  secret: ${JWT_SECRET}  # 必须使用环境变量

web3:
  domain: ${APP_DOMAIN}
```

**设置环境变量**:

```bash
export JWT_SECRET=$(openssl rand -base64 64)
export REDIS_HOST=your-redis-host
export REDIS_PASSWORD=your-redis-password
export DATABASE_URL=jdbc:mysql://your-db-host:3306/db
export DATABASE_USERNAME=your-username
export DATABASE_PASSWORD=your-password
export APP_DOMAIN=yourdomain.com
```

### 7.3 CORS 配置（生产环境）

**文件**: `src/main/java/com/yourcompany/project/config/CorsConfig.java`

```java
package com.yourcompany.project.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

@Configuration
public class CorsConfig {
    
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        
        // 允许的域名（生产环境必须指定）
        config.setAllowedOrigins(Arrays.asList(
            "https://yourdomain.com",
            "https://www.yourdomain.com"
        ));
        
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(Arrays.asList("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        
        return new CorsFilter(source);
    }
}
```

### 7.4 Rate Limiting（防止滥用）

**添加依赖**:

```xml
<dependency>
    <groupId>com.github.vladimir-bukhtoyarov</groupId>
    <artifactId>bucket4j-core</artifactId>
    <version>8.7.0</version>
</dependency>
```

**实现限流**:

```java
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    
    private final Map<String, Bucket> cache = new ConcurrentHashMap<>();
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String ip = request.getRemoteAddr();
        Bucket bucket = resolveBucket(ip);
        
        if (bucket.tryConsume(1)) {
            return true;
        } else {
            response.setStatus(429); // Too Many Requests
            return false;
        }
    }
    
    private Bucket resolveBucket(String ip) {
        return cache.computeIfAbsent(ip, k -> {
            // 每分钟最多 10 次请求
            Bandwidth limit = Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1)));
            return Bucket.builder().addLimit(limit).build();
        });
    }
}
```

---

## 8. 常见问题

### Q1: MetaMask 签名后验证失败

**原因**: 消息格式不一致

**解决**:
1. 确保前后端使用相同的消息格式
2. 检查 nonce 是否正确传递
3. 确认没有多余的空格或换行

### Q2: Token 一直提示过期

**原因**: 服务器时间不同步

**解决**:
```bash
# 同步服务器时间
sudo ntpdate -u time.nist.gov
```

### Q3: Redis 连接失败

**检查**:
```bash
# 测试 Redis 连接
redis-cli -h localhost -p 6379 ping
```

### Q4: 如何支持多链（Polygon、BSC）?

**修改 User 实体**:
```java
@Column(name = "chain_id")
private Integer chainId; // 1=Ethereum, 137=Polygon, 56=BSC
```

**前端获取链 ID**:
```javascript
const chainId = await provider.send('eth_chainId', []);
```

### Q5: 如何实现"登出所有设备"?

**方案**: 为每个用户维护 Token 版本号

```java
@Entity
public class User {
    @Column(name = "token_version")
    private Integer tokenVersion = 0; // 每次登出所有设备时 +1
}

// JWT 中包含版本号
public String generateAccessToken(String walletAddress, Integer tokenVersion) {
    return Jwts.builder()
        .setSubject(walletAddress)
        .claim("version", tokenVersion)
        // ...
        .compact();
}

// 验证时检查版本号
public boolean validateToken(String token) {
    Claims claims = parseToken(token);
    Integer tokenVersion = claims.get("version", Integer.class);
    User user = userRepository.findByWalletAddress(claims.getSubject());
    return tokenVersion.equals(user.getTokenVersion());
}
```

---

## 9. 下一步优化

### 9.1 支持多个钱包

使用 **RainbowKit** 或 **Web3Modal** 快速集成:

```bash
npm install @rainbow-me/rainbowkit wagmi viem
```

### 9.2 混合登录（Web2 + Web3）

允许用户绑定邮箱:

```java
@Entity
public class User {
    private String walletAddress; // Web3
    private String email;         // Web2
    private String password;      // Web2
}
```

### 9.3 Gas 费用代付（元交易）

使用 **OpenZeppelin Defender** 或 **Biconomy** SDK

### 9.4 链上数据展示

查询用户的 NFT 和 Token:

```java
// 使用 Web3j 查询余额
EthGetBalance balance = web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST).send();
```

---

## 10. 参考资源

- **SIWE 官方文档**: https://docs.login.xyz
- **Web3j 文档**: https://docs.web3j.io
- **Ethers.js 文档**: https://docs.ethers.org
- **JWT 最佳实践**: https://datatracker.ietf.org/doc/html/rfc8725
- **Spring Security 文档**: https://docs.spring.io/spring-security

---

## 附录: 完整代码仓库结构

```
project-root/
├── backend/                          # Spring Boot 后端
│   ├── src/main/java/
│   │   └── com/yourcompany/project/
│   │       ├── config/
│   │       │   ├── SecurityConfig.java
│   │       │   ├── RedisConfig.java
│   │       │   ├── JwtProperties.java
│   │       │   └── CorsConfig.java
│   │       ├── controller/
│   │       │   └── Web3AuthController.java
│   │       ├── service/
│   │       │   ├── Web3AuthService.java
│   │       │   ├── JwtService.java
│   │       │   └── UserService.java
│   │       ├── repository/
│   │       │   └── UserRepository.java
│   │       ├── entity/
│   │       │   └── User.java
│   │       ├── dto/
│   │       │   ├── NonceResponse.java
│   │       │   ├── Web3LoginRequest.java
│   │       │   └── AuthResponse.java
│   │       ├── security/
│   │       │   ├── JwtAuthenticationFilter.java
│   │       │   └── JwtAuthenticationEntryPoint.java
│   │       └── util/
│   │           └── SignatureUtils.java
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── application-prod.yml
│   └── pom.xml
│
└── frontend/                         # React 前端
    ├── src/
    │   ├── components/
    │   │   └── Web3LoginButton.jsx
    │   ├── utils/
    │   │   ├── web3Auth.js
    │   │   └── apiClient.js
    │   └── App.jsx
    └── package.json
```

---

**🎉 恭喜！您已完成 Web3 钱包登录功能的完整开发！**

有任何问题请参考文档或咨询团队技术负责人。
