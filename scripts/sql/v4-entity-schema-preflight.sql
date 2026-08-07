SELECT 'null_user_runtime_fields'
WHERE EXISTS (
    SELECT 1
    FROM public.users
    WHERE email_verified IS NULL
       OR enabled IS NULL
       OR created_at IS NULL
       OR updated_at IS NULL
);

SELECT 'null_web3_nonce_created_at'
WHERE EXISTS (
    SELECT 1
    FROM public.web3_nonces
    WHERE created_at IS NULL
);

SELECT 'invalid_email_verification_state'
WHERE EXISTS (
    SELECT 1
    FROM public.email_verification_codes
    WHERE is_used IS NULL
       OR retry_count IS NULL
       OR retry_count < 0
);

SELECT 'invalid_token_blacklist_state'
WHERE EXISTS (
    SELECT 1
    FROM public.token_blacklist
    WHERE token_type IS NULL
       OR token_type NOT IN ('ACCESS', 'REFRESH', 'ID')
       OR blacklisted_at IS NULL
);
