#!/usr/bin/env bash

# Rehearse or explicitly apply Flyway baseline adoption for an existing PostgreSQL schema.
# The default mode is read-only against the source database.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$PROJECT_DIR/scripts"
FINGERPRINT_SQL="$SCRIPT_DIR/sql/uniauth-schema-fingerprint.sql"
LOGIN_METHOD_PREFLIGHT_SQL="$SCRIPT_DIR/sql/v2-login-method-preflight.sql"
ENTITY_SCHEMA_PREFLIGHT_SQL="$SCRIPT_DIR/sql/v4-entity-schema-preflight.sql"
EMAIL_IDENTITY_PREFLIGHT_SQL="$SCRIPT_DIR/sql/v6-email-identity-preflight.sql"
MODE="${1:-rehearse}"
RUN_TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
ARTIFACT_DIR="${UNIAUTH_BASELINE_ARTIFACT_DIR:-$PROJECT_DIR/.local/uniauth/baseline-rehearsal/$RUN_TIMESTAMP-$$}"
CONTAINER_NAME="uniauth-flyway-rehearsal-${RUN_TIMESTAMP}-$$"
REHEARSAL_DATABASE="uniauth_baseline_rehearsal"
FRESH_DATABASE="uniauth_flyway_fresh"
REHEARSAL_USER="uniauth"
REHEARSAL_PASSWORD="uniauth-rehearsal-$$"
REHEARSAL_PORT=""
FLYWAY_CONFIG_FILE=""
BASELINE_LOCK_PID=""
BASELINE_LOCK_LOG=""
BASELINE_LOCK_APP_NAME="uniauth-baseline-apply-lock-$$"

SHARED_SCHEMA_LOCK_KEY="-632082753896054443"

# shellcheck source=scripts/runtime-guard.sh
source "$SCRIPT_DIR/runtime-guard.sh"

usage() {
    cat <<'EOF'
Usage:
  scripts/flyway-baseline-existing.sh rehearse
  scripts/flyway-baseline-existing.sh apply

Required source connection variables:
  POSTGRES_HOST POSTGRES_PORT POSTGRES_DATABASE POSTGRES_USER POSTGRES_PASSWORD

rehearse:
  Reads the source schema, creates a schema-only backup, and performs all writes only in a
  disposable PostgreSQL Docker container.

apply:
  Performs the same rehearsal first, then creates uniauth_flyway_schema_history and applies any
  pending migrations to the source database. It requires UNIAUTH_BASELINE_CONFIRM to match the
  token printed by rehearse.
EOF
}

cleanup() {
    if [ -n "$FLYWAY_CONFIG_FILE" ]; then
        rm -f "$FLYWAY_CONFIG_FILE"
    fi
    if [ -n "$BASELINE_LOCK_PID" ]; then
        kill -TERM "$BASELINE_LOCK_PID" >/dev/null 2>&1 || true
        wait "$BASELINE_LOCK_PID" >/dev/null 2>&1 || true
        BASELINE_LOCK_PID=""
    fi
    if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -Fxq "$CONTAINER_NAME"; then
        docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
    fi
    unset PGPASSWORD
}
trap cleanup EXIT

psql_value() {
    local host="$1"
    local port="$2"
    local database="$3"
    local user="$4"
    local password="$5"
    local sql="$6"

    PGPASSWORD="$password" psql \
        -X -qAt -v ON_ERROR_STOP=1 \
        -h "$host" -p "$port" -U "$user" -d "$database" \
        -c "$sql"
}

source_psql_value() {
    PGOPTIONS="-c default_transaction_read_only=on" \
        psql_value \
        "$POSTGRES_HOST" \
        "$POSTGRES_PORT" \
        "$POSTGRES_DATABASE" \
        "$POSTGRES_USER" \
        "$POSTGRES_PASSWORD" \
        "$1"
}

source_history_tables() {
    source_psql_value "
        SELECT concat_ws(
            ',',
            to_regclass('public.uniauth_flyway_schema_history'),
            to_regclass('public.flyway_schema_history')
        );
    "
}

