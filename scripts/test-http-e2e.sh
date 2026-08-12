#!/usr/bin/env bash

# Self-contained HTTP/PostgreSQL end-to-end regression harness.
# It starts disposable PostgreSQL containers, the real reference email service,
# and the real UniAuth start.sh process, then exercises authentication, Flyway,
# persistence, cookies, JWT, Web3, and the cross-service email contract.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/uniauth-http-e2e.XXXXXX")"
RUN_ID="$(date +%s)-$$"
CONTAINER_NAME="uniauth-http-e2e-${RUN_ID}"
EMAIL_SERVICE_CONTAINER_NAME="email-reference-http-e2e-${RUN_ID}"
DATABASE_NAME="uniauth_http_e2e_test"
DATABASE_USER="uniauth"
DATABASE_PASSWORD="uniauth-e2e-${RUN_ID}"
DATABASE_PORT=""
EMAIL_SERVICE_DATABASE_NAME="email_service_uniauth_e2e_test"
EMAIL_SERVICE_DATABASE_USER="email_service"
EMAIL_SERVICE_DATABASE_PASSWORD="email-service-http-${RUN_ID}"
EMAIL_SERVICE_DATABASE_PORT=""
APP_PID=""
EMAIL_SERVICE_PID=""
EMAIL_STUB_PID=""
APP_LOG="$TEMP_DIR/application.log"
EMAIL_SERVICE_LOG="$TEMP_DIR/reference-email-service.log"
EMAIL_STUB_LOG="$TEMP_DIR/email-service-stub.log"
EMAIL_STUB_CAPTURE_FILE="$TEMP_DIR/email-service-stub-capture.jsonl"
EMAIL_SERVICE_API_KEY="email-reference-e2e-${RUN_ID}"
EMAIL_SERVICE_PORT=""
EMAIL_STUB_PORT=""
COOKIE_JAR="$TEMP_DIR/cookies.txt"
BOOTSTRAP_ADMIN_COOKIE_JAR="$TEMP_DIR/bootstrap-admin-cookies.txt"
BOOTSTRAP_ADMIN_USERNAME="shell-admin-${RUN_ID}"
BOOTSTRAP_ADMIN_EMAIL="${BOOTSTRAP_ADMIN_USERNAME}@example.invalid"
BOOTSTRAP_ADMIN_INITIAL_PASSWORD="initial-admin-password-${RUN_ID}"
BOOTSTRAP_ADMIN_NEW_PASSWORD="strong-admin-password-${RUN_ID}"
INTROSPECTION_CLIENT_ID="resource-server-e2e"
INTROSPECTION_CLIENT_SECRET="introspection-e2e-${RUN_ID}-change-me-now"
JWT_RSA_KEY_FILE_VALUE="$TEMP_DIR/signing-key.ser"
JWT_KID_VALUE="key-1"
CSRF_HEADER_NAME=""
CSRF_TOKEN=""
LOGIN_RESPONSE=""
SERVER_PORT="${UNIAUTH_E2E_SERVER_PORT:-}"
export NO_PROXY="${NO_PROXY:+${NO_PROXY},}localhost,127.0.0.1,::1"
export no_proxy="${no_proxy:+${no_proxy},}localhost,127.0.0.1,::1"

fail() {
    echo "FAIL: $1" >&2
    if [ -s "$APP_LOG" ]; then
        echo "Last application log lines:" >&2
        tail -80 "$APP_LOG" >&2
    fi
    if [ -s "$EMAIL_SERVICE_LOG" ]; then
        echo "Last reference email service log lines:" >&2
        tail -60 "$EMAIL_SERVICE_LOG" >&2
    fi
    if [ -s "$EMAIL_STUB_LOG" ]; then
        echo "Last email stub log lines:" >&2
        tail -40 "$EMAIL_STUB_LOG" >&2
    fi
    exit 1
}

terminate_process_tree() {
    local process_id="$1"
    local child

    if ! kill -0 "$process_id" >/dev/null 2>&1; then
        return
    fi

    while IFS= read -r child; do
        if [ -n "$child" ]; then
            terminate_process_tree "$child"
        fi
    done < <(pgrep -P "$process_id" 2>/dev/null || true)

    kill -TERM "$process_id" >/dev/null 2>&1 || true
    for _ in $(seq 1 100); do
        if ! kill -0 "$process_id" >/dev/null 2>&1; then
            return
        fi
        sleep 0.1
    done
    kill -KILL "$process_id" >/dev/null 2>&1 || true
}

cleanup() {
    local exit_code=$?
    set +e

    if [ -n "$APP_PID" ]; then
        terminate_process_tree "$APP_PID"
        wait "$APP_PID" >/dev/null 2>&1 || true
    fi

    if [ -n "$EMAIL_SERVICE_PID" ]; then
        terminate_process_tree "$EMAIL_SERVICE_PID"
        wait "$EMAIL_SERVICE_PID" >/dev/null 2>&1 || true
    fi
    if [ -n "$EMAIL_STUB_PID" ]; then
        terminate_process_tree "$EMAIL_STUB_PID"
        wait "$EMAIL_STUB_PID" >/dev/null 2>&1 || true
    fi

    if docker ps -a --format '{{.Names}}' 2>/dev/null \
        | grep -Fxq "$CONTAINER_NAME"; then
        docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
    fi
    if docker ps -a --format '{{.Names}}' 2>/dev/null \
        | grep -Fxq "$EMAIL_SERVICE_CONTAINER_NAME"; then
        docker rm -f "$EMAIL_SERVICE_CONTAINER_NAME" >/dev/null 2>&1 || true
    fi

    rm -rf "$TEMP_DIR"
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

for command_name in curl docker grep jq mvn node pg_isready pgrep psql python3; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        fail "required command is unavailable: $command_name"
    fi
done

if ! docker info >/dev/null 2>&1; then
    fail "Docker is unavailable"
fi

allocate_port() {
    python3 - <<'PY'
import socket

with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

if [ -z "$SERVER_PORT" ]; then
    SERVER_PORT="$(allocate_port)"
fi
EMAIL_SERVICE_PORT="$(allocate_port)"
while [ "$EMAIL_SERVICE_PORT" = "$SERVER_PORT" ]; do
    EMAIL_SERVICE_PORT="$(allocate_port)"
done
EMAIL_STUB_PORT="$(allocate_port)"
while [ "$EMAIL_STUB_PORT" = "$SERVER_PORT" ] \
        || [ "$EMAIL_STUB_PORT" = "$EMAIL_SERVICE_PORT" ]; do
    EMAIL_STUB_PORT="$(allocate_port)"
done

BASE_URL="http://127.0.0.1:${SERVER_PORT}"
EMAIL_SERVICE_URL="http://127.0.0.1:${EMAIL_SERVICE_PORT}"
EMAIL_STUB_URL="http://127.0.0.1:${EMAIL_STUB_PORT}"
ACTIVE_EMAIL_SERVICE_URL="$EMAIL_SERVICE_URL"
EMAIL_DELIVERY_WORKER_ENABLED_VALUE=true

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

email_service_db_value() {
    local sql="$1"
    PGPASSWORD="$EMAIL_SERVICE_DATABASE_PASSWORD" psql \
        -X -qAt -v ON_ERROR_STOP=1 \
        -h 127.0.0.1 \
        -p "$EMAIL_SERVICE_DATABASE_PORT" \
        -U "$EMAIL_SERVICE_DATABASE_USER" \
        -d "$EMAIL_SERVICE_DATABASE_NAME" \
        -c "$sql"
}

wait_for_challenge_delivery_status() {
    local challenge_handle="$1"
    local expected_status="$2"
    local actual_status

    for _ in $(seq 1 150); do
        actual_status="$(db_value "
            SELECT delivery_status
            FROM email_verification_codes
            WHERE id = '$challenge_handle';
        ")"
        if [ "$actual_status" = "$expected_status" ]; then
            return
        fi
        if [ "$actual_status" = "FAILED" ] \
                && [ "$expected_status" != "FAILED" ]; then
            failure_reason="$(db_value "
                SELECT COALESCE(failure_reason, 'UNKNOWN')
                FROM email_verification_codes
                WHERE id = '$challenge_handle';
            ")"
            fail "challenge $challenge_handle failed before reaching $expected_status: $failure_reason"
        fi
        sleep 0.1
    done
    fail "challenge $challenge_handle did not reach $expected_status"
}

reference_email_code() {
    local challenge_handle="$1"
    email_service_db_value "
        SELECT (
            regexp_match(
                html_content,
                '<div class=\"code\"[^>]*>[[:space:]]*([0-9]{6})[[:space:]]*</div>'
            )
        )[1]
        FROM email_queue
        WHERE idempotency_key = 'email-challenge:$challenge_handle'
          AND status IN ('PENDING', 'PROCESSING', 'COMPLETED')
        LIMIT 1;
    "
}

stub_email_code() {
    local challenge_handle="$1"
    local idempotency_key="email-challenge:$challenge_handle"
    local code

    for _ in $(seq 1 100); do
        code="$(
            jq -rs \
                --arg idempotencyKey "$idempotency_key" \
                '[
                    .[]
                    | select(.idempotencyKey == $idempotencyKey)
                    | .variables.verificationCode
                 ] | last // empty' \
                "$EMAIL_STUB_CAPTURE_FILE" 2>/dev/null \
                || true
        )"
        if [[ "$code" =~ ^[0-9]{6}$ ]]; then
            printf '%s' "$code"
            return
        fi
        sleep 0.1
    done
    fail "email stub did not capture a rendered verification code"
}

expect_db_rejection() {
    local sql="$1"
    local description="$2"
    if db_value "$sql" >/dev/null 2>&1; then
        fail "$description"
    fi
}

post_json() {
    local path="$1"
    local payload="$2"
    curl -sS -X POST "${BASE_URL}${path}" \
        -H "Content-Type: application/json" \
        --data "$payload"
}

request_status() {
    local method="$1"
    local path="$2"
    shift 2
    curl -sS -o /dev/null -w '%{http_code}' \
        -X "$method" \
        "$@" \
        "${BASE_URL}${path}"
}

assert_cors_preflight() {
    local path="$1"
    local method="$2"
    local origin="$3"
    local expected_status="$4"
    local headers_file="$TEMP_DIR/cors-$(printf '%s' "$path" | tr '/?' '__').headers"
    local actual_status

    actual_status="$(
        curl -sS -o /dev/null -D "$headers_file" -w '%{http_code}' \
            -X OPTIONS "${BASE_URL}${path}" \
            -H "Origin: $origin" \
            -H "Access-Control-Request-Method: $method" \
            -H "Access-Control-Request-Headers: authorization, content-type"
    )"
    [ "$actual_status" = "$expected_status" ] \
        || fail "CORS preflight for $path returned $actual_status instead of $expected_status"

    if [ "$expected_status" = "200" ]; then
        grep -Fqi "Access-Control-Allow-Origin: $origin" "$headers_file" \
            || fail "CORS preflight for $path omitted the configured origin"
        grep -Fqi "Access-Control-Allow-Credentials: true" "$headers_file" \
            || fail "CORS preflight for $path omitted credential support"
        grep -Eqi "Access-Control-Allow-Methods:.*(^|[, ])${method}([, ]|$)" "$headers_file" \
            || fail "CORS preflight for $path omitted method $method"
    elif grep -qi '^Access-Control-Allow-Origin:' "$headers_file"; then
        fail "rejected CORS preflight for $path exposed an allow-origin header"
    fi
}

