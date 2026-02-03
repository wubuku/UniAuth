-- Fix all timestamp columns to use TIMESTAMP WITH TIME ZONE for proper timezone support
-- This migration updates the column types to support Instant/OffsetDateTime

-- Fix linked_at column
ALTER TABLE user_login_methods ALTER COLUMN linked_at TYPE TIMESTAMP WITH TIME ZONE;

-- Fix last_used_at column
ALTER TABLE user_login_methods ALTER COLUMN last_used_at TYPE TIMESTAMP WITH TIME ZONE;

-- Fix nonce_expires_at column
ALTER TABLE user_login_methods ALTER COLUMN nonce_expires_at TYPE TIMESTAMP WITH TIME ZONE;

-- Fix users table timestamp columns
ALTER TABLE users ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ALTER COLUMN updated_at TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ALTER COLUMN last_login_at TYPE TIMESTAMP WITH TIME ZONE;

-- Verify the changes
-- SELECT column_name, data_type FROM information_schema.columns
-- WHERE table_name IN ('user_login_methods', 'users')
-- ORDER BY table_name, column_name;