source_data_violations() {
    PGOPTIONS="-c default_transaction_read_only=on" \
        PGPASSWORD="$POSTGRES_PASSWORD" \
        psql -X -qAt -v ON_ERROR_STOP=1 \
            -h "$POSTGRES_HOST" \
            -p "$POSTGRES_PORT" \
            -U "$POSTGRES_USER" \
            -d "$POSTGRES_DATABASE" \
            -f "$LOGIN_METHOD_PREFLIGHT_SQL"
}

source_entity_schema_violations() {
    PGOPTIONS="-c default_transaction_read_only=on" \
        PGPASSWORD="$POSTGRES_PASSWORD" \
        psql -X -qAt -v ON_ERROR_STOP=1 \
            -h "$POSTGRES_HOST" \
            -p "$POSTGRES_PORT" \
            -U "$POSTGRES_USER" \
            -d "$POSTGRES_DATABASE" \
            -f "$ENTITY_SCHEMA_PREFLIGHT_SQL"
}

source_email_identity_violations() {
    PGOPTIONS="-c default_transaction_read_only=on" \
        PGPASSWORD="$POSTGRES_PASSWORD" \
        psql -X -qAt -v ON_ERROR_STOP=1 \
            -h "$POSTGRES_HOST" \
            -p "$POSTGRES_PORT" \
            -U "$POSTGRES_USER" \
            -d "$POSTGRES_DATABASE" \
            -f "$EMAIL_IDENTITY_PREFLIGHT_SQL"
}

schema_fingerprint() {
    local host="$1"
    local port="$2"
    local database="$3"
    local user="$4"
    local password="$5"
    local read_only="${6:-false}"

    if [ "$read_only" = "true" ]; then
        PGOPTIONS="-c default_transaction_read_only=on" \
            PGPASSWORD="$password" \
            psql -X -qAt -v ON_ERROR_STOP=1 \
                -h "$host" -p "$port" -U "$user" -d "$database" \
                -f "$FINGERPRINT_SQL"
    else
        PGPASSWORD="$password" \
            psql -X -qAt -v ON_ERROR_STOP=1 \
                -h "$host" -p "$port" -U "$user" -d "$database" \
                -f "$FINGERPRINT_SQL"
    fi
}

remove_incomplete_baseline_history() {
    local current_fingerprint
    local history_state

    current_fingerprint="$(
        schema_fingerprint \
            "$POSTGRES_HOST" \
            "$POSTGRES_PORT" \
            "$POSTGRES_DATABASE" \
            "$POSTGRES_USER" \
            "$POSTGRES_PASSWORD" \
            true
    )"
    if [ "$current_fingerprint" != "$SOURCE_FINGERPRINT" ]; then
        echo "ERROR: Flyway migrate failed after baseline and the managed schema changed; leaving history for manual recovery" >&2
        return 1
    fi

    history_state="$(
        source_psql_value "
            SELECT CASE
                WHEN count(*) = 1
                 AND bool_and(
                     version = '1'
                     AND type = 'BASELINE'
                     AND success IS TRUE
                 )
                THEN 'baseline-only'
                ELSE 'unexpected'
            END
            FROM public.uniauth_flyway_schema_history;
        "
    )"
    if [ "$history_state" != "baseline-only" ]; then
        echo "ERROR: Flyway migrate failed after baseline and history is not baseline-only; leaving it for manual recovery" >&2
        return 1
    fi

    psql_value \
        "$POSTGRES_HOST" \
        "$POSTGRES_PORT" \
        "$POSTGRES_DATABASE" \
        "$POSTGRES_USER" \
        "$POSTGRES_PASSWORD" \
        "DROP TABLE public.uniauth_flyway_schema_history;" >/dev/null
    echo "Flyway migrate failed after baseline; removed the incomplete baseline-only history table." >&2
}

