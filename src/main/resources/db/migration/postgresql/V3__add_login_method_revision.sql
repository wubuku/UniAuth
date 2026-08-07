-- Add a user-scoped optimistic CAS token for login-method collection changes.

ALTER TABLE public.users
    ADD COLUMN login_methods_revision bigint DEFAULT 0 NOT NULL,
    ADD CONSTRAINT ck_users_login_methods_revision_nonnegative
        CHECK (login_methods_revision >= 0);
