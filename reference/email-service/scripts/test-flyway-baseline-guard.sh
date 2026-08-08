#!/usr/bin/env bash

# Disposable PostgreSQL checks for fail-closed Flyway adoption and V2 data
# compatibility. This never baselines or migrates a shared database.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APPLICATION_JAR="${EMAIL_SERVICE_JAR_PATH:-$PROJECT_DIR/target/email-service-1.0.0.jar}"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/email-flyway-guard.XXXXXX")"
RUN_ID="$(date +%s)-$$"
CONTAINER_NAME="email-flyway-guard-${RUN_ID}"
DATABASE_USER="email_test"
DATABASE_PASSWORD="email-flyway-${RUN_ID}"
DATABASE_PORT=""
APP_PID=""
SERVER_PORT=""
APPLICATION_API_KEY=""
PYTHON_BIN="${PYTHON_BIN:-python3}"
export NO_PROXY="${NO_PROXY:+${NO_PROXY},}localhost,127.0.0.1,::1"
export no_proxy="${no_proxy:+${no_proxy},}localhost,127.0.0.1,::1"

fail() {
    echo "FAIL: $1" >&2
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

for command_name in awk curl docker grep java jq mvn pg_isready psql; do
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

header_value() {
    local headers_file="$1"
    local header_name="$2"
    awk -v wanted="$header_name" '
        {
            line = $0
            sub(/\r$/, "", line)
            separator = index(line, ":")
            if (separator == 0) {
                next
            }
            name = substr(line, 1, separator - 1)
            if (tolower(name) == tolower(wanted)) {
                value = substr(line, separator + 1)
                sub(/^[[:space:]]+/, "", value)
                print value
                exit
            }
        }
    ' "$headers_file"
}

assert_security_headers() {
    local headers_file="$TEMP_DIR/flyway-health.headers"
    curl -fsS \
        -D "$headers_file" \
        -o /dev/null \
        "http://127.0.0.1:${SERVER_PORT}/api/email/health"
    [ "$(header_value "$headers_file" "Cache-Control")" = "no-store" ] \
        || fail "migrated application did not return Cache-Control: no-store"
    [ "$(header_value "$headers_file" "Pragma")" = "no-cache" ] \
        || fail "migrated application did not return Pragma: no-cache"
    [ "$(header_value "$headers_file" "X-Content-Type-Options")" = "nosniff" ] \
        || fail "migrated application did not return X-Content-Type-Options: nosniff"
}

assert_repeated_api_key_rejected() {
    local status
    status="$(
        curl -sS \
            -o /dev/null \
            -w '%{http_code}' \
            -H "X-Email-Service-Key: $APPLICATION_API_KEY" \
            -H "X-Email-Service-Key: $APPLICATION_API_KEY" \
            "http://127.0.0.1:${SERVER_PORT}/api/email/health"
    )"
    [ "$status" = "401" ] \
        || fail "migrated application accepted repeated API-key headers"
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

start_application() {
    local database="$1"
    local log_file="$2"
    local flyway_target="${3:-}"
    local flyway_override="${4:-}"
    SERVER_PORT="$(free_port)"
    (
        export SPRING_PROFILES_ACTIVE=dev
        export EMAIL_SERVICE_BIND_ADDRESS=127.0.0.1
        export EMAIL_SERVICE_PORT="$SERVER_PORT"
        export EMAIL_SERVICE_API_KEY="$APPLICATION_API_KEY"
        export EMAIL_POSTGRES_HOST=127.0.0.1
        export EMAIL_POSTGRES_PORT="$DATABASE_PORT"
        export EMAIL_POSTGRES_DATABASE="$database"
        export EMAIL_POSTGRES_USER="$DATABASE_USER"
        export EMAIL_POSTGRES_PASSWORD="$DATABASE_PASSWORD"
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
        if [ -n "$flyway_target" ]; then
            export SPRING_FLYWAY_TARGET="$flyway_target"
        fi
        if [ -n "$flyway_override" ]; then
            export "$flyway_override"
        fi
        exec java -jar "$APPLICATION_JAR"
    ) >"$log_file" 2>&1 &
    APP_PID=$!
}

wait_for_application() {
    local log_file="$1"
    for _ in $(seq 1 90); do
        if [ -n "$APPLICATION_API_KEY" ]; then
            if curl -fsS \
                -H "X-Email-Service-Key: $APPLICATION_API_KEY" \
                "http://127.0.0.1:${SERVER_PORT}/api/email/health" \
                >/dev/null 2>&1; then
                return
            fi
        elif curl -fsS \
            "http://127.0.0.1:${SERVER_PORT}/api/email/health" \
            >/dev/null 2>&1; then
            return
        fi
        if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
            tail -80 "$log_file" >&2 || true
            fail "application exited before becoming ready"
        fi
        sleep 1
    done
    fail "application did not become ready"
}

expect_startup_failure() {
    local database="$1"
    local log_file="$2"
    local flyway_target="${3:-}"
    local flyway_override="${4:-}"

    start_application "$database" "$log_file" "$flyway_target" "$flyway_override"
    for _ in $(seq 1 60); do
        if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
            if wait "$APP_PID"; then
                fail "invalid Flyway startup exited successfully"
            fi
            APP_PID=""
            return
        fi
        sleep 1
    done

    stop_application
    fail "invalid Flyway startup remained running"
}

stop_application() {
    if [ -z "${APP_PID:-}" ]; then
        return
    fi
    kill -TERM "$APP_PID" >/dev/null 2>&1 || true
    wait "$APP_PID" >/dev/null 2>&1 || true
    APP_PID=""
}

if [ "${EMAIL_SERVICE_SKIP_BUILD:-false}" != "true" ]; then
    echo "Flyway guard: packaging the email service"
    (cd "$PROJECT_DIR" && mvn -q -DskipTests package)
fi
[ -r "$APPLICATION_JAR" ] \
    || fail "email service application JAR is unavailable: $APPLICATION_JAR"

echo "Flyway guard: starting disposable PostgreSQL"
docker run -d --rm \
    --name "$CONTAINER_NAME" \
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

DIRTY_DATABASE="email_dirty_schema_test"
SHARED_DATABASE="uniauth_test"
V2_DATABASE="email_v2_guard_test"
ORPHAN_DATABASE="email_orphan_guard_test"
CHECKSUM_DATABASE="email_checksum_guard_test"
CONFIG_DATABASE="email_flyway_config_guard_test"
create_database "$DIRTY_DATABASE"
create_database "$SHARED_DATABASE"
create_database "$V2_DATABASE"
create_database "$ORPHAN_DATABASE"
create_database "$CHECKSUM_DATABASE"
create_database "$CONFIG_DATABASE"

echo "1/14 Reject a shared database before Flyway can create schema objects"
shared_log="$TEMP_DIR/shared-database.log"
expect_startup_failure "$SHARED_DATABASE" "$shared_log"
grep -Fq "Email service database name must contain email or mail" "$shared_log" \
    || fail "shared-database failure did not report the runtime database guard"
[ "$(db_value "$SHARED_DATABASE" \
    "SELECT to_regclass('public.email_service_flyway_schema_history') IS NULL;")" = "t" ] \
    || fail "shared-database startup created Flyway history"
[ "$(db_value "$SHARED_DATABASE" \
    "SELECT to_regclass('public.email_queue') IS NULL;")" = "t" ] \
    || fail "shared-database startup created email schema objects"

echo "2/14 Reject a non-empty schema without Flyway history"
db_command "$DIRTY_DATABASE" \
    -c "CREATE TABLE unexpected_table (id bigint PRIMARY KEY);" >/dev/null
dirty_log="$TEMP_DIR/dirty-schema.log"
expect_startup_failure "$DIRTY_DATABASE" "$dirty_log"
grep -Fq "non-empty schema" "$dirty_log" \
    || fail "dirty-schema failure did not report the Flyway baseline guard"
[ "$(db_value "$DIRTY_DATABASE" \
    "SELECT to_regclass('public.email_service_flyway_schema_history') IS NULL;")" = "t" ] \
    || fail "dirty-schema startup created Flyway history"

echo "3/14 Apply only V1 to a disposable database"
v1_log="$TEMP_DIR/v1.log"
start_application "$V2_DATABASE" "$v1_log" 1
wait_for_application "$v1_log"
echo "4/14 Verify migrated application response security headers"
assert_security_headers
stop_application
APPLICATION_API_KEY="email-flyway-key-${RUN_ID}"
credential_log="$TEMP_DIR/v1-credential.log"
start_application "$V2_DATABASE" "$credential_log" 1
wait_for_application "$credential_log"
echo "5/14 Reject repeated API-key headers after migration"
assert_repeated_api_key_rejected
stop_application
APPLICATION_API_KEY=""
[ "$(db_value "$V2_DATABASE" \
    "SELECT count(*) FROM email_service_flyway_schema_history WHERE version = '1' AND success;")" = "1" ] \
    || fail "V1-only startup did not record migration V1"
[ "$(db_value "$V2_DATABASE" \
    "SELECT count(*) FROM email_service_flyway_schema_history WHERE version = '2';")" = "0" ] \
    || fail "V1-only startup unexpectedly applied V2"

echo "6/14 Reject V1 data that violates the V2 retry bound"
db_command "$V2_DATABASE" -c "
    INSERT INTO email_queue (
        recipient,
        subject,
        html_content,
        email_type,
        status,
        priority,
        retry_count,
        max_retries,
        created_time,
        updated_time
    ) VALUES (
        'invalid@example.test',
        'Invalid retry state',
        '<p>invalid</p>',
        'TEST',
        'PENDING',
        5,
        2,
        1,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    );
" >/dev/null
v2_failure_log="$TEMP_DIR/v2-failure.log"
expect_startup_failure "$V2_DATABASE" "$v2_failure_log"
grep -Fq "chk_email_queue_retry_bounds" "$v2_failure_log" \
    || fail "V2 failure did not identify the retry-bound constraint"

echo "7/14 Preserve V1 history and source data after failed V2"
[ "$(db_value "$V2_DATABASE" \
    "SELECT count(*) FROM email_service_flyway_schema_history WHERE version = '1' AND success;")" = "1" ] \
    || fail "failed V2 damaged V1 history"
[ "$(db_value "$V2_DATABASE" \
    "SELECT count(*) FROM email_service_flyway_schema_history WHERE version = '2';")" = "0" ] \
    || fail "failed V2 left a successful history row"
[ "$(db_value "$V2_DATABASE" \
    "SELECT count(*) FROM email_queue WHERE retry_count = 2 AND max_retries = 1;")" = "1" ] \
    || fail "failed V2 changed source data"

echo "8/14 Forward-fix retry data, apply V2, and exercise the UniAuth template contract"
db_command "$V2_DATABASE" \
    -c "UPDATE email_queue SET retry_count = max_retries WHERE retry_count > max_retries;" \
    >/dev/null
v2_success_log="$TEMP_DIR/v2-success.log"
start_application "$V2_DATABASE" "$v2_success_log"
wait_for_application "$v2_success_log"
template_response="$(
    curl -fsS \
        -X POST \
        -H "Content-Type: application/json" \
        --data "$(
            jq -cn '{
                to: "flyway-template@example.test",
                subject: "Flyway template contract",
                templateName: "email/email-verify",
                variables: {
                    code: "135790",
                    verificationCode: "135790",
                    username: "flyway-template@example.test",
                    expiryMinutes: 10
                },
                emailType: "VERIFICATION"
            }'
        )" \
        "http://127.0.0.1:${SERVER_PORT}/api/email/template"
)"
template_queue_id="$(jq -er '.queueId' <<<"$template_response")"
[ "$(jq -er '.success' <<<"$template_response")" = "true" ] \
    || fail "migrated application rejected the UniAuth template contract"