run_flyway() {
    local url="$1"
    local user="$2"
    local password="$3"
    local goal="$4"
    local config_file
    local exit_code=0

    # BSD mktemp only replaces a trailing XXXXXX template.
    config_file="$(mktemp "${TMPDIR:-/tmp}/uniauth-flyway.XXXXXX")"
    FLYWAY_CONFIG_FILE="$config_file"
    chmod 600 "$config_file"
    {
        printf 'flyway.url=%s\n' "$url"
        printf 'flyway.user=%s\n' "$user"
        printf 'flyway.password=%s\n' "$password"
        printf 'flyway.locations=filesystem:%s\n' \
            "$PROJECT_DIR/src/main/resources/db/migration/postgresql"
        printf 'flyway.table=uniauth_flyway_schema_history\n'
        printf 'flyway.defaultSchema=public\n'
        printf 'flyway.schemas=public\n'
        printf 'flyway.baselineOnMigrate=false\n'
        printf 'flyway.baselineVersion=1\n'
        printf 'flyway.baselineDescription=Approved existing UniAuth auth schema\n'
        printf 'flyway.cleanDisabled=true\n'
        printf 'flyway.validateOnMigrate=true\n'
        printf 'flyway.outOfOrder=false\n'
        printf 'flyway.group=true\n'
    } > "$config_file"

    (
        cd "$PROJECT_DIR"
        mvn -q -Dflyway.configFiles="$config_file" "flyway:$goal"
    ) || exit_code=$?
    rm -f "$config_file"
    FLYWAY_CONFIG_FILE=""
    return "$exit_code"
}

acquire_baseline_lock() {
    local lock_log="$ARTIFACT_DIR/baseline-apply-lock.log"
    local lock_state=""
    local holder_exit_code=0

    : > "$lock_log"
    BASELINE_LOCK_LOG="$lock_log"
    PGPASSWORD="$POSTGRES_PASSWORD" \
        PGAPPNAME="$BASELINE_LOCK_APP_NAME" \
        psql -X -qAt -v ON_ERROR_STOP=1 \
            -h "$POSTGRES_HOST" \
            -p "$POSTGRES_PORT" \
            -U "$POSTGRES_USER" \
            -d "$POSTGRES_DATABASE" \
            -c "
                SELECT pg_try_advisory_lock(${SHARED_SCHEMA_LOCK_KEY});
                SELECT pg_sleep(86400);
            " >"$lock_log" 2>&1 &
    BASELINE_LOCK_PID=$!

    for _ in $(seq 1 100); do
        if lock_state="$(
            source_psql_value "
                SELECT CASE
                    WHEN EXISTS (
                        SELECT 1
                        FROM pg_locks lock_entry
                        JOIN pg_stat_activity activity
                          ON activity.pid = lock_entry.pid
                        WHERE lock_entry.locktype = 'advisory'
                          AND lock_entry.granted
                          AND activity.application_name = '$BASELINE_LOCK_APP_NAME'
                    ) THEN 'LOCK_ACQUIRED'
                    WHEN EXISTS (
                        SELECT 1
                        FROM pg_stat_activity activity
                        WHERE activity.application_name = '$BASELINE_LOCK_APP_NAME'
                    ) THEN 'LOCK_BUSY'
                    ELSE 'LOCK_PENDING'
                END;
            "
        )"; then
            :
        else
            lock_state="LOCK_PENDING"
        fi
        if [ "$lock_state" = "LOCK_ACQUIRED" ]; then
            return 0
        fi
        if [ "$lock_state" = "LOCK_BUSY" ]; then
            kill -TERM "$BASELINE_LOCK_PID" >/dev/null 2>&1 || true
            wait "$BASELINE_LOCK_PID" >/dev/null 2>&1 || true
            BASELINE_LOCK_PID=""
            echo "ERROR: unable to acquire the shared Flyway advisory lock" >&2
            if [ -s "$lock_log" ]; then
                cat "$lock_log" >&2
            fi
            return 1
        fi
        if ! kill -0 "$BASELINE_LOCK_PID" >/dev/null 2>&1; then
            wait "$BASELINE_LOCK_PID" || holder_exit_code=$?
            echo "ERROR: unable to acquire the shared Flyway advisory lock" >&2
            if [ -s "$lock_log" ]; then
                cat "$lock_log" >&2
            fi
            BASELINE_LOCK_PID=""
            return "${holder_exit_code:-1}"
        fi
        sleep 0.1
    done

    kill -TERM "$BASELINE_LOCK_PID" >/dev/null 2>&1 || true
    wait "$BASELINE_LOCK_PID" >/dev/null 2>&1 || true
    BASELINE_LOCK_PID=""
    echo "ERROR: unable to acquire the shared Flyway advisory lock" >&2
    if [ -s "$lock_log" ]; then
        cat "$lock_log" >&2
    fi
    return 1
}

