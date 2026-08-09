-- F3 OAuth2 binding intent and Web3 challenge hardening.
-- Existing Web3 challenges cannot be associated with an opaque handle or
-- trusted source, so invalidate them at this security boundary.

DELETE FROM public.web3_nonces;

ALTER TABLE public.web3_nonces
    ADD COLUMN challenge_handle character varying(64),
    ADD COLUMN source_key character varying(128);

ALTER TABLE public.web3_nonces
    ALTER COLUMN challenge_handle SET NOT NULL,
    ALTER COLUMN source_key SET NOT NULL,
    ADD CONSTRAINT ck_web3_nonce_wallet_shape
        CHECK (wallet_address ~ '^0x[0-9a-f]{40}$'),
    ADD CONSTRAINT ck_web3_nonce_nonce_shape
        CHECK (nonce ~ '^[A-Za-z0-9]{16,64}$'),
    ADD CONSTRAINT ck_web3_nonce_handle_shape
        CHECK (challenge_handle ~
            '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$');

CREATE UNIQUE INDEX uk_web3_nonces_challenge_handle
    ON public.web3_nonces (challenge_handle);

CREATE TABLE public.web3_challenge_counters (
    bucket_key character varying(128) NOT NULL,
    active_count integer DEFAULT 0 NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT web3_challenge_counters_pkey PRIMARY KEY (bucket_key),
    CONSTRAINT ck_web3_challenge_counter_nonnegative
        CHECK (active_count >= 0)
);

CREATE TABLE public.oauth2_binding_intents (
    id character varying(36) NOT NULL,
    state_hash character varying(64) NOT NULL,
    session_id_hash character varying(64) NOT NULL,
    provider character varying(32) NOT NULL,
    user_id character varying(36) NOT NULL,
    security_version bigint NOT NULL,
    auth_time timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    consumed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT oauth2_binding_intents_pkey PRIMARY KEY (id),
    CONSTRAINT uk_oauth2_binding_intents_state_hash UNIQUE (state_hash),
    CONSTRAINT fk_oauth2_binding_intents_user
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE,
    CONSTRAINT ck_oauth2_binding_intent_id_uuid
        CHECK ((id)::text ~
            '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT ck_oauth2_binding_intent_provider
        CHECK (provider IN ('google', 'github', 'x')),
    CONSTRAINT ck_oauth2_binding_intent_security_version
        CHECK (security_version >= 0),
    CONSTRAINT ck_oauth2_binding_intent_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT ck_oauth2_binding_intent_consumed
        CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);

CREATE INDEX idx_oauth2_binding_intents_expiry
    ON public.oauth2_binding_intents (expires_at);

CREATE INDEX idx_oauth2_binding_intents_user_active
    ON public.oauth2_binding_intents (user_id, expires_at)
    WHERE consumed_at IS NULL;
