#!/usr/bin/env bash

# Complete repository verification gate. This intentionally uses disposable
# PostgreSQL containers and local browser/Python/SMTP test harnesses only.

set -euo pipefail

SOURCE_PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/uniauth-verification.XXXXXX")"
PROJECT_DIR="$TEMP_DIR/project"
SOURCE_FILE_LIST="$TEMP_DIR/source-files"
ROOT_SUREFIRE_REPORTS_SNAPSHOT="$TEMP_DIR/root-surefire-reports"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
VERIFICATION_ARTIFACTS_DIR="${VERIFICATION_ARTIFACTS_DIR:-}"
VERIFICATION_ARTIFACTS_ENABLED=false
VERIFICATION_ARTIFACTS_SUCCESS_PRESERVED=false
export TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmjs.org/}"
export NO_PROXY="${NO_PROXY:+${NO_PROXY},}localhost,127.0.0.1,::1"
export no_proxy="${no_proxy:+${no_proxy},}localhost,127.0.0.1,::1"

source "$SOURCE_PROJECT_DIR/scripts/verification-artifacts-guard.sh"

source_fingerprint() {
    (
        cd "$SOURCE_PROJECT_DIR"
        git rev-parse HEAD
        git diff --binary HEAD --
        while IFS= read -r -d '' path; do
            printf 'untracked:%s\0' "$path"
            shasum -a 256 "./$path"
        done < <(git ls-files --others --exclude-standard -z | sort -z)
    ) | shasum -a 256 | awk '{print $1}'
}

write_source_file_list() {
    (
        cd "$SOURCE_PROJECT_DIR"
        while IFS= read -r -d '' path; do
            if [ -e "$path" ] || [ -L "$path" ]; then
                printf '%s\0' "$path"
            fi
        done < <(git ls-files -co --exclude-standard -z | sort -z)
    )
}

preserve_verification_artifacts() {
    local exit_code="$1"
    local artifacts_run_dir
    local destination_dir
    local relative_path
    local source_path

    if [ "$VERIFICATION_ARTIFACTS_ENABLED" != "true" ]; then
        return
    fi

    artifacts_run_dir="$VERIFICATION_ARTIFACTS_DIR/$RUN_ID"
    mkdir -p "$artifacts_run_dir" || return 1
    if [ -d "$ROOT_SUREFIRE_REPORTS_SNAPSHOT" ]; then
        destination_dir="$artifacts_run_dir/target/surefire-reports"
        mkdir -p "$destination_dir" || return 1
        rsync -a "$ROOT_SUREFIRE_REPORTS_SNAPSHOT/" "$destination_dir/" \
            || return 1
    elif [ -d "$PROJECT_DIR/target/surefire-reports" ]; then
        destination_dir="$artifacts_run_dir/target/surefire-reports"
        mkdir -p "$destination_dir" || return 1
        rsync -a "$PROJECT_DIR/target/surefire-reports/" "$destination_dir/" \
            || return 1
    fi
    for relative_path in \
            frontend/test-results \
            frontend/playwright-report \
            frontend/blob-report; do
        source_path="$PROJECT_DIR/$relative_path"
        if [ ! -e "$source_path" ]; then
            continue
        fi
        destination_dir="$artifacts_run_dir/$(dirname "$relative_path")"
        mkdir -p "$destination_dir" || return 1
        rsync -a "$source_path" "$destination_dir/" || return 1
    done
    {
        printf 'exit_code=%s\n' "$exit_code"
        printf 'source_head=%s\n' \
            "$(git -C "$SOURCE_PROJECT_DIR" rev-parse HEAD 2>/dev/null || printf unavailable)"
        printf 'source_fingerprint=%s\n' "${SOURCE_FINGERPRINT:-unavailable}"
    } >"$artifacts_run_dir/verification-status.txt" || return 1
    echo "Verification artifacts: $artifacts_run_dir"
}

capture_root_surefire_reports() {
    local report_file

    report_file="$(
        find "$PROJECT_DIR/target/surefire-reports" \
            -name 'TEST-*.xml' \
            -type f \
            -print \
            -quit
    )"
    if [ -z "$report_file" ]; then
        echo "ERROR: root Surefire reports are missing after Maven tests" >&2
        return 1
    fi
    rm -rf "$ROOT_SUREFIRE_REPORTS_SNAPSHOT"
    mkdir -p "$ROOT_SUREFIRE_REPORTS_SNAPSHOT"
    rsync -a \
        "$PROJECT_DIR/target/surefire-reports/" \
        "$ROOT_SUREFIRE_REPORTS_SNAPSHOT/"
}

cleanup() {
    local exit_code="$1"
    trap - EXIT INT TERM
    set +e
    if verification_artifacts_need_preservation \
            "$VERIFICATION_ARTIFACTS_ENABLED" \
            "$VERIFICATION_ARTIFACTS_SUCCESS_PRESERVED" \
            "$exit_code"; then
        if ! preserve_verification_artifacts "$exit_code"; then
            echo "ERROR: failed to preserve verification artifacts" >&2
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

for command_name in awk bash docker find git grep java mvn node npm rsync \
        shasum sleep; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "ERROR: required command is unavailable: $command_name" >&2
        exit 1
    fi
done
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
    echo "ERROR: configured Python is unavailable: $PYTHON_BIN" >&2
    exit 1
fi
if [ -n "$VERIFICATION_ARTIFACTS_DIR" ]; then
    if ! resolved_artifacts_dir="$(
        validate_verification_artifacts_dir \
            "$PYTHON_BIN" \
            "$VERIFICATION_ARTIFACTS_DIR" \
            "$SOURCE_PROJECT_DIR"
    )"; then
        exit 1
    fi
    VERIFICATION_ARTIFACTS_DIR="$resolved_artifacts_dir"
    VERIFICATION_ARTIFACTS_ENABLED=true
