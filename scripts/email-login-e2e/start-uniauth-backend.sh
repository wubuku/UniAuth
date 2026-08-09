#!/usr/bin/env bash

# Start the real UniAuth Spring application against an explicit disposable DB.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# shellcheck source=../runtime-guard.sh
source "$PROJECT_DIR/scripts/runtime-guard.sh"

if ! command -v mvn >/dev/null 2>&1; then
    echo "ERROR: Maven is unavailable" >&2
    exit 1
fi

uniauth_require_oauth_credentials
uniauth_prepare_runtime "$PROJECT_DIR"
uniauth_require_env EMAIL_SERVICE_URL
uniauth_require_env EMAIL_SERVICE_API_KEY
uniauth_require_env JWT_RSA_KEY_FILE
uniauth_require_env SERVER_PORT

cd "$PROJECT_DIR"
exec mvn -q -DskipTests spring-boot:run

