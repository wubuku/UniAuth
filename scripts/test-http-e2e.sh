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
EMAIL_SERVICE_API_KEY="email-reference-e2e-${RUN_ID}"
EMAIL_SERVICE_PORT=""
EMAIL_STUB_PORT=""
COOKIE_JAR="$TEMP_DIR/cookies.txt"
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
        postgres:16 >/dev/null
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
            --port "$EMAIL_STUB_PORT" >"$EMAIL_STUB_LOG" 2>&1 &
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
        export JWT_RSA_KEY_FILE="$TEMP_DIR/signing-key.ser"
        export GOOGLE_CLIENT_ID=e2e-google
        export GOOGLE_CLIENT_SECRET=e2e-google-secret
        export GITHUB_CLIENT_ID=e2e-github
        export GITHUB_CLIENT_SECRET=e2e-github-secret
        export TWITTER_CLIENT_ID=e2e-x
        export TWITTER_CLIENT_SECRET=e2e-x-secret
        export APP_DEMO_DATA_ENABLED=false
        export APP_DEMO_DATA_DISPOSABLE=false
        export EMAIL_SERVICE_URL="$ACTIVE_EMAIL_SERVICE_URL"
        export EMAIL_SERVICE_API_KEY
        export APP_EMAIL_SERVICE_URL="$ACTIVE_EMAIL_SERVICE_URL"
        export APP_EMAIL_SERVICE_API_KEY="$EMAIL_SERVICE_API_KEY"
        export APP_FRONTEND_URL="$BASE_URL"
        export APP_WEB3_DOMAIN="127.0.0.1:${SERVER_PORT}"
        exec "$PROJECT_DIR/start.sh"
    ) >>"$APP_LOG" 2>&1 &
    APP_PID=$!
}

wait_for_application() {
    for _ in $(seq 1 150); do
        if curl -fsS "${BASE_URL}/oauth2/jwks" >/dev/null 2>&1; then
            return
        fi
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
  nonce: challenge.nonce,
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

echo "HTTP E2E: starting the reference email REST service"
start_email_service

echo "HTTP E2E: starting the real application through start.sh"
start_application
wait_for_application

echo "1/15 Verify Flyway-owned PostgreSQL startup"
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
")" = "8" ] || fail "Flyway did not create all eight managed tables"
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
        'idx_email_verification_pending_lookup',
        'idx_email_verification_email_created_at',
        'idx_email_verification_expires_at'
    );
")" = "3" ] \
    || fail "Flyway V4 did not create the email repository indexes"
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
    INSERT INTO users (id, username, email)
    VALUES (
        '00000000-0000-0000-0000-000000000101',
        'shell-primary-guard',
        'shell-primary-guard@example.invalid'
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
    INSERT INTO users (id, username, email)
    VALUES (
        '00000000-0000-0000-0000-000000000111',
        'shell-shape-guard',
        'shell-shape-guard@example.invalid'
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

echo "2/15 Verify fail-closed HTTP security boundaries"
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

echo "3/15 Register and authenticate a local account"
local_username="shell-user-${RUN_ID}"
local_email="${local_username}@example.invalid"
local_password="initial-password-${RUN_ID}"
local_new_password="updated-password-${RUN_ID}"

register_payload="$(
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
register_response="$(post_json /api/auth/register "$register_payload")"
local_user_id="$(jq -er '.id' <<<"$register_response")"
[ -n "$local_user_id" ] || fail "local registration did not return a user id"

duplicate_status="$(
    curl -sS -o "$TEMP_DIR/duplicate.json" -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/register" \
        -H "Content-Type: application/json" \
        --data "$register_payload"
)"
[ "$duplicate_status" = "400" ] || fail "duplicate registration did not return 400"

wrong_login_status="$(
    curl -sS -o /dev/null -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/login" \
        --data-urlencode "username=$local_username" \
        --data-urlencode "password=wrong-password"
)"
[ "$wrong_login_status" = "401" ] || fail "wrong password did not return 401"

