# 集成检查清单

> 状态：Reference。本文是复制认证模块时的通用检查表，不代表当前仓库已经逐项通过。
> 当前验证门槛见 [验证指南](../VERIFICATION.md)。

按照本清单逐项检查，确保认证模块正确集成到你的项目中。

## ✅ 代码集成

- [ ] 拷贝了 `config/`, `controller/`, `dto/`, `entity/`, `repository/`, `service/` 目录
- [ ] 使用 IDE 重构或 sed 命令修改了所有包名引用
- [ ] 验证了主应用类中的 `@EnableJpaRepositories` 和 `@ComponentScan` 注解
- [ ] 检查了是否有硬编码的包名路径

## 📦 Maven 依赖

- [ ] 添加了 `spring-boot-starter-security`
- [ ] 添加了 `spring-boot-starter-data-jpa`
- [ ] 添加了 `org.postgresql:postgresql`
- [ ] 添加了 JWT 相关依赖（jjwt-api, jjwt-impl, jjwt-jackson）
- [ ] 添加了 `spring-security-oauth2-authorization-server` （可选）
- [ ] 添加了 `spring-boot-starter-oauth2-client` （可选）
- [ ] 添加了 `org.springframework.session:spring-session-jdbc` （可选但推荐，用于 session 持久化）

## 🗄️ 数据库

- [ ] PostgreSQL 已安装并运行
- [ ] 创建了数据库：`your_project_db`
- [ ] 执行了 `schema-postgresql.sql` 脚本创建表
- [ ] 验证了以下认证相关的表已创建：
  - [ ] `users`
  - [ ] `user_login_methods`
  - [ ] `user_authorities`
  - [ ] `token_blacklist`

- [ ] （可选）验证了 Spring Session JDBC 表已创建（如果启用了 session 持久化）：
  - [ ] `SPRING_SESSION` - 存储 session 信息
  - [ ] `SPRING_SESSION_ATTRIBUTES` - 存储 session 属性值

## ⚙️ 配置文件

- [ ] 创建了 `application.yml` 并配置了以下内容：
  - [ ] `spring.datasource.url` - PostgreSQL 连接字符串
  - [ ] `spring.datasource.username` - 数据库用户名
  - [ ] `spring.datasource.password` - 数据库密码
  - [ ] `spring.jpa.hibernate.ddl-auto` - 设置为 `validate` 或 `update`
  - [ ] `spring.jpa.database-platform` - 设置为 `org.hibernate.dialect.PostgreSQLDialect`
  - [ ] （可选）`spring.session.store-type: jdbc` - 启用 JDBC session 持久化
  - [ ] （可选）`spring.session.jdbc.initialize-schema: always` - 自动创建 session 表

- [ ] 如果需要 OAuth2，配置了：
  - [ ] `spring.security.oauth2.client.registration.google` （Google）
  - [ ] `spring.security.oauth2.client.registration.github` （GitHub，可选）
  - [ ] `spring.security.oauth2.client.registration.x` （Twitter，可选）

## 🚀 应用启动验证

- [ ] 应用成功启动，没有编译错误
- [ ] 日志中没有关键的 Bean 初始化错误
- [ ] 数据库连接成功
- [ ] 所有 Repository 成功加载
- [ ] （可选）Spring Session JDBC 初始化成功，SPRING_SESSION 表已创建

## 🧪 功能测试

### 本地登录流程
- [ ] 成功注册新用户（POST /api/auth/register）
- [ ] 成功登录已注册用户（POST /api/auth/login）
- [ ] 获取了有效的 Access Token 和 Refresh Token
- [ ] 使用 Token 成功访问受保护资源（GET /api/auth/current-user）
- [ ] Token 过期后成功刷新（POST /api/auth/refresh）
- [ ] 成功登出（POST /api/auth/logout）

### 用户信息管理
- [ ] 可以获取当前用户信息（GET /api/auth/current-user）
- [ ] 可以更新用户信息（PUT /api/auth/user）
- [ ] 可以查看用户的所有登录方式（GET /api/user/login-methods）

### 权限管理（如果已配置）
- [ ] 用户具有正确的权限标记（ROLE_USER, ROLE_ADMIN 等）
- [ ] 受保护的端点正确检查权限
- [ ] `@PreAuthorize` 注解正常工作

## 🔌 OAuth2 集成（可选）

### Google OAuth2
- [ ] 获取了 Google Client ID 和 Client Secret
- [ ] 在 Google Cloud Console 中配置了重定向 URI
- [ ] 环境变量 `GOOGLE_CLIENT_ID` 和 `GOOGLE_CLIENT_SECRET` 已设置
- [ ] 成功跳转到 Google 登录页面
- [ ] 用户授权后成功回调
- [ ] 用户信息正确保存到数据库

### GitHub OAuth2（可选）
- [ ] 获取了 GitHub Client ID 和 Client Secret
- [ ] 在 GitHub 中配置了授权回调 URL
- [ ] 环境变量 `GITHUB_CLIENT_ID` 和 `GITHUB_CLIENT_SECRET` 已设置
- [ ] 成功跳转到 GitHub 登录页面
- [ ] 用户授权后成功回调

