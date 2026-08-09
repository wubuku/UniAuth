#!/usr/bin/env bash

# Disposable PostgreSQL backup/restore rehearsal. It covers an explicitly
# shared UniAuth database without reading the component .env file.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_SCRIPT="$PROJECT_DIR/scripts/backup-postgres.sh"
APPLICATION_JAR="${EMAIL_SERVICE_JAR_PATH:-$PROJECT_DIR/target/email-service-1.0.0.jar}"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/email-backup-restore.XXXXXX")"
RUN_ID="$(date +%s)-$$"
CONTAINER_NAME="email-backup-restore-${RUN_ID}"
SOURCE_DATABASE="uniauth_test"
RESTORE_DATABASE="email_service_backup_restore_test"
DATABASE_USER="email_backup_test"
DATABASE_PASSWORD="email-backup-${RUN_ID}"
DATABASE_PORT=""
SERVER_PORT=""
APP_PID=""
APP_LOG=""
API_KEY="email-backup-key-${RUN_ID}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
PG_CLIENT_DIR="$TEMP_DIR/pg-client"
PG_DUMP_WRAPPER="$PG_CLIENT_DIR/pg_dump"
WRONG_PG_DUMP="$PG_CLIENT_DIR/pg_dump-wrong-major"
PG_RESTORE_WRAPPER="$PG_CLIENT_DIR/pg_restore"
WRONG_PG_RESTORE="$PG_CLIENT_DIR/pg_restore-wrong-major"
export NO_PROXY="${NO_PROXY:+${NO_PROXY},}localhost,127.0.0.1,::1"
export no_proxy="${no_proxy:+${no_proxy},}localhost,127.0.0.1,::1"

fail() {
    echo "FAIL: $1" >&2
    if [ -n "$APP_LOG" ] && [ -s "$APP_LOG" ]; then
        echo "Last application log lines:" >&2
        tail -80 "$APP_LOG" >&2
    fi
    exit 1
}

