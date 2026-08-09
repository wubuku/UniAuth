#!/usr/bin/env bash

# Real-process proof that UniAuth and the reference email service can own
# disjoint objects in the same PostgreSQL database and public schema.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EMAIL_PROJECT_DIR="$PROJECT_DIR/reference/email-service"
UNIAUTH_JAR="${UNIAUTH_JAR_PATH:-$PROJECT_DIR/target/uni-auth-1.0.0.jar}"
EMAIL_JAR="${EMAIL_SERVICE_JAR_PATH:-$EMAIL_PROJECT_DIR/target/email-service-1.0.0.jar}"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/email-shared-schema.XXXXXX")"
RUN_ID="$(date +%s)-$$"
CONTAINER_NAME="email-shared-schema-${RUN_ID}"
DATABASE_USER="shared_schema_test"
DATABASE_PASSWORD="shared-schema-${RUN_ID}"
ROOT_FIRST_DATABASE="shared_email_root_first_test"
EMAIL_FIRST_DATABASE="shared_email_email_first_test"
DATABASE_PORT=""
APP_PID=""
APP_LOG=""
PYTHON_BIN="${PYTHON_BIN:-python3}"
API_KEY="shared-schema-key-${RUN_ID}"
export NO_PROXY="${NO_PROXY:+${NO_PROXY},}localhost,127.0.0.1,::1"
export no_proxy="${no_proxy:+${no_proxy},}localhost,127.0.0.1,::1"

fail() {
    echo "FAIL: $1" >&2
    if [ -n "$APP_LOG" ] && [ -s "$APP_LOG" ]; then
        echo "Last application log lines:" >&2
        tail -100 "$APP_LOG" >&2
    fi
    exit 1
}

