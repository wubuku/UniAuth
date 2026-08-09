#!/usr/bin/env bash

# Start the no-delivery email REST stub and capture accepted messages locally.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"

for variable_name in EMAIL_STUB_PORT EMAIL_STUB_API_KEY EMAIL_STUB_CAPTURE_FILE; do
    if [ -z "${!variable_name:-}" ]; then
        echo "ERROR: required environment variable is missing: ${variable_name}" >&2
        exit 1
    fi
done

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
    echo "ERROR: configured Python is unavailable: ${PYTHON_BIN}" >&2
    exit 1
fi

exec "$PYTHON_BIN" "$PROJECT_DIR/scripts/email_service_stub.py" \
    --host 127.0.0.1 \
    --port "$EMAIL_STUB_PORT" \
    --capture-file "$EMAIL_STUB_CAPTURE_FILE"

