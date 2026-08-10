#!/usr/bin/env bash

set -euo pipefail

SOURCE_PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPOSITORY_ROOT="$(cd "$SOURCE_PROJECT_DIR/../.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/email-verification.XXXXXX")"
PROJECT_DIR="$TEMP_DIR/project"
APPLICATION_JAR="$TEMP_DIR/email-service-1.0.0.jar"
UNIAUTH_MIGRATIONS_DIR="$TEMP_DIR/uniauth-migrations"
RUN_ID="email-$(date -u +%Y%m%dT%H%M%SZ)-$$"
EMAIL_SERVICE_VERIFICATION_ARTIFACTS_DIR="${EMAIL_SERVICE_VERIFICATION_ARTIFACTS_DIR:-}"
EMAIL_SERVICE_VERIFICATION_ARTIFACTS_ENABLED=false
EMAIL_SERVICE_VERIFICATION_ARTIFACTS_SUCCESS_PRESERVED=false
export TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}"

source "$REPOSITORY_ROOT/scripts/verification-artifacts-guard.sh"

source_fingerprint() {
    (
        cd "$REPOSITORY_ROOT"
        git ls-files -co --exclude-standard -z -- \
            reference/email-service \
            scripts/check-dependency-audit-report.py \
            scripts/check-dependency-suppressions.py \
            src/main/resources/db/migration/postgresql \
            | sort -z \
            | xargs -0 shasum -a 256 \
            | shasum -a 256 \
            | awk '{print $1}'
    )
}

preserve_verification_artifacts() {
    local exit_code="$1"
    local artifacts_run_dir

    if [ "$EMAIL_SERVICE_VERIFICATION_ARTIFACTS_ENABLED" != "true" ]; then
        return
    fi

    artifacts_run_dir="$EMAIL_SERVICE_VERIFICATION_ARTIFACTS_DIR/$RUN_ID"
    mkdir -p "$artifacts_run_dir" || return 1
    if [ -d "$PROJECT_DIR/target/surefire-reports" ]; then
        rsync -a "$PROJECT_DIR/target/surefire-reports" "$artifacts_run_dir/" \
            || return 1
    fi
    for report_name in dependency-check-report.html dependency-check-report.json; do
        if [ -f "$PROJECT_DIR/target/$report_name" ]; then
            rsync -a "$PROJECT_DIR/target/$report_name" "$artifacts_run_dir/" \
                || return 1
        fi
    done
    {
        printf 'exit_code=%s\n' "$exit_code"
        printf 'source_head=%s\n' \
            "$(git -C "$REPOSITORY_ROOT" rev-parse HEAD 2>/dev/null || printf unavailable)"
        printf 'source_fingerprint=%s\n' "${SOURCE_FINGERPRINT:-unavailable}"
    } >"$artifacts_run_dir/verification-status.txt" || return 1
    echo "Email verification artifacts: $artifacts_run_dir"
}

cleanup() {
    local exit_code="$1"
    trap - EXIT INT TERM
    set +e
    if verification_artifacts_need_preservation \
            "$EMAIL_SERVICE_VERIFICATION_ARTIFACTS_ENABLED" \
            "$EMAIL_SERVICE_VERIFICATION_ARTIFACTS_SUCCESS_PRESERVED" \
            "$exit_code"; then
        if ! preserve_verification_artifacts "$exit_code"; then
            echo "ERROR: failed to preserve email verification artifacts" >&2
            if [ "$exit_code" -eq 0 ]; then
                exit_code=1
            fi
        fi
    fi
    rm -rf "$TEMP_DIR"
    exit "$exit_code"
}
trap 'cleanup $?' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for command_name in awk bash curl docker git java jq mvn pg_isready psql \
        python3 rsync shasum; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "ERROR: required command is unavailable: $command_name" >&2
        exit 1
    fi
