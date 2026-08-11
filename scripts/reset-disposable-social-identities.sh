#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FINGERPRINT_SQL="$PROJECT_DIR/scripts/sql/uniauth-schema-fingerprint.sql"
EXPECTED_FINGERPRINT_FILE="$PROJECT_DIR/scripts/sql/uniauth-v8-schema-fingerprint.sha256"
SHARED_SCHEMA_LOCK_KEY="-632082753896054443"
MODE="preview"
PROVIDERS_INPUT=""

# shellcheck source=scripts/runtime-guard.sh
source "$PROJECT_DIR/scripts/runtime-guard.sh"

usage() {
    cat <<'EOF'
Usage:
  scripts/reset-disposable-social-identities.sh \
    --providers google,github[,x] [--apply]

Required environment:
  POSTGRES_HOST POSTGRES_PORT POSTGRES_DATABASE POSTGRES_USER POSTGRES_PASSWORD
  APP_DEMO_DATA_DISPOSABLE=true

The default mode is read-only. --apply deletes only non-managed users that have
exactly one login method and whose sole method belongs to a selected provider.
Managed testlocal/testsso/testboth fixtures and multi-method users are protected.
The target database must also contain the exact successful UniAuth Flyway V1-V8
history, optionally preceded by the supported shared-schema V0 baseline, and the
canonical V8 auth schema. --apply invalidates all Spring Sessions in the
disposable database because serialized sessions cannot be safely mapped to
deleted users.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --providers)
            if [ "$#" -lt 2 ]; then
                usage >&2
                exit 2
            fi
            PROVIDERS_INPUT="$2"
            shift 2
            ;;
        --apply)
            MODE="apply"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            exit 2
            ;;
    esac
done

if [ -z "$PROVIDERS_INPUT" ]; then
    echo "Error: --providers is required" >&2
    exit 2
fi

uniauth_require_postgres
uniauth_require_disposable_database_name "$POSTGRES_DATABASE"
if [ "${APP_DEMO_DATA_DISPOSABLE:-false}" != "true" ]; then
    echo "Error: APP_DEMO_DATA_DISPOSABLE must be exactly true" >&2
    exit 1
fi
if ! command -v psql >/dev/null 2>&1; then
    echo "Error: psql is required" >&2
    exit 1
fi
if [ ! -r "$FINGERPRINT_SQL" ] || [ ! -r "$EXPECTED_FINGERPRINT_FILE" ]; then
    echo "Error: canonical UniAuth schema fingerprint files are unavailable" >&2
    exit 1
fi

EXPECTED_SCHEMA_FINGERPRINT="$(
    tr -d '[:space:]' < "$EXPECTED_FINGERPRINT_FILE"
)"
if [[ ! "$EXPECTED_SCHEMA_FINGERPRINT" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Error: canonical UniAuth schema fingerprint is invalid" >&2
    exit 1
fi

declare -a provider_names=()
declare -a provider_values=()
IFS=',' read -r -a requested_providers <<<"$PROVIDERS_INPUT"
for requested in "${requested_providers[@]}"; do
    normalized="$(
        printf '%s' "$requested" \
            | tr '[:upper:]' '[:lower:]' \
            | tr -d '[:space:]'
    )"
    case "$normalized" in
        google)
            provider_value="GOOGLE"
            ;;
        github)
            provider_value="GITHUB"
            ;;
        x|twitter)
            normalized="x"
            provider_value="TWITTER"
            ;;
        *)
            echo "Error: unsupported provider '${requested}'" >&2
            exit 2
            ;;
    esac
    duplicate=false
    for existing in "${provider_values[@]:-}"; do
        if [ "$existing" = "$provider_value" ]; then
            duplicate=true
            break
        fi
    done
    if [ "$duplicate" = "false" ]; then
        provider_names+=("$normalized")
        provider_values+=("$provider_value")
    fi
done

if [ "${#provider_values[@]}" -eq 0 ]; then
    echo "Error: at least one provider is required" >&2
    exit 2
fi

provider_sql=""
provider_label=""
for index in "${!provider_values[@]}"; do
    if [ -n "$provider_sql" ]; then
        provider_sql+=", "
        provider_label+=","
    fi
    provider_sql+="'${provider_values[$index]}'"
    provider_label+="${provider_names[$index]}"
done

export PGPASSWORD="$POSTGRES_PASSWORD"
trap 'unset PGPASSWORD' EXIT

psql_args=(
    psql
    -X
    -v ON_ERROR_STOP=1
    -h "$POSTGRES_HOST"
    -p "$POSTGRES_PORT"
    -U "$POSTGRES_USER"
    -d "$POSTGRES_DATABASE"
)

echo "Disposable social identity reset:"
echo "  database:  ${POSTGRES_DATABASE}"
echo "  providers: ${provider_label}"
echo "  mode:      ${MODE}"