if [ "$MODE" != "rehearse" ] && [ "$MODE" != "apply" ]; then
    usage >&2
    exit 2
fi

for command_name in docker mvn psql pg_dump pg_isready awk sort grep; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "ERROR: required command is unavailable: $command_name" >&2
        exit 1
    fi
done
uniauth_require_postgres
uniauth_require_nonproduction_database_name "$POSTGRES_DATABASE"

SERVER_MAJOR="$(source_psql_value "SHOW server_version_num;")"
if [ "$SERVER_MAJOR" -lt 160000 ] || [ "$SERVER_MAJOR" -ge 170000 ]; then
    echo "ERROR: the approved baseline was verified on PostgreSQL 16.x" >&2
    exit 1
fi

EXPECTED_TABLE_COUNT=8
SOURCE_TABLE_COUNT="$(
    source_psql_value "
        SELECT count(*)
        FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = ANY (ARRAY[
              'users',
              'user_login_methods',
              'web3_nonces',
              'email_verification_codes',
              'user_authorities',
              'token_blacklist',
              'spring_session',
              'spring_session_attributes'
          ]);
    "
)"
if [ "$SOURCE_TABLE_COUNT" -ne "$EXPECTED_TABLE_COUNT" ]; then
    echo "ERROR: source database does not contain all eight approved UniAuth tables" >&2
    exit 1
fi

EXISTING_HISTORY="$(source_history_tables)"
if [ -n "$EXISTING_HISTORY" ]; then
    echo "ERROR: a Flyway history table already exists: $EXISTING_HISTORY" >&2
    exit 1
fi

SOURCE_DATA_VIOLATIONS="$(source_data_violations)"
if [ -n "$SOURCE_DATA_VIOLATIONS" ]; then
    echo "ERROR: source data is not compatible with pending login-method migration: $SOURCE_DATA_VIOLATIONS" >&2
    exit 1
fi

SOURCE_ENTITY_SCHEMA_VIOLATIONS="$(source_entity_schema_violations)"
if [ -n "$SOURCE_ENTITY_SCHEMA_VIOLATIONS" ]; then
    echo "ERROR: source data is not compatible with pending entity-schema migration: $SOURCE_ENTITY_SCHEMA_VIOLATIONS" >&2
    exit 1
fi

SOURCE_EMAIL_IDENTITY_VIOLATIONS="$(source_email_identity_violations)"
if [ -n "$SOURCE_EMAIL_IDENTITY_VIOLATIONS" ]; then
    echo "ERROR: source data is not compatible with pending email-identity migration: $SOURCE_EMAIL_IDENTITY_VIOLATIONS" >&2
    exit 1
fi

mkdir -p "$ARTIFACT_DIR"
SOURCE_FINGERPRINT="$(
    schema_fingerprint \
        "$POSTGRES_HOST" \
        "$POSTGRES_PORT" \
        "$POSTGRES_DATABASE" \
        "$POSTGRES_USER" \
        "$POSTGRES_PASSWORD" \
        true
)"

EXPORT_DIR="$ARTIFACT_DIR/source-schema"
mkdir -p "$EXPORT_DIR"
"$SCRIPT_DIR/export-schema-pg.sh" "$EXPORT_DIR"
SOURCE_DUMP="$(
    find "$EXPORT_DIR" -maxdepth 1 -type f -name 'schema-export-*.sql' \
        | LC_ALL=C sort \
        | tail -1
)"
if [ -z "$SOURCE_DUMP" ]; then
    echo "ERROR: source schema backup was not created" >&2
    exit 1
fi

docker run -d --rm \
    --name "$CONTAINER_NAME" \
    -e "POSTGRES_DB=$REHEARSAL_DATABASE" \
    -e "POSTGRES_USER=$REHEARSAL_USER" \
    -e "POSTGRES_PASSWORD=$REHEARSAL_PASSWORD" \
    -p 127.0.0.1::5432 \
    postgres:16.13 >/dev/null

