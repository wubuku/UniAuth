-- F1 email identity and verification challenge hardening.
-- Existing usable challenges are invalidated because their plaintext code and
-- credential-bearing metadata cannot cross the no-return cutover safely.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.users
        WHERE email NOT LIKE '%@oauth.local'
          AND email NOT LIKE '%@web3.local'
          AND (
              email <> lower(btrim(email))
              OR length(email) > 254
              OR email ~ '[[:cntrl:]]'
          )
    ) THEN
        RAISE EXCEPTION
            'V6 preflight: contact email values must already be canonical'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT lower(btrim(email))
        FROM public.users
        WHERE email NOT LIKE '%@oauth.local'
          AND email NOT LIKE '%@web3.local'
        GROUP BY lower(btrim(email))
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'V6 preflight: canonical contact email values conflict'
            USING ERRCODE = '23505';
    END IF;

    IF EXISTS (
        SELECT lower(btrim(local_username))
        FROM public.user_login_methods
        WHERE local_username IS NOT NULL
        GROUP BY lower(btrim(local_username))
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'V6 preflight: canonical local usernames conflict'
            USING ERRCODE = '23505';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.user_login_methods
        WHERE auth_provider = 'LOCAL'
          AND local_password_hash IS NULL
    ) THEN
        RAISE NOTICE
            'V6 preflight: historical passwordless LOCAL rows remain read-compatible but are no longer writable';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.email_verification_codes
        WHERE purpose = 'LOGIN'
          AND is_used IS FALSE
    ) THEN
        RAISE NOTICE
            'V6 cutover: invalidating unsupported active LOGIN verification challenges';
    END IF;
END
$$;

ALTER TABLE public.users
    ADD COLUMN email_identity_type character varying(32);

UPDATE public.users
SET email_identity_type = CASE
    WHEN email LIKE '%@oauth.local' OR email LIKE '%@web3.local'
        THEN 'SYNTHETIC'
    WHEN email_verified IS TRUE
        THEN 'VERIFIED_CONTACT'
    ELSE 'UNVERIFIED_CONTACT'
END;

ALTER TABLE public.users
    ALTER COLUMN email_identity_type SET NOT NULL,
    ADD CONSTRAINT ck_users_email_identity_type
        CHECK (email_identity_type IN (
            'VERIFIED_CONTACT',
            'UNVERIFIED_CONTACT',
            'SYNTHETIC'
        )),
    ADD CONSTRAINT ck_users_contact_email_canonical
        CHECK (
            email_identity_type = 'SYNTHETIC'
            OR (
                email = lower(btrim(email))
                AND length(email) <= 254
                AND email !~ '[[:cntrl:]]'
            )
        ),
    ADD CONSTRAINT ck_users_email_verified_identity
        CHECK (
            email_verified IS FALSE
            OR email_identity_type = 'VERIFIED_CONTACT'
        );

ALTER TABLE public.users
    DROP CONSTRAINT users_email_key;

CREATE UNIQUE INDEX uk_users_canonical_contact_email
    ON public.users (email)
    WHERE email_identity_type IN (
        'VERIFIED_CONTACT',
        'UNVERIFIED_CONTACT'
    );

ALTER TABLE public.user_login_methods
    ADD CONSTRAINT ck_local_username_canonical
        CHECK (
            auth_provider <> 'LOCAL'
            OR local_username IS NULL
            OR position('@' IN local_username) = 0
            OR (
                local_username = lower(btrim(local_username))
                AND length(local_username) <= 254
                AND local_username !~ '[[:cntrl:]]'
            )
        );

DROP INDEX public.uk_local_username;

CREATE UNIQUE INDEX uk_local_username
    ON public.user_login_methods (lower(btrim(local_username)))
    WHERE local_username IS NOT NULL;

ALTER TABLE public.email_verification_codes
    ADD COLUMN code_digest character varying(128),
    ADD COLUMN code_key_id character varying(64),
    ADD COLUMN delivery_status character varying(32),
    ADD COLUMN usage_status character varying(32),
    ADD COLUMN idempotency_key character varying(128),
    ADD COLUMN provider_delivery_id character varying(128),
    ADD COLUMN accepted_at timestamp(6) with time zone,
    ADD COLUMN activated_at timestamp(6) with time zone,
    ADD COLUMN failed_at timestamp(6) with time zone,
    ADD COLUMN delivery_deadline timestamp(6) with time zone,
    ADD COLUMN failure_reason character varying(64);