cleanup() {
    local exit_code=$?
    set +e
    stop_application
    if docker ps -a --format '{{.Names}}' 2>/dev/null \
        | grep -Fxq "$CONTAINER_NAME"; then
        docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
    fi
    rm -rf "$TEMP_DIR"
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

for command_name in awk cat chmod curl docker find grep java jq ln mvn \
        pg_isready psql seq shasum stat tail tr wc; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        fail "required command is unavailable: $command_name"
    fi
done
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
    fail "configured Python is unavailable: $PYTHON_BIN"
fi
if ! docker info >/dev/null 2>&1; then
    fail "Docker is unavailable"
fi

free_port() {
    "$PYTHON_BIN" - <<'PY'
import socket

with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

file_mode() {
    local path="$1"
    if stat -f '%Lp' "$path" >/dev/null 2>&1; then
        stat -f '%Lp' "$path"
    else
        stat -c '%a' "$path"
    fi
}

db_command() {
    local database="$1"
    shift
    PGPASSWORD="$DATABASE_PASSWORD" psql \
        -X -q -v ON_ERROR_STOP=1 \
        -h 127.0.0.1 \
        -p "$DATABASE_PORT" \
        -U "$DATABASE_USER" \
        -d "$database" \
        "$@"
}

db_value() {
    local database="$1"
    local sql="$2"
    db_command "$database" -At -c "$sql"
}

create_database() {
    db_command postgres -c "CREATE DATABASE \"$1\";" >/dev/null
}

database_fingerprint() {
    local database="$1"
    db_value "$database" "
        SELECT md5(
            COALESCE((
                SELECT string_agg(
                    concat_ws(
                        '|',
                        id,
                        recipient,
                        subject,
                        html_content,
                        COALESCE(email_type, '<null>'),
                        status,
                        priority,
                        retry_count,
                        max_retries,
                        COALESCE(next_retry_time::text, '<null>'),
                        COALESCE(error_message, '<null>'),
                        created_time::text,
                        updated_time::text,
                        COALESCE(processed_time::text, '<null>'),
                        COALESCE(metadata, '<null>'),
                        COALESCE(idempotency_key, '<null>'),
                        COALESCE(request_fingerprint, '<null>')
                    ),
                    E'\n'
                    ORDER BY id
                )
                FROM email_queue
            ), '')
            || '#'
            || COALESCE((
                SELECT string_agg(
                    concat_ws(
                        '|',
                        id,
                        COALESCE(queue_id::text, '<null>'),
                        recipient,
                        subject,
                        status,
                        COALESCE(error_message, '<null>'),
                        sent_time::text,
                        retry_count,
                        COALESCE(email_content, '<null>'),
                        COALESCE(email_type, '<null>'),
                        COALESCE(mail_provider, '<null>'),
                        COALESCE(duration_ms::text, '<null>'),
                        COALESCE(send_method, '<null>')
                    ),
                    E'\n'
                    ORDER BY id
                )
                FROM email_logs
            ), '')
        );
    "
}

start_application() {
    local database="$1"
    local database_layout="${2:-dedicated}"
    APP_LOG="$TEMP_DIR/${database}.log"
    SERVER_PORT="$(free_port)"
    (
        export SPRING_PROFILES_ACTIVE=dev
        export EMAIL_SERVICE_BIND_ADDRESS=127.0.0.1
        export EMAIL_SERVICE_PORT="$SERVER_PORT"
        export EMAIL_SERVICE_API_KEY="$API_KEY"
        export EMAIL_POSTGRES_HOST=127.0.0.1
        export EMAIL_POSTGRES_PORT="$DATABASE_PORT"
        export EMAIL_POSTGRES_DATABASE="$database"
        export EMAIL_POSTGRES_USER="$DATABASE_USER"
        export EMAIL_POSTGRES_PASSWORD="$DATABASE_PASSWORD"
        export EMAIL_DATABASE_LAYOUT="$database_layout"
        export SMTP_HOST=127.0.0.1
        export SMTP_PORT=1
        export SMTP_AUTH=false
        export SMTP_STARTTLS_ENABLE=false
        export SMTP_STARTTLS_REQUIRED=false
        export SMTP_SSL_ENABLE=false
        export SMTP_SSL_CHECK_SERVER_IDENTITY=true
        export EMAIL_FROM_ADDRESS=no-reply@example.test
        export EMAIL_QUEUE_EVENT_DRIVEN=false
        export EMAIL_RECOVERY_ENABLED=false
        export EMAIL_RATE_LIMIT_ENABLED=false
        exec java -jar "$APPLICATION_JAR"
    ) >"$APP_LOG" 2>&1 &
    APP_PID=$!
}

wait_for_application() {
    for _ in $(seq 1 90); do
        if curl -fsS \
            -H "X-Email-Service-Key: $API_KEY" \
            "http://127.0.0.1:${SERVER_PORT}/api/email/health" \
            >/dev/null 2>&1; then
            return
        fi
        if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
            fail "application exited before becoming ready"
        fi
        sleep 1
    done
    fail "application did not become ready"
}

stop_application() {
    if [ -z "${APP_PID:-}" ]; then
        return
    fi
    kill -TERM "$APP_PID" >/dev/null 2>&1 || true
    wait "$APP_PID" >/dev/null 2>&1 || true
    APP_PID=""
}

run_backup() {
    local database="$1"
    local port="$2"
    local output_dir="$3"
    local pg_dump_bin="${4:-$PG_DUMP_WRAPPER}"
    local pg_restore_bin="${5:-$PG_RESTORE_WRAPPER}"
    local database_layout="${6:-dedicated}"
    SPRING_PROFILES_ACTIVE=dev \
    EMAIL_SERVICE_ENV_FILE= \
    EMAIL_POSTGRES_HOST=127.0.0.1 \
    EMAIL_POSTGRES_PORT="$port" \
    EMAIL_POSTGRES_DATABASE="$database" \
    EMAIL_POSTGRES_USER="$DATABASE_USER" \
    EMAIL_POSTGRES_PASSWORD="$DATABASE_PASSWORD" \
    EMAIL_DATABASE_LAYOUT="$database_layout" \
    EMAIL_BACKUP_DIR="$output_dir" \
    EMAIL_BACKUP_CONNECT_TIMEOUT_SECONDS=1 \
    EMAIL_PG_DUMP_BIN="$pg_dump_bin" \
    EMAIL_PG_RESTORE_BIN="$pg_restore_bin" \
        "$BACKUP_SCRIPT"
}

run_backup_without_output_dir() {
    (
        unset EMAIL_BACKUP_DIR
        SPRING_PROFILES_ACTIVE=dev \
        EMAIL_SERVICE_ENV_FILE= \
        EMAIL_POSTGRES_HOST=127.0.0.1 \
        EMAIL_POSTGRES_PORT="$DATABASE_PORT" \
        EMAIL_POSTGRES_DATABASE="$SOURCE_DATABASE" \
        EMAIL_POSTGRES_USER="$DATABASE_USER" \
        EMAIL_POSTGRES_PASSWORD="$DATABASE_PASSWORD" \
        EMAIL_DATABASE_LAYOUT=shared-uniauth \
        EMAIL_BACKUP_CONNECT_TIMEOUT_SECONDS=1 \
        EMAIL_PG_DUMP_BIN="$PG_DUMP_WRAPPER" \
        EMAIL_PG_RESTORE_BIN="$PG_RESTORE_WRAPPER" \
            "$BACKUP_SCRIPT"
    )
}

assert_no_backup_artifacts() {
    local directory="$1"
    [ "$(find "$directory" -mindepth 1 -maxdepth 1 -type f | wc -l | tr -d ' ')" = "0" ] \
        || fail "failed backup left a regular file in $directory"
}

[ -x "$BACKUP_SCRIPT" ] \
    || fail "backup command is unavailable or not executable: $BACKUP_SCRIPT"

if [ "${EMAIL_SERVICE_SKIP_BUILD:-false}" != "true" ]; then
    echo "Backup/restore rehearsal: packaging the email service"
    (cd "$PROJECT_DIR" && mvn -q -DskipTests package)
fi
[ -r "$APPLICATION_JAR" ] \
    || fail "email service application JAR is unavailable: $APPLICATION_JAR"

echo "Backup/restore rehearsal: starting disposable PostgreSQL"
docker run -d --rm \
    --name "$CONTAINER_NAME" \
    -v "$TEMP_DIR:$TEMP_DIR" \
    -e "POSTGRES_DB=postgres" \
    -e "POSTGRES_USER=$DATABASE_USER" \
    -e "POSTGRES_PASSWORD=$DATABASE_PASSWORD" \
    -p 127.0.0.1::5432 \
    postgres:16 >/dev/null
DATABASE_PORT="$(
    docker port "$CONTAINER_NAME" 5432/tcp \
        | awk -F: 'NR == 1 {print $NF}'
)"

for _ in $(seq 1 60); do
    if PGPASSWORD="$DATABASE_PASSWORD" pg_isready \
        -h 127.0.0.1 \
        -p "$DATABASE_PORT" \
        -U "$DATABASE_USER" \
        -d postgres >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
if ! PGPASSWORD="$DATABASE_PASSWORD" pg_isready \
    -h 127.0.0.1 \
    -p "$DATABASE_PORT" \
    -U "$DATABASE_USER" \
    -d postgres >/dev/null 2>&1; then
    fail "disposable PostgreSQL did not become ready"
fi

mkdir -m 700 "$PG_CLIENT_DIR"
cat >"$PG_DUMP_WRAPPER" <<EOF
#!/usr/bin/env bash
set -euo pipefail

if [ "\${1:-}" = "--version" ]; then
    exec docker exec "$CONTAINER_NAME" pg_dump --version
fi

args=()
for arg in "\$@"; do
    case "\$arg" in
        --host=*)
            args+=("--host=127.0.0.1")
            ;;
        --port=*)
            args+=("--port=5432")
            ;;
        *)
            args+=("\$arg")
            ;;
    esac