REHEARSAL_PORT="$(
    docker port "$CONTAINER_NAME" 5432/tcp \
        | awk -F: 'NR == 1 {print $NF}'
)"
for _ in $(seq 1 60); do
    if PGPASSWORD="$REHEARSAL_PASSWORD" pg_isready \
        -h 127.0.0.1 \
        -p "$REHEARSAL_PORT" \
        -U "$REHEARSAL_USER" \
        -d "$REHEARSAL_DATABASE" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
if ! PGPASSWORD="$REHEARSAL_PASSWORD" pg_isready \
    -h 127.0.0.1 \
    -p "$REHEARSAL_PORT" \
    -U "$REHEARSAL_USER" \
    -d "$REHEARSAL_DATABASE" >/dev/null 2>&1; then
    echo "ERROR: rehearsal PostgreSQL container did not become ready" >&2
    exit 1
fi

PGPASSWORD="$REHEARSAL_PASSWORD" psql \
    -X -q -v ON_ERROR_STOP=1 \
    -h 127.0.0.1 \
    -p "$REHEARSAL_PORT" \
    -U "$REHEARSAL_USER" \
    -d "$REHEARSAL_DATABASE" \
    -f "$SOURCE_DUMP"

RESTORED_SOURCE_FINGERPRINT="$(
    schema_fingerprint \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$REHEARSAL_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD"
)"
if [ "$SOURCE_FINGERPRINT" != "$RESTORED_SOURCE_FINGERPRINT" ]; then
    echo "ERROR: source and restored pre-migration schema fingerprints differ" >&2
    exit 1
fi

psql_value \
    127.0.0.1 \
    "$REHEARSAL_PORT" \
    "$REHEARSAL_DATABASE" \
    "$REHEARSAL_USER" \
    "$REHEARSAL_PASSWORD" \
    "CREATE DATABASE $FRESH_DATABASE;" >/dev/null

REHEARSAL_URL="jdbc:postgresql://127.0.0.1:$REHEARSAL_PORT/$REHEARSAL_DATABASE"
FRESH_URL="jdbc:postgresql://127.0.0.1:$REHEARSAL_PORT/$FRESH_DATABASE"

run_flyway "$REHEARSAL_URL" "$REHEARSAL_USER" "$REHEARSAL_PASSWORD" baseline
run_flyway "$REHEARSAL_URL" "$REHEARSAL_USER" "$REHEARSAL_PASSWORD" migrate
run_flyway "$REHEARSAL_URL" "$REHEARSAL_USER" "$REHEARSAL_PASSWORD" validate
run_flyway "$FRESH_URL" "$REHEARSAL_USER" "$REHEARSAL_PASSWORD" migrate
run_flyway "$FRESH_URL" "$REHEARSAL_USER" "$REHEARSAL_PASSWORD" validate

RESTORED_MIGRATED_FINGERPRINT="$(
    schema_fingerprint \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$REHEARSAL_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD"
)"
FRESH_FINGERPRINT="$(
    schema_fingerprint \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$FRESH_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD"
)"

if [ "$RESTORED_MIGRATED_FINGERPRINT" != "$FRESH_FINGERPRINT" ]; then
    echo "ERROR: baselined and fresh migrated schema fingerprints differ" >&2
    exit 1
fi

RESTORED_HISTORY_TYPE="$(
    psql_value \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$REHEARSAL_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD" \
        "SELECT type FROM uniauth_flyway_schema_history WHERE version = '1';"
)"
FRESH_HISTORY_TYPE="$(
    psql_value \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$FRESH_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD" \
        "SELECT type FROM uniauth_flyway_schema_history WHERE version = '1';"
)"
if [ "$RESTORED_HISTORY_TYPE" != "BASELINE" ] || [ "$FRESH_HISTORY_TYPE" != "SQL" ]; then
    echo "ERROR: unexpected Flyway history types after rehearsal" >&2
    exit 1
fi