assert_auth_cookie_headers() {
    local headers_file="$1"
    local expected_access_max_age="$2"
    local expected_refresh_max_age="$3"
    local cookie_name
    local expected_max_age
    local header_line

    for cookie_name in accessToken refreshToken; do
        if [ "$cookie_name" = "accessToken" ]; then
            expected_max_age="$expected_access_max_age"
        else
            expected_max_age="$expected_refresh_max_age"
        fi
        header_line="$(
            tr -d '\r' <"$headers_file" \
                | grep -i "^set-cookie: ${cookie_name}=" \
                | tail -1 \
                || true
        )"
        [ -n "$header_line" ] || fail "missing ${cookie_name} Set-Cookie header"
        [[ "$header_line" == *"; Path=/"* ]] \
            || fail "${cookie_name} cookie did not use Path=/"
        [[ "$header_line" == *"; HttpOnly"* ]] \
            || fail "${cookie_name} cookie was not HttpOnly"
        [[ "$header_line" == *"; SameSite=Lax"* ]] \
            || fail "${cookie_name} cookie did not use SameSite=Lax"
        [[ "$header_line" == *"; Max-Age=${expected_max_age}"* ]] \
            || fail "${cookie_name} cookie used an unexpected Max-Age"
        [[ "$header_line" != *"; Secure"* ]] \
            || fail "${cookie_name} cookie was Secure in the local HTTP test profile"
    done
}

cookie_jar_value() {
    local cookie_name="$1"
    local cookie_jar="${2:-$COOKIE_JAR}"
    awk -F '\t' -v cookie_name="$cookie_name" '
        ($0 !~ /^#/ || $0 ~ /^#HttpOnly_/) && $6 == cookie_name {
            value = $7
        }
        END {
            if (value != "") {
                print value
            }
        }
    ' "$cookie_jar"
}

bootstrap_csrf() {
    local response

    response="$(
        curl -sS -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
            "${BASE_URL}/api/auth/csrf"
    )"
    CSRF_HEADER_NAME="$(jq -er '.headerName' <<<"$response")"
    CSRF_TOKEN="$(jq -er '.token' <<<"$response")"
    [ "$CSRF_HEADER_NAME" = "X-CSRF-Token" ] \
        || fail "CSRF bootstrap returned an unexpected header name"
    [ -n "$CSRF_TOKEN" ] || fail "CSRF bootstrap returned an empty token"
    [ -n "$(cookie_jar_value JSESSIONID)" ] \
        || fail "CSRF bootstrap did not establish a server session"
}

cookie_header_with_refresh() {
    local refresh_token_value="$1"
    local session_id

    session_id="$(cookie_jar_value JSESSIONID)"
    [ -n "$session_id" ] || fail "CSRF session cookie is unavailable"
    printf 'JSESSIONID=%s; refreshToken=%s' \
        "$session_id" \
        "$refresh_token_value"
}

introspect_token() {
    local token_value="$1"
    curl -sS -X POST "${BASE_URL}/oauth2/introspect" \
        -u "${INTROSPECTION_CLIENT_ID}:${INTROSPECTION_CLIENT_SECRET}" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        --data-urlencode "token=$token_value"
}

login_local_session() {
    local headers_file
    local -a curl_args

    headers_file="$(mktemp "$TEMP_DIR/login-headers.XXXXXX")"
    curl_args=(
        -sS
        -D "$headers_file"
        -b "$COOKIE_JAR"
        -c "$COOKIE_JAR"
        -X POST
        "${BASE_URL}/api/auth/login"
        -H "Content-Type: application/json"
    )
    if [ -n "$CSRF_HEADER_NAME" ] && [ -n "$CSRF_TOKEN" ]; then
        curl_args+=(-H "${CSRF_HEADER_NAME}: ${CSRF_TOKEN}")
    fi
    LOGIN_RESPONSE="$(
        curl "${curl_args[@]}" \
            --data "$(
                jq -cn \
                    --arg username "$local_username" \
                    --arg password "$local_password" \
                    '{username: $username, password: $password}'
            )"
    )"
    [ "$(jq -er '.authenticated' <<<"$LOGIN_RESPONSE")" = "true" ] \
        || fail "local login was not authenticated"
    if jq -e 'has("refreshToken")' <<<"$LOGIN_RESPONSE" >/dev/null; then
        fail "local login exposed the refresh token in JSON"
    fi
    access_token="$(jq -er '.accessToken' <<<"$LOGIN_RESPONSE")"
    refresh_token="$(cookie_jar_value refreshToken)"
    [ -n "$refresh_token" ] \
        || fail "local login did not store a refresh-token cookie"
    assert_auth_cookie_headers "$headers_file" 3600 604800
    bootstrap_csrf
}

start_email_service() {
    echo "HTTP E2E: packaging the reference email service"
    (
        cd "$PROJECT_DIR/reference/email-service"
        mvn -q -DskipTests package
    )

    docker run -d --rm \
        --name "$EMAIL_SERVICE_CONTAINER_NAME" \
        -e "POSTGRES_DB=$EMAIL_SERVICE_DATABASE_NAME" \
        -e "POSTGRES_USER=$EMAIL_SERVICE_DATABASE_USER" \
        -e "POSTGRES_PASSWORD=$EMAIL_SERVICE_DATABASE_PASSWORD" \
        -p 127.0.0.1::5432 \
        postgres:16.13 >/dev/null
    EMAIL_SERVICE_DATABASE_PORT="$(
        docker port "$EMAIL_SERVICE_CONTAINER_NAME" 5432/tcp \
            | awk -F: 'NR == 1 {print $NF}'
    )"

    for _ in $(seq 1 60); do
        if PGPASSWORD="$EMAIL_SERVICE_DATABASE_PASSWORD" pg_isready \
            -h 127.0.0.1 \
            -p "$EMAIL_SERVICE_DATABASE_PORT" \
            -U "$EMAIL_SERVICE_DATABASE_USER" \
            -d "$EMAIL_SERVICE_DATABASE_NAME" >/dev/null 2>&1; then
            break
        fi
        sleep 1
    done
    if ! PGPASSWORD="$EMAIL_SERVICE_DATABASE_PASSWORD" pg_isready \
        -h 127.0.0.1 \
        -p "$EMAIL_SERVICE_DATABASE_PORT" \
        -U "$EMAIL_SERVICE_DATABASE_USER" \
        -d "$EMAIL_SERVICE_DATABASE_NAME" >/dev/null 2>&1; then
        fail "reference email service PostgreSQL did not become ready"
    fi

    (
        export SPRING_PROFILES_ACTIVE=dev
        export EMAIL_SERVICE_BIND_ADDRESS=127.0.0.1
        export EMAIL_SERVICE_PORT
        export EMAIL_SERVICE_API_KEY
        export EMAIL_POSTGRES_HOST=127.0.0.1
        export EMAIL_POSTGRES_PORT="$EMAIL_SERVICE_DATABASE_PORT"
        export EMAIL_POSTGRES_DATABASE="$EMAIL_SERVICE_DATABASE_NAME"
        export EMAIL_POSTGRES_USER="$EMAIL_SERVICE_DATABASE_USER"
        export EMAIL_POSTGRES_PASSWORD="$EMAIL_SERVICE_DATABASE_PASSWORD"
        export SMTP_HOST=127.0.0.1
        export SMTP_PORT=1
        export SMTP_AUTH=false
        export SMTP_STARTTLS_ENABLE=false
        export SMTP_STARTTLS_REQUIRED=false
        export SMTP_SSL_ENABLE=false
        export SMTP_SSL_CHECK_SERVER_IDENTITY=true
        export EMAIL_FROM_ADDRESS=no-reply@example.test
        export EMAIL_FROM_NAME="UniAuth Reference E2E"
        export EMAIL_QUEUE_EVENT_DRIVEN=false
        export EMAIL_RECOVERY_ENABLED=false
        export EMAIL_RATE_LIMIT_ENABLED=false
        exec java -jar \
            "$PROJECT_DIR/reference/email-service/target/email-service-1.0.0.jar"
    ) >>"$EMAIL_SERVICE_LOG" 2>&1 &
    EMAIL_SERVICE_PID=$!

    for _ in $(seq 1 90); do
        if curl -fsS \
                -H "X-Email-Service-Key: $EMAIL_SERVICE_API_KEY" \
                "$EMAIL_SERVICE_URL/api/email/health" >/dev/null 2>&1; then
            return
        fi
        if ! kill -0 "$EMAIL_SERVICE_PID" >/dev/null 2>&1; then
            fail "reference email service exited before becoming ready"
        fi
        sleep 1
    done
    fail "reference email service did not become ready"
}

start_email_stub() {
    EMAIL_STUB_API_KEY="$EMAIL_SERVICE_API_KEY" \
        python3 "$PROJECT_DIR/scripts/email_service_stub.py" \
            --port "$EMAIL_STUB_PORT" \
            --capture-file "$EMAIL_STUB_CAPTURE_FILE" \
            >"$EMAIL_STUB_LOG" 2>&1 &
    EMAIL_STUB_PID=$!

    for _ in $(seq 1 50); do
        if curl -fsS \
                -H "X-Email-Service-Key: $EMAIL_SERVICE_API_KEY" \
                "$EMAIL_STUB_URL/api/email/health" >/dev/null 2>&1; then
            return
        fi
        if ! kill -0 "$EMAIL_STUB_PID" >/dev/null 2>&1; then
            fail "email service stub exited before becoming ready"
        fi
        sleep 0.1
    done
    fail "email service stub did not become ready"
}

