#!/usr/bin/env bash

# Aggregate the independently runnable services for the real browser email flow.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/uniauth-email-browser-e2e.XXXXXX")"
RUN_ID="$(date +%s)-$$"
PYTHON_BIN="${PYTHON_BIN:-python3}"
POSTGRES_CONTAINER_NAME="uniauth-email-browser-e2e-${RUN_ID}"
POSTGRES_DATABASE="uniauth_email_browser_e2e_test"
POSTGRES_USER="uniauth"
POSTGRES_PASSWORD="uniauth-email-browser-${RUN_ID}"
EMAIL_STUB_API_KEY="email-browser-${RUN_ID}"
EMAIL_CAPTURE_FILE="$TEMP_DIR/mailbox.jsonl"
JWT_RSA_KEY_FILE="$TEMP_DIR/rsa-keys.ser"
EMAIL="${EMAIL_LOGIN_E2E_EMAIL:-email-browser-${RUN_ID}@example.test}"
PASSWORD="${EMAIL_LOGIN_E2E_PASSWORD:-EmailBrowser123!}"
POSTGRES_PID=""
EMAIL_STUB_PID=""
BACKEND_PID=""
FRONTEND_PID=""
RESOURCE_PID=""
POSTGRES_LOG="$TEMP_DIR/postgres.log"
EMAIL_STUB_LOG="$TEMP_DIR/email-stub.log"
BACKEND_LOG="$TEMP_DIR/backend.log"
FRONTEND_LOG="$TEMP_DIR/frontend.log"
RESOURCE_LOG="$TEMP_DIR/resource-server.log"
export NO_PROXY="${NO_PROXY:+${NO_PROXY},}localhost,127.0.0.1,::1"
export no_proxy="${no_proxy:+${no_proxy},}localhost,127.0.0.1,::1"

fail() {
    echo "FAIL: $1" >&2
    for log_file in \
            "$POSTGRES_LOG" \
            "$EMAIL_STUB_LOG" \
            "$BACKEND_LOG" \
            "$FRONTEND_LOG" \
            "$RESOURCE_LOG"; do
        if [ -s "$log_file" ]; then
            echo "Last lines from $(basename "$log_file"):" >&2
            tail -60 "$log_file" >&2
        fi
    done
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
    for _ in $(seq 1 50); do
        if ! kill -0 "$process_id" >/dev/null 2>&1; then
            break
        fi
        sleep 0.1
    done
    if kill -0 "$process_id" >/dev/null 2>&1; then
        kill -KILL "$process_id" >/dev/null 2>&1 || true
    fi
    wait "$process_id" >/dev/null 2>&1 || true
}