login_headers="$TEMP_DIR/login-headers.txt"
login_response="$(
    curl -sS -D "$login_headers" -c "$COOKIE_JAR" \
        -X POST "${BASE_URL}/api/auth/login" \
        --data-urlencode "username=$local_username" \
        --data-urlencode "password=$local_password"
)"
[ "$(jq -er '.authenticated' <<<"$login_response")" = "true" ] \
    || fail "local login was not authenticated"
access_token="$(jq -er '.accessToken' <<<"$login_response")"
refresh_token="$(jq -er '.refreshToken' <<<"$login_response")"
assert_auth_cookie_headers "$login_headers" 3600 604800

echo "4/15 Verify protected APIs, persistence, and JWT contracts"
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

introspection="$(
    curl -sS -X POST "${BASE_URL}/oauth2/introspect" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        --data-urlencode "token=$access_token"
)"
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

echo "5/15 Restart the application without replaying migrations or losing data"
stop_application
start_application
wait_for_application

[ "$(db_value "
    SELECT count(*)
    FROM uniauth_flyway_schema_history
    WHERE version IN ('1', '2', '3') AND type = 'SQL' AND success = true;
")" = "3" ] || fail "application restart changed the Flyway migration history"
[ "$(db_value "SELECT count(*) FROM users WHERE id = '$local_user_id';")" = "1" ] \
    || fail "application restart lost the registered user"
restarted_user="$(
    curl -sS \
        -H "Authorization: Bearer $access_token" \
        "${BASE_URL}/api/user"
)"
[ "$(jq -er '.userId' <<<"$restarted_user")" = "$local_user_id" ] \
    || fail "the pre-restart access token did not work after restart"

echo "6/15 Refresh tokens and reject token type confusion"
refresh_headers="$TEMP_DIR/refresh-headers.txt"
refresh_response="$(
    curl -sS -D "$refresh_headers" -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
        -X POST "${BASE_URL}/api/auth/refresh"
)"
new_access_token="$(jq -er '.accessToken' <<<"$refresh_response")"
new_refresh_token="$(jq -er '.refreshToken' <<<"$refresh_response")"
[ "$new_access_token" != "$access_token" ] \
    || fail "refresh did not rotate the access token"
[ "$new_refresh_token" != "$refresh_token" ] \
    || fail "refresh did not rotate the refresh token"
assert_auth_cookie_headers "$refresh_headers" 3600 604800

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
        -H "Cookie: refreshToken=$access_token"
)"
[ "$access_as_refresh_status" = "401" ] \
    || fail "access token was accepted as a refresh token"

echo "7/15 Authenticate a new Web3 account and reject message tampering"
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

wallet_status="$(curl -sS "${BASE_URL}/api/auth/web3/status/${web3_address}")"
[ "$(jq -er '.isBound' <<<"$wallet_status")" = "true" ] \
    || fail "Web3 wallet status was not persisted"

repeat_web3_challenge="$(signed_challenge "$web3_wallet")"
repeat_web3_login="$(post_json /api/auth/web3/verify "$repeat_web3_challenge")"
[ "$(jq -er '.userId' <<<"$repeat_web3_login")" = "$web3_user_id" ] \
    || fail "repeat Web3 login created a different user"
[ "$(jq -er '.isNewUser' <<<"$repeat_web3_login")" = "false" ] \
    || fail "repeat Web3 login was incorrectly marked as new"

echo "8/15 Verify header/cookie identity precedence"
conflicting_identity="$(
    curl -sS \
        -H "Authorization: Bearer $web3_access_token" \
        -H "Cookie: accessToken=$access_token" \
        "${BASE_URL}/api/user"
)"
[ "$(jq -er '.userId' <<<"$conflicting_identity")" = "$web3_user_id" ] \
    || fail "the access-token cookie overrode the Authorization header"
manual_cookie_identity="$(
    curl -sS \
        -H "Cookie: accessToken=$access_token" \
        "${BASE_URL}/api/user"
)"
[ "$(jq -er '.userId' <<<"$manual_cookie_identity")" = "$local_user_id" ] \
    || fail "cookie-only authentication selected the wrong identity"