start_application() {
    (
        export SPRING_PROFILES_ACTIVE=test
        export POSTGRES_HOST=127.0.0.1
        export POSTGRES_PORT="$DATABASE_PORT"
        export POSTGRES_DATABASE="$DATABASE_NAME"
        export POSTGRES_USER="$DATABASE_USER"
        export POSTGRES_PASSWORD="$DATABASE_PASSWORD"
        export SERVER_PORT
        export JWT_RSA_KEY_FILE="$JWT_RSA_KEY_FILE_VALUE"
        export JWT_KID="$JWT_KID_VALUE"
        export GOOGLE_CLIENT_ID=e2e-google
        export GOOGLE_CLIENT_SECRET=e2e-google-secret
        export GITHUB_CLIENT_ID=e2e-github
        export GITHUB_CLIENT_SECRET=e2e-github-secret
        export TWITTER_CLIENT_ID=e2e-x
        export TWITTER_CLIENT_SECRET=e2e-x-secret
        export APP_DEMO_DATA_ENABLED=false
        export APP_DEMO_DATA_DISPOSABLE=false
        export APP_BOOTSTRAP_ADMIN_ENABLED=true
        export APP_BOOTSTRAP_ADMIN_USERNAME="$BOOTSTRAP_ADMIN_USERNAME"
        export APP_BOOTSTRAP_ADMIN_EMAIL="$BOOTSTRAP_ADMIN_EMAIL"
        export APP_BOOTSTRAP_ADMIN_PASSWORD="$BOOTSTRAP_ADMIN_INITIAL_PASSWORD"
        export APP_BOOTSTRAP_ADMIN_DISPLAY_NAME="Shell E2E Administrator"
        export EMAIL_SERVICE_URL="$ACTIVE_EMAIL_SERVICE_URL"
        export EMAIL_SERVICE_API_KEY
        export APP_EMAIL_SERVICE_URL="$ACTIVE_EMAIL_SERVICE_URL"
        export APP_EMAIL_SERVICE_API_KEY="$EMAIL_SERVICE_API_KEY"
        export EMAIL_DELIVERY_WORKER_ENABLED="$EMAIL_DELIVERY_WORKER_ENABLED_VALUE"
        export APP_EMAIL_DELIVERY_WORKER_ENABLED="$EMAIL_DELIVERY_WORKER_ENABLED_VALUE"
        export EMAIL_DELIVERY_WORKER_DELAY_MS=100
        export APP_EMAIL_DELIVERY_WORKER_DELAY_MS=100
        export EMAIL_DELIVERY_MAX_ATTEMPTS=3
        export APP_EMAIL_DELIVERY_MAX_ATTEMPTS=3
        export EMAIL_DELIVERY_BASE_RETRY_SECONDS=1
        export APP_EMAIL_DELIVERY_BASE_RETRY_SECONDS=1
        export EMAIL_DELIVERY_PROCESSING_TIMEOUT_SECONDS=1
        export APP_EMAIL_DELIVERY_PROCESSING_TIMEOUT_SECONDS=1
        export EMAIL_DELIVERY_DEADLINE_SECONDS=120
        export APP_EMAIL_DELIVERY_DEADLINE_SECONDS=120
        export APP_FRONTEND_URL="$BASE_URL"
        export APP_WEB3_DOMAIN="127.0.0.1:${SERVER_PORT}"
        export INTROSPECTION_CLIENT_ID
        export INTROSPECTION_CLIENT_SECRET
        exec "$PROJECT_DIR/start.sh"
    ) >>"$APP_LOG" 2>&1 &
    APP_PID=$!
}

wait_for_application() {
    local consecutive_ready=0

    for _ in $(seq 1 150); do
        if curl -fsS "${BASE_URL}/oauth2/jwks" >/dev/null 2>&1; then
            consecutive_ready=$((consecutive_ready + 1))
            if [ "$consecutive_ready" -ge 3 ]; then
                return
            fi
            sleep 0.2
            continue
        fi
        consecutive_ready=0
        if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
            fail "application process exited before becoming ready"
        fi
        sleep 1
    done
    fail "application did not become ready"
}

stop_application() {
    if [ -z "$APP_PID" ]; then
        return
    fi
    terminate_process_tree "$APP_PID"
    wait "$APP_PID" >/dev/null 2>&1 || true
    APP_PID=""
}

create_wallet() {
    (
        cd "$PROJECT_DIR/frontend"
        node --input-type=module <<'NODE'
import { Wallet } from 'ethers';

const wallet = Wallet.createRandom();
process.stdout.write(JSON.stringify({
  address: wallet.address.toLowerCase(),
  privateKey: wallet.privateKey
}));
NODE
    )
}

signed_challenge() {
    local wallet_json="$1"
    local address
    local private_key
    local nonce_response
    local nonce_file

    address="$(jq -er '.address' <<<"$wallet_json")"
    private_key="$(jq -er '.privateKey' <<<"$wallet_json")"
    nonce_response="$(curl -sS "${BASE_URL}/api/auth/web3/nonce/${address}")"
    nonce_file="$(mktemp "$TEMP_DIR/web3-nonce.XXXXXX")"
    printf '%s' "$nonce_response" > "$nonce_file"

    (
        cd "$PROJECT_DIR/frontend"
        PRIVATE_KEY="$private_key" NONCE_FILE="$nonce_file" \
            node --input-type=module <<'NODE'
import fs from 'node:fs';
import { Wallet } from 'ethers';

const wallet = new Wallet(process.env.PRIVATE_KEY);
const challenge = JSON.parse(fs.readFileSync(process.env.NONCE_FILE, 'utf8'));
const signature = await wallet.signMessage(challenge.message);

process.stdout.write(JSON.stringify({
  walletAddress: wallet.address.toLowerCase(),
  message: challenge.message,
  signature,
  challengeHandle: challenge.challengeHandle,
  nonce: challenge.nonce,
  chainId: challenge.chainId
}));
NODE
    )
}

resign_web3_challenge() {
    local wallet_json="$1"
    local message="$2"
    local nonce="$3"
    local challenge_handle="$4"
    local private_key

    private_key="$(jq -er '.privateKey' <<<"$wallet_json")"
    (
        cd "$PROJECT_DIR/frontend"
        PRIVATE_KEY="$private_key" SIWE_MESSAGE="$message" SIWE_NONCE="$nonce" \
            CHALLENGE_HANDLE="$challenge_handle" \
            node --input-type=module <<'NODE'
import { Wallet } from 'ethers';

const wallet = new Wallet(process.env.PRIVATE_KEY);
const signature = await wallet.signMessage(process.env.SIWE_MESSAGE);
process.stdout.write(JSON.stringify({
  walletAddress: wallet.address.toLowerCase(),
  message: process.env.SIWE_MESSAGE,
  signature,
  challengeHandle: process.env.CHALLENGE_HANDLE,
  nonce: process.env.SIWE_NONCE,
  chainId: 1
}));
NODE
    )
}

echo "HTTP E2E: starting disposable PostgreSQL"
docker run -d --rm \
    --name "$CONTAINER_NAME" \
    -e "POSTGRES_DB=$DATABASE_NAME" \
    -e "POSTGRES_USER=$DATABASE_USER" \
    -e "POSTGRES_PASSWORD=$DATABASE_PASSWORD" \
    -p 127.0.0.1::5432 \
    postgres:16.13 >/dev/null

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

echo "HTTP E2E: starting the reference email REST service"
start_email_service

echo "HTTP E2E: starting the real application through start.sh"
start_application
wait_for_application

echo "1/17 Verify Flyway-owned PostgreSQL startup"
[ "$(db_value "
    SELECT count(*)
    FROM uniauth_flyway_schema_history
    WHERE version = '1' AND type = 'SQL' AND success = true;
")" = "1" ] || fail "Flyway V1 was not recorded as a successful SQL migration"
[ "$(db_value "
    SELECT count(*)
    FROM uniauth_flyway_schema_history
    WHERE version = '2' AND type = 'SQL' AND success = true;
")" = "1" ] || fail "Flyway V2 was not recorded as a successful SQL migration"
[ "$(db_value "
    SELECT count(*)
    FROM uniauth_flyway_schema_history
    WHERE version = '3' AND type = 'SQL' AND success = true;
")" = "1" ] || fail "Flyway V3 was not recorded as a successful SQL migration"
[ "$(db_value "
    SELECT count(*)
    FROM uniauth_flyway_schema_history
    WHERE version = '4' AND type = 'SQL' AND success = true;
")" = "1" ] || fail "Flyway V4 was not recorded as a successful SQL migration"
[ "$(db_value "
    SELECT count(*)
    FROM uniauth_flyway_schema_history
    WHERE version = '5' AND type = 'SQL' AND success = true;
")" = "1" ] || fail "Flyway V5 was not recorded as a successful SQL migration"
[ "$(db_value "
    SELECT count(*)
    FROM uniauth_flyway_schema_history
    WHERE version = '6' AND type = 'SQL' AND success = true;
")" = "1" ] || fail "Flyway V6 was not recorded as a successful SQL migration"
[ "$(db_value "
    SELECT count(*)
    FROM uniauth_flyway_schema_history
    WHERE version = '7' AND type = 'SQL' AND success = true;
")" = "1" ] || fail "Flyway V7 was not recorded as a successful SQL migration"
[ "$(db_value "
    SELECT count(*)
    FROM uniauth_flyway_schema_history
    WHERE version = '8' AND type = 'SQL' AND success = true;
")" = "1" ] || fail "Flyway V8 was not recorded as a successful SQL migration"
[ "$(db_value "
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
        'spring_session_attributes',
        'email_delivery_outbox',
        'auth_rate_limits',
        'security_events',
        'token_families',
        'oauth2_binding_intents',
        'web3_challenge_counters'
      ]);
")" = "14" ] || fail "Flyway did not create all fourteen managed tables"
[ "$(db_value "SELECT to_regclass('public.flyway_schema_history') IS NULL;")" = "t" ] \
    || fail "the default Flyway history table was unexpectedly created"
[ "$(db_value "
    SELECT data_type
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'user_login_methods'
      AND column_name = 'linked_at';
")" = "timestamp with time zone" ] \
    || fail "Flyway V2 did not migrate linked_at to timestamp with time zone"
[ "$(db_value "SELECT to_regclass('public.uk_login_methods_one_primary');")" \
    = "uk_login_methods_one_primary" ] \
    || fail "Flyway V2 did not create the primary login-method unique index"
[ "$(db_value "
    SELECT data_type || ':' || is_nullable
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'web3_nonces'
      AND column_name = 'message';
")" = "text:NO" ] || fail "Flyway V5 did not require the Web3 SIWE message"
[ "$(db_value "
    SELECT data_type || ':' || is_nullable || ':' || column_default
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'users'
      AND column_name = 'login_methods_revision';
")" = "bigint:NO:0" ] \
    || fail "Flyway V3 did not create the login-method revision column"
[ "$(db_value "
    SELECT count(*)
    FROM pg_constraint
    WHERE conname = 'ck_users_login_methods_revision_nonnegative'
      AND conrelid = 'public.users'::regclass;
")" = "1" ] \
    || fail "Flyway V3 did not create the nonnegative revision check"
[ "$(db_value "
    SELECT is_nullable || ':' || column_default
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'email_verification_codes'
      AND column_name = 'retry_count';
")" = "NO:0" ] \
    || fail "Flyway V4 did not align email retry state"
[ "$(db_value "
    SELECT count(*)
    FROM pg_constraint
    WHERE conname IN (
        'ck_email_verification_retry_count_nonnegative',
        'ck_token_blacklist_token_type'
    );
")" = "2" ] \
    || fail "Flyway V4 did not create the entity state checks"
[ "$(db_value "
    SELECT count(*)
    FROM pg_indexes
    WHERE schemaname = 'public'
      AND indexname IN (
        'uk_email_challenge_one_active',
        'idx_email_challenge_handle_lookup',
        'idx_email_challenge_delivery',
        'idx_email_delivery_outbox_pending',
        'idx_auth_rate_limits_expires_at',
        'idx_security_events_subject_created'
    );
")" = "6" ] \
    || fail "Flyway V6 did not create the durable email and security indexes"
[ "$(db_value "
    SELECT count(*)
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'email_verification_codes'
      AND column_name IN ('verification_code', 'metadata', 'is_used');
")" = "0" ] \
    || fail "Flyway V6 retained plaintext challenge credential columns"
[ "$(db_value "
    SELECT data_type || ':' || is_nullable || ':' || column_default
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'users'
      AND column_name = 'token_security_version';
")" = "bigint:NO:0" ] \
    || fail "Flyway V7 did not create the user token security version"
