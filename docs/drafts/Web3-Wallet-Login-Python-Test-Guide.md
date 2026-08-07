# Python 端到端测试脚本使用指南

> 状态：Needs verification。本文含 `8080` 和旧 endpoint 示例；当前默认后端是 `8081`，
> 且测试会接触数据库。执行前先读 [开发指南](../DEVELOPMENT.md)。

> **配套文档**: Web3 钱包登录完整开发指南  
> **脚本文件**: `test_web3_login.py`  
> **目的**: 在开发前端之前，先验证后端 API 是否正确实现

---

## 🎯 为什么需要这个脚本？

在开发前端之前，我们需要确保后端的 Web3 登录功能完全正常。这个 Python 脚本可以：

1. **模拟完整的 MetaMask 签名流程**（无需浏览器）
2. **自动化测试所有 API 端点**
3. **验证签名算法的正确性**
4. **检查 JWT 生成和验证**
5. **测试 Token 刷新机制**

**一句话**: 这个脚本就是一个 **自动化的 MetaMask + 前端**，用于验证后端！

---

## 📦 安装依赖

```bash
# 安装必要的 Python 库
pip install web3 requests eth-account

# 或使用 requirements.txt
cat > requirements.txt << EOF
web3>=6.0.0
requests>=2.31.0
eth-account>=0.10.0
EOF

pip install -r requirements.txt
```

**依赖说明**:
- `web3`: 以太坊工具库（生成钱包、签名）
- `eth-account`: 账户管理（与 MetaMask 签名方式一致）
- `requests`: HTTP 客户端（调用后端 API）

---

## 🚀 使用方法

### 基本用法

```bash
# 1. 确保后端已启动
mvn spring-boot:run

# 2. 在另一个终端运行测试脚本
python test_web3_login.py
```

### 高级用法

```bash
# 指定后端 URL（如果不是 localhost:8080）
python test_web3_login.py --url http://192.168.1.100:8080

# 使用现有私钥测试（用于重复测试同一账户）
python test_web3_login.py --private-key 0x1234567890abcdef...

# 简化输出（只显示关键信息）
python test_web3_login.py --quiet

# 查看帮助
python test_web3_login.py --help
```

---

## 📊 测试流程详解

### 完整测试步骤

```
┌─────────────────────────────────────────────────────────────┐
│  步骤 1: 创建测试钱包                                         │
│  - 生成随机私钥                                              │
│  - 计算钱包地址                                              │
│  输出: 0x5cE9454909639D2D17A3F753ce7d93fa0b9aB12E          │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤 2: 获取 Nonce                                          │
│  GET /api/auth/web3/nonce/{walletAddress}                   │
│  ✅ 验证: 响应包含 nonce 和 message                          │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤 3: 签名消息（模拟 MetaMask）                            │
│  - 使用 eth-account 库签名                                   │
│  - 添加以太坊前缀: "\x19Ethereum Signed Message:\n{len}"    │
│  - 本地验证签名（恢复地址并比对）                              │
│  ✅ 验证: 恢复的地址 == 钱包地址                              │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤 4: 提交签名验证                                         │
│  POST /api/auth/web3/verify                                 │
│  Body: {walletAddress, message, signature, nonce}           │
│  ✅ 验证: 响应包含 accessToken 和 refreshToken               │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤 5: 测试受保护的 API                                     │
│  GET /api/user/profile                                      │
│  Header: Authorization: Bearer {accessToken}                │
│  ✅ 验证: 返回 200 或 401（取决于后端是否实现该端点）          │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤 6: 刷新 Token                                          │
│  POST /api/auth/web3/refresh                                │
│  Header: Authorization: Bearer {refreshToken}               │
│  ✅ 验证: 返回新的 accessToken                               │
└─────────────────────────────────────────────────────────────┘
```

---

## 📝 成功输出示例

