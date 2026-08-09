#!/usr/bin/env bash

# Creates an owner-only PostgreSQL custom-format backup containing only the
# email-service relations and Flyway history. The command is read-only against
# PostgreSQL and publishes the archive only after pg_dump and pg_restore
# validation both succeed.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${EMAIL_SERVICE_ENV_FILE:-}"
BACKUP_TEMP=""
CHECKSUM_TEMP=""
FINAL_BACKUP=""
FINAL_CHECKSUM=""
PUBLISHED=false

# shellcheck source=runtime-guard.sh
source "$PROJECT_DIR/scripts/runtime-guard.sh"

fail() {
    echo "ERROR: $1" >&2
    exit 1
}

cleanup() {
    local exit_code=$?
    trap - EXIT INT TERM
    set +e
    [ -z "$BACKUP_TEMP" ] || rm -f -- "$BACKUP_TEMP"
    [ -z "$CHECKSUM_TEMP" ] || rm -f -- "$CHECKSUM_TEMP"
    if [ "$PUBLISHED" != "true" ]; then
        [ -z "$FINAL_BACKUP" ] || rm -f -- "$FINAL_BACKUP"
        [ -z "$FINAL_CHECKSUM" ] || rm -f -- "$FINAL_CHECKSUM"
    fi
    exit "$exit_code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

file_mode() {
    local path="$1"
    if stat -f '%Lp' "$path" >/dev/null 2>&1; then
        stat -f '%Lp' "$path"
    else
        stat -c '%a' "$path"
    fi
}

if [ -n "$ENV_FILE" ]; then
    email_service_validate_env_file "$ENV_FILE" || exit 1
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
fi

for command_name in awk chmod date mkdir mktemp mv psql rm shasum stat; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        fail "required command is unavailable: $command_name"
    fi
done

profile="${SPRING_PROFILES_ACTIVE:-}"
case "$profile" in
    dev|prod)
        ;;
    *)
        fail "SPRING_PROFILES_ACTIVE must be exactly dev or prod"
        ;;
esac

for variable_name in EMAIL_POSTGRES_HOST EMAIL_POSTGRES_PORT \
        EMAIL_POSTGRES_DATABASE EMAIL_POSTGRES_USER EMAIL_POSTGRES_PASSWORD \
        EMAIL_BACKUP_DIR; do
    email_service_require_env "$variable_name" || exit 1
done

database_host="$EMAIL_POSTGRES_HOST"
database_port="$EMAIL_POSTGRES_PORT"
database_name="$EMAIL_POSTGRES_DATABASE"
database_user="$EMAIL_POSTGRES_USER"
database_password="$EMAIL_POSTGRES_PASSWORD"
connect_timeout="${EMAIL_BACKUP_CONNECT_TIMEOUT_SECONDS:-5}"
backup_dir="$EMAIL_BACKUP_DIR"
database_layout="${EMAIL_DATABASE_LAYOUT:-dedicated}"
pg_dump_bin="${EMAIL_PG_DUMP_BIN:-pg_dump}"
pg_restore_bin="${EMAIL_PG_RESTORE_BIN:-pg_restore}"

if ! command -v "$pg_dump_bin" >/dev/null 2>&1; then
    fail "configured pg_dump command is unavailable: $pg_dump_bin"
fi
if ! command -v "$pg_restore_bin" >/dev/null 2>&1; then
    fail "configured pg_restore command is unavailable: $pg_restore_bin"
fi

email_service_require_host EMAIL_POSTGRES_HOST "$database_host" || exit 1
email_service_require_integer_range \
    EMAIL_POSTGRES_PORT "$database_port" 1 65535 || exit 1
email_service_require_integer_range \
    EMAIL_BACKUP_CONNECT_TIMEOUT_SECONDS "$connect_timeout" 1 60 || exit 1
email_service_require_database_target \
    "$profile" \
    "$database_name" \
    "$database_layout" || exit 1

case "$database_name" in
    *[!A-Za-z0-9_.-]*)
        fail "EMAIL_POSTGRES_DATABASE must use only letters, digits, dot, underscore, or hyphen"
        ;;
