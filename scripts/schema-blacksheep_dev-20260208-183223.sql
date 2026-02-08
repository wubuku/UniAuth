-- =====================================================
-- UniAuth 数据库表结构导出
-- 导出时间: 2026-02-08 18:32:24
-- 数据库: blacksheep_dev
-- =====================================================

-- 可执行的 SQL 语句

-- -------------------------------------------
-- 表: users
-- -------------------------------------------

CREATE TABLE public.users (
    id character varying(36) NOT NULL,
    username character varying(255) NOT NULL,
    email character varying(255) NOT NULL,
    display_name character varying(255),
    avatar_url text,
    email_verified boolean DEFAULT false,
    enabled boolean DEFAULT true,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    last_login_at timestamp without time zone
);
ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);
ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_username_key UNIQUE (username);
CREATE INDEX idx_users_email ON public.users USING btree (email);
CREATE INDEX idx_users_username ON public.users USING btree (username);

-- -------------------------------------------
-- 表: user_login_methods
-- -------------------------------------------

CREATE TABLE public.user_login_methods (
    id character varying(36) NOT NULL,
    user_id character varying(36) NOT NULL,
    auth_provider character varying(50) NOT NULL,
    provider_user_id character varying(255),
    provider_email character varying(255),
    provider_username character varying(255),
    local_username character varying(255),
    local_password_hash character varying(255),
    is_primary boolean DEFAULT false,
    is_verified boolean DEFAULT false,
    linked_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    last_used_at timestamp without time zone,
    chain_id integer DEFAULT 1,
    web3_nonce character varying(100),
    wallet_metadata jsonb,
    nonce_expires_at timestamp with time zone
);
ALTER TABLE ONLY public.user_login_methods
    ADD CONSTRAINT user_login_methods_pkey PRIMARY KEY (id);
CREATE INDEX idx_login_methods_primary ON public.user_login_methods USING btree (user_id, is_primary);
CREATE INDEX idx_login_methods_provider ON public.user_login_methods USING btree (auth_provider, provider_user_id);
CREATE INDEX idx_login_methods_user_id ON public.user_login_methods USING btree (user_id);
CREATE INDEX idx_user_login_methods_chain_id ON public.user_login_methods USING btree (chain_id) WHERE ((auth_provider)::text = 'WEB3'::text);
CREATE INDEX idx_user_login_methods_web3_nonce ON public.user_login_methods USING btree (web3_nonce) WHERE ((auth_provider)::text = 'WEB3'::text);
CREATE UNIQUE INDEX uk_local_username ON public.user_login_methods USING btree (local_username) WHERE (local_username IS NOT NULL);
CREATE UNIQUE INDEX uk_provider_user ON public.user_login_methods USING btree (auth_provider, provider_user_id) WHERE (provider_user_id IS NOT NULL);
CREATE UNIQUE INDEX uk_user_login_provider ON public.user_login_methods USING btree (user_id, auth_provider);
ALTER TABLE ONLY public.user_login_methods
    ADD CONSTRAINT user_login_methods_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- -------------------------------------------
-- 表: web3_nonces
-- -------------------------------------------

CREATE TABLE public.web3_nonces (
    id character varying(36) NOT NULL,
    wallet_address character varying(255) NOT NULL,
    nonce character varying(100) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE ONLY public.web3_nonces
    ADD CONSTRAINT web3_nonces_pkey PRIMARY KEY (id);
CREATE INDEX idx_web3_nonces_expires_at ON public.web3_nonces USING btree (expires_at);
CREATE INDEX idx_web3_nonces_wallet_address ON public.web3_nonces USING btree (wallet_address);
CREATE UNIQUE INDEX web3_nonces_wallet_address_key ON public.web3_nonces USING btree (wallet_address);

-- -------------------------------------------
-- 表: user_authorities
-- -------------------------------------------

CREATE TABLE public.user_authorities (
    user_id character varying(36) NOT NULL,
    authority character varying(255) NOT NULL
);
ALTER TABLE ONLY public.user_authorities
    ADD CONSTRAINT user_authorities_pkey PRIMARY KEY (user_id, authority);
ALTER TABLE ONLY public.user_authorities
    ADD CONSTRAINT user_authorities_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

-- -------------------------------------------
-- 表: token_blacklist
-- -------------------------------------------

CREATE TABLE public.token_blacklist (
    id character varying(36) NOT NULL,
    jti character varying(255) NOT NULL,
    token_type character varying(255),
    user_id character varying(36),
    expires_at timestamp without time zone NOT NULL,
    blacklisted_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    reason character varying(255)
);
ALTER TABLE ONLY public.token_blacklist
    ADD CONSTRAINT token_blacklist_jti_key UNIQUE (jti);
ALTER TABLE ONLY public.token_blacklist
    ADD CONSTRAINT token_blacklist_pkey PRIMARY KEY (id);
CREATE INDEX idx_expires_at ON public.token_blacklist USING btree (expires_at);
CREATE INDEX idx_jti ON public.token_blacklist USING btree (jti);
CREATE INDEX idx_token_blacklist_expires_at ON public.token_blacklist USING btree (expires_at);
CREATE INDEX idx_token_blacklist_jti ON public.token_blacklist USING btree (jti);

-- -------------------------------------------
-- 表: spring_session
-- -------------------------------------------

CREATE TABLE public.spring_session (
    primary_id character(36) NOT NULL,
    session_id character(36) NOT NULL,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name character varying(100)
);
ALTER TABLE ONLY public.spring_session
    ADD CONSTRAINT spring_session_pkey PRIMARY KEY (primary_id);
CREATE UNIQUE INDEX spring_session_ix1 ON public.spring_session USING btree (session_id);
CREATE INDEX spring_session_ix2 ON public.spring_session USING btree (expiry_time);
CREATE INDEX spring_session_ix3 ON public.spring_session USING btree (principal_name);

-- -------------------------------------------
-- 表: spring_session_attributes
-- -------------------------------------------

CREATE TABLE public.spring_session_attributes (
    session_primary_id character(36) NOT NULL,
    attribute_name character varying(200) NOT NULL,
    attribute_bytes bytea NOT NULL
);
ALTER TABLE ONLY public.spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_pkey PRIMARY KEY (session_primary_id, attribute_name);
ALTER TABLE ONLY public.spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_session_primary_id_fkey FOREIGN KEY (session_primary_id) REFERENCES public.spring_session(primary_id) ON DELETE CASCADE;