done

docker_args=(-e "PGPASSWORD=\${PGPASSWORD:-}")
if [ -n "\${PGCONNECT_TIMEOUT:-}" ]; then
    docker_args+=(-e "PGCONNECT_TIMEOUT=\$PGCONNECT_TIMEOUT")
fi
exec docker exec \
    "\${docker_args[@]}" \
    "$CONTAINER_NAME" \
    pg_dump "\${args[@]}"
EOF
cat >"$WRONG_PG_DUMP" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [ "${1:-}" = "--version" ]; then
    echo "pg_dump (PostgreSQL) 99.0"
    exit 0
fi
echo "wrong-major pg_dump must not be invoked for a backup" >&2
exit 97
EOF
cat >"$PG_RESTORE_WRAPPER" <<EOF
#!/usr/bin/env bash
set -euo pipefail

if [ "\${1:-}" = "--version" ]; then
    exec docker exec "$CONTAINER_NAME" pg_restore --version
fi

args=()
for arg in "\$@"; do
    case "\$arg" in
        --host=*)
            args+=("--host=127.0.0.1")
            ;;
        --port=*)
            args+=("--port=5432")
            ;;
        *)
            args+=("\$arg")
            ;;
    esac
done

docker_args=(-e "PGPASSWORD=\${PGPASSWORD:-}")
if [ -n "\${PGCONNECT_TIMEOUT:-}" ]; then
    docker_args+=(-e "PGCONNECT_TIMEOUT=\$PGCONNECT_TIMEOUT")