### Twitter/X OAuth2（可选）
- [ ] 获取了 Twitter Client ID 和 Client Secret
- [ ] 在 Twitter Developer Portal 中配置了重定向 URI
- [ ] 环境变量 `TWITTER_CLIENT_ID` 和 `TWITTER_CLIENT_SECRET` 已设置
- [ ] 成功跳转到 Twitter 登录页面

## 👥 用户绑定（如果启用 OAuth2）

- [ ] 本地用户可以绑定 Google 账户
- [ ] 本地用户可以绑定 GitHub 账户（如果配置）
- [ ] 用户可以解绑已绑定的 SSO 账户
- [ ] 同一 SSO 账户不能绑定多个本地用户
- [ ] 在 `user_login_methods` 表中正确记录了所有登录方式

## 🔒 安全相关

- [ ] 密码使用 BCrypt 加密（不是明文）
- [ ] JWT Token 使用 HMAC-SHA256 签名
- [ ] Token 在 HttpOnly Cookie 中存储（防止 XSS）
- [ ] CSRF 保护已启用
- [ ] 敏感信息（Client ID, Secret 等）存储在环境变量中
- [ ] 生产环境已配置 HTTPS

## 📝 日志和监控

- [ ] 可以看到认证相关的 DEBUG 日志
- [ ] 登录失败时有清晰的错误信息
- [ ] Token 验证失败时有相应的日志
- [ ] 可以追踪用户的认证历史

## 🌐 前端集成（如果有前端项目）

- [ ] 前端调用 `/api/auth/register` 创建账户
- [ ] 前端调用 `/api/auth/login` 进行登录
- [ ] 前端正确存储和使用 Access Token
- [ ] 前端在请求 header 中添加 `Authorization: Bearer <token>`
- [ ] 前端处理 401 错误并刷新 Token
- [ ] 前端显示用户信息（从 `/api/auth/current-user` 获取）
- [ ] 前端登出时清除本地 Token

## 🚀 生产环境检查

- [ ] 配置了 `application-prod.yml`
- [ ] 生产数据库已创建并初始化
- [ ] OAuth2 credentials 已配置为生产环境的值
- [ ] Token 过期时间设置合理（Access: 1 小时，Refresh: 7 天）
- [ ] 日志级别设置为 INFO（不是 DEBUG）
- [ ] 启用了 HTTPS 和安全 Cookie
- [ ] 数据库备份策略已实施

## 📊 性能相关

- [ ] 测试了大量用户登录场景（并发）
- [ ] 查看了数据库查询性能（可用 EXPLAIN 分析）
- [ ] 配置了数据库连接池大小（HikariCP）
- [ ] 认证端点的响应时间在可接受范围内（< 500ms）

## 📋 文档和维护

- [ ] 更新了项目的 README，说明认证功能
- [ ] 为新增的自定义字段编写了注释
- [ ] 记录了任何修改和扩展
- [ ] 创建了运维文档（如何重置密码、管理用户等）

## ❌ 常见问题自检

### 编译问题
- [ ] 所有包名都正确引用（没有 `com.example.oauth2demo`）
- [ ] 没有循环依赖
- [ ] 所有导入语句正确

### 运行时问题
- [ ] 应用启动时没有 ClassNotFoundException
- [ ] 没有 NoSuchBeanDefinitionException
- [ ] 数据库连接成功，没有 SQLException

### 功能问题
- [ ] 登录不返回 401（密码哈希正确）
- [ ] Token 可以正确验证
- [ ] 用户信息正确保存和读取

### 集成问题
- [ ] 没有与现有代码冲突
- [ ] 权限管理与现有业务逻辑兼容
- [ ] 数据库表名没有冲突

## ✨ 额外优化建议

- [ ] 添加了速率限制（Rate Limiting）防止暴力攻击
- [ ] 配置了 Token 黑名单管理
- [ ] 实现了审计日志（谁在何时进行了认证）
- [ ] 添加了两因素认证（2FA，可选）
- [ ] 配置了密码重置流程
- [ ] 实现了账户锁定机制（多次失败后）

## 🔄 Session 持久化与多服务器支持

- [ ] 已启用 Spring Session JDBC（可选）
- [ ] 验证了 SPRING_SESSION 表自动创建
- [ ] 验证了 session 在数据库中持久化
- [ ] 测试了应用重启后 session 仍然有效
- [ ] （可选）测试了多服务器实例共享 session
- [ ] （如果使用多服务器）配置了所有实例指向同一数据库
- [ ] 监控了 SPRING_SESSION 表的大小和性能

---

## 🎯 完成标志

当所有项都已检查且打勾时，恭喜你！认证模块已成功集成到你的项目中。

最后一步：
```bash
# 运行应用
mvn spring-boot:run

# 或使用 IDE 的 Run 按钮
# 或打包后运行：
mvn clean package
java -jar target/your-project.jar
```

如有任何问题，请参考 `INTEGRATION-GUIDE.md` 中的 "常见集成问题排查" 部分。
