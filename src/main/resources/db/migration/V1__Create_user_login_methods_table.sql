-- 创建user_login_methods表 (PostgreSQL Compatible)
CREATE TABLE IF NOT EXISTS user_login_methods (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    auth_provider TEXT NOT NULL,
    provider_user_id TEXT,
    provider_email TEXT,
    provider_username TEXT,
    local_username TEXT,
    local_password_hash TEXT,
    is_primary BOOLEAN DEFAULT FALSE,
    is_verified BOOLEAN DEFAULT FALSE,
    linked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP,
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