RESTORED_V2_HISTORY_TYPE="$(
    psql_value \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$REHEARSAL_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD" \
        "SELECT type FROM uniauth_flyway_schema_history WHERE version = '2';"
)"
FRESH_V2_HISTORY_TYPE="$(
    psql_value \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$FRESH_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD" \
        "SELECT type FROM uniauth_flyway_schema_history WHERE version = '2';"
)"
if [ "$RESTORED_V2_HISTORY_TYPE" != "SQL" ] || [ "$FRESH_V2_HISTORY_TYPE" != "SQL" ]; then
    echo "ERROR: Flyway V2 was not applied in both rehearsal paths" >&2
    exit 1
fi

RESTORED_V3_HISTORY_TYPE="$(
    psql_value \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$REHEARSAL_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD" \
        "SELECT type FROM uniauth_flyway_schema_history WHERE version = '3';"
)"
FRESH_V3_HISTORY_TYPE="$(
    psql_value \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$FRESH_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD" \
        "SELECT type FROM uniauth_flyway_schema_history WHERE version = '3';"
)"
if [ "$RESTORED_V3_HISTORY_TYPE" != "SQL" ] || [ "$FRESH_V3_HISTORY_TYPE" != "SQL" ]; then
    echo "ERROR: Flyway V3 was not applied in both rehearsal paths" >&2
    exit 1
fi

RESTORED_V4_HISTORY_TYPE="$(
    psql_value \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$REHEARSAL_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD" \
        "SELECT type FROM uniauth_flyway_schema_history WHERE version = '4';"
)"
FRESH_V4_HISTORY_TYPE="$(
    psql_value \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$FRESH_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD" \
        "SELECT type FROM uniauth_flyway_schema_history WHERE version = '4';"
)"
if [ "$RESTORED_V4_HISTORY_TYPE" != "SQL" ] || [ "$FRESH_V4_HISTORY_TYPE" != "SQL" ]; then
    echo "ERROR: Flyway V4 was not applied in both rehearsal paths" >&2
    exit 1
fi

RESTORED_V5_HISTORY_TYPE="$(
    psql_value \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$REHEARSAL_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD" \
        "SELECT type FROM uniauth_flyway_schema_history WHERE version = '5';"
)"
FRESH_V5_HISTORY_TYPE="$(
    psql_value \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$FRESH_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD" \
        "SELECT type FROM uniauth_flyway_schema_history WHERE version = '5';"
)"
if [ "$RESTORED_V5_HISTORY_TYPE" != "SQL" ] || [ "$FRESH_V5_HISTORY_TYPE" != "SQL" ]; then
    echo "ERROR: Flyway V5 was not applied in both rehearsal paths" >&2
    exit 1
fi

RESTORED_V6_HISTORY_TYPE="$(
    psql_value \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$REHEARSAL_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD" \
        "SELECT type FROM uniauth_flyway_schema_history WHERE version = '6';"
)"
FRESH_V6_HISTORY_TYPE="$(
    psql_value \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$FRESH_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD" \
        "SELECT type FROM uniauth_flyway_schema_history WHERE version = '6';"
)"
if [ "$RESTORED_V6_HISTORY_TYPE" != "SQL" ] || [ "$FRESH_V6_HISTORY_TYPE" != "SQL" ]; then
    echo "ERROR: Flyway V6 was not applied in both rehearsal paths" >&2
    exit 1
fi

RESTORED_WEB3_MESSAGE_COLUMN="$(
    psql_value \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$REHEARSAL_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD" \
        "SELECT data_type || ':' || is_nullable
         FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'web3_nonces'
           AND column_name = 'message';"
)"
FRESH_WEB3_MESSAGE_COLUMN="$(
    psql_value \
        127.0.0.1 \
        "$REHEARSAL_PORT" \
        "$FRESH_DATABASE" \
        "$REHEARSAL_USER" \
        "$REHEARSAL_PASSWORD" \
        "SELECT data_type || ':' || is_nullable
         FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'web3_nonces'
           AND column_name = 'message';"
)"
if [ "$RESTORED_WEB3_MESSAGE_COLUMN" != "text:NO" ] \
    || [ "$FRESH_WEB3_MESSAGE_COLUMN" != "text:NO" ]; then
    echo "ERROR: Flyway V5 did not bind web3_nonces to a required message column" >&2
    exit 1