schema_guard_sql() {
    cat <<'SQL'
DO $$
DECLARE
    baseline_zero_count integer;
    expected_version integer;
    required_table text;
    required_table_names text[] := ARRAY[
        'users',
        'user_login_methods',
        'user_authorities',
        'token_blacklist',
        'spring_session',
        'spring_session_attributes',
        'web3_nonces',
        'email_verification_codes',
        'email_delivery_outbox',
        'security_events',
        'token_families',
        'oauth2_binding_intents',
        'web3_challenge_counters',
        'auth_rate_limits'
    ];
    required_cascade_fk text;
    required_cascade_fks text[] := ARRAY[
        'user_login_methods_user_id_fkey',
        'user_authorities_user_id_fkey',
        'spring_session_attributes_session_primary_id_fkey',
        'fk_token_families_user',
        'fk_oauth2_binding_intents_user'
    ];
    required_constraint text;
    required_constraints text[] := ARRAY[
        'users_pkey',
        'users_username_key',
        'user_login_methods_pkey',
        'auth_rate_limits_pkey',
        'ck_users_token_security_version_nonnegative',
        'ck_oauth2_binding_intent_provider'
    ];
    required_index text;
    required_indexes text[] := ARRAY[
        'uk_local_username',
        'uk_provider_user',
        'uk_user_login_provider',
        'uk_web3_nonces_challenge_handle',
        'uk_oauth2_binding_intents_state_hash'
    ];
BEGIN
    IF to_regclass('public.uniauth_flyway_schema_history') IS NULL THEN
        RAISE EXCEPTION
            'refusing reset: public.uniauth_flyway_schema_history is missing';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM public.uniauth_flyway_schema_history
         WHERE success IS DISTINCT FROM true
            OR version IS NULL
    ) THEN
        RAISE EXCEPTION
            'refusing reset: Flyway history contains failed or repeatable rows';
    END IF;

    FOR expected_version IN 1..8 LOOP
        IF (
            SELECT count(*)
              FROM public.uniauth_flyway_schema_history
             WHERE version::text = expected_version::text
               AND success IS TRUE
               AND (
                   (expected_version = 1 AND type IN ('SQL', 'BASELINE'))
                   OR (expected_version > 1 AND type = 'SQL')
               )
        ) <> 1 THEN
            RAISE EXCEPTION
                'refusing reset: expected exactly one successful UniAuth Flyway V% row',
                expected_version;
        END IF;
    END LOOP;

    SELECT count(*)
      INTO baseline_zero_count
      FROM public.uniauth_flyway_schema_history
     WHERE version::text = '0'
       AND type = 'BASELINE'
       AND success IS TRUE;

    IF baseline_zero_count > 1
       OR (
           SELECT count(*)
             FROM public.uniauth_flyway_schema_history
       ) <> 8 + baseline_zero_count THEN
        RAISE EXCEPTION
            'refusing reset: expected exact successful UniAuth V1-V8 history with at most one V0 baseline';
    END IF;

    IF baseline_zero_count = 1 AND EXISTS (
        SELECT 1
          FROM public.uniauth_flyway_schema_history
         WHERE version::text = '1'
           AND type <> 'SQL'
    ) THEN
        RAISE EXCEPTION
            'refusing reset: shared-schema V0 baseline requires SQL migration V1';
    END IF;

    FOREACH required_table IN ARRAY required_table_names LOOP
        IF to_regclass('public.' || required_table) IS NULL THEN
            RAISE EXCEPTION
                'refusing reset: required UniAuth table is missing: %',
                required_table;
        END IF;
    END LOOP;

    FOREACH required_constraint IN ARRAY required_constraints LOOP
        IF NOT EXISTS (
            SELECT 1
              FROM pg_constraint constraint_row
              JOIN pg_namespace namespace_row
                ON namespace_row.oid = constraint_row.connamespace
             WHERE namespace_row.nspname = 'public'
               AND constraint_row.conname = required_constraint
        ) THEN
            RAISE EXCEPTION
                'refusing reset: required UniAuth constraint is missing: %',
                required_constraint;
        END IF;
    END LOOP;

    FOREACH required_cascade_fk IN ARRAY required_cascade_fks LOOP
        IF NOT EXISTS (
            SELECT 1
              FROM pg_constraint constraint_row
              JOIN pg_namespace namespace_row
                ON namespace_row.oid = constraint_row.connamespace
             WHERE namespace_row.nspname = 'public'
               AND constraint_row.conname = required_cascade_fk
               AND constraint_row.contype = 'f'
               AND constraint_row.confdeltype = 'c'
        ) THEN
            RAISE EXCEPTION
                'refusing reset: required cascading foreign key is missing: %',
                required_cascade_fk;
        END IF;
    END LOOP;

    FOREACH required_index IN ARRAY required_indexes LOOP
        IF to_regclass('public.' || required_index) IS NULL THEN
            RAISE EXCEPTION
                'refusing reset: required UniAuth index is missing: %',
                required_index;
        END IF;
    END LOOP;
END
$$;
SQL
}

schema_fingerprint_query() {
    sed '$ s/;[[:space:]]*$//' "$FINGERPRINT_SQL"
}

