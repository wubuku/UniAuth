#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_CLIENT_SECRET_FILE="$PROJECT_DIR/../docs/client_secret_864964610919-fe6l31cv6ervqflfjpd9ov9sun9olqa7.apps.googleusercontent.com.json"

# shellcheck source=scripts/runtime-guard.sh
source "$PROJECT_DIR/scripts/runtime-guard.sh"

echo "=== UniAuth OAuth2 demo startup ==="

if [ -z "${GOOGLE_CLIENT_ID:-}" ] || [ -z "${GOOGLE_CLIENT_SECRET:-}" ]; then
    CLIENT_SECRET_FILE="${GOOGLE_CLIENT_SECRET_FILE:-$DEFAULT_CLIENT_SECRET_FILE}"
    if [ ! -f "$CLIENT_SECRET_FILE" ]; then
        echo "Error: Google OAuth2 credentials are not set and no client JSON was found" >&2
        exit 1
    fi
    if ! command -v python3 >/dev/null 2>&1; then
        echo "Error: python3 is required to parse the Google client JSON" >&2
        exit 1
    fi

    export GOOGLE_CLIENT_ID="$(
        python3 -c 'import json,sys; data=json.load(open(sys.argv[1])); cfg=data.get("web") or data.get("installed") or {}; print(cfg.get("client_id", ""))' \
            "$CLIENT_SECRET_FILE"
    )"
    export GOOGLE_CLIENT_SECRET="$(
        python3 -c 'import json,sys; data=json.load(open(sys.argv[1])); cfg=data.get("web") or data.get("installed") or {}; print(cfg.get("client_secret", ""))' \
            "$CLIENT_SECRET_FILE"
    )"
fi

uniauth_require_oauth_credentials
uniauth_prepare_runtime "$PROJECT_DIR"

echo "Compiling backend..."
(cd "$PROJECT_DIR" && mvn clean compile)

echo "Starting UniAuth at http://localhost:${SERVER_PORT:-8081}"
echo "Press Ctrl+C to stop"
(cd "$PROJECT_DIR" && mvn spring-boot:run)