[ "$(db_value "
    SELECT count(*)
    FROM pg_constraint
    WHERE conrelid = 'public.token_families'::regclass
      AND conname IN (
        'token_families_pkey',
        'fk_token_families_user',
        'ck_token_families_id_uuid',
        'ck_token_families_security_version_nonnegative',
        'ck_token_families_generation_nonnegative',
        'ck_token_families_expiry',
        'ck_token_families_revoke_shape'
      );
")" = "7" ] \
    || fail "Flyway V7 did not create the token-family constraints"
[ "$(db_value "
    SELECT count(*)
    FROM pg_indexes
    WHERE schemaname = 'public'
      AND indexname IN (
        'idx_token_families_user_active',
        'idx_token_families_expires_at'
      );
")" = "2" ] \
    || fail "Flyway V7 did not create the token-family indexes"
[ "$(db_value "
    SELECT count(*)
    FROM pg_indexes
    WHERE schemaname = 'public'
      AND indexname IN (
        'uk_web3_nonces_challenge_handle',
        'idx_oauth2_binding_intents_expiry',
        'idx_oauth2_binding_intents_user_active'
      );
")" = "3" ] \
    || fail "Flyway V8 did not create the OAuth/Web3 contract indexes"
[ "$(db_value "
    SELECT count(*)
    FROM pg_indexes
    WHERE schemaname = 'public'
      AND indexname IN (
        'idx_users_email',
        'idx_users_username',
        'idx_web3_nonces_wallet_address',
        'idx_jti',
        'idx_token_blacklist_jti',
        'idx_expires_at'
    );
")" = "0" ] \
    || fail "Flyway V4 left redundant indexes in place"

expect_db_rejection "
    BEGIN;
    INSERT INTO users (
        id, username, email, email_verified, email_identity_type
    )
    VALUES (
        '00000000-0000-0000-0000-000000000101',
        'shell-primary-guard',
        'shell-primary-guard@example.invalid',
        false,
        'UNVERIFIED_CONTACT'
    );
    INSERT INTO user_login_methods (
        id, user_id, auth_provider, local_username, is_primary, is_verified
    ) VALUES (
        '00000000-0000-0000-0000-000000000102',
        '00000000-0000-0000-0000-000000000101',
        'LOCAL',
        'shell-primary-guard',
        true,
        false
    );
    INSERT INTO user_login_methods (
        id, user_id, auth_provider, provider_user_id, is_primary, is_verified
    ) VALUES (
        '00000000-0000-0000-0000-000000000103',
        '00000000-0000-0000-0000-000000000101',
        'GITHUB',
        'shell-primary-guard',
        true,
        true
    );
    COMMIT;
" "database accepted two primary login methods for one user"

expect_db_rejection "
    BEGIN;
    INSERT INTO users (
        id, username, email, email_verified, email_identity_type
    )
    VALUES (
        '00000000-0000-0000-0000-000000000111',
        'shell-shape-guard',
        'shell-shape-guard@example.invalid',
        false,
        'UNVERIFIED_CONTACT'
    );
    INSERT INTO user_login_methods (
        id, user_id, auth_provider, is_primary, is_verified
    ) VALUES (
        '00000000-0000-0000-0000-000000000112',
        '00000000-0000-0000-0000-000000000111',
        'GOOGLE',
        true,
        true
    );
    COMMIT;
" "database accepted an invalid provider login-method shape"

echo "2/17 Verify one CORS policy across every security filter chain"
for cors_case in \
        "/api/auth/login POST" \
        "/oauth2/jwks GET" \
        "/api/user GET" \
        "/oauth2/authorization/github GET"; do
    read -r cors_path cors_method <<<"$cors_case"
    assert_cors_preflight "$cors_path" "$cors_method" "http://localhost:5173" "200"
    assert_cors_preflight "$cors_path" "$cors_method" "https://evil.example" "403"
done

echo "3/17 Verify fail-closed HTTP security boundaries"
[ "$(request_status GET /api/user)" = "401" ] \
    || fail "anonymous current-user request did not return 401"
[ "$(request_status GET /api/auth/check-user)" = "403" ] \
    || fail "removed auth route was not denied"
[ "$(request_status POST /api/auth/not-allowlisted)" = "403" ] \
    || fail "unknown auth route was not denied"
[ "$(request_status GET /api/auth/web3/nonce/not-a-wallet)" = "400" ] \
    || fail "invalid Web3 address did not return 400"

jwks_response="$(curl -sS "${BASE_URL}/oauth2/jwks")"
[ "$(jq -er '.keys[0].kty' <<<"$jwks_response")" = "RSA" ] \
    || fail "JWKS did not expose an RSA key"
[ "$(jq -er '.keys[0].alg' <<<"$jwks_response")" = "RS256" ] \
    || fail "JWKS did not expose RS256"

echo "4/17 Register and authenticate a local account"
bootstrap_admin_user_id="$(db_value "
    SELECT u.id
    FROM users u
    JOIN user_login_methods m ON m.user_id = u.id
    WHERE u.username = '$BOOTSTRAP_ADMIN_USERNAME'
      AND u.email = '$BOOTSTRAP_ADMIN_EMAIL'
      AND u.enabled = true
      AND m.auth_provider = 'LOCAL'
      AND m.local_username = '$BOOTSTRAP_ADMIN_USERNAME'
      AND m.local_password_hash IS NOT NULL;
")"
[ -n "$bootstrap_admin_user_id" ] \
    || fail "bootstrap administrator was not created with local credentials"
[ "$(db_value "
    SELECT count(*)
    FROM user_authorities
    WHERE user_id = '$bootstrap_admin_user_id'
      AND authority IN ('ROLE_USER', 'ROLE_ADMIN');
")" = "2" ] || fail "bootstrap administrator did not receive user and admin roles"

bootstrap_wrong_login_status="$(
    curl -sS -o /dev/null -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/login" \
        -H "Content-Type: application/json" \
        --data "$(
            jq -cn \
                --arg username "$BOOTSTRAP_ADMIN_USERNAME" \
                '{username: $username, password: "wrong-password"}'
        )"
)"
[ "$bootstrap_wrong_login_status" = "401" ] \
    || fail "bootstrap administrator accepted a wrong password"

bootstrap_login_headers="$TEMP_DIR/bootstrap-admin-login-headers.txt"
bootstrap_login_response="$(
    curl -sS -D "$bootstrap_login_headers" \
        -b "$BOOTSTRAP_ADMIN_COOKIE_JAR" \
        -c "$BOOTSTRAP_ADMIN_COOKIE_JAR" \
        -X POST "${BASE_URL}/api/auth/login" \
        -H "Content-Type: application/json" \
        --data "$(
            jq -cn \
                --arg username "$BOOTSTRAP_ADMIN_USERNAME" \
                --arg password "$BOOTSTRAP_ADMIN_INITIAL_PASSWORD" \
                '{username: $username, password: $password}'
        )"
)"
[ "$(jq -er '.authenticated' <<<"$bootstrap_login_response")" = "true" ] \
    || fail "bootstrap administrator could not log in by username"
[ "$(jq -er '.user.authorities | index("ROLE_ADMIN") != null' \
    <<<"$bootstrap_login_response")" = "true" ] \
    || fail "bootstrap administrator login response omitted ROLE_ADMIN"
bootstrap_admin_access_token="$(
    jq -er '.accessToken' <<<"$bootstrap_login_response"
)"
bootstrap_admin_refresh_token="$(
    cookie_jar_value refreshToken "$BOOTSTRAP_ADMIN_COOKIE_JAR"
)"
[ -n "$bootstrap_admin_refresh_token" ] \
    || fail "bootstrap administrator login did not set a refresh cookie"
assert_auth_cookie_headers "$bootstrap_login_headers" 3600 604800

bootstrap_current_user="$(
    curl -sS \
        -H "Authorization: Bearer $bootstrap_admin_access_token" \
        "${BASE_URL}/api/user"
)"
[ "$(jq -er '.userName' <<<"$bootstrap_current_user")" \
    = "$BOOTSTRAP_ADMIN_USERNAME" ] \
    || fail "bootstrap administrator current-user name changed"
[ "$(jq -er '.hasLocalPassword' <<<"$bootstrap_current_user")" = "true" ] \
    || fail "bootstrap administrator was not reported as password-capable"

bootstrap_admin_csrf_response="$(
    curl -sS \
        -b "$BOOTSTRAP_ADMIN_COOKIE_JAR" \
        -c "$BOOTSTRAP_ADMIN_COOKIE_JAR" \
        "${BASE_URL}/api/auth/csrf"
)"
bootstrap_admin_csrf_header="$(
    jq -er '.headerName' <<<"$bootstrap_admin_csrf_response"
)"
bootstrap_admin_csrf_token="$(
    jq -er '.token' <<<"$bootstrap_admin_csrf_response"
)"
bootstrap_change_headers="$TEMP_DIR/bootstrap-admin-change-headers.txt"
bootstrap_change_status="$(
    curl -sS -o "$TEMP_DIR/bootstrap-admin-change.json" \
        -D "$bootstrap_change_headers" \
        -w '%{http_code}' \
        -b "$BOOTSTRAP_ADMIN_COOKIE_JAR" \
        -c "$BOOTSTRAP_ADMIN_COOKIE_JAR" \
        -X PUT "${BASE_URL}/api/user/password" \
        -H "${bootstrap_admin_csrf_header}: ${bootstrap_admin_csrf_token}" \
        -H "Content-Type: application/json" \
        --data "$(
            jq -cn \
                --arg currentPassword "$BOOTSTRAP_ADMIN_INITIAL_PASSWORD" \
                --arg newPassword "$BOOTSTRAP_ADMIN_NEW_PASSWORD" \
                '{
                  currentPassword: $currentPassword,
                  newPassword: $newPassword,
                  newPasswordConfirm: $newPassword
                }'
        )"
)"
[ "$bootstrap_change_status" = "200" ] \
    || fail "bootstrap administrator could not change its password"
[ "$(jq -er '.success' "$TEMP_DIR/bootstrap-admin-change.json")" = "true" ] \
    || fail "bootstrap administrator password change did not return success"
assert_auth_cookie_headers "$bootstrap_change_headers" 0 0
grep -qi 'set-cookie: JSESSIONID=.*Max-Age=0' "$bootstrap_change_headers" \
    || fail "password change did not clear the session cookie"

[ "$(request_status GET /api/user \
    -H "Authorization: Bearer $bootstrap_admin_access_token")" = "401" ] \
    || fail "password change left the old administrator access token active"
[ "$(jq -er '.active' <<<"$(introspect_token \
    "$bootstrap_admin_access_token")")" = "false" ] \
    || fail "introspection left the old administrator access token active"

bootstrap_admin_csrf_response="$(
    curl -sS \
        -b "$BOOTSTRAP_ADMIN_COOKIE_JAR" \
        -c "$BOOTSTRAP_ADMIN_COOKIE_JAR" \
        "${BASE_URL}/api/auth/csrf"
)"
bootstrap_admin_csrf_header="$(
    jq -er '.headerName' <<<"$bootstrap_admin_csrf_response"
)"
bootstrap_admin_csrf_token="$(
    jq -er '.token' <<<"$bootstrap_admin_csrf_response"
)"
bootstrap_admin_session_id="$(
    cookie_jar_value JSESSIONID "$BOOTSTRAP_ADMIN_COOKIE_JAR"
)"
bootstrap_refresh_status="$(
    curl -sS -o /dev/null -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/refresh" \
        -H "${bootstrap_admin_csrf_header}: ${bootstrap_admin_csrf_token}" \
        -H "Cookie: JSESSIONID=${bootstrap_admin_session_id}; refreshToken=${bootstrap_admin_refresh_token}"
)"
[ "$bootstrap_refresh_status" = "401" ] \
    || fail "password change left the old administrator refresh token active"

