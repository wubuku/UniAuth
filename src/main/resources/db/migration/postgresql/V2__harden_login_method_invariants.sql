-- Harden login-method invariants without changing the V1 baseline.
-- The approved V1 source stored Instant values as UTC timestamp-without-time-zone.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.users u
        LEFT JOIN public.user_login_methods m ON m.user_id = u.id
        GROUP BY u.id
        HAVING count(m.id) = 0
            OR count(*) FILTER (WHERE m.is_primary IS TRUE) <> 1
    ) THEN
        RAISE EXCEPTION
            'V2 preflight: every user must have exactly one primary login method'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.user_login_methods
        WHERE user_id IS NULL
           OR auth_provider IS NULL
           OR is_primary IS NULL
           OR is_verified IS NULL
           OR linked_at IS NULL
    ) THEN
        RAISE EXCEPTION
            'V2 preflight: login method runtime fields must be non-null'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.user_login_methods
        WHERE auth_provider NOT IN ('LOCAL', 'GOOGLE', 'GITHUB', 'TWITTER', 'WEB3')
    ) THEN
        RAISE EXCEPTION
            'V2 preflight: unknown auth_provider value'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
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
    ) THEN
        RAISE EXCEPTION
            'V2 preflight: invalid login method provider field shape'
            USING ERRCODE = '23514';
    END IF;
END
$$;

ALTER TABLE public.user_login_methods
    ALTER COLUMN is_primary SET DEFAULT false,
    ALTER COLUMN is_primary SET NOT NULL,
    ALTER COLUMN is_verified SET DEFAULT false,
    ALTER COLUMN is_verified SET NOT NULL,
    ALTER COLUMN linked_at TYPE timestamp(6) with time zone
        USING linked_at AT TIME ZONE 'UTC',
    ALTER COLUMN linked_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN linked_at SET NOT NULL,
    ALTER COLUMN last_used_at TYPE timestamp(6) with time zone
        USING last_used_at AT TIME ZONE 'UTC';

ALTER TABLE public.user_login_methods
    ADD CONSTRAINT ck_login_methods_provider
        CHECK (auth_provider IN ('LOCAL', 'GOOGLE', 'GITHUB', 'TWITTER', 'WEB3')),
    ADD CONSTRAINT ck_login_methods_provider_shape
        CHECK (
            (
                auth_provider = 'LOCAL'
                AND local_username IS NOT NULL
                AND provider_user_id IS NULL
            ) OR (
                auth_provider IN ('GOOGLE', 'GITHUB', 'TWITTER', 'WEB3')
                AND provider_user_id IS NOT NULL
                AND local_username IS NULL
                AND local_password_hash IS NULL
            )
        );

CREATE UNIQUE INDEX uk_login_methods_one_primary
    ON public.user_login_methods (user_id)
    WHERE is_primary IS TRUE;
