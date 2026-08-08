#!/usr/bin/env bash

# Complete repository verification gate. This intentionally uses disposable
# PostgreSQL containers and local browser/Python/SMTP test harnesses only.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmjs.org/}"
export NO_PROXY="${NO_PROXY:+${NO_PROXY},}localhost,127.0.0.1,::1"
export no_proxy="${no_proxy:+${no_proxy},}localhost,127.0.0.1,::1"

for command_name in bash docker git java mvn node npm; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "ERROR: required command is unavailable: $command_name" >&2
        exit 1
    fi
done
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
    echo "ERROR: configured Python is unavailable: $PYTHON_BIN" >&2
    exit 1
fi
if ! docker info >/dev/null 2>&1; then
    echo "ERROR: Docker is unavailable" >&2
    exit 1
fi

cd "$PROJECT_DIR"

echo "Verification 1/11: shell syntax"
bash -n \
    build-frontend.sh \
    start.sh \
    start-with-frontend.sh \
    scripts/*.sh \
    reference/email-service/start.sh \
    reference/email-service/scripts/*.sh

echo "Verification 2/11: frontend clean dependency install"
(
    cd frontend
    npm ci --registry="$NPM_REGISTRY"
)

echo "Verification 3/11: frontend dependency audit"
(
    cd frontend
    npm audit --registry="$NPM_REGISTRY" --audit-level=high
)

echo "Verification 4/11: Java compilation and test compilation"
mvn clean compile test-compile

echo "Verification 5/11: Java integration tests"
mvn test

echo "Verification 6/11: reference email-service compilation and integration tests"
reference/email-service/scripts/verify.sh

echo "Verification 7/11: HTTP and Flyway shell E2E"
scripts/test-http-e2e.sh
scripts/test-flyway-baseline-guard.sh

echo "Verification 8/11: frontend lint, typecheck, and production build"
(
    cd frontend
    npm run lint
    npx tsc --noEmit
    npm run build
)

echo "Verification 9/11: Mock Playwright"
(
    cd frontend
    npm run test:e2e
)

echo "Verification 10/11: Python resource-server contracts"
(
    cd python-resource-server
    "$PYTHON_BIN" -m unittest -v
)

echo "Verification 11/11: documentation links and patch hygiene"
"$PYTHON_BIN" .agents/skills/project-docs/scripts/check_relative_links.py \
    README.md \
    AGENTS.md \
    docs \
    frontend/README.md \
    python-resource-server/README.md \
    reference/email-service \
    .agents/skills/project-docs
git diff --check

echo "PASS: complete repository verification gate"