done
if [ -n "$EMAIL_SERVICE_VERIFICATION_ARTIFACTS_DIR" ]; then
    if ! resolved_artifacts_dir="$(
        validate_verification_artifacts_dir \
            python3 \
            "$EMAIL_SERVICE_VERIFICATION_ARTIFACTS_DIR" \
            "$REPOSITORY_ROOT"
    )"; then
        exit 1
    fi
    EMAIL_SERVICE_VERIFICATION_ARTIFACTS_DIR="$resolved_artifacts_dir"
    EMAIL_SERVICE_VERIFICATION_ARTIFACTS_ENABLED=true
fi
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
mkdir -p "$UNIAUTH_MIGRATIONS_DIR"
rsync -a \
    "$REPOSITORY_ROOT/src/main/resources/db/migration/postgresql/" \
    "$UNIAUTH_MIGRATIONS_DIR/"
mkdir -p "$TEMP_DIR/verification-tools"
rsync -a \
    "$REPOSITORY_ROOT/scripts/check-dependency-audit-report.py" \
    "$REPOSITORY_ROOT/scripts/check-dependency-suppressions.py" \
    "$TEMP_DIR/verification-tools/"
if [ "$(source_fingerprint)" != "$SOURCE_FINGERPRINT" ]; then
    echo "ERROR: email service sources changed while creating the verification snapshot; rerun the gate" >&2
    exit 1
fi

echo "Email verification 1/7: shell syntax"
bash -n "$PROJECT_DIR/start.sh" "$PROJECT_DIR"/scripts/*.sh

echo "Email verification 2/7: compilation and ApplicationContext tests"
(
    cd "$PROJECT_DIR"
    mvn clean compile test-compile
    mvn -Duniauth.migrations.dir="$UNIAUTH_MIGRATIONS_DIR" test
    mvn -DskipTests package
)
cp "$PROJECT_DIR/target/email-service-1.0.0.jar" "$APPLICATION_JAR"

echo "Email verification 3/7: Maven dependency audit"
python3 "$TEMP_DIR/verification-tools/check-dependency-suppressions.py" \
    "$PROJECT_DIR/config/dependency-check-suppressions.xml"
(
    cd "$PROJECT_DIR"
    mvn -DskipTests dependency-check:check
)
python3 "$TEMP_DIR/verification-tools/check-dependency-audit-report.py" \
    "$PROJECT_DIR/target/dependency-check-report.json"
[ -s "$PROJECT_DIR/target/dependency-check-report.html" ] \
    || { echo "ERROR: email dependency audit HTML report is missing" >&2; exit 1; }

echo "Email verification 4/7: runtime guard matrix"
"$PROJECT_DIR/scripts/test-runtime-guard.sh"

echo "Email verification 5/7: real HTTP/PostgreSQL process E2E"
EMAIL_SERVICE_SKIP_BUILD=true \
    EMAIL_SERVICE_JAR_PATH="$APPLICATION_JAR" \
    "$PROJECT_DIR/scripts/test-http-e2e.sh"

echo "Email verification 6/7: Flyway fail-closed guard"
EMAIL_SERVICE_SKIP_BUILD=true \
    EMAIL_SERVICE_JAR_PATH="$APPLICATION_JAR" \
    "$PROJECT_DIR/scripts/test-flyway-baseline-guard.sh"

echo "Email verification 7/7: PostgreSQL backup/restore rehearsal"
EMAIL_SERVICE_SKIP_BUILD=true \
    EMAIL_SERVICE_JAR_PATH="$APPLICATION_JAR" \
    "$PROJECT_DIR/scripts/test-backup-restore-rehearsal.sh"

git -C "$REPOSITORY_ROOT" diff --check -- reference/email-service
if [ "$(source_fingerprint)" != "$SOURCE_FINGERPRINT" ]; then
    echo "ERROR: email service sources changed during verification; rerun the gate" >&2
    exit 1
fi
if [ "$EMAIL_SERVICE_VERIFICATION_ARTIFACTS_ENABLED" = "true" ]; then
    if ! preserve_verification_artifacts 0; then
        echo "ERROR: failed to preserve successful email verification artifacts" >&2
        exit 1
    fi
    EMAIL_SERVICE_VERIFICATION_ARTIFACTS_SUCCESS_PRESERVED=true
fi
echo "PASS: email service verification gate"