fi
exec docker exec \
    "\${docker_args[@]}" \
    "$CONTAINER_NAME" \
    pg_restore "\${args[@]}"
EOF
cat >"$WRONG_PG_RESTORE" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [ "${1:-}" = "--version" ]; then
    echo "pg_restore (PostgreSQL) 99.0"
    exit 0
fi
echo "wrong-major pg_restore must not be invoked for archive validation" >&2
exit 97
EOF
chmod 700 \
    "$PG_DUMP_WRAPPER" \
    "$WRONG_PG_DUMP" \
    "$PG_RESTORE_WRAPPER" \
    "$WRONG_PG_RESTORE"

create_database "$SOURCE_DATABASE"

echo "1/10 Create a Flyway-owned source database with all queue lifecycle states"
start_application "$SOURCE_DATABASE" shared-uniauth
wait_for_application
[ "$(db_value "$SOURCE_DATABASE" \
    "SELECT count(*) FROM email_service_flyway_schema_history WHERE success;")" = "5" ] \
    || fail "source database did not apply Flyway V1 through V5"
db_command "$SOURCE_DATABASE" -c "
    INSERT INTO email_queue (
        recipient, subject, html_content, email_type, status, priority,
        retry_count, max_retries, next_retry_time, error_message,
        created_time, updated_time, processed_time, metadata
    ) VALUES
        (
            'pending@example.test', 'Pending', '<p>pending</p>', 'TEST',
            'PENDING', 9, 1, 3, CURRENT_TIMESTAMP + INTERVAL '5 minutes',
            NULL, CURRENT_TIMESTAMP - INTERVAL '5 minutes',
            CURRENT_TIMESTAMP - INTERVAL '1 minute', NULL, '{\"state\":\"pending\"}'
        ),
        (
            'processing@example.test', 'Processing', '<p>processing</p>', 'TEST',
            'PROCESSING', 8, 1, 3, NULL, NULL,
            CURRENT_TIMESTAMP - INTERVAL '5 minutes',
            CURRENT_TIMESTAMP - INTERVAL '1 minute', NULL, '{\"state\":\"processing\"}'
        ),
        (
            'completed@example.test', 'Completed', '<redacted/>', 'TEST',
            'COMPLETED', 7, 0, 3, NULL, NULL,
            CURRENT_TIMESTAMP - INTERVAL '5 minutes',
            CURRENT_TIMESTAMP - INTERVAL '1 minute',
            CURRENT_TIMESTAMP - INTERVAL '1 minute', NULL
        ),
        (
            'failed@example.test', 'Failed', '<redacted/>', 'TEST',
            'FAILED', 6, 3, 3, NULL, 'SMTP failed',
            CURRENT_TIMESTAMP - INTERVAL '5 minutes',
            CURRENT_TIMESTAMP - INTERVAL '1 minute',
            CURRENT_TIMESTAMP - INTERVAL '1 minute', NULL
        );

    INSERT INTO email_logs (
        queue_id, recipient, subject, status, error_message, sent_time,
        retry_count, email_content, email_type, mail_provider, duration_ms,
        send_method
    )
    SELECT
        id,
        recipient,
        subject,
        CASE WHEN status = 'COMPLETED' THEN 'SUCCESS' ELSE 'FAILED' END,
        CASE WHEN status = 'COMPLETED' THEN NULL ELSE 'fixture audit' END,
        CURRENT_TIMESTAMP,
        retry_count,
        NULL,
        email_type,
        'fixture',
        10,
        'SCHEDULED'
    FROM email_queue
    WHERE recipient IN (
        'completed@example.test',
        'failed@example.test'
    );

    UPDATE email_queue
    SET idempotency_key = 'backup-pending-1',
        request_fingerprint = repeat('a', 64)
    WHERE recipient = 'pending@example.test';
