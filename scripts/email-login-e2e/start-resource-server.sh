#!/usr/bin/env bash

# Start the real Python resource REST API with explicit UniAuth/JWKS settings.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"

for variable_name in \
        AUTH_SERVER_URL \
        JWKS_URL \
        RESOURCE_SERVER_PORT \
        CORS_ALLOWED_ORIGINS; do
    if [ -z "${!variable_name:-}" ]; then
        echo "ERROR: required environment variable is missing: ${variable_name}" >&2
        exit 1
    fi
done

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
    echo "ERROR: configured Python is unavailable: ${PYTHON_BIN}" >&2
    exit 1
fi

cd "$PROJECT_DIR/python-resource-server"
exec "$PYTHON_BIN" app.py

