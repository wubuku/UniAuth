SELECT 'users_without_exactly_one_primary'
WHERE EXISTS (
    SELECT 1
    FROM public.users u
    LEFT JOIN public.user_login_methods m ON m.user_id = u.id
    GROUP BY u.id
    HAVING count(m.id) = 0
        OR count(*) FILTER (WHERE m.is_primary IS TRUE) <> 1
);

SELECT 'null_login_method_runtime_fields'
WHERE EXISTS (
    SELECT 1
    FROM public.user_login_methods
    WHERE user_id IS NULL
       OR auth_provider IS NULL
       OR is_primary IS NULL
       OR is_verified IS NULL
       OR linked_at IS NULL
);

SELECT 'unknown_auth_provider'
WHERE EXISTS (
    SELECT 1
    FROM public.user_login_methods
    WHERE auth_provider NOT IN ('LOCAL', 'GOOGLE', 'GITHUB', 'TWITTER', 'WEB3')
);

SELECT 'invalid_login_method_provider_shape'
WHERE EXISTS (
    SELECT 1
    FROM public.user_login_methods
    WHERE (
        auth_provider = 'LOCAL'
        AND (local_username IS NULL OR provider_user_id IS NOT NULL)
    ) OR (
        auth_provider IN ('GOOGLE', 'GITHUB', 'TWITTER', 'WEB3')
        AND (
            provider_user_id IS NULL
            OR local_username IS NOT NULL
            OR local_password_hash IS NOT NULL
        )
    )
);