esac

case "$backup_dir" in
    /*)
        ;;
    *)
        fail "EMAIL_BACKUP_DIR must be an absolute path"
        ;;
esac

if [ -L "$backup_dir" ]; then
    fail "backup directory must not be a symbolic link"
fi
if [ -e "$backup_dir" ] && [ ! -d "$backup_dir" ]; then
    fail "backup path exists but is not a directory"
fi

umask 077
mkdir -p -- "$backup_dir"
if [ -L "$backup_dir" ]; then
    fail "backup directory must not be a symbolic link"
fi
backup_dir_mode="$(file_mode "$backup_dir")"
if (( (8#$backup_dir_mode & 077) != 0 )); then
    fail "backup directory must not be accessible by group or others"
fi

server_version_num="$(
    PGPASSWORD="$database_password" \
    PGCONNECT_TIMEOUT="$connect_timeout" \
    psql \
        -X -qAt -v ON_ERROR_STOP=1 \
        --no-password \
        --host="$database_host" \
        --port="$database_port" \
        --username="$database_user" \
        --dbname="$database_name" \
        -c "SHOW server_version_num;"
)"
if ! [[ "$server_version_num" =~ ^[0-9]+$ ]]; then
    fail "unable to determine the source PostgreSQL major version"
fi
server_major=$((10#$server_version_num / 10000))

for relation_name in \
        email_queue \
        email_queue_id_seq \
        email_logs \
        email_logs_id_seq \
        email_service_flyway_schema_history; do
    relation_exists="$(
        PGPASSWORD="$database_password" \
        PGCONNECT_TIMEOUT="$connect_timeout" \
        psql \
            -X -qAt -v ON_ERROR_STOP=1 \
            --no-password \
            --host="$database_host" \
            --port="$database_port" \
            --username="$database_user" \
            --dbname="$database_name" \
            -c "SELECT to_regclass('public.${relation_name}') IS NOT NULL;"
    )"
    if [ "$relation_exists" != "t" ]; then
        fail "email service schema object is missing: public.${relation_name}"
    fi
done

successful_migration_counts="$(
    PGPASSWORD="$database_password" \
    PGCONNECT_TIMEOUT="$connect_timeout" \
    psql \
        -X -qAt -v ON_ERROR_STOP=1 \
        --no-password \
        --host="$database_host" \
        --port="$database_port" \
        --username="$database_user" \
        --dbname="$database_name" \
        -c "
            SELECT concat_ws(
                ',',
                count(*) FILTER (WHERE success IS TRUE AND version = '1'),
                count(*) FILTER (WHERE success IS TRUE AND version = '2'),
                count(*) FILTER (WHERE success IS TRUE AND version = '3'),
                count(*) FILTER (WHERE success IS TRUE AND version = '4'),
                count(*) FILTER (WHERE success IS TRUE AND version = '5')
            )
            FROM public.email_service_flyway_schema_history
        "
)"
baseline_migrations="$(
    PGPASSWORD="$database_password" \
    PGCONNECT_TIMEOUT="$connect_timeout" \
    psql \
        -X -qAt -v ON_ERROR_STOP=1 \
        --no-password \
        --host="$database_host" \
        --port="$database_port" \
        --username="$database_user" \
        --dbname="$database_name" \
        -c "
            SELECT count(*)
            FROM public.email_service_flyway_schema_history
            WHERE success IS TRUE
              AND type = 'BASELINE'
              AND version = '0';
        "
)"
unexpected_migrations="$(
    PGPASSWORD="$database_password" \
    PGCONNECT_TIMEOUT="$connect_timeout" \
    psql \
        -X -qAt -v ON_ERROR_STOP=1 \
        --no-password \
        --host="$database_host" \
        --port="$database_port" \
        --username="$database_user" \
        --dbname="$database_name" \
        -c "
            SELECT count(*)
            FROM public.email_service_flyway_schema_history
            WHERE success IS NOT TRUE
               OR (
                    (type = 'SQL' AND version IN ('1', '2', '3', '4', '5'))
                    OR (type = 'BASELINE' AND version = '0')
               ) IS NOT TRUE;
        "
)"
baseline_history_is_valid=false
if [ "$database_layout" = "dedicated" ] \
        && [ "$baseline_migrations" = "0" ]; then
    baseline_history_is_valid=true
elif [ "$database_layout" = "shared-uniauth" ] \
        && { [ "$baseline_migrations" = "0" ] \
            || [ "$baseline_migrations" = "1" ]; }; then
    baseline_history_is_valid=true
fi
if [ "$successful_migration_counts" != "1,1,1,1,1" ] \
        || [ "$baseline_history_is_valid" != "true" ] \
        || [ "$unexpected_migrations" != "0" ]; then
    fail "email service Flyway history must match the expected V1 through V5 chain"
fi

pg_dump_major="$(
    "$pg_dump_bin" --version \
        | awk '{
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^[0-9]+(\.[0-9]+)+$/) {
                    split($i, version, ".")
                    print version[1]
                    exit
                }
            }
        }'
)"
if ! [[ "$pg_dump_major" =~ ^[0-9]+$ ]]; then
    fail "unable to determine the configured pg_dump major version"
fi
if [ "$pg_dump_major" != "$server_major" ]; then
    fail "pg_dump major version ${pg_dump_major} must match source PostgreSQL major ${server_major}"
fi
pg_restore_major="$(
    "$pg_restore_bin" --version \
        | awk '{
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^[0-9]+(\.[0-9]+)+$/) {
                    split($i, version, ".")
                    print version[1]
                    exit
                }
            }
        }'
)"
if ! [[ "$pg_restore_major" =~ ^[0-9]+$ ]]; then
    fail "unable to determine the configured pg_restore major version"
fi
if [ "$pg_restore_major" != "$server_major" ]; then
    fail "pg_restore major version ${pg_restore_major} must match source PostgreSQL major ${server_major}"
fi

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_name="email-service-${database_name}-${timestamp}-$$.dump"
FINAL_BACKUP="$backup_dir/$backup_name"
FINAL_CHECKSUM="${FINAL_BACKUP}.sha256"
[ ! -e "$FINAL_BACKUP" ] && [ ! -L "$FINAL_BACKUP" ] \
    || fail "backup archive path already exists"
[ ! -e "$FINAL_CHECKSUM" ] && [ ! -L "$FINAL_CHECKSUM" ] \
    || fail "backup checksum path already exists"
BACKUP_TEMP="$(mktemp "$backup_dir/.${backup_name}.tmp.XXXXXX")"
CHECKSUM_TEMP="$(mktemp "$backup_dir/.${backup_name}.sha256.tmp.XXXXXX")"

PGPASSWORD="$database_password" \
PGCONNECT_TIMEOUT="$connect_timeout" \
"$pg_dump_bin" \
    --no-password \
    --format=custom \
    --no-owner \
    --no-acl \
    --strict-names \
    --table=public.email_queue \
    --table=public.email_queue_id_seq \
    --table=public.email_logs \
    --table=public.email_logs_id_seq \
    --table=public.email_service_flyway_schema_history \
    --host="$database_host" \
    --port="$database_port" \
    --username="$database_user" \
    --dbname="$database_name" \
    --file="$BACKUP_TEMP"

[ -s "$BACKUP_TEMP" ] || fail "pg_dump produced an empty backup archive"
"$pg_restore_bin" --list "$BACKUP_TEMP" >/dev/null

backup_checksum="$(shasum -a 256 "$BACKUP_TEMP" | awk '{print $1}')"
[ -n "$backup_checksum" ] || fail "unable to calculate the backup checksum"
printf '%s  %s\n' "$backup_checksum" "$backup_name" >"$CHECKSUM_TEMP"
chmod 600 "$BACKUP_TEMP" "$CHECKSUM_TEMP"

mv -- "$BACKUP_TEMP" "$FINAL_BACKUP"
BACKUP_TEMP=""
mv -- "$CHECKSUM_TEMP" "$FINAL_CHECKSUM"
CHECKSUM_TEMP=""
PUBLISHED=true

printf '%s\n' "$FINAL_BACKUP"