echo "9/15 Bind and manage a Web3 login method for the local account"
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

second_binding_wallet="$(create_wallet)"
second_binding_challenge="$(signed_challenge "$second_binding_wallet")"
second_binding_status="$(
    curl -sS -o "$TEMP_DIR/second-binding.json" -w '%{http_code}' \
        -X POST "${BASE_URL}/api/auth/web3/bind" \
        -H "Authorization: Bearer $new_access_token" \
        -H "Content-Type: application/json" \
        --data "$second_binding_challenge"
)"
[ "$second_binding_status" = "400" ] \
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

delete_last_status="$(
    request_status \
        DELETE \
        "/api/user/login-methods/${local_method_id}" \
        -H "Authorization: Bearer $new_access_token"
)"
[ "$delete_last_status" = "400" ] \
    || fail "the last login method could be deleted"

echo "10/15 Run the email registration and password-reset HTTP flow"
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
        "$PROJECT_DIR/scripts/test-email-registration.sh"; then
    fail "email registration/password-reset subflow failed"
fi
[ "$(email_service_db_value "
    SELECT count(*)
    FROM email_queue
    WHERE recipient = '$email_flow_address'
      AND email_type = 'VERIFICATION'
      AND status = 'PENDING';
")" = "1" ] || fail "reference email service did not persist the registration template"
[ "$(email_service_db_value "
    SELECT count(*)
    FROM email_queue
    WHERE recipient = '$email_flow_address'
      AND email_type = 'PASSWORD_RESET'
      AND status = 'PENDING';
")" = "1" ] || fail "reference email service did not persist the password-reset template"
[ "$(email_service_db_value "
    SELECT count(*)
    FROM email_queue
    WHERE recipient = '$email_flow_address'
      AND html_content <> ''
      AND status = 'PENDING';
")" = "2" ] || fail "reference email service did not persist rendered template content"

echo "11/15 Run registration and password-reset rejection contracts"
if ! DISPOSABLE_TEST_ENVIRONMENT=true \
    BASE_URL="$BASE_URL" \
    EMAIL_EXISTS="$email_flow_address" \
    EMAIL_NOT_REGISTERED="shell-contract-${RUN_ID}@example.invalid" \
    TEST_PASSWORD="$local_new_password" \
        "$PROJECT_DIR/scripts/test-registration-password-reset.sh"; then
    fail "registration/password-reset rejection contract subflow failed"
fi

echo "12/15 Reject unsupported purpose and failed email acceptance"
stop_application
start_email_stub
ACTIVE_EMAIL_SERVICE_URL="$EMAIL_STUB_URL"
start_application
wait_for_application
before_failed_send_count="$(db_value "SELECT count(*) FROM email_verification_codes;")"
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

for delivery_case in rejected rate-limited; do
    delivery_email="${delivery_case}-${RUN_ID}@example.invalid"
    expected_status=503
    if [ "$delivery_case" = "rate-limited" ]; then
        expected_status=429
    fi
    delivery_status="$(
        request_status \
            POST \
            /api/auth/send-verification-code \
            -H "Content-Type: application/json" \
            --data "$(
                jq -cn \
                    --arg email "$delivery_email" \
                    '{email: $email, purpose: "REGISTRATION"}'
            )"
    )"
    [ "$delivery_status" = "$expected_status" ] \
        || fail "$delivery_case email acceptance returned $delivery_status"
    [ "$(db_value "
        SELECT count(*)
        FROM email_verification_codes
        WHERE email = '$delivery_email';
    ")" = "0" ] || fail "$delivery_case email acceptance persisted a challenge"
done
[ "$(db_value "SELECT count(*) FROM email_verification_codes;")" \
    = "$before_failed_send_count" ] \
    || fail "rejected purpose or delivery attempt changed challenge state"

