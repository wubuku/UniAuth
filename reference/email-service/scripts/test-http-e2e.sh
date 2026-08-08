#!/usr/bin/env bash

# Real process + HTTP + PostgreSQL contract test. SMTP delivery is disabled;
# the Java ApplicationContext E2E suite owns the GreenMail delivery boundary.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APPLICATION_JAR="${EMAIL_SERVICE_JAR_PATH:-$PROJECT_DIR/target/email-service-1.0.0.jar}"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/email-http-e2e.XXXXXX")"
RUN_ID="$(date +%s)-$$"
CONTAINER_NAME="email-http-e2e-${RUN_ID}"
DATABASE_NAME="email_service_http_test"
DATABASE_USER="email_test"
DATABASE_PASSWORD="email-http-${RUN_ID}"
DATABASE_PORT=""
SERVER_PORT=""
APP_PID=""
APP_LOG="$TEMP_DIR/application.log"
API_KEY="email-http-key-${RUN_ID}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
export NO_PROXY="${NO_PROXY:+${NO_PROXY},}localhost,127.0.0.1,::1"
export no_proxy="${no_proxy:+${no_proxy},}localhost,127.0.0.1,::1"

fail() {
    echo "FAIL: $1" >&2
    if [ -s "$APP_LOG" ]; then
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

for command_name in curl docker grep java jq mvn pg_isready psql; do
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

db_value() {
    local sql="$1"
    PGPASSWORD="$DATABASE_PASSWORD" psql \
        -X -qAt -v ON_ERROR_STOP=1 \
        -h 127.0.0.1 \
        -p "$DATABASE_PORT" \
        -U "$DATABASE_USER" \
        -d "$DATABASE_NAME" \
        -c "$sql"
}

request_status() {
    local method="$1"
    local path="$2"
    shift 2
    curl -sS -o /dev/null -w '%{http_code}' \
        -X "$method" \
        "$@" \
        "http://127.0.0.1:${SERVER_PORT}${path}"
}

start_application() {
    SERVER_PORT="$(free_port)"
    (
        export SPRING_PROFILES_ACTIVE=dev
        export EMAIL_SERVICE_BIND_ADDRESS=127.0.0.1
        export EMAIL_SERVICE_PORT="$SERVER_PORT"
        export EMAIL_SERVICE_API_KEY="$API_KEY"
        export EMAIL_POSTGRES_HOST=127.0.0.1
        export EMAIL_POSTGRES_PORT="$DATABASE_PORT"
        export EMAIL_POSTGRES_DATABASE="$DATABASE_NAME"
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
        export EMAIL_FROM_NAME="UniAuth Shell E2E"
        export EMAIL_QUEUE_EVENT_DRIVEN=false
        export EMAIL_RECOVERY_ENABLED=false
        export EMAIL_RATE_LIMIT_ENABLED=false
        export EMAIL_MAX_RETRY_ATTEMPTS=4
        exec java -jar "$APPLICATION_JAR"
    ) >>"$APP_LOG" 2>&1 &
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

if [ "${EMAIL_SERVICE_SKIP_BUILD:-false}" != "true" ]; then
    echo "HTTP E2E: packaging the email service"
    (cd "$PROJECT_DIR" && mvn -q -DskipTests package)
fi
[ -r "$APPLICATION_JAR" ] \
    || fail "email service application JAR is unavailable: $APPLICATION_JAR"

echo "HTTP E2E: starting disposable PostgreSQL"
docker run -d --rm \
    --name "$CONTAINER_NAME" \
    -e "POSTGRES_DB=$DATABASE_NAME" \
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
        -d "$DATABASE_NAME" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
if ! PGPASSWORD="$DATABASE_PASSWORD" pg_isready \
    -h 127.0.0.1 \
    -p "$DATABASE_PORT" \
    -U "$DATABASE_USER" \
    -d "$DATABASE_NAME" >/dev/null 2>&1; then
    fail "disposable PostgreSQL did not become ready"
fi

start_application
wait_for_application

echo "1/9 Verify Flyway-owned startup"
[ "$(db_value "SELECT count(*) FROM email_service_flyway_schema_history WHERE success;")" = "2" ] \
    || fail "Flyway did not record V1 and V2"

echo "2/9 Verify API-key enforcement"
[ "$(request_status GET /api/email/health)" = "401" ] \
    || fail "health endpoint accepted a missing API key"
[ "$(request_status GET /api/email/health -H "X-Email-Service-Key: wrong")" = "401" ] \
    || fail "health endpoint accepted an incorrect API key"
[ "$(request_status GET '/api;version=1/email;tenant=test/health')" = "401" ] \
    || fail "matrix parameters bypassed API-key enforcement"
[ "$(request_status GET '/api;version=1/email;tenant=test/health' \
    -H "X-Email-Service-Key: $API_KEY")" = "200" ] \
    || fail "matrix-parameter request did not reach the protected health endpoint"

echo "3/9 Verify health and template discovery contracts"
health="$(
    curl -fsS \
        -H "X-Email-Service-Key: $API_KEY" \
        "http://127.0.0.1:${SERVER_PORT}/api/email/health"
)"
[ "$(jq -er '.status' <<<"$health")" = "UP" ] \
    || fail "health endpoint did not return UP"
templates="$(
    curl -fsS \
        -H "X-Email-Service-Key: $API_KEY" \
        "http://127.0.0.1:${SERVER_PORT}/api/email/templates"
)"
jq -e 'index("email/email-verify") != null and index("email/password-reset") != null' \
    <<<"$templates" >/dev/null \
    || fail "required UniAuth templates were not advertised"

