# 异构资源服务器集成 - 验证清单

> 状态：Historical。本文记录的是 2026-01-25 的一次验证快照，未按 2026-08-07
> 当前代码重新执行。当前基线见 [验证指南](docs/VERIFICATION.md)；
> 文中的端口和部署域名不得替代 [配置基线](docs/CONFIGURATION.md)。

## ✅ 代码完整性检查

### 前端页面
- [x] HomePage.tsx - 原始首页完整保留
- [x] LoginPage.tsx - 登录页面完整
- [x] TestPage.tsx - 包含所有原始功能 + 资源服务器测试
  - [x] 17 处"绑定"相关功能
  - [x] 4 处 Python 资源服务器测试
  - [x] 用户信息显示
  - [x] 登录方式管理

### 后端服务
- [x] OAuth2TokenController - JWKS 和 Introspect 端点
- [x] JwtTokenService - Token 生成带 kid 字段
- [x] SecurityConfig - OAuth2 成功处理器
- [x] WebConfig - CORS 配置
- [x] AuthController - 登录和 Token 返回

### 环境配置
- [x] vite.config.ts - 生产构建使用相对路径
- [x] application.yml - Profile 配置
- [x] pom.xml - 依赖配置

## ✅ 功能验证

### 登录流程
```
✅ 用户登录 (testboth/password123)
✅ POST /api/auth/login → 200 OK
✅ 响应包含 accessToken (753+ 字符)
✅ 响应包含 refreshToken
✅ Token 存储到 localStorage
✅ Token 存储到 HttpOnly Cookie
```

### Token 结构
```
✅ 头部包含 "kid": "key-1"
✅ 算法: RS256
✅ 载荷包含: userId, email, authorities
✅ 载荷包含: aud: "resource-server"
✅ 载荷包含: iss: "https://auth.example.com"
✅ 载荷包含: jti (唯一标识)
```

### OAuth2 端点
```
✅ GET /oauth2/jwks → 返回 RSA 公钥
✅ POST /oauth2/introspect → Token 验证
✅ 返回 active: true/false
✅ 返回用户信息 (sub, userId, email, authorities)
```

## ✅ 测试账户

| 用户名 | 密码 | 登录方式 | 状态 |
|--------|------|--------|------|
| testlocal | password123 | 本地 | ✅ 工作 |
| testboth | password123 | 本地 + Google | ✅ 工作 |
| testsso | - | Google SSO | ✅ 配置 |

## ✅ 已知限制

- HTTPS 页面访问 HTTP API 会触发混合内容警告（浏览器安全）
- 此限制不影响登录和 Token 获取
- 生产环境应使用 HTTPS 后端

## 📋 部署清单

- [x] 所有源代码已提交
- [x] 前端已编译到 Spring Boot 静态资源
- [x] 数据库初始化脚本准备好
- [x] 测试账户已创建
- [x] 文档已更新

## 🚀 启动命令

```bash
# cd repo-root
export $(cat .env | xargs)
mvn clean compile spring-boot:run
```

## 🌐 访问地址

```
https://api.u2511175.nyat.app:55139/
```

---

**历史验证状态（2026-01-25）**: 当时记录为“全部通过”，当前版本未复验
**最后验证时间**: 2026-01-25 23:00  
**版本**: 4f4bee7