bootstrap_old_password_status="$(
    curl -sS -o /dev/null -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/login" \
        -H "Content-Type: application/json" \
        --data "$(
            jq -cn \
                --arg username "$BOOTSTRAP_ADMIN_USERNAME" \
                --arg password "$BOOTSTRAP_ADMIN_INITIAL_PASSWORD" \
                '{username: $username, password: $password}'
        )"
)"
[ "$bootstrap_old_password_status" = "401" ] \
    || fail "bootstrap administrator initial password still worked after change"
bootstrap_new_password_status="$(
    curl -sS -o /dev/null -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/login" \
        -H "Content-Type: application/json" \
        --data "$(
            jq -cn \
                --arg username "$BOOTSTRAP_ADMIN_USERNAME" \
                --arg password "$BOOTSTRAP_ADMIN_NEW_PASSWORD" \
                '{username: $username, password: $password}'
        )"
)"
[ "$bootstrap_new_password_status" = "200" ] \
    || fail "bootstrap administrator new password did not work"

local_username="shell-user-${RUN_ID}"
local_email="${local_username}@example.invalid"
local_password="initial-password-${RUN_ID}"
local_new_password="updated-password-${RUN_ID}"

registration_preview_payload="$(
    jq -cn \
        --arg username "$local_username" \
        --arg email "$local_email" \
        --arg password "$local_password" \
        '{
          username: $username,
          email: $email,
          password: $password,
          displayName: "Shell E2E User"
        }'
)"
registration_preview="$(
    post_json /api/auth/register "$registration_preview_payload"
)"
[ "$(jq -er '.requireEmailVerification' <<<"$registration_preview")" = "true" ] \
    || fail "local registration preview did not require email verification"
[ "$(db_value "
    SELECT count(*)
    FROM users
    WHERE username = '$local_username' OR email = '$local_email';
")" = "0" ] || fail "registration preview persisted an unverified user"

registration_send_response="$(
    post_json \
        /api/auth/send-verification-code \
        "$(jq -cn --arg email "$local_email" \
            '{email: $email, purpose: "REGISTRATION"}')"
)"
registration_handle="$(
    jq -er '.challengeHandle' <<<"$registration_send_response"
)"
wait_for_challenge_delivery_status "$registration_handle" "ACTIVE"
registration_code="$(reference_email_code "$registration_handle")"
[[ "$registration_code" =~ ^[0-9]{6}$ ]] \
    || fail "reference email service did not render the registration code"

register_payload="$(
    jq -cn \
        --arg challengeHandle "$registration_handle" \
        --arg username "$local_username" \
        --arg email "$local_email" \
        --arg password "$local_password" \
        --arg verificationCode "$registration_code" \
        '{
          challengeHandle: $challengeHandle,
          username: $username,
          email: $email,
          password: $password,
          displayName: "Shell E2E User",
          verificationCode: $verificationCode
        }'
)"
registration_headers="$TEMP_DIR/registration-headers.txt"
register_response="$(
    curl -sS -D "$registration_headers" \
        -X POST "${BASE_URL}/api/auth/verify-email" \
        -H "Content-Type: application/json" \
        --data "$register_payload"
)"
local_user_id="$(jq -er '.user.id' <<<"$register_response")"
[ -n "$local_user_id" ] || fail "local registration did not return a user id"
assert_auth_cookie_headers "$registration_headers" 3600 604800

duplicate_status="$(
    curl -sS -o "$TEMP_DIR/duplicate.json" -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/verify-email" \
        -H "Content-Type: application/json" \
        --data "$register_payload"
)"
[ "$duplicate_status" = "400" ] \
    || fail "consumed registration challenge was accepted twice"

wrong_login_status="$(
    curl -sS -o /dev/null -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/login" \
        -H "Content-Type: application/json" \
        --data "$(
            jq -cn \
                --arg username "$local_username" \
                '{username: $username, password: "wrong-password"}'
        )"
)"
[ "$wrong_login_status" = "401" ] || fail "wrong password did not return 401"

login_local_session
login_response="$LOGIN_RESPONSE"
family_id="$(db_value "
    SELECT id
    FROM token_families
    WHERE user_id = '$local_user_id'
      AND revoked_at IS NULL
    ORDER BY created_at DESC
    LIMIT 1;
")"
[ -n "$family_id" ] || fail "local login did not create a token family"

oauth_login_status="$(
    curl -sS -o /dev/null -w '%{http_code}' \
        -b "$COOKIE_JAR" \
        -c "$COOKIE_JAR" \
        "${BASE_URL}/oauth2/authorization/github"
)"
[ "$oauth_login_status" = "302" ] \
    || fail "ordinary OAuth login did not start authorization"
[ "$(db_value "
    SELECT count(*)
    FROM oauth2_binding_intents
    WHERE user_id = '$local_user_id';
")" = "0" ] \
    || fail "ordinary OAuth login created a binding intent from existing cookies"

oauth_bind_status="$(
    curl -sS -o /dev/null -w '%{http_code}' \
        -b "$COOKIE_JAR" \
        -c "$COOKIE_JAR" \
        -H "Authorization: Bearer $access_token" \
        "${BASE_URL}/oauth2/bind/github"
)"
[ "$oauth_bind_status" = "302" ] \
    || fail "explicit OAuth binding did not start authorization"
[ "$(db_value "
    SELECT count(*)
    FROM oauth2_binding_intents
    WHERE user_id = '$local_user_id'
      AND provider = 'github'
      AND consumed_at IS NULL
      AND expires_at > CURRENT_TIMESTAMP;
")" = "1" ] \
    || fail "explicit OAuth binding did not persist one active intent"
db_value "
    DELETE FROM oauth2_binding_intents
    WHERE user_id = '$local_user_id';
" >/dev/null

echo "5/17 Verify protected APIs, persistence, and JWT contracts"
current_user="$(
    curl -sS \
        -H "Authorization: Bearer $access_token" \
        "${BASE_URL}/api/user"
)"
[ "$(jq -er '.userId' <<<"$current_user")" = "$local_user_id" ] \
    || fail "current-user response did not match the registered account"

cookie_user_status="$(
    curl -sS -o /dev/null -w '%{http_code}' \
        -b "$COOKIE_JAR" \
        "${BASE_URL}/api/user"
)"
[ "$cookie_user_status" = "200" ] \
    || fail "access-token cookie did not authenticate the current-user endpoint"

login_methods="$(
    curl -sS \
        -H "Authorization: Bearer $access_token" \
        "${BASE_URL}/api/user/login-methods"
)"
[ "$(jq -er '.count' <<<"$login_methods")" = "1" ] \
    || fail "new local account did not have exactly one login method"
[ "$(jq -er '.loginMethods[0].authProvider' <<<"$login_methods")" = "local" ] \
    || fail "local login method contract changed unexpectedly"

anonymous_introspection_status="$(
    curl -sS -o "$TEMP_DIR/introspection-anonymous.json" -w '%{http_code}' \
        -X POST "${BASE_URL}/oauth2/introspect" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        --data-urlencode "token=$access_token"
)"
[ "$anonymous_introspection_status" = "401" ] \
    || fail "anonymous token introspection was not rejected"
query_introspection_status="$(
    curl -sS -o "$TEMP_DIR/introspection-query.json" -w '%{http_code}' \
        -X POST "${BASE_URL}/oauth2/introspect?token=${access_token}" \
        -u "${INTROSPECTION_CLIENT_ID}:${INTROSPECTION_CLIENT_SECRET}" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        --data-urlencode "token=$access_token"
)"
[ "$query_introspection_status" = "400" ] \
    || fail "query token introspection was not rejected"
duplicate_introspection_status="$(
    curl -sS -o "$TEMP_DIR/introspection-duplicate.json" -w '%{http_code}' \
        -X POST "${BASE_URL}/oauth2/introspect" \
        -u "${INTROSPECTION_CLIENT_ID}:${INTROSPECTION_CLIENT_SECRET}" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        --data-urlencode "token=$access_token" \
        --data-urlencode "token=$access_token"
)"
[ "$duplicate_introspection_status" = "400" ] \
    || fail "duplicate introspection token input was not rejected"
cookie_introspection_status="$(
    curl -sS -o "$TEMP_DIR/introspection-cookie.json" -w '%{http_code}' \
        -X POST "${BASE_URL}/oauth2/introspect" \
        -u "${INTROSPECTION_CLIENT_ID}:${INTROSPECTION_CLIENT_SECRET}" \
        -b "$COOKIE_JAR" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        --data-urlencode "token=$access_token"
)"
[ "$cookie_introspection_status" = "400" ] \
    || fail "browser-cookie introspection was not rejected"
introspection="$(introspect_token "$access_token")"
[ "$(jq -er '.active' <<<"$introspection")" = "true" ] \
    || fail "access token introspection was not active"
[ "$(jq -er '.sub' <<<"$introspection")" = "$local_user_id" ] \
    || fail "access token subject did not match the user id"
[ "$(jq -er '.aud' <<<"$introspection")" = "resource-server" ] \
    || fail "access token audience changed unexpectedly"

[ "$(db_value "
    SELECT count(*)
    FROM user_login_methods
    WHERE user_id = '$local_user_id'
      AND auth_provider = 'LOCAL'
      AND last_used_at IS NOT NULL;
")" = "1" ] || fail "successful login did not persist last_used_at"

echo "6/17 Restart the application without replaying migrations or losing data"
stop_application
start_application
wait_for_application

[ "$(db_value "
    SELECT count(*)
    FROM uniauth_flyway_schema_history
    WHERE version IN ('1', '2', '3', '4', '5', '6', '7', '8')
      AND type = 'SQL'
      AND success = true;
")" = "8" ] || fail "application restart changed the Flyway migration history"
[ "$(db_value "SELECT count(*) FROM users WHERE id = '$local_user_id';")" = "1" ] \
    || fail "application restart lost the registered user"
restarted_user="$(
    curl -sS \
        -H "Authorization: Bearer $access_token" \
        "${BASE_URL}/api/user"
)"
[ "$(jq -er '.userId' <<<"$restarted_user")" = "$local_user_id" ] \
    || fail "the pre-restart access token did not work after restart"

echo "7/17 Refresh tokens and reject token type confusion"
refresh_headers="$TEMP_DIR/refresh-headers.txt"
refresh_response="$(
    curl -sS -D "$refresh_headers" -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
        -X POST "${BASE_URL}/api/auth/refresh" \
        -H "${CSRF_HEADER_NAME}: ${CSRF_TOKEN}"
)"
new_access_token="$(jq -er '.accessToken' <<<"$refresh_response")"
if jq -e 'has("refreshToken")' <<<"$refresh_response" >/dev/null; then
    fail "refresh exposed the refresh token in JSON"
fi
new_refresh_token="$(cookie_jar_value refreshToken)"
[ "$new_access_token" != "$access_token" ] \
    || fail "refresh did not rotate the access token"