[ "$(db_value "$V2_DATABASE" \
    "SELECT count(*) FROM email_queue
     WHERE id = $template_queue_id
       AND recipient = 'flyway-template@example.test'
       AND email_type = 'VERIFICATION'
       AND status = 'PENDING'
       AND position('135790' in html_content) > 0;")" = "1" ] \
    || fail "migrated application did not persist the rendered UniAuth template"
stop_application
[ "$(db_value "$V2_DATABASE" \
    "SELECT count(*) FROM email_service_flyway_schema_history WHERE version = '2' AND success;")" = "1" ] \
    || fail "forward-fixed database did not apply V2"
[ "$(db_value "$V2_DATABASE" "
    SELECT count(*)
    FROM pg_constraint
    WHERE conname = 'chk_email_queue_retry_bounds';
")" = "1" ] || fail "V2 retry-bound constraint is missing"

echo "9/14 Reject V1 logs that reference a missing queue row"
orphan_v1_log="$TEMP_DIR/orphan-v1.log"
start_application "$ORPHAN_DATABASE" "$orphan_v1_log" 1
wait_for_application "$orphan_v1_log"
stop_application
db_command "$ORPHAN_DATABASE" -c "
    INSERT INTO email_logs (
        queue_id,
        recipient,
        subject,
        status,
        sent_time,
        retry_count
    ) VALUES (
        999,
        'orphan@example.test',
        'Orphan queue reference',
        'FAILED',
        CURRENT_TIMESTAMP,
        0
    );