echo "4/9 Enqueue the UniAuth verification template over real HTTP"
payload="$(
    jq -cn '{
      to: "shell@example.test",
      subject: "Verify your email",
      templateName: "email/email-verify",
      variables: {
        code: "246810",
        verificationCode: "246810",
        username: "shell@example.test",
        expiryMinutes: 10
      },
      emailType: "VERIFICATION"
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
    || fail "template endpoint did not return success=true"
queue_id="$(jq -er '.queueId' <<<"$response")"

echo "5/9 Verify rendered content and configured queue policy in PostgreSQL"
[ "$(db_value "SELECT status FROM email_queue WHERE id = $queue_id;")" = "PENDING" ] \
    || fail "event-disabled request was not left pending"
[ "$(db_value "SELECT max_retries FROM email_queue WHERE id = $queue_id;")" = "4" ] \
    || fail "configured retry limit was not persisted"
[ "$(db_value "SELECT position('246810' in html_content) > 0 FROM email_queue WHERE id = $queue_id;")" = "t" ] \
    || fail "Thymeleaf output did not contain the verification code"
[ "$(db_value "SELECT count(*) FROM email_logs;")" = "0" ] \
    || fail "HTTP E2E unexpectedly attempted SMTP delivery"

echo "6/9 Verify queue detail omits rendered content and metadata"
queue_detail="$(
    curl -fsS \
        -H "X-Email-Service-Key: $API_KEY" \
        "http://127.0.0.1:${SERVER_PORT}/api/email/queue/${queue_id}"
)"
[ "$(jq -er '.id' <<<"$queue_detail")" = "$queue_id" ] \
    || fail "queue detail returned the wrong queue id"
[ "$(jq -er '.status' <<<"$queue_detail")" = "PENDING" ] \
    || fail "queue detail returned the wrong status"
jq -e 'has("htmlContent") | not' <<<"$queue_detail" >/dev/null \
    || fail "queue detail exposed rendered email content"
jq -e 'has("metadata") | not' <<<"$queue_detail" >/dev/null \
    || fail "queue detail exposed internal metadata"
if grep -Fq "246810" <<<"$queue_detail"; then
    fail "queue detail exposed the verification code"
fi

echo "7/9 Reject malformed and unsupported requests without persistence"
before_count="$(db_value "SELECT count(*) FROM email_queue;")"
bad_subject="$(
    jq -cn '{
      to: "shell@example.test",
      subject: "Allowed\r\nBcc: attacker@example.test",
      htmlContent: "<p>content</p>"
    }'
)"
[ "$(request_status POST /api/email/simple \
    -H "Content-Type: application/json" \
    -H "X-Email-Service-Key: $API_KEY" \
    --data "$bad_subject")" = "400" ] \
    || fail "header-injection subject was accepted"
bad_email_type="$(
    jq -cn '{
      to: "shell@example.test",
      subject: "Invalid email type",
      templateName: "email/email-verify",
      variables: {
        verificationCode: "000000",
        username: "shell@example.test",
        expiryMinutes: 10
      },
      emailType: "VERIFICATION\r\nX-Injected: true"
    }'
)"
[ "$(request_status POST /api/email/template \
    -H "Content-Type: application/json" \
    -H "X-Email-Service-Key: $API_KEY" \
    --data "$bad_email_type")" = "400" ] \
    || fail "header-injection emailType was accepted"
unknown_template="$(
    jq -cn '{
      to: "shell@example.test",
      subject: "Unknown",
      templateName: "email/unknown",
      variables: {},
      emailType: "VERIFICATION"
    }'
)"
[ "$(request_status POST /api/email/template \
    -H "Content-Type: application/json" \
    -H "X-Email-Service-Key: $API_KEY" \
    --data "$unknown_template")" = "400" ] \
    || fail "unsupported template was accepted"
[ "$(request_status GET /api/email/queue/not-a-number \
    -H "X-Email-Service-Key: $API_KEY")" = "400" ] \
    || fail "invalid queue id did not return 400"
[ "$(request_status GET /api/email/not-found \
    -H "X-Email-Service-Key: $API_KEY")" = "404" ] \
    || fail "unknown email API resource did not return 404"
[ "$(request_status PUT /api/email/health \
    -H "X-Email-Service-Key: $API_KEY")" = "405" ] \
    || fail "unsupported email API method did not return 405"
[ "$(request_status POST /api/email/simple \
    -H "Content-Type: text/plain" \
    -H "X-Email-Service-Key: $API_KEY" \
    --data "not-json")" = "415" ] \
    || fail "unsupported email API media type did not return 415"
[ "$(db_value "SELECT count(*) FROM email_queue;")" = "$before_count" ] \
    || fail "rejected requests created queue rows"

echo "8/9 Enforce bounded log pagination"
[ "$(request_status GET '/api/email/logs?page=1&size=101' \
    -H "X-Email-Service-Key: $API_KEY")" = "400" ] \
    || fail "oversized log page was accepted"

echo "9/9 Restart without replaying migrations or losing the queue"
stop_application
start_application
wait_for_application
[ "$(db_value "SELECT count(*) FROM email_service_flyway_schema_history WHERE success;")" = "2" ] \
    || fail "restart changed Flyway history"
[ "$(db_value "SELECT count(*) FROM email_queue WHERE id = $queue_id;")" = "1" ] \
    || fail "restart lost the queued email"

echo "PASS: email service HTTP/PostgreSQL E2E"