[ "$new_refresh_token" != "$refresh_token" ] \
    || fail "refresh did not rotate the refresh token"
assert_auth_cookie_headers "$refresh_headers" 3600 604800
[ "$(db_value "
    SELECT current_generation
    FROM token_families
    WHERE id = '$family_id';
")" = "1" ] || fail "refresh did not advance the token-family generation"

refresh_replay_status="$(
    curl -sS -o "$TEMP_DIR/refresh-replay.json" -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/refresh" \
        -H "${CSRF_HEADER_NAME}: ${CSRF_TOKEN}" \
        -H "Cookie: $(cookie_header_with_refresh "$refresh_token")"
)"
[ "$refresh_replay_status" = "401" ] \
    || fail "a consumed refresh token was accepted for replay"
[ "$(db_value "
    SELECT revoke_reason
    FROM token_families
    WHERE id = '$family_id';
")" = "REFRESH_REPLAY" ] \
    || fail "refresh replay did not revoke the whole token family"

login_local_session
new_access_token="$access_token"
new_refresh_token="$refresh_token"
family_id="$(db_value "
    SELECT id
    FROM token_families
    WHERE user_id = '$local_user_id'
      AND revoked_at IS NULL
    ORDER BY created_at DESC
    LIMIT 1;
")"
[ -n "$family_id" ] || fail "re-login did not create a replacement token family"

refresh_as_bearer_status="$(
    curl -sS -o /dev/null -w '%{http_code}' \
        -H "Authorization: Bearer $refresh_token" \
        "${BASE_URL}/api/user"
)"
[ "$refresh_as_bearer_status" = "401" ] \
    || fail "refresh token was accepted as an access token"

access_as_refresh_status="$(
    curl -sS -o /dev/null -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/refresh" \
        -H "${CSRF_HEADER_NAME}: ${CSRF_TOKEN}" \
        -H "Cookie: $(cookie_header_with_refresh "$access_token")"
)"
[ "$access_as_refresh_status" = "401" ] \
    || fail "access token was accepted as a refresh token"

echo "8/17 Authenticate a new Web3 account and reject message tampering"
web3_wallet="$(create_wallet)"
web3_address="$(jq -er '.address' <<<"$web3_wallet")"
web3_challenge="$(signed_challenge "$web3_wallet")"
tampered_web3_challenge="$(
    jq -c '.message = (.message + "\nTampered: true")' <<<"$web3_challenge"
)"
tampered_web3_status="$(
    curl -sS -o "$TEMP_DIR/web3-tampered.json" -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/web3/verify" \
        -H "Content-Type: application/json" \
        --data "$tampered_web3_challenge"
)"
[ "$tampered_web3_status" = "401" ] \
    || fail "a Web3 challenge with a tampered message was accepted"
tampered_domain_message="$(
    jq -er '.message | sub("^[^ ]+"; "attacker.example")' <<<"$web3_challenge"
)"
tampered_domain_challenge="$(
    resign_web3_challenge \
        "$web3_wallet" \
        "$tampered_domain_message" \
        "$(jq -er '.nonce' <<<"$web3_challenge")" \
        "$(jq -er '.challengeHandle' <<<"$web3_challenge")"
)"
tampered_domain_status="$(
    curl -sS -o "$TEMP_DIR/web3-domain-tampered.json" -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/web3/verify" \
        -H "Content-Type: application/json" \
        --data "$tampered_domain_challenge"
)"
[ "$tampered_domain_status" = "401" ] \
    || fail "a Web3 challenge with a tampered domain was accepted"
wrong_chain_challenge="$(jq -c '.chainId = 5' <<<"$web3_challenge")"
wrong_chain_status="$(
    curl -sS -o "$TEMP_DIR/web3-chain-tampered.json" -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/web3/verify" \
        -H "Content-Type: application/json" \
        --data "$wrong_chain_challenge"
)"
[ "$wrong_chain_status" = "401" ] \
    || fail "a Web3 challenge with a mismatched request chain ID was accepted"
web3_headers="$TEMP_DIR/web3-headers.txt"
web3_login="$(
    curl -sS -D "$web3_headers" \
        -X POST "${BASE_URL}/api/auth/web3/verify" \
        -H "Content-Type: application/json" \
        --data "$web3_challenge"
)"
[ "$(jq -er '.walletAddress' <<<"$web3_login")" = "$web3_address" ] \
    || fail "Web3 login returned the wrong wallet address"
[ "$(jq -er '.isNewUser' <<<"$web3_login")" = "true" ] \
    || fail "first Web3 login was not marked as a new user"
web3_user_id="$(jq -er '.userId' <<<"$web3_login")"
web3_access_token="$(jq -er '.accessToken' <<<"$web3_login")"
assert_auth_cookie_headers "$web3_headers" 3600 604800

replay_status="$(
    curl -sS -o "$TEMP_DIR/web3-replay.json" -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/web3/verify" \
        -H "Content-Type: application/json" \
        --data "$web3_challenge"
)"
[ "$replay_status" = "401" ] || fail "consumed Web3 nonce was replayable"

wallet_status="$(
    curl -sS -o /dev/null -w '%{http_code}' \
        "${BASE_URL}/api/auth/web3/status/${web3_address}"
)"
[ "$wallet_status" = "403" ] \
    || fail "removed Web3 wallet status oracle was not denied by default"

concurrent_web3_challenge="$(signed_challenge "$web3_wallet")"
concurrent_status_one="$TEMP_DIR/web3-concurrent-one.status"
concurrent_status_two="$TEMP_DIR/web3-concurrent-two.status"
(
    curl -sS -o /dev/null -w '%{http_code}\n' \
        -X POST "${BASE_URL}/api/auth/web3/verify" \
        -H "Content-Type: application/json" \
        --data "$concurrent_web3_challenge" >"$concurrent_status_one"
) &
concurrent_pid_one=$!
(
    curl -sS -o /dev/null -w '%{http_code}\n' \
        -X POST "${BASE_URL}/api/auth/web3/verify" \
        -H "Content-Type: application/json" \
        --data "$concurrent_web3_challenge" >"$concurrent_status_two"
) &
concurrent_pid_two=$!
wait "$concurrent_pid_one"
wait "$concurrent_pid_two"
concurrent_successes="$(
    grep -hFx "200" "$concurrent_status_one" "$concurrent_status_two" | wc -l | tr -d ' '
)"
concurrent_rejections="$(
    grep -hFx "401" "$concurrent_status_one" "$concurrent_status_two" | wc -l | tr -d ' '
)"
[ "$concurrent_successes" = "1" ] && [ "$concurrent_rejections" = "1" ] \
    || fail "concurrent Web3 challenge submissions did not resolve as 200/401"

repeat_web3_challenge="$(signed_challenge "$web3_wallet")"
repeat_web3_login="$(post_json /api/auth/web3/verify "$repeat_web3_challenge")"
[ "$(jq -er '.userId' <<<"$repeat_web3_login")" = "$web3_user_id" ] \
    || fail "repeat Web3 login created a different user"
[ "$(jq -er '.isNewUser' <<<"$repeat_web3_login")" = "false" ] \
    || fail "repeat Web3 login was incorrectly marked as new"

echo "9/17 Verify header/cookie identity precedence"
conflicting_identity_status="$(
    curl -sS -o "$TEMP_DIR/conflicting-identity.json" -w '%{http_code}' \
        -H "Authorization: Bearer $web3_access_token" \
        -H "Cookie: accessToken=$access_token" \
        "${BASE_URL}/api/user"
)"
[ "$conflicting_identity_status" = "401" ] \
    || fail "conflicting header and Cookie identities were not rejected"
duplicate_header_status="$(
    curl -sS -o "$TEMP_DIR/duplicate-authorization.json" -w '%{http_code}' \
        -H "Authorization: Bearer $access_token" \
        -H "Authorization: Bearer $access_token" \
        "${BASE_URL}/api/user"
)"
[ "$duplicate_header_status" = "401" ] \
    || fail "duplicate Authorization headers were not rejected"
duplicate_cookie_status="$(
    curl -sS -o "$TEMP_DIR/duplicate-access-cookie.json" -w '%{http_code}' \
        -H "Cookie: accessToken=$access_token; accessToken=$access_token" \
        "${BASE_URL}/api/user"
)"
[ "$duplicate_cookie_status" = "401" ] \
    || fail "duplicate access-token cookies were not rejected"
manual_cookie_identity="$(
    curl -sS \
        -H "Cookie: accessToken=$access_token" \
        "${BASE_URL}/api/user"
)"
[ "$(jq -er '.userId' <<<"$manual_cookie_identity")" = "$local_user_id" ] \
    || fail "cookie-only authentication selected the wrong identity"

echo "10/17 Bind and manage a Web3 login method for the local account"
binding_wallet="$(create_wallet)"
binding_challenge="$(signed_challenge "$binding_wallet")"
missing_binding_token_status="$(
    curl -sS -o "$TEMP_DIR/missing-binding-token.json" -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/web3/bind" \
        -H "Content-Type: application/json" \
        --data "$binding_challenge"
)"
[ "$missing_binding_token_status" = "401" ] \
    || fail "Web3 wallet binding without an access token did not return 401"

refresh_binding_status="$(
    curl -sS -o "$TEMP_DIR/refresh-binding.json" -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/web3/bind" \
        -H "Authorization: Bearer $new_refresh_token" \
        -H "Content-Type: application/json" \
        --data "$binding_challenge"
)"
[ "$refresh_binding_status" = "401" ] \
    || fail "a refresh token was accepted for Web3 wallet binding"

binding_response="$(
    curl -sS -X POST "${BASE_URL}/api/auth/web3/bind" \
        -H "Authorization: Bearer $new_access_token" \
        -H "Content-Type: application/json" \
        --data "$binding_challenge"
)"
[ "$(jq -er '.errorCode' <<<"$binding_response")" = "SUCCESS" ] \
    || fail "authenticated local account could not bind a Web3 wallet"

login_local_session
new_access_token="$access_token"
new_refresh_token="$refresh_token"

second_binding_wallet="$(create_wallet)"
second_binding_challenge="$(signed_challenge "$second_binding_wallet")"
second_binding_status="$(
    curl -sS -o "$TEMP_DIR/second-binding.json" -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/web3/bind" \
        -H "Authorization: Bearer $new_access_token" \
        -H "Content-Type: application/json" \
        --data "$second_binding_challenge"
)"
[ "$second_binding_status" = "409" ] \
    || fail "a second Web3 wallet was accepted for the same user"

[ "$(db_value "
    SELECT count(*)
    FROM user_login_methods
    WHERE user_id = '$local_user_id';
")" = "2" ] || fail "Web3 binding was not persisted as a second login method"

managed_methods="$(
    curl -sS \
        -H "Authorization: Bearer $new_access_token" \
        "${BASE_URL}/api/user/login-methods"
)"
local_method_id="$(
    jq -er '.loginMethods[] | select(.authProvider == "local") | .id' \
        <<<"$managed_methods"
)"
bound_web3_method_id="$(
    jq -er '.loginMethods[] | select(.authProvider == "web3") | .id' \
        <<<"$managed_methods"
)"

