-- Web3 Wallet Login Migration Script
-- Version: V4__Add_web3_login_support.sql
-- Description: Adds support for Web3 wallet authentication

-- 1. Add chain_id column to user_login_methods for multi-chain support
ALTER TABLE user_login_methods ADD COLUMN IF NOT EXISTS chain_id INTEGER DEFAULT 1;
COMMENT ON COLUMN user_login_methods.chain_id IS 'Blockchain chain ID (1=Ethereum mainnet, 137=Polygon, etc.)';

-- 2. Add nonce storage column for Web3 login (temporary storage for login nonce)
ALTER TABLE user_login_methods ADD COLUMN IF NOT EXISTS web3_nonce VARCHAR(100);
COMMENT ON COLUMN user_login_methods.web3_nonce IS 'Temporary storage for Web3 login nonce';

-- 3. Add nonce expiration column
ALTER TABLE user_login_methods ADD COLUMN IF NOT EXISTS nonce_expires_at TIMESTAMP;
COMMENT ON COLUMN user_login_methods.nonce_expires_at IS 'Expiration time for Web3 login nonce';

-- 4. Add wallet metadata column for storing additional wallet info
ALTER TABLE user_login_methods ADD COLUMN IF NOT EXISTS wallet_metadata JSONB;
COMMENT ON COLUMN user_login_methods.wallet_metadata IS 'Additional wallet metadata (chain_id, wallet_type, etc.)';

-- 5. Create index for efficient Web3 nonce lookups
CREATE INDEX IF NOT EXISTS idx_user_login_methods_web3_nonce
ON user_login_methods(web3_nonce)
WHERE auth_provider = 'WEB3';

-- 6. Create index for efficient chain_id lookups
CREATE INDEX IF NOT EXISTS idx_user_login_methods_chain_id
ON user_login_methods(chain_id)
WHERE auth_provider = 'WEB3';

-- 7. Update existing WEB3 records if any (for forward compatibility)
UPDATE user_login_methods
SET chain_id = 1
WHERE auth_provider = 'WEB3' AND chain_id IS NULL;

-- 8. Add comment to auth_provider enum values (if using PostgreSQL enum)
-- COMMENT ON TYPE auth_provider IS 'Authentication provider types: LOCAL, GOOGLE, GITHUB, TWITTER, WEB3';

-- Verification query: Check if WEB3 provider exists
-- SELECT DISTINCT auth_provider FROM user_login_methods WHERE auth_provider = 'WEB3';

-- Rollback script (if needed):
-- DROP INDEX IF EXISTS idx_user_login_methods_web3_nonce;
-- DROP INDEX IF EXISTS idx_user_login_methods_chain_id;
-- ALTER TABLE user_login_methods DROP COLUMN IF EXISTS chain_id;
-- ALTER TABLE user_login_methods DROP COLUMN IF EXISTS web3_nonce;
-- ALTER TABLE user_login_methods DROP COLUMN IF EXISTS nonce_expires_at;
-- ALTER TABLE user_login_methods DROP COLUMN IF EXISTS wallet_metadata;