fi
if ! docker info >/dev/null 2>&1; then
    echo "ERROR: Docker is unavailable" >&2
    exit 1
fi

SOURCE_FINGERPRINT="$(source_fingerprint)"
write_source_file_list >"$SOURCE_FILE_LIST"
mkdir -p "$PROJECT_DIR"
rsync -a \
    --from0 \
    --files-from="$SOURCE_FILE_LIST" \
    "$SOURCE_PROJECT_DIR/" \
    "$PROJECT_DIR/"
if [ "$(source_fingerprint)" != "$SOURCE_FINGERPRINT" ]; then
    echo "ERROR: repository sources changed while creating the verification snapshot; rerun the gate" >&2
    exit 1
fi
git -C "$PROJECT_DIR" init -q
git -C "$PROJECT_DIR" add -A
git -C "$PROJECT_DIR" \
    -c user.name="UniAuth Verification" \
    -c user.email="verification@localhost" \
    commit -qm "verification snapshot"

if [ "${UNIAUTH_VERIFICATION_SIGNAL_TEST_MODE:-false}" = "true" ]; then
    echo "Verification signal self-test ready"
    while true; do
        sleep 1
    done
fi

cd "$PROJECT_DIR"

echo "Verification 1/12: shell syntax"
bash -n \
    build-frontend.sh \
    start.sh \
    start-with-frontend.sh \
    scripts/*.sh \
    scripts/email-login-e2e/*.sh \
    reference/email-service/start.sh \
    reference/email-service/scripts/*.sh
bash scripts/test-verification-artifacts-guard.sh

echo "Verification 2/12: frontend clean dependency install"
(
    cd frontend
    npm ci --registry="$NPM_REGISTRY"
)

echo "Verification 3/12: frontend dependency audit"
(
    cd frontend
    npm audit --registry="$NPM_REGISTRY" --audit-level=high
)

echo "Verification 4/12: Java compilation and test compilation"
mvn clean compile test-compile

echo "Verification 5/12: Java integration tests"
mvn test
capture_root_surefire_reports

echo "Verification 6/12: reference email-service compilation and integration tests"
EMAIL_SERVICE_ARTIFACTS_DIR=""
if [ "$VERIFICATION_ARTIFACTS_ENABLED" = "true" ]; then
    EMAIL_SERVICE_ARTIFACTS_DIR="$VERIFICATION_ARTIFACTS_DIR/$RUN_ID/email-service"
fi
EMAIL_SERVICE_VERIFICATION_ARTIFACTS_DIR="$EMAIL_SERVICE_ARTIFACTS_DIR" \
    reference/email-service/scripts/verify.sh
if [ "$VERIFICATION_ARTIFACTS_ENABLED" = "true" ]; then
    email_status_file="$(
        find "$EMAIL_SERVICE_ARTIFACTS_DIR" \
            -name verification-status.txt \
            -type f \
            -print \
            -quit
    )"
    [ -n "$email_status_file" ] \
        || { echo "ERROR: email verification status artifact is missing" >&2; exit 1; }
    grep -Fxq 'exit_code=0' "$email_status_file" \
        || { echo "ERROR: email verification artifact did not record success" >&2; exit 1; }
    email_report_file="$(
        find "$EMAIL_SERVICE_ARTIFACTS_DIR" \
            -name 'TEST-*.xml' \
            -type f \
            -print \
            -quit
    )"
    [ -n "$email_report_file" ] \
        || { echo "ERROR: email Surefire report artifact is missing" >&2; exit 1; }
fi

echo "Verification 7/12: HTTP and Flyway shell E2E"
scripts/test-email-shared-schema-e2e.sh
scripts/test-http-e2e.sh
scripts/test-flyway-baseline-guard.sh

echo "Verification 8/12: frontend lint, typecheck, and production build"
(
    cd frontend
    npm run lint
    npx tsc --noEmit
    npm run build
)

echo "Verification 9/12: Mock Playwright"
(
    cd frontend
    PLAYWRIGHT_PORT="$(
        "$PYTHON_BIN" - <<'PY'
import socket

with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
    )"
    export PLAYWRIGHT_PORT
    npm run test:e2e
)

echo "Verification 10/12: real email-login browser E2E"
PYTHON_BIN="$PYTHON_BIN" scripts/test-email-login-browser-e2e.sh

echo "Verification 11/12: Python contracts"
"$PYTHON_BIN" scripts/test_email_service_stub.py
(
    cd python-resource-server
    "$PYTHON_BIN" -m unittest -v
)

echo "Verification 12/12: documentation links and patch hygiene"
"$PYTHON_BIN" .agents/skills/project-docs/scripts/check_relative_links.py \
    README.md \
    AGENTS.md \
    docs \
    frontend/README.md \
    python-resource-server/README.md \
    reference/email-service \
    .agents/skills/project-docs
git -C "$SOURCE_PROJECT_DIR" diff --check
if [ "$(source_fingerprint)" != "$SOURCE_FINGERPRINT" ]; then
    echo "ERROR: repository sources changed during verification; rerun the gate" >&2
    exit 1
fi

if [ "$VERIFICATION_ARTIFACTS_ENABLED" = "true" ]; then
    if ! preserve_verification_artifacts 0; then
        echo "ERROR: failed to preserve successful verification artifacts" >&2
        exit 1
    fi
    VERIFICATION_ARTIFACTS_SUCCESS_PRESERVED=true
fi
echo "PASS: complete repository verification gate"