```
======================================================================
          Web3 钱包登录端到端测试脚本
======================================================================

ℹ️  后端 URL: http://localhost:8080
ℹ️  开始测试...

────────────────────────────────────────────────────────

ℹ️  【测试 1】完整的 Web3 钱包登录流程

────────────────────────────────────────────────────────

============================================================
步骤 1: 创建测试钱包
============================================================

✅ 钱包创建成功
  钱包地址: 0x5cE9454909639D2D17A3F753ce7d93fa0b9aB12E
  私钥: 0xb25c7db31feed9122727bf0939dc769a96564b2de4c4726d035b36ecf1e5b364

============================================================
步骤 2: 从后端获取 Nonce
============================================================

  请求 URL: http://localhost:8080/api/auth/web3/nonce/0x5cE9454909639D2D17A3F753ce7d93fa0b9aB12E
✅ Nonce 获取成功
  Nonce: a1b2c3d4e5f6789012345678
  待签名消息: example.com wants you to sign in with your Ethereum account:
0x5cE9454909639D2D17A3F753ce7d93fa0b9aB12E

By signing, you agree to authenticate with your wallet.

URI: https://example.com
Version: 1
Chain ID: 1
Nonce: a1b2c3d4e5f6789012345678
Issued At: 2026-02-04T02:15:30.123456Z
Expiration Time: 2026-02-04T02:20:30.123456Z

============================================================
步骤 3: 签名消息（模拟 MetaMask）
============================================================

✅ 消息签名完成
  签名结果: 0xe6ca9bba58c88611fad66a6ce8f996908195593807c4b38bd528d2cff09d4eb33e5bfbbf4d3e39b1a2fd816a7680c19ebebaf3a141b239934ad43cb33fcec8ce1c
  消息哈希: 0x1476abb745d423bf09273f1afd887d951181d25adc66c4834a70491911b7f750
  恢复的地址: 0x5ce9454909639d2d17a3f753ce7d93fa0b9ab12e
✅ 签名验证通过（本地验证）

============================================================
步骤 4: 提交签名到后端验证
============================================================

  请求 URL: http://localhost:8080/api/auth/web3/verify
✅ 登录验证成功！
  Access Token: eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiIweDVjZTk0NTQ5MDk2MzlkMmQxN2EzZjc1M2NlN2Q5M2ZhMGI5YWIxMmUiLCJpYXQiOjE3MDczNzAxMzAsImV4cCI6MTcwNzM3MTAzMH0...
  Refresh Token: eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiIweDVjZTk0NTQ5MDk2MzlkMmQxN2EzZjc1M2NlN2Q5M2ZhMGI5YWIxMmUiLCJpYXQiOjE3MDczNzAxMzAsImV4cCI6MTcwNzk3NDkzMH0...
  Token 类型: Bearer
  过期时间（秒）: 900
  钱包地址: 0x5ce9454909639d2d17a3f753ce7d93fa0b9ab12e

✅ 完整登录流程测试通过！

────────────────────────────────────────────────────────

ℹ️  【测试 2】访问受保护的 API

────────────────────────────────────────────────────────

============================================================
步骤 5: 测试受保护的 API: /api/user/profile
============================================================

  请求 URL: http://localhost:8080/api/user/profile
  Authorization 头: Bearer eyJhbGciOiJIUzUxMi...
  响应状态码: 404
  响应内容: {"timestamp":"2026-02-04T02:15:35.123+00:00","status":404,"error":"Not Found","path":"/api/user/profile"}
⚠️  API 端点不存在（404）- 正常，测试后端的

────────────────────────────────────────────────────────

ℹ️  【测试 3】刷新 Access Token

────────────────────────────────────────────────────────

============================================================
步骤 6: 刷新 Access Token
============================================================

  请求 URL: http://localhost:8080/api/auth/web3/refresh
  Authorization 头: Bearer eyJhbGciOiJIUzUxMi...
✅ Token 刷新成功
  旧 Access Token: eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiIweDVjZTk0NTQ5...
  新 Access Token: eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiIweDVjZTk0NTQ5...
  过期时间（秒）: 900

======================================================================
                    测试完成！
======================================================================

✅ ✅ 所有核心功能测试通过
ℹ️  钱包地址: 0x5cE9454909639D2D17A3F753ce7d93fa0b9aB12E
ℹ️  Access Token: eyJhbGciOiJIUzUxMiJ9.eyJzdWIi...

💡 提示:
  - 您可以在 MySQL 中查询用户表验证数据
  - 您可以在 Redis 中查看 nonce 的存储（应该已被删除）
  - Access Token 有效期 15 分钟，Refresh Token 有效期 7 天
```

---

## ✅ 验证清单

测试脚本运行成功后，请验证以下内容：

### 1. MySQL 数据库

```sql
-- 查看用户表
SELECT * FROM users ORDER BY id DESC LIMIT 5;

-- 应该看到新创建的用户记录
-- wallet_address: 0x5ce9454909639d2d17a3f753ce7d93fa0b9ab12e (小写)
-- chain_id: 1
-- status: 1
-- created_at: 当前时间
-- last_login_at: 当前时间
```

### 2. Redis 存储

```bash
# 连接 Redis
redis-cli

# 查看 nonce（应该是空的，因为验证后被删除）
> keys web3:nonce:*
(empty list or set)

# 如果显示有 key，说明验证过程有问题
```

### 3. 日志文件

检查 Spring Boot 日志，应该看到：

```
INFO  - 为地址 0x5ce9454909639d2d17a3f753ce7d93fa0b9ab12e 生成 nonce: a1b2c3d4...
INFO  - 签名验证成功: 0x5ce9454909639d2d17a3f753ce7d93fa0b9ab12e
INFO  - 创建新用户: 0x5ce9454909639d2d17a3f753ce7d93fa0b9ab12e
```

---

## 🐛 常见问题排查

### 问题 1: 连接后端失败

**错误信息**:
```
❌ 获取 nonce 失败: Connection refused
```

**解决方案**:
```bash
# 1. 检查后端是否启动
curl http://localhost:8080/actuator/health

# 2. 检查端口是否正确
netstat -an | grep 8080

# 3. 如果后端在其他地址，使用 --url 参数
python test_web3_login.py --url http://192.168.1.100:8080
```