fi

REPORT_FILE="$ARTIFACT_DIR/rehearsal-result.txt"
{
    echo "timestamp=$RUN_TIMESTAMP"
    echo "database=$POSTGRES_DATABASE"
    echo "source_fingerprint=$SOURCE_FINGERPRINT"
    echo "restored_source_fingerprint=$RESTORED_SOURCE_FINGERPRINT"
    echo "restored_migrated_fingerprint=$RESTORED_MIGRATED_FINGERPRINT"
    echo "fresh_migrated_fingerprint=$FRESH_FINGERPRINT"
    echo "restored_history_type=$RESTORED_HISTORY_TYPE"
    echo "fresh_history_type=$FRESH_HISTORY_TYPE"
    echo "restored_v2_history_type=$RESTORED_V2_HISTORY_TYPE"
    echo "fresh_v2_history_type=$FRESH_V2_HISTORY_TYPE"
    echo "restored_v3_history_type=$RESTORED_V3_HISTORY_TYPE"
    echo "fresh_v3_history_type=$FRESH_V3_HISTORY_TYPE"
    echo "restored_v4_history_type=$RESTORED_V4_HISTORY_TYPE"
    echo "fresh_v4_history_type=$FRESH_V4_HISTORY_TYPE"
    echo "restored_v5_history_type=$RESTORED_V5_HISTORY_TYPE"
    echo "fresh_v5_history_type=$FRESH_V5_HISTORY_TYPE"
    echo "restored_v6_history_type=$RESTORED_V6_HISTORY_TYPE"
    echo "fresh_v6_history_type=$FRESH_V6_HISTORY_TYPE"
    echo "restored_web3_message_column=$RESTORED_WEB3_MESSAGE_COLUMN"
    echo "fresh_web3_message_column=$FRESH_WEB3_MESSAGE_COLUMN"
    echo "source_dump=$SOURCE_DUMP"
} > "$REPORT_FILE"

CONFIRM_TOKEN="baseline:$POSTGRES_DATABASE:$SOURCE_FINGERPRINT"
echo "Flyway baseline rehearsal passed."
echo "Schema fingerprint: $SOURCE_FINGERPRINT"
echo "Artifacts: $ARTIFACT_DIR"
echo "Apply confirmation token: $CONFIRM_TOKEN"

