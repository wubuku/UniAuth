#!/usr/bin/env bash

# Start one disposable PostgreSQL 16 container for the browser email-login E2E.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/postgres-readiness.sh
source "$SCRIPT_DIR/../lib/postgres-readiness.sh"

for variable_name in \
        E2E_POSTGRES_CONTAINER_NAME \
        POSTGRES_PORT \
        POSTGRES_DATABASE \
        POSTGRES_USER \
        POSTGRES_PASSWORD; do
    if [ -z "${!variable_name:-}" ]; then
        echo "ERROR: required environment variable is missing: ${variable_name}" >&2
        exit 1
    fi
done

case "$POSTGRES_DATABASE" in
    test|test_*|test-*|*_test|*-test|demo|demo_*|demo-*|*_demo|*-demo)
        ;;
    *)
        echo "ERROR: POSTGRES_DATABASE must be explicitly disposable" >&2
        exit 1
        ;;
esac

for command_name in docker pg_isready; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "ERROR: required command is unavailable: ${command_name}" >&2
        exit 1
    fi
done

cleanup() {
    docker rm -f "$E2E_POSTGRES_CONTAINER_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

# The container is not started with --rm so a crashed PostgreSQL still has logs
# to inspect; the EXIT trap removes it in every path.
docker run -d \
    --name "$E2E_POSTGRES_CONTAINER_NAME" \
    -e "POSTGRES_DB=$POSTGRES_DATABASE" \
    -e "POSTGRES_USER=$POSTGRES_USER" \
    -e "POSTGRES_PASSWORD=$POSTGRES_PASSWORD" \
    -p "127.0.0.1:${POSTGRES_PORT}:5432" \
    postgres:16.13 >/dev/null

if ! wait_for_disposable_postgres \
        "$E2E_POSTGRES_CONTAINER_NAME" \
        "$POSTGRES_PORT" \
        "$POSTGRES_USER" \
        "$POSTGRES_PASSWORD" \
        "$POSTGRES_DATABASE"; then
    echo "ERROR: PostgreSQL did not become ready" >&2
    exit 1
fi

while docker inspect "$E2E_POSTGRES_CONTAINER_NAME" >/dev/null 2>&1; do
    sleep 1
done

