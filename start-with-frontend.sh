#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=scripts/runtime-guard.sh
source "$PROJECT_DIR/scripts/runtime-guard.sh"

if ! command -v java >/dev/null 2>&1; then
    echo "Error: Java 17+ is required" >&2
    exit 1
fi
if ! command -v mvn >/dev/null 2>&1; then
    echo "Error: Maven is required" >&2
    exit 1
fi

uniauth_require_oauth_credentials
uniauth_prepare_runtime "$PROJECT_DIR"

echo "Building frontend..."
(cd "$PROJECT_DIR" && ./build-frontend.sh)

echo "Compiling backend..."
(cd "$PROJECT_DIR" && mvn clean compile -q)

echo "Starting integrated frontend/backend at http://localhost:${SERVER_PORT:-8081}"
echo "Press Ctrl+C to stop"
(cd "$PROJECT_DIR" && mvn spring-boot:run)