" >/dev/null
[ "$(db_value "$SOURCE_DATABASE" "SELECT count(*) FROM email_queue;")" = "4" ] \
    || fail "source queue fixture was not created"
[ "$(db_value "$SOURCE_DATABASE" "SELECT count(*) FROM email_logs;")" = "2" ] \
    || fail "source log fixture was not created"
[ "$(db_value "$SOURCE_DATABASE" "
    SELECT count(*)
    FROM email_queue
    WHERE status IN ('COMPLETED', 'FAILED')
      AND html_content = '<redacted/>'
      AND metadata IS NULL;
")" = "2" ] || fail "source terminal queue fixtures are not redacted"
[ "$(db_value "$SOURCE_DATABASE" \
    "SELECT count(*) FROM email_logs WHERE email_content IS NULL;")" = "2" ] \
    || fail "source log fixtures retained rendered content"
db_command "$SOURCE_DATABASE" -c "
    CREATE TABLE users (
        id character varying(36) PRIMARY KEY,
        username character varying(255) NOT NULL,
        email character varying(255) NOT NULL
    );
    INSERT INTO users (id, username, email)
    VALUES (
        'shared-backup-user',
        'shared-backup-user',
        'shared-backup-user@example.test'
    );
" >/dev/null
SOURCE_FINGERPRINT="$(database_fingerprint "$SOURCE_DATABASE")"

echo "2/10 Require an explicit backup directory"
if run_backup_without_output_dir \
        >"$TEMP_DIR/missing-directory.out" \
        2>"$TEMP_DIR/missing-directory.err"; then
    fail "backup accepted an implicit output directory"
fi
grep -Fq "required environment variable EMAIL_BACKUP_DIR is not set" \
    "$TEMP_DIR/missing-directory.err" \
    || fail "missing output directory did not report the explicit configuration guard"

echo "3/10 Reject an unsafe shared database or incomplete Flyway history"
shared_dir="$TEMP_DIR/shared"
mkdir -m 700 "$shared_dir"
if run_backup "$SOURCE_DATABASE" "$DATABASE_PORT" "$shared_dir" \
        >"$TEMP_DIR/shared.out" 2>"$TEMP_DIR/shared.err"; then
    fail "backup accepted a shared UniAuth database without explicit layout"
fi
grep -Fq "EMAIL_DATABASE_LAYOUT=shared-uniauth is required" "$TEMP_DIR/shared.err" \
    || fail "shared-database rejection did not report the safety guard"
assert_no_backup_artifacts "$shared_dir"

history_dir="$TEMP_DIR/incomplete-history"
mkdir -m 700 "$history_dir"
db_command "$SOURCE_DATABASE" -c "
    UPDATE email_service_flyway_schema_history
    SET version = '2'
    WHERE version = '3';