race_method_id="shell-race-${RUN_ID}"
db_value "
    INSERT INTO user_login_methods (
        id,
        user_id,
        auth_provider,
        provider_user_id,
        provider_email,
        provider_username,
        is_primary,
        is_verified
    ) VALUES (
        '$race_method_id',
        '$local_user_id',
        'GITHUB',
        '$race_method_id',
        '$local_email',
        'shell-race',
        false,
        true
    );

    CREATE OR REPLACE FUNCTION e2e_delay_login_method_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
    AS \$\$
    BEGIN
        PERFORM pg_sleep(0.75);
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END
    \$\$;

    CREATE TRIGGER e2e_delay_login_method_mutation
    BEFORE UPDATE OR DELETE ON user_login_methods
    FOR EACH ROW
    EXECUTE FUNCTION e2e_delay_login_method_mutation();
" >/dev/null

race_delete_body="$TEMP_DIR/login-method-race-delete.json"
race_delete_status_file="$TEMP_DIR/login-method-race-delete.status"
race_primary_body="$TEMP_DIR/login-method-race-primary.json"
race_primary_status_file="$TEMP_DIR/login-method-race-primary.status"

curl -sS -o "$race_delete_body" -w '%{http_code}' \
    -X DELETE \
    -H "Authorization: Bearer $new_access_token" \
    "${BASE_URL}/api/user/login-methods/${race_method_id}" \
    >"$race_delete_status_file" &
race_delete_pid=$!
curl -sS -o "$race_primary_body" -w '%{http_code}' \
    -X PUT \
    -H "Authorization: Bearer $new_access_token" \
    "${BASE_URL}/api/user/login-methods/${race_method_id}/primary" \
    >"$race_primary_status_file" &
race_primary_pid=$!

wait "$race_delete_pid"
wait "$race_primary_pid"

db_value "
    DROP TRIGGER e2e_delay_login_method_mutation ON user_login_methods;
    DROP FUNCTION e2e_delay_login_method_mutation();
" >/dev/null

race_delete_status="$(cat "$race_delete_status_file")"
race_primary_status="$(cat "$race_primary_status_file")"
if ! {
    [ "$race_delete_status" = "200" ] && [ "$race_primary_status" = "409" ];
} && ! {
    [ "$race_delete_status" = "409" ] && [ "$race_primary_status" = "200" ];
}; then
    fail "concurrent login-method mutation did not return one 200 and one 409"
fi

if [ "$race_delete_status" = "409" ]; then
    race_conflict_body="$race_delete_body"
else
    race_conflict_body="$race_primary_body"
fi
race_conflict_error="$(jq -er '.error' "$race_conflict_body")"
case "$race_conflict_error" in
    "登录方式已被并发修改，请重试"|"主登录方式已被并发修改，请重试")
        ;;
    *)
        fail "concurrent login-method mutation returned an unstable conflict message"
        ;;
esac

login_local_session
new_access_token="$access_token"
new_refresh_token="$refresh_token"

[ "$(db_value "
    SELECT count(*)
    FROM user_login_methods
    WHERE user_id = '$local_user_id';
")" -ge "1" ] || fail "concurrent login-method mutation removed every login method"
[ "$(db_value "
    SELECT count(*)
    FROM user_login_methods
    WHERE user_id = '$local_user_id' AND is_primary = true;
")" = "1" ] || fail "concurrent login-method mutation did not leave exactly one primary"
[ "$(db_value "
    SELECT login_methods_revision
    FROM users
    WHERE id = '$local_user_id';
")" = "1" ] || fail "concurrent login-method mutation did not claim exactly one revision"

if [ "$(db_value "
    SELECT count(*)
    FROM user_login_methods
    WHERE id = '$race_method_id';
")" = "1" ]; then
    race_cleanup_status="$(
        request_status \
            DELETE \
            "/api/user/login-methods/${race_method_id}" \
            -H "Authorization: Bearer $new_access_token"
    )"
    [ "$race_cleanup_status" = "200" ] \
        || fail "the surviving race fixture could not be removed"
fi

login_local_session
new_access_token="$access_token"
new_refresh_token="$refresh_token"

set_primary_status="$(
    request_status \
        PUT \
        "/api/user/login-methods/${bound_web3_method_id}/primary" \
        -H "Authorization: Bearer $new_access_token"
)"
[ "$set_primary_status" = "200" ] \
    || fail "the bound Web3 method could not be set as primary"
[ "$(db_value "
    SELECT count(*)
    FROM user_login_methods
    WHERE user_id = '$local_user_id' AND is_primary = true;
")" = "1" ] || fail "setting primary did not leave exactly one primary method"
[ "$(db_value "
    SELECT id
    FROM user_login_methods
    WHERE user_id = '$local_user_id' AND is_primary = true;
")" = "$bound_web3_method_id" ] || fail "the requested Web3 method was not persisted as primary"

delete_bound_status="$(
    request_status \
        DELETE \
        "/api/user/login-methods/${bound_web3_method_id}" \
        -H "Authorization: Bearer $new_access_token"
)"
[ "$delete_bound_status" = "200" ] \
    || fail "the bound Web3 login method could not be deleted"
[ "$(db_value "
    SELECT count(*)
    FROM user_login_methods
    WHERE user_id = '$local_user_id'
      AND id = '$local_method_id'
      AND is_primary = true;
")" = "1" ] || fail "deleting the primary Web3 method did not promote the local method"

login_local_session
new_access_token="$access_token"
new_refresh_token="$refresh_token"

delete_last_status="$(
    request_status \
        DELETE \
        "/api/user/login-methods/${local_method_id}" \
        -H "Authorization: Bearer $new_access_token"
)"
[ "$delete_last_status" = "400" ] \
    || fail "the last login method could be deleted"

echo "11/17 Run the email registration and password-reset HTTP flow"
email_flow_address="shell-email-${RUN_ID}@example.invalid"
if ! DISPOSABLE_TEST_ENVIRONMENT=true \
    BASE_URL="$BASE_URL" \
    EMAIL="$email_flow_address" \
    PASSWORD="$local_password" \
    NEW_PASSWORD="$local_new_password" \
    POSTGRES_HOST=127.0.0.1 \
    POSTGRES_PORT="$DATABASE_PORT" \
    POSTGRES_DATABASE="$DATABASE_NAME" \
    POSTGRES_USER="$DATABASE_USER" \
    POSTGRES_PASSWORD="$DATABASE_PASSWORD" \
    EMAIL_POSTGRES_HOST=127.0.0.1 \
    EMAIL_POSTGRES_PORT="$EMAIL_SERVICE_DATABASE_PORT" \
    EMAIL_POSTGRES_DATABASE="$EMAIL_SERVICE_DATABASE_NAME" \
    EMAIL_POSTGRES_USER="$EMAIL_SERVICE_DATABASE_USER" \
    EMAIL_POSTGRES_PASSWORD="$EMAIL_SERVICE_DATABASE_PASSWORD" \
        "$PROJECT_DIR/scripts/test-email-registration.sh"; then
    fail "email registration/password-reset subflow failed"
fi
[ "$(email_service_db_value "
    SELECT count(*)
    FROM email_queue
    WHERE recipient = '$email_flow_address'
      AND email_type = 'VERIFICATION'
      AND status = 'PENDING'
      AND idempotency_key LIKE 'email-challenge:%';
")" = "1" ] || fail "reference email service did not persist the registration template"
[ "$(email_service_db_value "
    SELECT count(*)
    FROM email_queue
    WHERE recipient = '$email_flow_address'
      AND email_type = 'PASSWORD_RESET'
      AND status = 'PENDING'
      AND idempotency_key LIKE 'email-challenge:%';
")" = "1" ] || fail "reference email service did not persist the password-reset template"
[ "$(email_service_db_value "
    SELECT count(*)
    FROM email_queue
    WHERE recipient = '$email_flow_address'
      AND html_content <> ''
      AND status = 'PENDING';
")" = "2" ] || fail "reference email service did not persist rendered template content"

echo "12/17 Run registration and password-reset rejection contracts"
if ! DISPOSABLE_TEST_ENVIRONMENT=true \
    BASE_URL="$BASE_URL" \
    EMAIL_EXISTS="$email_flow_address" \
    EMAIL_NOT_REGISTERED="shell-contract-${RUN_ID}@example.invalid" \
    TEST_PASSWORD="$local_new_password" \
        "$PROJECT_DIR/scripts/test-registration-password-reset.sh"; then
    fail "registration/password-reset rejection contract subflow failed"
fi

echo "13/17 Verify durable email delivery recovery and terminal failure"
stop_application
start_email_stub
ACTIVE_EMAIL_SERVICE_URL="$EMAIL_STUB_URL"
EMAIL_DELIVERY_WORKER_ENABLED_VALUE=false
start_application
wait_for_application
before_unsupported_count="$(db_value "SELECT count(*) FROM email_verification_codes;")"
for unsupported_purpose in LOGIN PASSWORD_RESET UNKNOWN; do
    unsupported_status="$(
        request_status \
            POST \
            /api/auth/send-verification-code \
            -H "Content-Type: application/json" \
            --data "$(
                jq -cn \
                    --arg email "unsupported-${RUN_ID}@example.invalid" \
                    --arg purpose "$unsupported_purpose" \
                    '{email: $email, purpose: $purpose}'
            )"
    )"
    [ "$unsupported_status" = "400" ] \
        || fail "unsupported email purpose $unsupported_purpose did not return 400"
done
[ "$(db_value "SELECT count(*) FROM email_verification_codes;")" \
    = "$before_unsupported_count" ] \
    || fail "unsupported email purpose changed challenge state"

pending_email="pending-restart-${RUN_ID}@example.invalid"
pending_response="$(
    post_json \
        /api/auth/send-verification-code \
        "$(jq -cn --arg email "$pending_email" \
            '{email: $email, purpose: "REGISTRATION"}')"
)"
pending_handle="$(jq -er '.challengeHandle' <<<"$pending_response")"
sleep 0.5
[ "$(db_value "
    SELECT delivery_status || ':' || usage_status
    FROM email_verification_codes
    WHERE id = '$pending_handle';
")" = "PENDING_DELIVERY:UNUSED" ] \
    || fail "worker-disabled challenge did not remain pending"
[ "$(db_value "
    SELECT status || ':' || attempt_count
    FROM email_delivery_outbox
    WHERE challenge_id = '$pending_handle';
")" = "PENDING:0" ] \
    || fail "worker-disabled outbox was processed unexpectedly"

stop_application
EMAIL_DELIVERY_WORKER_ENABLED_VALUE=true
start_application
wait_for_application
wait_for_challenge_delivery_status "$pending_handle" "ACTIVE"
[ "$(db_value "
    SELECT status
    FROM email_delivery_outbox
    WHERE challenge_id = '$pending_handle';
")" = "ACCEPTED" ] \
    || fail "pending outbox was not accepted after worker restart"

accepted_timeout_email="accepted-timeout-${RUN_ID}@example.invalid"
accepted_timeout_response="$(
    post_json \
        /api/auth/send-verification-code \
        "$(jq -cn --arg email "$accepted_timeout_email" \
            '{email: $email, purpose: "REGISTRATION"}')"
)"
accepted_timeout_handle="$(
    jq -er '.challengeHandle' <<<"$accepted_timeout_response"
)"
wait_for_challenge_delivery_status "$accepted_timeout_handle" "ACTIVE"
[ "$(db_value "
    SELECT status || ':' || attempt_count
    FROM email_delivery_outbox
    WHERE challenge_id = '$accepted_timeout_handle';
")" = "ACCEPTED:2" ] \
    || fail "lost acceptance response was not reconciled idempotently"