" >/dev/null
orphan_failure_log="$TEMP_DIR/orphan-failure.log"
expect_startup_failure "$ORPHAN_DATABASE" "$orphan_failure_log"
grep -Fq "fk_email_logs_queue" "$orphan_failure_log" \
    || fail "orphan-log V2 failure did not identify the foreign key"
[ "$(db_value "$ORPHAN_DATABASE" \
    "SELECT count(*) FROM email_service_flyway_schema_history WHERE version = '2';")" = "0" ] \
    || fail "orphan-log failure left a successful V2 history row"
[ "$(db_value "$ORPHAN_DATABASE" \
    "SELECT count(*) FROM email_logs WHERE queue_id = 999;")" = "1" ] \
    || fail "orphan-log failure changed source data"

echo "10/14 Forward-fix the orphan reference and apply V2 successfully"
db_command "$ORPHAN_DATABASE" \
    -c "UPDATE email_logs SET queue_id = NULL WHERE queue_id = 999;" \
    >/dev/null
orphan_success_log="$TEMP_DIR/orphan-success.log"
start_application "$ORPHAN_DATABASE" "$orphan_success_log"
wait_for_application "$orphan_success_log"
stop_application
[ "$(db_value "$ORPHAN_DATABASE" \
    "SELECT count(*) FROM email_service_flyway_schema_history WHERE version = '2' AND success;")" = "1" ] \
    || fail "forward-fixed orphan database did not apply V2"