" >/dev/null
if run_backup \
        "$SOURCE_DATABASE" \
        "$DATABASE_PORT" \
        "$history_dir" \
        "$PG_DUMP_WRAPPER" \
        "$PG_RESTORE_WRAPPER" \
        shared-uniauth \
        >"$TEMP_DIR/history.out" 2>"$TEMP_DIR/history.err"; then
    fail "backup accepted duplicate V2 history with missing V3"
fi
grep -Fq "email service Flyway history must match the expected V1 through V5 chain" \
    "$TEMP_DIR/history.err" \
    || fail "incomplete history rejection did not report the Flyway guard"
assert_no_backup_artifacts "$history_dir"
db_command "$SOURCE_DATABASE" -c "
    UPDATE email_service_flyway_schema_history
    SET version = '3'
    WHERE version = '2'
      AND description = 'enforce queue lifecycle state';
" >/dev/null

db_command "$SOURCE_DATABASE" -c "
    INSERT INTO email_service_flyway_schema_history (
        installed_rank,
        version,
        description,
        type,
        script,
        checksum,
        installed_by,
        execution_time,
        success
    )
    SELECT
        max(installed_rank) + 1,
        '6',
        'unexpected future migration',
        'SQL',
        'V6__unexpected_future_migration.sql',
        0,
        current_user,
        0,
        true
    FROM email_service_flyway_schema_history;
" >/dev/null
if run_backup \
        "$SOURCE_DATABASE" \
        "$DATABASE_PORT" \
        "$history_dir" \
        "$PG_DUMP_WRAPPER" \
        "$PG_RESTORE_WRAPPER" \
        shared-uniauth \
        >"$TEMP_DIR/unexpected-history.out" \
        2>"$TEMP_DIR/unexpected-history.err"; then
    fail "backup accepted an unknown future Flyway migration"
fi
grep -Fq "email service Flyway history must match the expected V1 through V5 chain" \
    "$TEMP_DIR/unexpected-history.err" \
    || fail "unexpected history rejection did not report the Flyway guard"
assert_no_backup_artifacts "$history_dir"
db_command "$SOURCE_DATABASE" -c "
    DELETE FROM email_service_flyway_schema_history
    WHERE version = '6'
      AND description = 'unexpected future migration';
" >/dev/null

db_command "$SOURCE_DATABASE" -c "
    INSERT INTO email_service_flyway_schema_history (
        installed_rank,
        version,
        description,
        type,
        script,
        checksum,
        installed_by,
        execution_time,
        success
    )
    SELECT
        max(installed_rank) + 1,
        NULL,
        'unexpected repeatable migration',
        'SQL',
        'R__unexpected_repeatable_migration.sql',
        0,
        current_user,
        0,
        true
    FROM email_service_flyway_schema_history;
" >/dev/null
if run_backup \
        "$SOURCE_DATABASE" \
        "$DATABASE_PORT" \
        "$history_dir" \
        "$PG_DUMP_WRAPPER" \
        "$PG_RESTORE_WRAPPER" \
        shared-uniauth \
        >"$TEMP_DIR/repeatable-history.out" \
        2>"$TEMP_DIR/repeatable-history.err"; then
    fail "backup accepted an unknown repeatable Flyway migration"
fi
grep -Fq "email service Flyway history must match the expected V1 through V5 chain" \
    "$TEMP_DIR/repeatable-history.err" \
    || fail "repeatable history rejection did not report the Flyway guard"
assert_no_backup_artifacts "$history_dir"
db_command "$SOURCE_DATABASE" -c "
    DELETE FROM email_service_flyway_schema_history
    WHERE version IS NULL
      AND description = 'unexpected repeatable migration';
" >/dev/null

echo "4/10 Reject a pg_dump major that differs from the source server"
version_dir="$TEMP_DIR/version-mismatch"
mkdir -m 700 "$version_dir"
if run_backup \
        "$SOURCE_DATABASE" \
        "$DATABASE_PORT" \
        "$version_dir" \
        "$WRONG_PG_DUMP" \
        "$PG_RESTORE_WRAPPER" \
        shared-uniauth \
        >"$TEMP_DIR/version.out" 2>"$TEMP_DIR/version.err"; then
    fail "backup accepted a mismatched pg_dump major version"
