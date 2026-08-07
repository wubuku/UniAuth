-- UniAuth 数据库Schema定义
-- 支持多种登录方式：本地账号密码登录、Google登录、GitHub登录、X(Twitter)平台登录、Web3钱包登录
-- PostgreSQL版本

-- =====================================================
-- 用户表 (users)
-- 存储用户的基本信息，是整个系统的基础表
-- =====================================================
CREATE TABLE IF NOT EXISTS users (
    -- 主键：UUID格式，由应用层生成
    -- 格式：xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
    id VARCHAR(36) PRIMARY KEY,
    
    -- 用户名：用于本地账号密码登录
    -- 唯一约束，不可为空
    username TEXT UNIQUE NOT NULL,
    
    -- 邮箱：用于用户身份验证和通知
    -- 唯一约束，不可为空
    email TEXT UNIQUE NOT NULL,
    
    -- 显示名称：用户友好的名称
    display_name TEXT,
    
    -- 头像URL：用户头像的链接地址
    avatar_url TEXT,
    
    -- 邮箱是否已验证：默认false
    -- 对于Google登录等OAuth2.0登录方式，此字段通常为true
    email_verified BOOLEAN DEFAULT FALSE,
    
    -- 用户是否已启用：默认true
    -- 禁用后用户无法登录
    enabled BOOLEAN DEFAULT TRUE,
    
    -- 创建时间：记录用户账号创建时间
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- 更新时间：记录用户信息最后更新时间
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- 最后登录时间：记录用户最后登录时间
    last_login_at TIMESTAMP
);

-- 用户表索引
-- 邮箱索引：用于快速查找用户
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
-- 用户名索引：用于本地登录验证
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);

-- =====================================================
-- 用户登录方式表 (user_login_methods)
-- 存储用户绑定的各种登录方式，是支持多方式登录的核心表
-- =====================================================
CREATE TABLE IF NOT EXISTS user_login_methods (
    -- 主键：UUID格式，由应用层生成
    id VARCHAR(36) PRIMARY KEY,
    
    -- 关联用户ID：引用users表的UUID
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    -- 认证提供商：标识登录方式类型
    -- 取值范围：
    --   - 'LOCAL'      : 本地账号密码登录
    --   - 'GOOGLE'     : Google OAuth2.0登录
    --   - 'GITHUB'     : GitHub OAuth2.0登录
    --   - 'X'          : X(Twitter) OAuth2.0登录
    --   - 'WEB3'       : Web3钱包登录（MetaMask等）
    auth_provider TEXT NOT NULL,
    
    -- 提供商用户ID：在各OAuth2.0提供商平台上的用户唯一标识
    -- 对于OAuth2.0登录方式（如Google、GitHub、X），此字段存储提供商返回的用户ID
    -- 示例：
    --   - Google登录：存储Google账号的sub claim
    --   - GitHub登录：存储GitHub的用户ID
    --   - X登录：存储Twitter的用户ID
    --   - Web3登录：通常为空，使用wallet_address作为标识
    provider_user_id TEXT,
    
    -- 提供商邮箱：在OAuth2.0登录时获取的用户邮箱
    -- 注意：用户可能在提供商平台更改邮箱，此字段可能不完全准确
    provider_email TEXT,
    
    -- 提供商用户名：在OAuth2.0登录时获取的用户名
    -- 对于GitHub登录，存储GitHub用户名
    -- 对于X登录，存储Twitter用户名
    provider_username TEXT,
    
    -- 本地用户名：用于本地账号密码登录
    -- 格式：通常与主users表的username一致，但允许不同
    local_username TEXT,
    
    -- 本地密码哈希：bcrypt加密的密码
    -- 仅在auth_provider='LOCAL'时使用
    local_password_hash TEXT,
    
    -- 是否为主要登录方式：默认false
    -- 用户可设置多个登录方式，但只能有一个主要方式
    -- 主要方式在用户信息展示、权限继承等场景中使用
    is_primary BOOLEAN DEFAULT FALSE,
    
    -- 是否已验证：默认false
    -- 对于本地登录，可用于邮箱验证流程
    -- 对于OAuth2.0登录，通常为true（因为提供商已验证）
    is_verified BOOLEAN DEFAULT FALSE,
    
    -- 绑定时间：记录此登录方式的绑定时间
    linked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- 最后使用时间：记录此登录方式最后使用的时间
    last_used_at TIMESTAMP,
    
    -- =====================================================
    -- Web3钱包登录专用字段（auth_provider='WEB3'时使用）
    -- =====================================================
    
    -- 区块链链ID：标识使用的区块链网络
    -- 取值标准：
    --   - 1   : Ethereum Mainnet
    --   - 5   : Ethereum Goerli Testnet
    --   - 137 : Polygon Mainnet
    --   - 80001: Polygon Mumbai Testnet
    --   - 其他 : 兼容EVM的其他链
    -- 默认值：1（Ethereum Mainnet）
    chain_id INTEGER DEFAULT 1,
    
    -- Web3临时nonce：用于Web3登录签名验证
    -- 登录流程：
    --   1. 用户请求登录时，服务器生成随机nonce
    --   2. 前端让用户用私钥签名nonce
    --   3. 服务器验证签名后，用wallet_address查找用户
    --   4. 验证成功后，此字段被清空
    web3_nonce VARCHAR(100),
    
    -- 钱包元数据：JSON格式，存储钱包相关信息
    -- 结构示例：
    -- {
    --   "wallet_type": "metamask",      // 钱包类型
    --   "wallet_address": "0x...",      // 钱包地址（与provider_user_id不同）
    --   "chain_id": 1,                   // 链ID（与chain_id字段一致）
    --   "ens_name": "xxx.eth",           // ENS域名（如有）
    --   "sign_message": "xxx"           // 用户签名的消息
    -- }
    wallet_metadata JSONB,
    
    -- nonce过期时间：Web3 nonce的有效期
    -- 通常设置为10-30分钟
    nonce_expires_at TIMESTAMP WITH TIME ZONE
);