[ "$(
    jq -rs \
        --arg idempotencyKey "email-challenge:$accepted_timeout_handle" \
        '[.[] | select(.idempotencyKey == $idempotencyKey)] | length' \
        "$EMAIL_STUB_CAPTURE_FILE"
)" = "1" ] || fail "accepted-timeout retry created duplicate provider queues"

delivery_failed_email="delivery-failed-${RUN_ID}@example.invalid"
delivery_failed_response="$(
    post_json \
        /api/auth/send-verification-code \
        "$(jq -cn --arg email "$delivery_failed_email" \
            '{email: $email, purpose: "REGISTRATION"}')"
)"
delivery_failed_handle="$(
    jq -er '.challengeHandle' <<<"$delivery_failed_response"
)"
wait_for_challenge_delivery_status "$delivery_failed_handle" "FAILED"
[ "$(db_value "
    SELECT usage_status || ':' || failure_reason
    FROM email_verification_codes
    WHERE id = '$delivery_failed_handle';
")" = "INVALIDATED:PROVIDER_DELIVERY_FAILED" ] \
    || fail "provider final failure left a usable challenge"

for delivery_case in rejected rate-limited; do
    delivery_email="${delivery_case}-${RUN_ID}@example.invalid"
    delivery_response="$(
        post_json \
            /api/auth/send-verification-code \
            "$(jq -cn --arg email "$delivery_email" \
                '{email: $email, purpose: "REGISTRATION"}')"
    )"
    [ "$(jq -er '.success' <<<"$delivery_response")" = "true" ] \
        || fail "$delivery_case delivery was not durably queued"
    delivery_handle="$(jq -er '.challengeHandle' <<<"$delivery_response")"
    wait_for_challenge_delivery_status "$delivery_handle" "FAILED"
    [ "$(db_value "
        SELECT usage_status
        FROM email_verification_codes
        WHERE id = '$delivery_handle';
    ")" = "INVALIDATED" ] \
        || fail "$delivery_case final failure left a usable challenge"
    [ "$(db_value "
        SELECT status || ':' || attempt_count
        FROM email_delivery_outbox
        WHERE challenge_id = '$delivery_handle';
    ")" = "FAILED:3" ] \
        || fail "$delivery_case delivery did not exhaust the retry budget"
done

echo "14/17 Exhaust an invalid email verification retry budget"
retry_email="shell-retry-${RUN_ID}@example.invalid"
retry_send_payload="$(
    jq -cn \
        --arg email "$retry_email" \
        '{
          email: $email,
          purpose: "REGISTRATION"
        }'
)"
retry_send_response="$(
    post_json /api/auth/send-verification-code "$retry_send_payload"
)"
[ "$(jq -er '.success' <<<"$retry_send_response")" = "true" ] \
    || fail "retry-budget verification code was not created"
retry_handle="$(jq -er '.challengeHandle' <<<"$retry_send_response")"
wait_for_challenge_delivery_status "$retry_handle" "ACTIVE"
retry_code="$(stub_email_code "$retry_handle")"
wrong_retry_code="000000"
if [ "$retry_code" = "$wrong_retry_code" ]; then
    wrong_retry_code="111111"
fi
for attempt in $(seq 1 5); do
    retry_response_file="$TEMP_DIR/retry-${attempt}.json"
    retry_status="$(
        curl -sS -o "$retry_response_file" -w '%{http_code}' \
            -X POST "${BASE_URL}/api/auth/verify-email" \
            -H "Content-Type: application/json" \
            --data "$(
                jq -cn \
                    --arg handle "$retry_handle" \
                    --arg email "$retry_email" \
                    --arg code "$wrong_retry_code" \
                    '{
                      challengeHandle: $handle,
                      username: $email,
                      email: $email,
                      password: "retry-test-password",
                      displayName: "Retry Test",
                      verificationCode: $code
                    }'
            )"
    )"
    [ "$retry_status" = "400" ] \
        || fail "invalid verification attempt $attempt did not return 400"
    [ "$(db_value "
        SELECT retry_count
        FROM email_verification_codes
        WHERE id = '$retry_handle';
    ")" = "$attempt" ] \
        || fail "invalid verification attempt $attempt did not consume one retry"
done
[ "$(db_value "
    SELECT usage_status
    FROM email_verification_codes
    WHERE id = '$retry_handle';
")" = "INVALIDATED" ] \
    || fail "exhausted email verification challenge remained usable"

echo "15/17 Verify logout cookie clearing"
logout_headers="$TEMP_DIR/logout-headers.txt"
logout_response="$(
    curl -sS -D "$logout_headers" \
        -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
        -X POST "${BASE_URL}/api/auth/logout" \
        -H "${CSRF_HEADER_NAME}: ${CSRF_TOKEN}"
)"
[ "$(jq -er '.message' <<<"$logout_response")" = "Logged out successfully" ] \
    || fail "logout did not return the success contract"
grep -qi 'set-cookie: accessToken=.*Max-Age=0' "$logout_headers" \
    || fail "logout did not clear the access-token cookie"
grep -qi 'set-cookie: refreshToken=.*Max-Age=0' "$logout_headers" \
    || fail "logout did not clear the refresh-token cookie"
assert_auth_cookie_headers "$logout_headers" 0 0

revoked_access_status="$(
    curl -sS -o /dev/null -w '%{http_code}' \
        -H "Authorization: Bearer $new_access_token" \
        "${BASE_URL}/api/user"
)"
[ "$revoked_access_status" = "401" ] \
    || fail "logout did not revoke the current access token"
bootstrap_csrf
revoked_refresh_status="$(
    curl -sS -o /dev/null -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/refresh" \
        -H "${CSRF_HEADER_NAME}: ${CSRF_TOKEN}" \
        -H "Cookie: $(cookie_header_with_refresh "$new_refresh_token")"
)"
[ "$revoked_refresh_status" = "401" ] \
    || fail "logout did not revoke the current refresh token"
revoked_introspection="$(introspect_token "$new_access_token")"
[ "$(jq -er '.active' <<<"$revoked_introspection")" = "false" ] \
    || fail "introspection reported a logged-out access token as active"
[ "$(db_value "
    SELECT count(*)
    FROM token_families
    WHERE user_id = '$local_user_id'
      AND revoke_reason = 'LOGOUT';
")" -ge "1" ] || fail "logout did not persist token-family revocation"

echo "16/17 Rehearse emergency signing-key rotation and revocation"
rm -f "$COOKIE_JAR"
bootstrap_csrf
login_local_session
rotation_old_access_token="$access_token"
[ "$(request_status GET /api/user \
    -H "Authorization: Bearer $rotation_old_access_token")" = "200" ] \
    || fail "pre-rotation access token was not active"

stop_application
retired_key_file="$TEMP_DIR/retired-signing-key.ser"
mv "$JWT_RSA_KEY_FILE_VALUE" "$retired_key_file"
JWT_RSA_KEY_FILE_VALUE="$TEMP_DIR/signing-key-rotated.ser"
JWT_KID_VALUE="key-rotation-b"
start_application
wait_for_application

[ "$(request_status GET /api/user \
    -H "Authorization: Bearer $rotation_old_access_token")" = "401" ] \
    || fail "old access token remained valid after emergency key rotation"
rotation_old_introspection="$(introspect_token "$rotation_old_access_token")"
[ "$(jq -er '.active' <<<"$rotation_old_introspection")" = "false" ] \
    || fail "introspection reported a retired-key token as active"
[ "$(curl -fsS "${BASE_URL}/oauth2/jwks" | jq -er '.keys[0].kid')" \
    = "$JWT_KID_VALUE" ] \
    || fail "JWKS did not publish the rotated key id"

rm -f "$COOKIE_JAR"
bootstrap_csrf
login_local_session
[ "$(request_status GET /api/user \
    -H "Authorization: Bearer $access_token")" = "200" ] \
    || fail "new access token was not valid after signing-key rotation"
rm -f "$retired_key_file"
[ ! -e "$retired_key_file" ] \
    || fail "retired signing key fixture was not destroyed"

echo "17/17 Verify final database invariants"
[ "$(db_value "SELECT current_database();")" = "$DATABASE_NAME" ] \
    || fail "the E2E harness connected to an unexpected database"
[ "$(db_value "SELECT count(*) FROM uniauth_flyway_schema_history;")" = "8" ] \
    || fail "Flyway history contained unexpected rows after application restarts"
active_web3_challenges="$(db_value "
    SELECT count(*)
    FROM web3_nonces
    WHERE expires_at > CURRENT_TIMESTAMP;
")"
[ "$(db_value "
    SELECT count(*)
    FROM web3_nonces
    WHERE expires_at <= CURRENT_TIMESTAMP;
")" = "0" ] || fail "expired Web3 challenges remained in the database"
[ "$(db_value "
    SELECT COALESCE(MAX(active_count), 0)
    FROM web3_challenge_counters
    WHERE bucket_key = 'global';
")" = "$active_web3_challenges" ] \
    || fail "the global Web3 capacity counter drifted from active challenges"
[ "$(db_value "
    SELECT COALESCE(SUM(active_count), 0)
    FROM web3_challenge_counters
    WHERE bucket_key LIKE 'source:%';
")" = "$active_web3_challenges" ] \
    || fail "Web3 source capacity counters drifted from active challenges"
[ "$(db_value "
    SELECT count(*)
    FROM users
    WHERE id = '$local_user_id' OR id = '$web3_user_id';
")" = "2" ] || fail "expected local and Web3 users were not persisted"
[ "$(db_value "
    SELECT count(*)
    FROM email_verification_codes
    WHERE usage_status = 'USED';
")" -ge "3" ] || fail "email registration and reset challenges were not consumed"
[ "$(db_value "
    SELECT count(*)
    FROM email_delivery_outbox
    WHERE status = 'ACCEPTED';
")" -ge "5" ] || fail "accepted email deliveries were not durably recorded"
[ "$(db_value "
    SELECT count(*)
    FROM security_events
    WHERE event_type IN (
        'EMAIL_CHALLENGE_QUEUED',
        'EMAIL_DELIVERY_ACCEPTED',
        'EMAIL_DELIVERY_FAILED',
        'EMAIL_CHALLENGE_CONSUMED'
    );
")" -ge "10" ] || fail "email security events were not durably recorded"
[ "$(db_value "
    SELECT count(*)
    FROM (
        SELECT user_id
        FROM user_login_methods
        GROUP BY user_id
        HAVING count(*) FILTER (WHERE is_primary = true) <> 1
    ) invalid_primary_users;
")" = "0" ] || fail "one or more users ended without exactly one primary login method"
[ "$(db_value "
    SELECT count(*)
    FROM token_families
    WHERE user_id = '$local_user_id'
      AND revoke_reason IN ('REFRESH_REPLAY', 'LOGOUT');
")" -ge "2" ] || fail "final token-family revocation history was incomplete"

echo "PASS: HTTP/PostgreSQL/Flyway/Web3/email end-to-end checks completed"