fi
grep -Fq "pg_dump major version 99 must match source PostgreSQL major 16" \
    "$TEMP_DIR/version.err" \
    || fail "pg_dump version rejection did not report both major versions"
assert_no_backup_artifacts "$version_dir"
if run_backup \
        "$SOURCE_DATABASE" \
        "$DATABASE_PORT" \
        "$version_dir" \
        "$PG_DUMP_WRAPPER" \
        "$WRONG_PG_RESTORE" \
        shared-uniauth \
        >"$TEMP_DIR/restore-version.out" 2>"$TEMP_DIR/restore-version.err"; then
    fail "backup accepted a mismatched pg_restore major version"
fi
grep -Fq "pg_restore major version 99 must match source PostgreSQL major 16" \
    "$TEMP_DIR/restore-version.err" \
    || fail "pg_restore version rejection did not report both major versions"
assert_no_backup_artifacts "$version_dir"

echo "5/10 Remove temporary files when pg_dump cannot connect"
failure_dir="$TEMP_DIR/failure"
mkdir -m 700 "$failure_dir"
if run_backup \
        "$SOURCE_DATABASE" \
        1 \
        "$failure_dir" \
        "$PG_DUMP_WRAPPER" \
        "$PG_RESTORE_WRAPPER" \
        shared-uniauth \
        >"$TEMP_DIR/failure.out" 2>"$TEMP_DIR/failure.err"; then
    fail "backup unexpectedly succeeded against an unavailable PostgreSQL port"
fi
assert_no_backup_artifacts "$failure_dir"

echo "6/10 Reject a symbolic-link backup directory"
real_dir="$TEMP_DIR/real-backups"
linked_dir="$TEMP_DIR/linked-backups"
mkdir -m 700 "$real_dir"
ln -s "$real_dir" "$linked_dir"
if run_backup \
        "$SOURCE_DATABASE" \
        "$DATABASE_PORT" \
        "$linked_dir" \
        "$PG_DUMP_WRAPPER" \
        "$PG_RESTORE_WRAPPER" \
        shared-uniauth \
        >"$TEMP_DIR/symlink.out" 2>"$TEMP_DIR/symlink.err"; then
    fail "backup accepted a symbolic-link output directory"
fi
grep -Fq "backup directory must not be a symbolic link" "$TEMP_DIR/symlink.err" \
    || fail "symbolic-link rejection did not report the output guard"
assert_no_backup_artifacts "$real_dir"

echo "7/10 Create an atomic owner-only PostgreSQL custom-format backup"
backup_dir="$TEMP_DIR/backups"
mkdir -m 700 "$backup_dir"
BACKUP_FILE="$(
    run_backup \
        "$SOURCE_DATABASE" \
        "$DATABASE_PORT" \
        "$backup_dir" \
        "$PG_DUMP_WRAPPER" \
        "$PG_RESTORE_WRAPPER" \
        shared-uniauth
)"
[ -f "$BACKUP_FILE" ] || fail "backup command did not create the reported archive"
[ -f "${BACKUP_FILE}.sha256" ] || fail "backup command did not create a checksum"
[ "$(file_mode "$BACKUP_FILE")" = "600" ] \
    || fail "backup archive is not owner-only"
[ "$(file_mode "${BACKUP_FILE}.sha256")" = "600" ] \
    || fail "backup checksum is not owner-only"
(
    cd "$backup_dir"
    shasum -a 256 -c "$(basename "${BACKUP_FILE}.sha256")" >/dev/null
)
"$PG_RESTORE_WRAPPER" --list "$BACKUP_FILE" \
    | grep -Fq "TABLE public email_queue" \
    || fail "backup archive does not contain email_queue"
"$PG_RESTORE_WRAPPER" --list "$BACKUP_FILE" \
    | grep -Fq "TABLE public email_logs" \
    || fail "backup archive does not contain email_logs"