-- 用户登录方式索引

-- 用户ID索引：快速查找用户的所有登录方式
CREATE INDEX IF NOT EXISTS idx_login_methods_user_id ON user_login_methods(user_id);

-- 提供商索引：用于OAuth2.0回调处理
-- 格式：(auth_provider, provider_user_id)
-- 场景：根据Google/GitHub/X返回的provider_user_id快速查找用户
CREATE INDEX IF NOT EXISTS idx_login_methods_provider ON user_login_methods(auth_provider, provider_user_id);

-- 主要方式索引：查找用户的主要登录方式
CREATE INDEX IF NOT EXISTS idx_login_methods_primary ON user_login_methods(user_id, is_primary);

-- Web3相关索引：优化Web3钱包登录性能
CREATE INDEX IF NOT EXISTS idx_user_login_methods_chain_id ON user_login_methods(chain_id) WHERE auth_provider = 'WEB3';
CREATE INDEX IF NOT EXISTS idx_user_login_methods_web3_nonce ON user_login_methods(web3_nonce) WHERE auth_provider = 'WEB3';

-- 唯一性约束

-- 同一用户不能有多个相同类型的登录方式
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_login_provider ON user_login_methods(user_id, auth_provider);

-- 本地用户名唯一（如果已设置）
CREATE UNIQUE INDEX IF NOT EXISTS uk_local_username ON user_login_methods(local_username) WHERE local_username IS NOT NULL;

-- 提供商用户ID唯一（如果已设置）
-- 同一个Google/GitHub/X账户只能被绑定一次
CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_user ON user_login_methods(auth_provider, provider_user_id) WHERE provider_user_id IS NOT NULL;

