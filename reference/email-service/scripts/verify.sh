#!/usr/bin/env bash

set -euo pipefail

SOURCE_PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPOSITORY_ROOT="$(cd "$SOURCE_PROJECT_DIR/../.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/email-verification.XXXXXX")"
PROJECT_DIR="$TEMP_DIR/project"
APPLICATION_JAR="$TEMP_DIR/email-service-1.0.0.jar"
export TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}"

source_fingerprint() {
    (
        cd "$REPOSITORY_ROOT"
        git ls-files -co --exclude-standard -z -- reference/email-service \
            | sort -z \
            | xargs -0 shasum -a 256 \
            | shasum -a 256 \
            | awk '{print $1}'
    )
}

cleanup() {
    local exit_code=$?
    rm -rf "$TEMP_DIR"
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

for command_name in awk bash curl docker git java jq mvn pg_isready psql \
        python3 rsync shasum; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "ERROR: required command is unavailable: $command_name" >&2
        exit 1
    fi
done
if ! docker info >/dev/null 2>&1; then
    echo "ERROR: Docker is unavailable" >&2
    exit 1
fi

SOURCE_FINGERPRINT="$(source_fingerprint)"
mkdir -p "$PROJECT_DIR"
rsync -a \
    --exclude '/.env' \
    --exclude '/target/' \
    "$SOURCE_PROJECT_DIR/" \
    "$PROJECT_DIR/"

echo "Email verification 1/5: shell syntax"
bash -n "$PROJECT_DIR/start.sh" "$PROJECT_DIR"/scripts/*.sh

echo "Email verification 2/5: compilation and ApplicationContext tests"
(
    cd "$PROJECT_DIR"
    mvn clean compile test-compile
    mvn test
    mvn -DskipTests package
)
cp "$PROJECT_DIR/target/email-service-1.0.0.jar" "$APPLICATION_JAR"

echo "Email verification 3/5: runtime guard matrix"
"$PROJECT_DIR/scripts/test-runtime-guard.sh"

echo "Email verification 4/5: real HTTP/PostgreSQL process E2E"
EMAIL_SERVICE_SKIP_BUILD=true \
    EMAIL_SERVICE_JAR_PATH="$APPLICATION_JAR" \
    "$PROJECT_DIR/scripts/test-http-e2e.sh"

echo "Email verification 5/5: Flyway fail-closed guard"
EMAIL_SERVICE_SKIP_BUILD=true \
    EMAIL_SERVICE_JAR_PATH="$APPLICATION_JAR" \
    "$PROJECT_DIR/scripts/test-flyway-baseline-guard.sh"

git -C "$REPOSITORY_ROOT" diff --check -- reference/email-service
if [ "$(source_fingerprint)" != "$SOURCE_FINGERPRINT" ]; then
    echo "ERROR: email service sources changed during verification; rerun the gate" >&2
    exit 1
fi
echo "PASS: email service verification gate"