stop_application() {
    if [ -z "${APP_PID:-}" ]; then
        return
    fi
    kill -TERM "$APP_PID" >/dev/null 2>&1 || true
    wait "$APP_PID" >/dev/null 2>&1 || true
    APP_PID=""
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

for command_name in awk curl docker grep java mvn pg_isready psql seq tail; do
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

start_uniauth() {
    local database="$1"
    local server_port
    server_port="$(free_port)"
    APP_LOG="$TEMP_DIR/uniauth-${database}.log"
    (
        export SPRING_PROFILES_ACTIVE=test
        export POSTGRES_HOST=127.0.0.1
        export POSTGRES_PORT="$DATABASE_PORT"
        export POSTGRES_DATABASE="$database"
        export POSTGRES_USER="$DATABASE_USER"
        export POSTGRES_PASSWORD="$DATABASE_PASSWORD"
        export SERVER_PORT="$server_port"
        export JWT_RSA_KEY_FILE="$TEMP_DIR/${database}-signing-key.ser"
        export GOOGLE_CLIENT_ID=shared-schema-google
        export GOOGLE_CLIENT_SECRET=shared-schema-google-secret
        export GITHUB_CLIENT_ID=shared-schema-github
        export GITHUB_CLIENT_SECRET=shared-schema-github-secret
        export TWITTER_CLIENT_ID=shared-schema-x
        export TWITTER_CLIENT_SECRET=shared-schema-x-secret
        export APP_DEMO_DATA_ENABLED=false
        export APP_DEMO_DATA_DISPOSABLE=false
        export EMAIL_SERVICE_URL=http://127.0.0.1:1
        export EMAIL_SERVICE_API_KEY=
        export APP_FRONTEND_URL="http://127.0.0.1:${server_port}"
        export APP_WEB3_DOMAIN="127.0.0.1:${server_port}"
        exec java -jar "$UNIAUTH_JAR"
    ) >"$APP_LOG" 2>&1 &
    APP_PID=$!

    for _ in $(seq 1 150); do
        if curl -fsS "http://127.0.0.1:${server_port}/oauth2/jwks" \
                >/dev/null 2>&1; then
            return
        fi
        if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
            fail "UniAuth exited before becoming ready on $database"
        fi
        sleep 1
    done
    fail "UniAuth did not become ready on $database"
}

start_email_service() {
    local database="$1"
    local server_port
    server_port="$(free_port)"
    APP_LOG="$TEMP_DIR/email-${database}.log"
    (
        export SPRING_PROFILES_ACTIVE=dev
        export EMAIL_SERVICE_BIND_ADDRESS=127.0.0.1
        export EMAIL_SERVICE_PORT="$server_port"
        export EMAIL_SERVICE_API_KEY="$API_KEY"
        export EMAIL_DATABASE_LAYOUT=shared-uniauth
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
        exec java -jar "$EMAIL_JAR"
    ) >"$APP_LOG" 2>&1 &
    APP_PID=$!

    for _ in $(seq 1 120); do
        if curl -fsS \
                -H "X-Email-Service-Key: $API_KEY" \
                "http://127.0.0.1:${server_port}/api/email/health" \
                >/dev/null 2>&1; then
            return
        fi
        if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
            fail "email service exited before becoming ready on $database"
        fi
        sleep 1
    done
    fail "email service did not become ready on $database"
}

if [ "${EMAIL_SHARED_SCHEMA_SKIP_BUILD:-false}" != "true" ]; then
    echo "Shared-schema E2E: packaging UniAuth and the email service"
    (cd "$PROJECT_DIR" && mvn -q -DskipTests package)
    (cd "$EMAIL_PROJECT_DIR" && mvn -q -DskipTests package)
fi
[ -r "$UNIAUTH_JAR" ] || fail "UniAuth application JAR is unavailable"
[ -r "$EMAIL_JAR" ] || fail "email-service application JAR is unavailable"

echo "Shared-schema E2E: starting disposable PostgreSQL"
docker run -d --rm \
    --name "$CONTAINER_NAME" \
    -e POSTGRES_DB=postgres \
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
create_database "$ROOT_FIRST_DATABASE"
create_database "$EMAIL_FIRST_DATABASE"

echo "1/4 Start UniAuth first on an empty shared public schema"
start_uniauth "$ROOT_FIRST_DATABASE"
stop_application
[ "$(db_value "$ROOT_FIRST_DATABASE" "
    SELECT count(*)
    FROM uniauth_flyway_schema_history
    WHERE type = 'BASELINE' AND version = '0';
")" = "0" ] || fail "root-first UniAuth unexpectedly created a baseline marker"

echo "2/4 Start the email service second, then restart both schema owners"
start_email_service "$ROOT_FIRST_DATABASE"
stop_application
[ "$(db_value "$ROOT_FIRST_DATABASE" "
    SELECT count(*)
    FROM email_service_flyway_schema_history
    WHERE type = 'BASELINE' AND version = '0' AND success;
")" = "1" ] || fail "email service did not create the guarded baseline marker"
[ "$(db_value "$ROOT_FIRST_DATABASE" "
    SELECT count(*)
    FROM email_service_flyway_schema_history
    WHERE type = 'SQL' AND version IN ('1', '2', '3', '4', '5') AND success;
")" = "5" ] || fail "email service did not apply V1 through V5"
start_uniauth "$ROOT_FIRST_DATABASE"
stop_application
start_email_service "$ROOT_FIRST_DATABASE"
stop_application
[ "$(db_value "$ROOT_FIRST_DATABASE" "
    SELECT count(*)
    FROM uniauth_flyway_schema_history
    WHERE success;
")" = "8" ] || fail "root-first restart changed UniAuth Flyway history"
[ "$(db_value "$ROOT_FIRST_DATABASE" "
    SELECT count(*)
    FROM email_service_flyway_schema_history
    WHERE success;
")" = "6" ] || fail "root-first restart changed email-service Flyway history"

echo "3/4 Start the email service first on an empty shared public schema"
start_email_service "$EMAIL_FIRST_DATABASE"
stop_application
[ "$(db_value "$EMAIL_FIRST_DATABASE" "
    SELECT count(*)
    FROM email_service_flyway_schema_history
    WHERE type = 'BASELINE' AND version = '0';
")" = "0" ] || fail "email-first service unexpectedly created a baseline marker"

echo "4/4 Start UniAuth second, then restart both schema owners"
start_uniauth "$EMAIL_FIRST_DATABASE"
stop_application
[ "$(db_value "$EMAIL_FIRST_DATABASE" "
    SELECT count(*)
    FROM uniauth_flyway_schema_history
    WHERE type = 'BASELINE' AND version = '0' AND success;
")" = "1" ] || fail "UniAuth did not create the guarded baseline marker"
[ "$(db_value "$EMAIL_FIRST_DATABASE" "
    SELECT count(*)
    FROM uniauth_flyway_schema_history
    WHERE type = 'SQL'
      AND version IN ('1', '2', '3', '4', '5', '6', '7', '8')
      AND success;
")" = "8" ] || fail "UniAuth did not apply V1 through V8"
[ "$(db_value "$EMAIL_FIRST_DATABASE" "
    SELECT count(*)
    FROM information_schema.tables
    WHERE table_schema = 'public'
      AND table_name IN (
          'users',
          'token_families',
          'email_queue',
          'email_logs'
      );
")" = "4" ] || fail "shared public schema is missing managed tables"
[ "$(db_value "$EMAIL_FIRST_DATABASE" \
    "SELECT to_regclass('public.flyway_schema_history') IS NULL;")" = "t" ] \
    || fail "an unowned default Flyway history table was created"
start_email_service "$EMAIL_FIRST_DATABASE"
stop_application
start_uniauth "$EMAIL_FIRST_DATABASE"
stop_application
[ "$(db_value "$EMAIL_FIRST_DATABASE" "
    SELECT count(*)
    FROM email_service_flyway_schema_history
    WHERE success;
")" = "5" ] || fail "email-first restart changed email-service Flyway history"
[ "$(db_value "$EMAIL_FIRST_DATABASE" "
    SELECT count(*)
    FROM uniauth_flyway_schema_history
    WHERE success;
")" = "9" ] || fail "email-first restart changed UniAuth Flyway history"

echo "PASS: shared database/public schema process E2E"