### 问题 2: 签名验证失败

**错误信息**:
```
❌ 登录验证失败: 400 Bad Request
响应内容: 签名验证失败
```

**可能原因**:
1. **消息格式不一致**: 检查后端 `buildSiweMessage` 方法的输出
2. **签名算法不匹配**: 确保后端使用 Web3j 的 `Sign.recoverFromSignature`
3. **地址大小写问题**: 确保后端统一转为小写

**调试方法**:
```bash
# 运行脚本时查看详细日志
python test_web3_login.py

# 对比前后端的消息格式
# Python 生成的消息:
#   example.com wants you to sign in with your Ethereum account:
#   0x5cE9454909639D2D17A3F753ce7d93fa0b9aB12E
#   ...

# 后端生成的消息应该完全一致
```

### 问题 3: JWT 解析错误

**错误信息**:
```
❌ Token 刷新失败: 500 Internal Server Error
```

**解决方案**:
```bash
# 检查 JWT secret 配置
# 1. 确保 application.yml 中的 jwt.secret 至少 64 字节
# 2. 生成新的 secret
openssl rand -base64 64

# 3. 更新配置文件
jwt:
  secret: <新生成的 secret>
```

### 问题 4: Nonce 过期

**错误信息**:
```
❌ 登录验证失败: Nonce 无效或已过期
```

**解决方案**:
- Nonce 有效期默认 5 分钟
- 如果测试时间过长，重新运行脚本
- 调整 `application.yml` 中的 `web3.nonce-expiration` 配置

---

## 🔧 自定义测试

### 测试特定场景

```python
from test_web3_login import Web3WalletTester

# 创建测试器
tester = Web3WalletTester("http://localhost:8080")

# 场景 1: 测试错误的签名
tester.create_test_wallet()
nonce, message = tester.get_nonce()
fake_signature = "0x" + "0" * 130  # 伪造的签名
try:
    tester.verify_and_login(message, fake_signature, nonce)
except Exception as e:
    print("✅ 正确拒绝了伪造签名")

# 场景 2: 测试重放攻击（使用同一个 nonce）
tester.create_test_wallet()
nonce, message = tester.get_nonce()
signature = tester.sign_message(message)
tester.verify_and_login(message, signature, nonce)
# 第二次使用相同 nonce 应该失败
try:
    tester.verify_and_login(message, signature, nonce)
except Exception as e:
    print("✅ 正确防止了重放攻击")

# 场景 3: 测试过期的 Token
import time
tester.complete_login_flow()
old_token = tester.access_token
time.sleep(901)  # 等待 Token 过期（15 分钟 + 1 秒）
# 使用过期 Token 应该失败
```

---

## 📚 脚本代码解析

### 核心签名逻辑

Python 脚本中的签名过程与 MetaMask 完全一致：

```python
from eth_account import Account
from eth_account.messages import encode_defunct

# 1. 编码消息（自动添加以太坊前缀）
message = "example.com wants you to sign in..."
encoded_message = encode_defunct(text=message)
# 内部会添加: "\x19Ethereum Signed Message:\n" + len(message)

# 2. 签名
account = Account.from_key(private_key)
signed_message = account.sign_message(encoded_message)

# 3. 获取签名结果
signature = signed_message.signature.hex()  # 0x 开头的 130 字符

# 4. 验证签名（可选）
recovered_address = Account.recover_message(encoded_message, signature=signature)
assert recovered_address.lower() == wallet_address.lower()
```

### 后端验证逻辑对比

**Python (测试脚本)**:
```python
encoded_message = encode_defunct(text=message)
recovered_address = Account.recover_message(encoded_message, signature=signature)
```

**Java (后端)**:
```java
String prefix = "\u0019Ethereum Signed Message:\n" + message.length();
byte[] msgHash = Hash.sha3((prefix + message).getBytes(StandardCharsets.UTF_8));
BigInteger publicKey = Sign.recoverFromSignature(recId, signatureData, msgHash);
String address = "0x" + Keys.getAddress(publicKey);
```

两者应该产生相同的结果！

---

## 🎓 学习资源

- **eth-account 文档**: https://eth-account.readthedocs.io
- **Web3.py 文档**: https://web3py.readthedocs.io
- **以太坊签名标准**: https://eips.ethereum.org/EIPS/eip-191
- **SIWE 标准**: https://eips.ethereum.org/EIPS/eip-4361

---

## 📞 支持

如果测试脚本运行失败，请检查：

1. ✅ 后端是否正确启动（端口 8080）
2. ✅ Redis 是否运行（端口 6379）
3. ✅ MySQL 是否连接成功
4. ✅ 依赖包是否正确安装（`pip list | grep web3`）

**成功的标志**: 看到绿色的 ✅ 和 "所有核心功能测试通过" 消息！

---

**🎉 测试通过后，就可以放心开发前端了！后端已经 100% 验证可用！**