if [ "$MODE" = "apply" ]; then
    if [ "${UNIAUTH_BASELINE_CONFIRM:-}" != "$CONFIRM_TOKEN" ]; then
        echo "ERROR: UNIAUTH_BASELINE_CONFIRM does not match the rehearsal token" >&2
        exit 1
    fi

    APPLY_SOURCE_FINGERPRINT="$(
        schema_fingerprint \
            "$POSTGRES_HOST" \
            "$POSTGRES_PORT" \
            "$POSTGRES_DATABASE" \
            "$POSTGRES_USER" \
            "$POSTGRES_PASSWORD" \
            true
    )"
    if [ "$APPLY_SOURCE_FINGERPRINT" != "$SOURCE_FINGERPRINT" ]; then
        echo "ERROR: source schema changed during rehearsal; refusing baseline apply" >&2
        exit 1
    fi

    APPLY_EXISTING_HISTORY="$(source_history_tables)"
    if [ -n "$APPLY_EXISTING_HISTORY" ]; then
        echo "ERROR: a Flyway history table appeared during rehearsal: $APPLY_EXISTING_HISTORY" >&2
        exit 1
    fi

    APPLY_SOURCE_DATA_VIOLATIONS="$(source_data_violations)"
    if [ -n "$APPLY_SOURCE_DATA_VIOLATIONS" ]; then
        echo "ERROR: source data changed during rehearsal; refusing baseline apply: $APPLY_SOURCE_DATA_VIOLATIONS" >&2
        exit 1
    fi

    APPLY_SOURCE_ENTITY_SCHEMA_VIOLATIONS="$(source_entity_schema_violations)"
    if [ -n "$APPLY_SOURCE_ENTITY_SCHEMA_VIOLATIONS" ]; then
        echo "ERROR: source entity-schema data changed during rehearsal; refusing baseline apply: $APPLY_SOURCE_ENTITY_SCHEMA_VIOLATIONS" >&2
        exit 1
    fi

    APPLY_SOURCE_EMAIL_IDENTITY_VIOLATIONS="$(source_email_identity_violations)"
    if [ -n "$APPLY_SOURCE_EMAIL_IDENTITY_VIOLATIONS" ]; then
        echo "ERROR: source email-identity data changed during rehearsal; refusing baseline apply: $APPLY_SOURCE_EMAIL_IDENTITY_VIOLATIONS" >&2
        exit 1
    fi

    if ! acquire_baseline_lock; then
        exit 1
    fi

    # Re-check the source after acquiring the shared lock.  The rehearsal
    # checks above only prove the state at the end of the read-only rehearsal.
    APPLY_SOURCE_FINGERPRINT="$(
        schema_fingerprint \
            "$POSTGRES_HOST" \
            "$POSTGRES_PORT" \
            "$POSTGRES_DATABASE" \
            "$POSTGRES_USER" \
            "$POSTGRES_PASSWORD" \
            true
    )"
    if [ "$APPLY_SOURCE_FINGERPRINT" != "$SOURCE_FINGERPRINT" ]; then
        echo "ERROR: source schema changed before the locked baseline apply" >&2
        exit 1
    fi

    APPLY_EXISTING_HISTORY="$(source_history_tables)"
    if [ -n "$APPLY_EXISTING_HISTORY" ]; then
        echo "ERROR: a Flyway history table appeared before the locked baseline apply: $APPLY_EXISTING_HISTORY" >&2
        exit 1
    fi

    APPLY_SOURCE_DATA_VIOLATIONS="$(source_data_violations)"
    if [ -n "$APPLY_SOURCE_DATA_VIOLATIONS" ]; then
        echo "ERROR: source data changed before the locked baseline apply: $APPLY_SOURCE_DATA_VIOLATIONS" >&2
        exit 1
    fi

    APPLY_SOURCE_ENTITY_SCHEMA_VIOLATIONS="$(source_entity_schema_violations)"
    if [ -n "$APPLY_SOURCE_ENTITY_SCHEMA_VIOLATIONS" ]; then
        echo "ERROR: source entity-schema data changed before the locked baseline apply: $APPLY_SOURCE_ENTITY_SCHEMA_VIOLATIONS" >&2
        exit 1
    fi

    APPLY_SOURCE_EMAIL_IDENTITY_VIOLATIONS="$(source_email_identity_violations)"
    if [ -n "$APPLY_SOURCE_EMAIL_IDENTITY_VIOLATIONS" ]; then
        echo "ERROR: source email-identity data changed before the locked baseline apply: $APPLY_SOURCE_EMAIL_IDENTITY_VIOLATIONS" >&2
        exit 1
    fi

    SOURCE_URL="jdbc:postgresql://$POSTGRES_HOST:$POSTGRES_PORT/$POSTGRES_DATABASE"
    run_flyway "$SOURCE_URL" "$POSTGRES_USER" "$POSTGRES_PASSWORD" baseline
    if run_flyway "$SOURCE_URL" "$POSTGRES_USER" "$POSTGRES_PASSWORD" migrate; then
        :
    else
        MIGRATE_EXIT_CODE=$?
        remove_incomplete_baseline_history || true
        exit "$MIGRATE_EXIT_CODE"
    fi
    run_flyway "$SOURCE_URL" "$POSTGRES_USER" "$POSTGRES_PASSWORD" validate

    POST_APPLY_FINGERPRINT="$(
        schema_fingerprint \
            "$POSTGRES_HOST" \
            "$POSTGRES_PORT" \
            "$POSTGRES_DATABASE" \
            "$POSTGRES_USER" \
            "$POSTGRES_PASSWORD" \
            true
    )"
    if [ "$POST_APPLY_FINGERPRINT" != "$FRESH_FINGERPRINT" ]; then
        echo "ERROR: applied source schema differs from the rehearsed migration result" >&2
        exit 1
    fi

    echo "Flyway baseline applied to the explicitly confirmed source database."
fi