schema_validation_psql() {
    schema_guard_sql
    schema_fingerprint_query
    cat <<SQL
\gset
SELECT :'schema_fingerprint' = '${EXPECTED_SCHEMA_FINGERPRINT}'
    AS schema_fingerprint_matches
\gset
\if :schema_fingerprint_matches
\else
\echo 'refusing reset: canonical UniAuth V8 schema fingerprint mismatch'
\quit 3
\endif
SQL
}

echo "Validating canonical UniAuth V8 schema before preview..."
"${psql_args[@]}" -qAt <<SQL
BEGIN;
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
$(schema_validation_psql)
COMMIT;
SQL

"${psql_args[@]}" -P pager=off <<SQL
WITH selected_users AS (
    SELECT DISTINCT u.id
      FROM public.users u
      JOIN public.user_login_methods selected
        ON selected.user_id = u.id
     WHERE selected.auth_provider IN (${provider_sql})
       AND u.username NOT IN (
           'testlocal',
           'testsso@example.com',
           'testboth'
       )
       AND u.email NOT IN (
           'testlocal@example.com',
           'testsso@example.com',
           'testboth@example.com'
       )
)
SELECT u.id AS user_id,
       u.username,
       u.email,
       lm.auth_provider,
       lm.provider_username,
       lm.provider_email,
       (
           SELECT count(*)
             FROM public.user_login_methods all_methods
            WHERE all_methods.user_id = u.id
       ) AS login_method_count
  FROM selected_users selected
  JOIN public.users u ON u.id = selected.id
  JOIN public.user_login_methods lm ON lm.user_id = u.id
 ORDER BY u.id, lm.auth_provider;
SQL

blocker_count="$(
    "${psql_args[@]}" -qAt <<SQL
SELECT count(DISTINCT u.id)
  FROM public.users u
  JOIN public.user_login_methods selected
    ON selected.user_id = u.id
 WHERE selected.auth_provider IN (${provider_sql})
   AND u.username NOT IN (
       'testlocal',
       'testsso@example.com',
       'testboth'
   )
   AND u.email NOT IN (
       'testlocal@example.com',
       'testsso@example.com',
       'testboth@example.com'
   )
   AND (
       SELECT count(*)
         FROM public.user_login_methods all_methods
        WHERE all_methods.user_id = u.id
   ) <> 1;
SQL
)"

if [ "$blocker_count" != "0" ]; then
    echo "Error: ${blocker_count} selected user(s) have multiple login methods." >&2
    echo "Refusing to modify a multi-method identity; use the authenticated UI." >&2
    exit 1
fi

if [ "$MODE" = "preview" ]; then
    echo "Preview complete; no data was changed."
    exit 0
fi

"${psql_args[@]}" -P pager=off <<SQL
BEGIN;
SET LOCAL lock_timeout = '5s';
SELECT pg_advisory_xact_lock(${SHARED_SCHEMA_LOCK_KEY});

$(schema_validation_psql)

LOCK TABLE public.users IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.user_login_methods IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.spring_session IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.spring_session_attributes IN SHARE ROW EXCLUSIVE MODE;

DO \$\$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM public.users u
          JOIN public.user_login_methods selected
            ON selected.user_id = u.id
         WHERE selected.auth_provider IN (${provider_sql})
           AND u.username NOT IN (
               'testlocal',
               'testsso@example.com',
               'testboth'
           )
           AND u.email NOT IN (
               'testlocal@example.com',
               'testsso@example.com',
               'testboth@example.com'
           )
           AND (
               SELECT count(*)
                 FROM public.user_login_methods all_methods
                WHERE all_methods.user_id = u.id
           ) <> 1
    ) THEN
        RAISE EXCEPTION
            'selected provider belongs to a multi-method identity';
    END IF;
END
\$\$;

CREATE TEMP TABLE reset_social_users ON COMMIT DROP AS
SELECT DISTINCT u.id
  FROM public.users u
  JOIN public.user_login_methods selected
    ON selected.user_id = u.id
 WHERE selected.auth_provider IN (${provider_sql})
   AND u.username NOT IN (
       'testlocal',
       'testsso@example.com',
       'testboth'
   )
   AND u.email NOT IN (
       'testlocal@example.com',
       'testsso@example.com',
       'testboth@example.com'
   )
   AND (
       SELECT count(*)
         FROM public.user_login_methods all_methods
        WHERE all_methods.user_id = u.id
   ) = 1;

DELETE FROM public.token_blacklist blacklist
 USING reset_social_users target
 WHERE blacklist.user_id = target.id;

WITH deleted_sessions AS (
    DELETE FROM public.spring_session
    RETURNING primary_id
)
SELECT count(*) AS invalidated_spring_sessions FROM deleted_sessions;

WITH deleted AS (
    DELETE FROM public.users users
     USING reset_social_users target
     WHERE users.id = target.id
     RETURNING users.id
)
SELECT count(*) AS deleted_social_users FROM deleted;

$(schema_validation_psql)

COMMIT;
SQL

echo "Disposable social identities were reset."