[ "$(db_value "$ORPHAN_DATABASE" \
    "SELECT count(*) FROM email_logs WHERE queue_id IS NULL;")" = "1" ] \
    || fail "forward-fixed orphan log was not preserved"

echo "11/14 Reject checksum drift without changing data, then recover"
checksum_initial_log="$TEMP_DIR/checksum-initial.log"
start_application "$CHECKSUM_DATABASE" "$checksum_initial_log"
wait_for_application "$checksum_initial_log"
stop_application
db_command "$CHECKSUM_DATABASE" -c "
    INSERT INTO email_queue (
        recipient,
        subject,
        html_content,
        email_type,
        status,
        priority,
        retry_count,
        max_retries,
        created_time,
        updated_time
    ) VALUES (
        'checksum@example.test',
        'Checksum guard',
        '<p>preserve me</p>',
        'TEST',
        'PENDING',
        5,
        0,
        3,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    );
" >/dev/null
original_checksum="$(db_value "$CHECKSUM_DATABASE" "
    SELECT checksum
    FROM email_service_flyway_schema_history
    WHERE version = '1';
")"
drifted_checksum="$((original_checksum + 1))"
db_command "$CHECKSUM_DATABASE" -c "
    UPDATE email_service_flyway_schema_history
    SET checksum = $drifted_checksum
    WHERE version = '1';
" >/dev/null
checksum_failure_log="$TEMP_DIR/checksum-failure.log"
expect_startup_failure "$CHECKSUM_DATABASE" "$checksum_failure_log"
grep -Fq "Migration checksum mismatch" "$checksum_failure_log" \
    || fail "checksum drift failure did not report a migration checksum mismatch"