UPDATE public.email_verification_codes
SET delivery_status = 'FAILED',
    usage_status = 'INVALIDATED',
    failed_at = CURRENT_TIMESTAMP,
    failure_reason = CASE
        WHEN purpose = 'LOGIN' THEN 'UNSUPPORTED_PURPOSE'
        ELSE 'F1_CUTOVER'
    END;

ALTER TABLE public.email_verification_codes
    ALTER COLUMN delivery_status SET NOT NULL,
    ALTER COLUMN usage_status SET NOT NULL,
    DROP CONSTRAINT email_verification_codes_purpose_check,
    DROP COLUMN verification_code,
    DROP COLUMN metadata,
    DROP COLUMN is_used,
    ADD CONSTRAINT ck_email_challenge_purpose
        CHECK (purpose IN ('REGISTRATION', 'PASSWORD_RESET')),
    ADD CONSTRAINT ck_email_challenge_delivery_status
        CHECK (delivery_status IN (
            'PENDING_DELIVERY',
            'ACCEPTED',
            'ACTIVE',
            'FAILED'
        )),
    ADD CONSTRAINT ck_email_challenge_usage_status
        CHECK (usage_status IN (
            'UNUSED',
            'USED',
            'INVALIDATED',
            'EXPIRED'
        )),
    ADD CONSTRAINT ck_email_challenge_canonical_email
        CHECK (
            email = lower(btrim(email))
            AND length(email) <= 254
            AND email !~ '[[:cntrl:]]'
        ),
    ADD CONSTRAINT ck_email_challenge_secret_shape
        CHECK (
            usage_status IN ('INVALIDATED', 'EXPIRED')
            OR (
                code_digest IS NOT NULL
                AND code_key_id IS NOT NULL
            )
        );

CREATE UNIQUE INDEX uk_email_challenge_one_active
    ON public.email_verification_codes (email, purpose)
    WHERE usage_status = 'UNUSED'
      AND delivery_status IN ('PENDING_DELIVERY', 'ACCEPTED', 'ACTIVE');

CREATE INDEX idx_email_challenge_handle_lookup
    ON public.email_verification_codes (id, email, purpose);

CREATE INDEX idx_email_challenge_delivery
    ON public.email_verification_codes (
        delivery_status,
        delivery_deadline,
        created_at
    );

CREATE TABLE public.email_delivery_outbox (
    id character varying(36) NOT NULL,
    challenge_id character varying(36) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    status character varying(32) NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp(6) with time zone NOT NULL,
    processing_started_at timestamp(6) with time zone,
    provider_delivery_id character varying(128),
    last_error_code character varying(64),
    created_at timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT email_delivery_outbox_pkey PRIMARY KEY (id),
    CONSTRAINT email_delivery_outbox_challenge_key UNIQUE (challenge_id),
    CONSTRAINT email_delivery_outbox_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT email_delivery_outbox_challenge_fkey
        FOREIGN KEY (challenge_id)
        REFERENCES public.email_verification_codes(id)
        ON DELETE CASCADE,
    CONSTRAINT ck_email_delivery_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'FAILED')),
    CONSTRAINT ck_email_delivery_outbox_attempt_count
        CHECK (attempt_count >= 0)
);

CREATE INDEX idx_email_delivery_outbox_pending
    ON public.email_delivery_outbox (status, next_attempt_at, created_at);

CREATE TABLE public.auth_rate_limits (
    bucket_key character varying(128) NOT NULL,
    window_started_at timestamp(6) with time zone NOT NULL,
    request_count integer NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT auth_rate_limits_pkey PRIMARY KEY (bucket_key),
    CONSTRAINT ck_auth_rate_limit_count CHECK (request_count >= 0)
);

CREATE INDEX idx_auth_rate_limits_expires_at
    ON public.auth_rate_limits (expires_at);

CREATE TABLE public.security_events (
    id character varying(36) NOT NULL,
    event_type character varying(64) NOT NULL,
    subject_id character varying(128),
    request_id character varying(128) NOT NULL,
    outcome character varying(32) NOT NULL,
    reason_code character varying(64),
    created_at timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT security_events_pkey PRIMARY KEY (id),
    CONSTRAINT ck_security_event_outcome
        CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED'))
);

CREATE INDEX idx_security_events_subject_created
    ON public.security_events (subject_id, created_at DESC);

CREATE OR REPLACE FUNCTION public.reject_security_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'security_events is append-only'
        USING ERRCODE = '55000';
END
$$;

CREATE TRIGGER security_events_append_only
BEFORE UPDATE OR DELETE ON public.security_events
FOR EACH ROW
EXECUTE FUNCTION public.reject_security_event_mutation();
