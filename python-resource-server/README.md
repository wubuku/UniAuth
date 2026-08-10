# Python 异构资源服务器

> 状态：Live。当前代码监听 `5002`，供应链锁定 Python 3.10，通过环境变量配置认证服务器/JWKS，
> 使用 TLS 默认验证，并优先读取 JWT `username` claim。请先阅读
> [配置基线](../docs/CONFIGURATION.md) 和 [验证指南](../docs/VERIFICATION.md)。

这是一个 Flask 实现的示例 REST 资源服务器，展示了如何验证来自 Spring Boot
认证服务器的 JWT。它不提供资源展示页面；当前展示页面是 UniAuth React 前端的
`/resource-test`，并通过跨 origin Bearer 请求访问本服务。

## 功能

- ✅ 从 JWKS 端点获取公钥
- ✅ 验证 JWT Token 签名
- ✅ 支持 Token 过期检查
- ✅ CORS 配置支持跨域请求
- ✅ 受保护的 API 端点
- ✅ Token 缓存以提高性能

## 安装

### 前置条件

- Python 3.10
- pip

### 设置

```bash
# 创建虚拟环境
python3.10 -m venv venv
source venv/bin/activate  # Linux/macOS
# 或 venv\Scripts\activate  # Windows

# 从带 hash 的精确运行时锁安装
python -m pip install --require-hashes -r requirements.lock
```

## 运行

```bash
python app.py
```

服务器将在 `http://0.0.0.0:5002` 上启动。

## API 端点

### 健康检查
```bash
GET /health
```

### 受保护资源
```bash
GET /api/protected
Authorization: Bearer <JWT_TOKEN>
```

返回示例：
```json
{
  "message": "Access granted",
  "user": {
    "id": "user-id",
    "username": "actual-username",
    "email": "user@example.com",
    "authorities": ["ROLE_USER"]
  },
  "resource": {
    "data": "This is protected data from Python resource server"
  }
}
```

当前实现优先把 `username` claim 写入响应；仅在兼容缺少该 claim 的旧 token 时
回退到 `sub`。UniAuth 新 token 的 `sub` 是用户 UUID，不应作为新 token 的显示用户名。

### 受保护资源信息
```bash
GET /api/protected/info
Authorization: Bearer <JWT_TOKEN>
```

## 配置

| 环境变量 | 默认值 |
|----------|--------|
| `AUTH_SERVER_URL` | `http://localhost:8081` |
| `JWKS_URL` | `${AUTH_SERVER_URL}/oauth2/jwks` |
| `JWT_ISSUER` | `https://auth.example.com` |
| `JWT_AUDIENCE` | `resource-server` |
| `RESOURCE_SERVER_PORT` | `5002` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:8081` |
| `FLASK_DEBUG` | `false` |

HTTPS 使用 `requests` 默认 CA 验证，不提供跳过证书验证的配置。

## Token 验证流程

1. 显式 dev/diagnostics 演示前端从登录/注册 JSON 响应取得 access token
2. diagnostics 路径从 localStorage 读取 access token，并在 Authorization 头中发送
   Bearer Token 到资源服务器；资源请求使用 `credentials: omit`，不携带资源 origin Cookie
3. 资源服务器从认证服务器获取 JWKS（缓存 1 小时）
4. 资源服务器使用 JWKS 中的公钥验证 Token 签名
5. 如果验证成功，返回受保护资源

UniAuth 还会写入 HttpOnly access-token Cookie，但 JavaScript 无法读取 HttpOnly
Cookie，完全不同 host 的资源服务器也不会收到 UniAuth host-only Cookie。因此当前
跨 origin diagnostics 流程依赖 JSON/localStorage Bearer token，而不是 Cookie。
生产构建排除 `/resource-test` 及 diagnostics bundle，普通生产认证路径不持久化
Bearer credential。若将该示例产品化，应继续使用内存 access token +
HttpOnly refresh cookie，或采用 BFF 让浏览器只持有 HttpOnly session cookie。

## 安全考虑

- ✅ Token 签名验证（RS256）
- ✅ Token 过期检查
- ✅ Audience（受众）声明验证
- ✅ Issuer（颁发者）验证
- ✅ CORS 配置限制跨域访问

## 依赖

- **Flask**: Web 框架
- **Flask-CORS**: CORS 支持
- **PyJWT**: JWT Token 处理
- **cryptography**: 密码学库
- **requests**: HTTP 客户端

## 测试

离线回归测试使用临时 RSA key 和 mock JWKS，不访问外部网络：

```bash
python3 -m unittest -v test_app.py
```

完整供应链门禁会重新生成并比较 runtime/tools hash lock，在隔离 venv 中安装、
运行 20 条契约测试并执行 `pip-audit`：

```bash
PYTHON_BIN=python3 scripts/verify-python-supply-chain.sh
```

当前 `cryptography 48.0.1` 的 3 个限时 audit 例外记录在
`pip-audit-exceptions.json`，均于 2026-10-01 UTC 到期；对应路径不被本示例使用。

真实联调可通过以下方式测试受保护端点：

```bash
# 1. 登录获取 Token
AUTH_SERVER_URL="${AUTH_SERVER_URL:-http://localhost:8081}"
TOKEN=$(curl -s -X POST "${AUTH_SERVER_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"testboth","password":"password123"}' | jq -r '.accessToken')

# 2. 使用 Token 访问受保护资源
curl -H "Authorization: Bearer $TOKEN" http://localhost:5002/api/protected
```

该联调会依赖已启动的 UniAuth。启动前必须确认所选 profile 和数据库目标安全。

完整的邮箱注册、登录、前端回跳和跨 origin 浏览器验证见
[邮箱登录浏览器 E2E](../docs/EMAIL_LOGIN_BROWSER_E2E.md)。

## 生产部署

对于生产环境，建议使用 WSGI 应用服务器如 Gunicorn：

```bash
pip install gunicorn
gunicorn -w 4 -b 0.0.0.0:5002 app:app
```

## 许可证

MIT