[ "$(db_value "$CHECKSUM_DATABASE" \
    "SELECT count(*) FROM email_queue WHERE recipient = 'checksum@example.test';")" = "1" ] \
    || fail "checksum validation failure changed migrated data"
[ "$(db_value "$CHECKSUM_DATABASE" \
    "SELECT count(*) FROM email_service_flyway_schema_history WHERE success;")" = "2" ] \
    || fail "checksum validation failure changed Flyway history"
[ "$(db_value "$CHECKSUM_DATABASE" "
    SELECT checksum
    FROM email_service_flyway_schema_history
    WHERE version = '1';
")" = "$drifted_checksum" ] \
    || fail "checksum validation failure rewrote the drifted history checksum"
db_command "$CHECKSUM_DATABASE" -c "
    UPDATE email_service_flyway_schema_history
    SET checksum = $original_checksum
    WHERE version = '1';
" >/dev/null
checksum_recovery_log="$TEMP_DIR/checksum-recovery.log"
start_application "$CHECKSUM_DATABASE" "$checksum_recovery_log"
wait_for_application "$checksum_recovery_log"
stop_application
[ "$(db_value "$CHECKSUM_DATABASE" \
    "SELECT count(*) FROM email_queue WHERE recipient = 'checksum@example.test';")" = "1" ] \
    || fail "checksum recovery lost migrated data"
[ "$(db_value "$CHECKSUM_DATABASE" "
    SELECT checksum
    FROM email_service_flyway_schema_history
    WHERE version = '1';
")" = "$original_checksum" ] \
    || fail "checksum recovery did not preserve the explicitly restored checksum"

echo "12/14 Reject a schema-owner override that weakens Flyway cleanup protection"
config_failure_log="$TEMP_DIR/config-failure.log"
expect_startup_failure \
    "$CONFIG_DATABASE" \
    "$config_failure_log" \
    "" \
    "SPRING_FLYWAY_CLEAN_DISABLED=false"
grep -Fq "SPRING_FLYWAY_CLEAN_DISABLED must be exactly true" "$config_failure_log" \
    || fail "unsafe Flyway override did not fail in the runtime guard"
[ "$(db_value "$CONFIG_DATABASE" \
    "SELECT to_regclass('public.email_service_flyway_schema_history') IS NULL;")" = "t" ] \
    || fail "unsafe Flyway override created migration history"
[ "$(db_value "$CONFIG_DATABASE" \
    "SELECT to_regclass('public.email_queue') IS NULL;")" = "t" ] \
    || fail "unsafe Flyway override created email tables"

echo "13/14 Reject a schema-owner override that ignores missing locations"
missing_location_failure_log="$TEMP_DIR/missing-location-failure.log"
expect_startup_failure \
    "$CONFIG_DATABASE" \
    "$missing_location_failure_log" \
    "" \
    "SPRING_FLYWAY_FAIL_ON_MISSING_LOCATIONS=false"
grep -Fq \
    "SPRING_FLYWAY_FAIL_ON_MISSING_LOCATIONS must be exactly true" \
    "$missing_location_failure_log" \
    || fail "missing-location policy override did not fail in the runtime guard"
[ "$(db_value "$CONFIG_DATABASE" \
    "SELECT to_regclass('public.email_service_flyway_schema_history') IS NULL;")" = "t" ] \
    || fail "missing-location policy override created migration history"

echo "14/14 Reject a schema-owner override that disables migration naming validation"
naming_failure_log="$TEMP_DIR/migration-naming-failure.log"
expect_startup_failure \
    "$CONFIG_DATABASE" \
    "$naming_failure_log" \
    "" \
    "SPRING_FLYWAY_VALIDATE_MIGRATION_NAMING=false"
grep -Fq \
    "SPRING_FLYWAY_VALIDATE_MIGRATION_NAMING must be exactly true" \
    "$naming_failure_log" \
    || fail "migration naming override did not fail in the runtime guard"
[ "$(db_value "$CONFIG_DATABASE" \
    "SELECT to_regclass('public.email_service_flyway_schema_history') IS NULL;")" = "t" ] \
    || fail "migration naming override created migration history"

echo "PASS: email service Flyway baseline guard"
