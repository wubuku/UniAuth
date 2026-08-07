-- 邮箱验证码存储表
-- 用于存储邮箱验证码，支持注册、登录、密码重置等场景

CREATE TABLE IF NOT EXISTS email_verification_codes (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    verification_code VARCHAR(10) NOT NULL,
    purpose VARCHAR(50) NOT NULL,           -- REGISTRATION, LOGIN, PASSWORD_RESET
    metadata TEXT,                            -- JSON格式，存储注册元数据（仅REGISTRATION类型使用）
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    retry_count INTEGER DEFAULT 0,           -- 验证失败次数
    is_used BOOLEAN DEFAULT FALSE,          -- 是否已使用
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
