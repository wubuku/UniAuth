#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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

LOCK TABLE public.users IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.user_login_methods IN SHARE ROW EXCLUSIVE MODE;

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

WITH deleted AS (
    DELETE FROM public.users users
     USING reset_social_users target
     WHERE users.id = target.id
     RETURNING users.id
)
SELECT count(*) AS deleted_social_users FROM deleted;

COMMIT;
SQL

echo "Disposable social identities were reset."
