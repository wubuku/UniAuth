#!/usr/bin/env bash
# Fast feedback for day-to-day refactoring.
#
# This is intentionally NOT a delivery gate. It runs only the layers that give
# quick signal on a local change:
#   1. backend compile and test-compile
#   2. backend Surefire tests (unit + PostgreSQL integration, no E2E harnesses)
#   3. frontend ESLint and TypeScript
#   4. frontend Vitest component tests (no coverage)
#
# It never starts the real backend/Vite, Playwright, Shell HTTP E2E, Flyway
# guards, the email service, Python tests, supply-chain audits, sensitive
# scanning or documentation checks. Run `scripts/verify.sh` before delivery.
#
# Usage:
#   PYTHON_BIN=python3 scripts/verify-fast.sh
#
# `PYTHON_BIN` is accepted for parity with the full gate so CI and developers
# can use the same environment; this script does not use Python.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if ! command -v mvn >/dev/null 2>&1; then
    echo "ERROR: mvn is required for the fast gate" >&2
    exit 1
fi
if ! command -v npm >/dev/null 2>&1; then
    echo "ERROR: npm is required for the fast gate" >&2
    exit 1
fi
if [ ! -d "$REPO_ROOT/frontend/node_modules" ]; then
    echo "ERROR: frontend/node_modules is missing; run 'npm ci' in frontend first" >&2
    exit 1
fi

echo "FAST 1/4: backend compile"
mvn -q clean compile test-compile

echo "FAST 2/4: backend tests"
mvn -q test

echo "FAST 3/4: frontend lint and typecheck"
(
    cd "$REPO_ROOT/frontend"
    npm run lint
    npx tsc --noEmit
)

echo "FAST 4/4: frontend unit tests"
(
    cd "$REPO_ROOT/frontend"
    npm run test:unit
)

echo "PASS: fast feedback gate"
echo "NOTE: this is not the delivery gate. Playwright, Shell/HTTP E2E, Flyway,"
echo "      email service, Python, supply-chain, sensitive scan and docs checks"
echo "      are skipped; run scripts/verify.sh before delivery."