-- =====================================================
-- Web3临时Nonce表 (web3_nonces)
-- 用于Web3登录时的nonce管理，提供更安全的登录流程
-- 注意：此表与user_login_methods中的web3_nonce字段功能类似
-- 推荐使用此表进行nonce管理，以获得更好的安全性和性能
-- =====================================================
CREATE TABLE IF NOT EXISTS web3_nonces (
    -- 主键：UUID格式，由应用层生成
    id VARCHAR(36) PRIMARY KEY,
    
    -- 钱包地址：用于签名验证
    -- 存储格式：统一为小写（0x开头）
    -- 用途：在用户签名nonce后，用此地址验证签名
    wallet_address VARCHAR(255) NOT NULL,
    
    -- 临时nonce：服务器生成的随机字符串
    -- 长度建议：32-64字符
    -- 有效期：通常10-30分钟
    nonce VARCHAR(100) NOT NULL,
    
    -- 过期时间：nonce的有效期
    -- 过期后需要重新生成nonce
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    
    -- 创建时间：记录nonce生成时间
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Web3 Nonce表索引

-- 钱包地址索引：快速查找钱包的nonce记录
CREATE INDEX IF NOT EXISTS idx_web3_nonces_wallet_address ON web3_nonces(wallet_address);

-- 过期时间索引：用于清理过期nonce
CREATE INDEX IF NOT EXISTS idx_web3_nonces_expires_at ON web3_nonces(expires_at);

-- 钱包地址唯一约束：同一时间一个钱包只能有一个有效的nonce
CREATE UNIQUE INDEX IF NOT EXISTS web3_nonces_wallet_address_key ON web3_nonces(wallet_address);

-- =====================================================
-- 用户权限关联表 (user_authorities)
-- 存储用户的角色和权限信息
-- =====================================================
CREATE TABLE IF NOT EXISTS user_authorities (
    -- 用户ID：引用users表的UUID
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    -- 权限/角色名称
    -- 常用权限示例：
    --   - 'ROLE_USER'      : 普通用户
    --   - 'ROLE_ADMIN'     : 管理员
    --   - 'ROLE_MODERATOR' : moderator
    --   - 'SCOPE_READ'     : 读权限
    --   - 'SCOPE_WRITE'    : 写权限
    authority TEXT NOT NULL,
    
    -- 联合主键：(user_id, authority)
    PRIMARY KEY (user_id, authority)
);

-- =====================================================
-- Token黑名单表 (token_blacklist)
-- 用于JWT Token失效管理（登出、Token泄露等场景）
-- =====================================================
CREATE TABLE IF NOT EXISTS token_blacklist (
    -- 主键：UUID格式，由应用层生成
    id VARCHAR(36) PRIMARY KEY,
    
    -- JWT ID：Token的唯一标识
    -- 从JWT的'jti' claim获取
    jti TEXT UNIQUE NOT NULL,
    
    -- Token类型：如'ACCESS_TOKEN'、'REFRESH_TOKEN'
    token_type TEXT,
    
    -- 用户ID：此Token所属用户的UUID
    -- 允许NULL，用于非认证用户的token（如anonymous token）
    user_id VARCHAR(36),
    
    -- 过期时间：Token的过期时间
    -- 用于自动清理过期记录
    expires_at TIMESTAMP NOT NULL,
    
    -- 加入黑名单时间：记录Token被加入黑名单的时间
    blacklisted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- 加入黑名单原因：如'LOGOUT'、'TOKEN_REVOKED'、'SECURITY_BREACH'
    reason TEXT
);

-- Token黑名单表索引

-- JTI索引：快速查找特定token
CREATE INDEX IF NOT EXISTS idx_token_blacklist_jti ON token_blacklist(jti);

-- 过期时间索引：用于清理过期记录
CREATE INDEX IF NOT EXISTS idx_token_blacklist_expires_at ON token_blacklist(expires_at);

-- =====================================================
-- JWT密钥表 (jwt_keys)
-- 注意：此表为早期设计遗留，当前版本未使用，可安全忽略或删除
-- 如需支持JWT密钥轮换功能，可参考此表结构重新启用
-- =====================================================
-- CREATE TABLE IF NOT EXISTS jwt_keys (
--     -- 主键：UUID格式，由应用层生成
--     id VARCHAR(36) PRIMARY KEY,
--     
--     -- 密钥内容：实际的对称/非对称密钥
--     -- 对于HS256：存储共享密钥
--     -- 对于RS256：存储私钥
--     secret_key TEXT NOT NULL,
--     
--     -- 算法：JWT签名算法
--     -- 常用值：'HS256'、'HS384'、'HS512'、'RS256'、'ES256'
--     -- 推荐：RS256（更安全，支持密钥分离）
--     algorithm TEXT NOT NULL,
--     
--     -- 是否激活：默认为true
--     -- 仅激活的密钥用于签名
--     -- 验证时使用所有密钥（包括非激活的）以支持密钥轮换
--     active BOOLEAN DEFAULT TRUE NOT NULL,
--     
--     -- 创建时间
--     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
--     
--     -- 更新时间
--     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
-- );

-- JWT密钥表索引
-- 注意：此索引为早期设计遗留，当前版本未使用
-- CREATE INDEX IF NOT EXISTS idx_jwt_keys_algorithm_active ON jwt_keys(algorithm, active);

-- =====================================================
-- Spring Session表 (spring_session)
-- 用于Spring Session管理，支持分布式Session
-- 由Spring Session框架自动管理
-- =====================================================
CREATE TABLE IF NOT EXISTS spring_session (
    -- 主键：固定36字符
    primary_id CHAR(36) PRIMARY KEY,
    
    -- Session ID：客户端Session标识
    session_id CHAR(36) NOT NULL,
    
    -- 创建时间：毫秒时间戳
    creation_time BIGINT NOT NULL,
    
    -- 最后访问时间：毫秒时间戳
    last_access_time BIGINT NOT NULL,
    
    -- 最大不活跃间隔：秒数
    -- 如：1800表示30分钟无活动则过期
    max_inactive_interval INTEGER NOT NULL,
    
    -- 过期时间：毫秒时间戳
    expiry_time BIGINT NOT NULL,
    
    -- 主体名称：通常为用户名
    principal_name VARCHAR(100)
);

-- Spring Session索引

-- Session ID唯一索引
CREATE UNIQUE INDEX IF NOT EXISTS spring_session_ix1 ON spring_session(session_id);

-- 过期时间索引：用于清理过期Session
CREATE INDEX IF NOT EXISTS spring_session_ix2 ON spring_session(expiry_time);

-- 主体名称索引：按用户名查找Session
CREATE INDEX IF NOT EXISTS spring_session_ix3 ON spring_session(principal_name);

-- =====================================================
-- Spring Session属性表 (spring_session_attributes)
-- 存储Session中的属性数据
-- 由Spring Session框架自动管理
-- =====================================================
CREATE TABLE IF NOT EXISTS spring_session_attributes (
    -- 关联Session主键：引用spring_session表
    session_primary_id CHAR(36) NOT NULL REFERENCES spring_session(primary_id) ON DELETE CASCADE,
    
    -- 属性名称：Session中的key
    attribute_name VARCHAR(200) NOT NULL,
    
    -- 属性值：序列化的属性内容
    attribute_bytes BYTEA NOT NULL,
    
    -- 联合主键：(session_primary_id, attribute_name)
    PRIMARY KEY (session_primary_id, attribute_name)
);

-- =====================================================
-- 登录方式详细说明
-- =====================================================
/*
支持的登录方式及其字段使用说明：

1. 本地账号密码登录 (LOCAL)
   - auth_provider: 'LOCAL'
   - local_username: 存储用户名
   - local_password_hash: 存储bcrypt加密的密码
   - provider_user_id: 不使用
   - chain_id/web3_nonce/wallet_metadata: 不使用

2. Google登录 (GOOGLE)
   - auth_provider: 'GOOGLE'
   - provider_user_id: 存储Google返回的sub claim
   - provider_email: 存储Google账户邮箱
   - provider_username: 存储Google账户显示名称
   - email_verified: 通常为true（Google已验证）
   - chain_id/web3_nonce/wallet_metadata: 不使用

3. GitHub登录 (GITHUB)
   - auth_provider: 'GITHUB'
   - provider_user_id: 存储GitHub用户ID
   - provider_email: 存储GitHub账户邮箱（需要user:email权限）
   - provider_username: 存储GitHub用户名
   - chain_id/web3_nonce/wallet_metadata: 不使用

4. X(Twitter)登录 (X)
   - auth_provider: 'X'
   - provider_user_id: 存储Twitter用户ID
   - provider_email: 存储Twitter账户邮箱（需要email权限）
   - provider_username: 存储Twitter用户名（handle）
   - chain_id/web3_nonce/wallet_metadata: 不使用

5. Web3钱包登录 (WEB3)
   - auth_provider: 'WEB3'
   - provider_user_id: 通常不使用，使用wallet_address作为标识
   - chain_id: 指定区块链网络（1=Ethereum Mainnet）
   - web3_nonce: 存储临时nonce用于签名验证
   - wallet_metadata: JSON格式存储钱包信息，包括：
     * wallet_type: 钱包类型（metamask、walletconnect等）
     * wallet_address: 钱包地址
     * chain_id: 链ID（与外层chain_id一致）
     * 其他元数据
   - nonce_expires_at: web3_nonce的过期时间
   - 登录流程：
     1. 前端请求nonce
     2. 服务器在web3_nonces表或此表的web3_nonce字段创建nonce
     3. 前端让用户签名nonce
     4. 服务器验证签名
     5. 验证通过后完成登录
*/

-- =====================================================
-- 常见操作示例
-- =====================================================
/*
-- 查询用户的某类型登录方式
SELECT * FROM user_login_methods WHERE user_id = 'xxx' AND auth_provider = 'GOOGLE';

-- 查询使用某钱包登录的用户
SELECT * FROM user_login_methods WHERE auth_provider = 'WEB3' AND wallet_address = '0x...';

-- 清理过期的Web3 nonce
DELETE FROM web3_nonces WHERE expires_at < NOW();

-- 清理过期的token黑名单记录
DELETE FROM token_blacklist WHERE expires_at < NOW();

-- 禁用某用户的登录
UPDATE users SET enabled = FALSE WHERE id = 'xxx';

-- 查询用户的所有权限
SELECT authority FROM user_authorities WHERE user_id = 'xxx';
*/