cleanup() {
    local exit_code=$?
    trap - EXIT INT TERM
    set +e
    {
        for process_id in \
                "$FRONTEND_PID" \
                "$RESOURCE_PID" \
                "$BACKEND_PID" \
                "$EMAIL_STUB_PID" \
                "$POSTGRES_PID"; do
            if [ -n "$process_id" ]; then
                terminate_process_tree "$process_id"
            fi
        done
        wait || true
        docker rm -f "$POSTGRES_CONTAINER_NAME" || true
        rm -rf "$TEMP_DIR"
    } >/dev/null 2>&1
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

for command_name in curl docker mvn node npm pg_isready pgrep; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        fail "required command is unavailable: ${command_name}"
    fi
done
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
    fail "configured Python is unavailable: ${PYTHON_BIN}"
fi
if ! docker info >/dev/null 2>&1; then
    fail "Docker is unavailable"
fi

allocate_port() {
    "$PYTHON_BIN" - <<'PY'
import socket

with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

POSTGRES_PORT="$(allocate_port)"
EMAIL_STUB_PORT="$(allocate_port)"
BACKEND_PORT="$(allocate_port)"
FRONTEND_PORT="$(allocate_port)"
RESOURCE_PORT="$(allocate_port)"
BACKEND_URL="http://127.0.0.1:${BACKEND_PORT}"
FRONTEND_URL="http://127.0.0.1:${FRONTEND_PORT}"
EMAIL_STUB_URL="http://127.0.0.1:${EMAIL_STUB_PORT}"
RESOURCE_URL="http://localhost:${RESOURCE_PORT}"

wait_for_http() {
    local name="$1"
    local url="$2"
    shift 2

    for _ in $(seq 1 150); do
        if curl -fsS "$@" "$url" >/dev/null 2>&1; then
            return
        fi
        sleep 0.2
    done
    fail "${name} did not become ready"
}

echo "Email browser E2E 1/6: start disposable PostgreSQL"
E2E_POSTGRES_CONTAINER_NAME="$POSTGRES_CONTAINER_NAME" \
POSTGRES_PORT="$POSTGRES_PORT" \
POSTGRES_DATABASE="$POSTGRES_DATABASE" \
POSTGRES_USER="$POSTGRES_USER" \
POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
    "$PROJECT_DIR/scripts/email-login-e2e/start-postgres.sh" \
    >"$POSTGRES_LOG" 2>&1 &
POSTGRES_PID=$!
for _ in $(seq 1 60); do
    if PGPASSWORD="$POSTGRES_PASSWORD" pg_isready \
            -h 127.0.0.1 \
            -p "$POSTGRES_PORT" \
            -U "$POSTGRES_USER" \
            -d "$POSTGRES_DATABASE" >/dev/null 2>&1; then
        break
    fi
    sleep 0.2
done
PGPASSWORD="$POSTGRES_PASSWORD" pg_isready \
    -h 127.0.0.1 \
    -p "$POSTGRES_PORT" \
    -U "$POSTGRES_USER" \
    -d "$POSTGRES_DATABASE" >/dev/null 2>&1 \
    || fail "PostgreSQL did not become ready"

echo "Email browser E2E 2/6: start no-delivery email stub"
PYTHON_BIN="$PYTHON_BIN" \
EMAIL_STUB_PORT="$EMAIL_STUB_PORT" \
EMAIL_STUB_API_KEY="$EMAIL_STUB_API_KEY" \
EMAIL_STUB_CAPTURE_FILE="$EMAIL_CAPTURE_FILE" \
    "$PROJECT_DIR/scripts/email-login-e2e/start-email-stub.sh" \
    >"$EMAIL_STUB_LOG" 2>&1 &
EMAIL_STUB_PID=$!
wait_for_http \
    "email stub" \
    "$EMAIL_STUB_URL/api/email/health" \
    -H "X-Email-Service-Key: $EMAIL_STUB_API_KEY"

echo "Email browser E2E 3/6: start real UniAuth backend"
SPRING_PROFILES_ACTIVE=test \
SPRING_FLYWAY_ENABLED=true \
POSTGRES_HOST=127.0.0.1 \
POSTGRES_PORT="$POSTGRES_PORT" \
POSTGRES_DATABASE="$POSTGRES_DATABASE" \
POSTGRES_USER="$POSTGRES_USER" \
POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
SERVER_PORT="$BACKEND_PORT" \
JWT_RSA_KEY_FILE="$JWT_RSA_KEY_FILE" \
GOOGLE_CLIENT_ID=e2e-google \
GOOGLE_CLIENT_SECRET=e2e-google-secret \
GITHUB_CLIENT_ID=e2e-github \
GITHUB_CLIENT_SECRET=e2e-github-secret \
TWITTER_CLIENT_ID=e2e-x \
TWITTER_CLIENT_SECRET=e2e-x-secret \
APP_DEMO_DATA_ENABLED=false \
APP_DEMO_DATA_DISPOSABLE=false \
APP_FRONTEND_URL="$FRONTEND_URL" \
APP_WEB3_DOMAIN="127.0.0.1:${BACKEND_PORT}" \
EMAIL_SERVICE_URL="$EMAIL_STUB_URL" \
EMAIL_SERVICE_API_KEY="$EMAIL_STUB_API_KEY" \
EMAIL_DELIVERY_WORKER_ENABLED=true \
APP_EMAIL_DELIVERY_WORKER_ENABLED=true \
EMAIL_DELIVERY_WORKER_DELAY_MS=100 \
APP_EMAIL_DELIVERY_WORKER_DELAY_MS=100 \
EMAIL_DELIVERY_BASE_RETRY_SECONDS=1 \
APP_EMAIL_DELIVERY_BASE_RETRY_SECONDS=1 \
EMAIL_DELIVERY_PROCESSING_TIMEOUT_SECONDS=1 \
APP_EMAIL_DELIVERY_PROCESSING_TIMEOUT_SECONDS=1 \
    "$PROJECT_DIR/scripts/email-login-e2e/start-uniauth-backend.sh" \
    >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
wait_for_http "UniAuth backend" "$BACKEND_URL/oauth2/jwks"

echo "Email browser E2E 4/6: start real Python resource API"
PYTHON_BIN="$PYTHON_BIN" \
AUTH_SERVER_URL="$BACKEND_URL" \
JWKS_URL="$BACKEND_URL/oauth2/jwks" \
JWT_ISSUER=https://auth.example.com \
JWT_AUDIENCE=resource-server \
RESOURCE_SERVER_PORT="$RESOURCE_PORT" \
CORS_ALLOWED_ORIGINS="$FRONTEND_URL" \
FLASK_DEBUG=false \
    "$PROJECT_DIR/scripts/email-login-e2e/start-resource-server.sh" \
    >"$RESOURCE_LOG" 2>&1 &
RESOURCE_PID=$!
wait_for_http "Python resource server" "$RESOURCE_URL/health"

echo "Email browser E2E 5/6: start real Vite frontend"
FRONTEND_PORT="$FRONTEND_PORT" \
VITE_DEV_PROXY_TARGET="$BACKEND_URL" \
VITE_RESOURCE_SERVER_URL="$RESOURCE_URL" \
VITE_AUTH_DIAGNOSTICS=true \
    "$PROJECT_DIR/scripts/email-login-e2e/start-frontend.sh" \
    >"$FRONTEND_LOG" 2>&1 &
FRONTEND_PID=$!
wait_for_http "Vite frontend" "$FRONTEND_URL/login"

echo "Email browser E2E 6/6: run Playwright registration/login/resource flow"
if ! (
        cd "$PROJECT_DIR/frontend"
        EMAIL_LOGIN_E2E_FRONTEND_URL="$FRONTEND_URL" \
        EMAIL_LOGIN_E2E_RESOURCE_URL="$RESOURCE_URL" \
        EMAIL_LOGIN_E2E_CAPTURE_FILE="$EMAIL_CAPTURE_FILE" \
        EMAIL_LOGIN_E2E_EMAIL="$EMAIL" \
        EMAIL_LOGIN_E2E_PASSWORD="$PASSWORD" \
            npx playwright test --config playwright.email-login.config.ts
    ); then
    fail "Playwright email registration/login/resource flow failed"
fi

echo "PASS: real email registration/login browser E2E completed"
