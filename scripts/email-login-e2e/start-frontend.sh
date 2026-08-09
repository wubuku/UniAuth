#!/usr/bin/env bash

# Start the real Vite frontend with explicit backend and resource API targets.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

for variable_name in \
        FRONTEND_PORT \
        VITE_DEV_PROXY_TARGET \
        VITE_RESOURCE_SERVER_URL; do
    if [ -z "${!variable_name:-}" ]; then
        echo "ERROR: required environment variable is missing: ${variable_name}" >&2
        exit 1
    fi
done

for command_name in node npm; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "ERROR: required command is unavailable: ${command_name}" >&2
        exit 1
    fi
done

cd "$PROJECT_DIR/frontend"
exec npm run dev -- \
    --host 127.0.0.1 \
    --port "$FRONTEND_PORT" \
    --strictPort

