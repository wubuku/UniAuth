-- Create dedicated table for Web3 login nonce storage
-- This separates temporary nonce data from permanent user login method records

CREATE TABLE IF NOT EXISTS web3_nonces (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    wallet_address VARCHAR(255) NOT NULL UNIQUE,
    nonce VARCHAR(100) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for fast lookup by wallet address
CREATE INDEX IF NOT EXISTS idx_web3_nonces_wallet_address ON web3_nonces(wallet_address);

-- Index for cleanup of expired nonces
CREATE INDEX IF NOT EXISTS idx_web3_nonces_expires_at ON web3_nonces(expires_at);

-- Verify the table structure
-- \d web3_nonces