if "$PG_RESTORE_WRAPPER" --list "$BACKUP_FILE" \
        | grep -Eq "TABLE public users|TABLE DATA public users"; then
    fail "component backup unexpectedly contains UniAuth users"
fi

echo "8/10 Restore into an empty database and compare schema and data"
create_database "$RESTORE_DATABASE"
PGPASSWORD="$DATABASE_PASSWORD" "$PG_RESTORE_WRAPPER" \
    --exit-on-error \
    --single-transaction \
    --no-owner \
    --no-acl \
    --host=127.0.0.1 \
    --port="$DATABASE_PORT" \
    --username="$DATABASE_USER" \
    --dbname="$RESTORE_DATABASE" \
    "$BACKUP_FILE"
[ "$(database_fingerprint "$RESTORE_DATABASE")" = "$SOURCE_FINGERPRINT" ] \
    || fail "restored queue/log data differs from the source"
[ "$(db_value "$RESTORE_DATABASE" \
    "SELECT count(*) FROM email_service_flyway_schema_history WHERE success;")" = "5" ] \
    || fail "restored Flyway history is incomplete"
[ "$(db_value "$RESTORE_DATABASE" "
    SELECT count(*)
    FROM pg_constraint
    WHERE conname = 'chk_email_queue_lifecycle_state';
")" = "1" ] || fail "restored lifecycle constraint is missing"
[ "$(db_value "$RESTORE_DATABASE" "
    SELECT count(*)
    FROM pg_constraint
    WHERE conname IN (
        'chk_email_logs_content_redacted',
        'chk_email_queue_terminal_payload_redacted'
    );
")" = "2" ] || fail "restored payload redaction constraints are missing"
[ "$(db_value "$RESTORE_DATABASE" \
    "SELECT to_regclass('public.users') IS NULL;")" = "t" ] \
    || fail "component restore unexpectedly created UniAuth users"

echo "9/10 Start the real application on the restore and append through HTTP"
stop_application
start_application "$RESTORE_DATABASE" dedicated
wait_for_application
max_queue_id="$(db_value "$RESTORE_DATABASE" "SELECT max(id) FROM email_queue;")"
payload="$(
    jq -cn '{
      to: "restored@example.test",
      subject: "Restored database write",
      templateName: "email/email-verify",
      variables: {
        code: "135790",
        verificationCode: "135790",
        username: "restored@example.test",
        expiryMinutes: 10
      },
      emailType: "VERIFICATION",
      idempotencyKey: "restored-delivery-1"
    }'
)"
response="$(
    curl -fsS \
        -X POST \
        -H "Content-Type: application/json" \
        -H "X-Email-Service-Key: $API_KEY" \
        --data "$payload" \
        "http://127.0.0.1:${SERVER_PORT}/api/email/template"
)"
[ "$(jq -er '.success' <<<"$response")" = "true" ] \
    || fail "restored application did not accept a template request"
new_queue_id="$(jq -er '.queueId' <<<"$response")"
[ "$new_queue_id" -gt "$max_queue_id" ] \
    || fail "restored sequence did not continue above existing queue ids"
[ "$(db_value "$RESTORE_DATABASE" "SELECT count(*) FROM email_queue;")" = "5" ] \
    || fail "restored application did not persist the new queue row"
[ "$(db_value "$RESTORE_DATABASE" "
    SELECT count(*)
    FROM email_queue
    WHERE id = $new_queue_id
      AND idempotency_key = 'restored-delivery-1'
      AND length(request_fingerprint) = 64;
")" = "1" ] || fail "restored application did not persist delivery identity"

echo "10/10 Restart without replaying migrations or losing restored data"
stop_application
start_application "$RESTORE_DATABASE" dedicated
wait_for_application
[ "$(db_value "$RESTORE_DATABASE" \
    "SELECT count(*) FROM email_service_flyway_schema_history WHERE success;")" = "5" ] \
    || fail "restored restart changed Flyway history"
[ "$(db_value "$RESTORE_DATABASE" "SELECT count(*) FROM email_queue;")" = "5" ] \
    || fail "restored restart lost queue data"

echo "PASS: email service backup/restore rehearsal"
