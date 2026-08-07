-- Align the remaining entity nullability/default contracts and repository indexes.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.users
        WHERE email_verified IS NULL
           OR enabled IS NULL
           OR created_at IS NULL
           OR updated_at IS NULL
    ) THEN
        RAISE EXCEPTION
            'V4 preflight: user runtime fields must be non-null'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.web3_nonces
        WHERE created_at IS NULL
    ) THEN
        RAISE EXCEPTION
            'V4 preflight: Web3 nonce creation time must be non-null'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.email_verification_codes
        WHERE is_used IS NULL
           OR retry_count IS NULL
           OR retry_count < 0
    ) THEN
        RAISE EXCEPTION
            'V4 preflight: email verification state must be non-null and nonnegative'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.token_blacklist
        WHERE token_type IS NULL
           OR token_type NOT IN ('ACCESS', 'REFRESH', 'ID')
           OR blacklisted_at IS NULL
    ) THEN
        RAISE EXCEPTION
            'V4 preflight: token blacklist state is invalid'
            USING ERRCODE = '23514';
    END IF;
END
$$;

ALTER TABLE public.users
    ALTER COLUMN email_verified SET DEFAULT false,
    ALTER COLUMN email_verified SET NOT NULL,
    ALTER COLUMN enabled SET DEFAULT true,
    ALTER COLUMN enabled SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE public.web3_nonces
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE public.email_verification_codes
    ALTER COLUMN is_used SET DEFAULT false,
    ALTER COLUMN is_used SET NOT NULL,
    ALTER COLUMN retry_count SET DEFAULT 0,
    ALTER COLUMN retry_count SET NOT NULL,
    ADD CONSTRAINT ck_email_verification_retry_count_nonnegative
        CHECK (retry_count >= 0);

ALTER TABLE public.token_blacklist
    ALTER COLUMN token_type SET NOT NULL,
    ALTER COLUMN blacklisted_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN blacklisted_at SET NOT NULL,
    ADD CONSTRAINT ck_token_blacklist_token_type
        CHECK (token_type IN ('ACCESS', 'REFRESH', 'ID'));

CREATE INDEX idx_email_verification_pending_lookup
    ON public.email_verification_codes (email, purpose, created_at DESC)
    WHERE is_used IS FALSE;

CREATE INDEX idx_email_verification_email_created_at
    ON public.email_verification_codes (email, created_at DESC);

CREATE INDEX idx_email_verification_expires_at
    ON public.email_verification_codes (expires_at);

DROP INDEX public.idx_users_email;
DROP INDEX public.idx_users_username;
DROP INDEX public.idx_web3_nonces_wallet_address;
DROP INDEX public.idx_jti;
DROP INDEX public.idx_token_blacklist_jti;
DROP INDEX public.idx_expires_at;