echo "13/15 Exhaust an invalid email verification retry budget"
retry_email="shell-retry-${RUN_ID}@example.invalid"
retry_send_payload="$(
    jq -cn \
        --arg email "$retry_email" \
        '{
          email: $email,
          purpose: "REGISTRATION",
          password: "retry-test-password"
        }'
)"
retry_send_response="$(
    post_json /api/auth/send-verification-code "$retry_send_payload"
)"
[ "$(jq -er '.success' <<<"$retry_send_response")" = "true" ] \
    || fail "retry-budget verification code was not created"
retry_code="$(db_value "
    SELECT verification_code
    FROM email_verification_codes
    WHERE email = '$retry_email'
      AND purpose = 'REGISTRATION'
      AND is_used = false
    ORDER BY created_at DESC
    LIMIT 1;
")"
wrong_retry_code="000000"
if [ "$retry_code" = "$wrong_retry_code" ]; then
    wrong_retry_code="111111"
fi
for attempt in $(seq 1 5); do
    expected_remaining=$((5 - attempt))
    retry_response_file="$TEMP_DIR/retry-${attempt}.json"
    retry_status="$(
        curl -sS -o "$retry_response_file" -w '%{http_code}' \
            -X POST "${BASE_URL}/api/auth/verify-email" \
            -H "Content-Type: application/json" \
            --data "$(
                jq -cn \
                    --arg email "$retry_email" \
                    --arg code "$wrong_retry_code" \
                    '{email: $email, verificationCode: $code}'
            )"
    )"
    [ "$retry_status" = "400" ] \
        || fail "invalid verification attempt $attempt did not return 400"
    [ "$(jq -er '.remainingAttempts' "$retry_response_file")" = "$expected_remaining" ] \
        || fail "invalid verification attempt $attempt returned the wrong retry budget"
done
[ "$(db_value "
    SELECT count(*)
    FROM email_verification_codes
    WHERE email = '$retry_email' AND is_used = false;
")" = "0" ] || fail "exhausted email verification challenge remained usable"

echo "14/15 Verify logout cookie clearing"
logout_headers="$TEMP_DIR/logout-headers.txt"
logout_response="$(
    curl -sS -D "$logout_headers" \
        -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
        -X POST "${BASE_URL}/api/auth/logout"
)"
[ "$(jq -er '.message' <<<"$logout_response")" = "Logged out successfully" ] \
    || fail "logout did not return the success contract"
grep -qi 'set-cookie: accessToken=.*Max-Age=0' "$logout_headers" \
    || fail "logout did not clear the access-token cookie"
grep -qi 'set-cookie: refreshToken=.*Max-Age=0' "$logout_headers" \
    || fail "logout did not clear the refresh-token cookie"
assert_auth_cookie_headers "$logout_headers" 0 0

echo "15/15 Verify final database invariants"
[ "$(db_value "SELECT current_database();")" = "$DATABASE_NAME" ] \
    || fail "the E2E harness connected to an unexpected database"
[ "$(db_value "SELECT count(*) FROM uniauth_flyway_schema_history;")" = "4" ] \
    || fail "Flyway history contained unexpected rows after two application starts"
[ "$(db_value "SELECT count(*) FROM web3_nonces;")" = "0" ] \
    || fail "consumed Web3 nonces remained in the database"
[ "$(db_value "
    SELECT count(*)
    FROM users
    WHERE id = '$local_user_id' OR id = '$web3_user_id';
")" = "2" ] || fail "expected local and Web3 users were not persisted"
[ "$(db_value "
    SELECT count(*)
    FROM email_verification_codes
    WHERE is_used = true;
")" -ge "2" ] || fail "email verification and reset codes were not consumed"
[ "$(db_value "
    SELECT count(*)
    FROM (
        SELECT user_id
        FROM user_login_methods
        GROUP BY user_id
        HAVING count(*) FILTER (WHERE is_primary = true) <> 1
    ) invalid_primary_users;
")" = "0" ] || fail "one or more users ended without exactly one primary login method"

echo "PASS: HTTP/PostgreSQL/Flyway/Web3/email end-to-end checks completed"
