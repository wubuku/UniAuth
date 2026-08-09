ALTER TABLE public.users
    ADD COLUMN token_security_version bigint DEFAULT 0 NOT NULL,
    ADD CONSTRAINT ck_users_token_security_version_nonnegative
        CHECK (token_security_version >= 0);

CREATE TABLE public.token_families (
    id character varying(36) NOT NULL,
    user_id character varying(36) NOT NULL,
    security_version bigint NOT NULL,
    current_generation bigint DEFAULT 0 NOT NULL,
    auth_time timestamp(6) with time zone,
    created_at timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    revoked_at timestamp(6) with time zone,
    revoke_reason character varying(64),
    CONSTRAINT token_families_pkey PRIMARY KEY (id),
    CONSTRAINT fk_token_families_user
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE,
    CONSTRAINT ck_token_families_id_uuid
        CHECK ((id)::text ~
            '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT ck_token_families_security_version_nonnegative
        CHECK (security_version >= 0),
    CONSTRAINT ck_token_families_generation_nonnegative
        CHECK (current_generation >= 0),
    CONSTRAINT ck_token_families_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT ck_token_families_revoke_shape
        CHECK (
            (revoked_at IS NULL AND revoke_reason IS NULL)
            OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)
        )
);

CREATE INDEX idx_token_families_user_active
    ON public.token_families (user_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_token_families_expires_at
    ON public.token_families (expires_at);
