#!/usr/bin/env bash

# Complete repository verification gate. This intentionally uses disposable
# PostgreSQL containers and local browser/Python/SMTP test harnesses only.

set -euo pipefail

SOURCE_PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/uniauth-verification.XXXXXX")"
PROJECT_DIR="$TEMP_DIR/project"
SOURCE_FILE_LIST="$TEMP_DIR/source-files"
ROOT_SUREFIRE_REPORTS_SNAPSHOT="$TEMP_DIR/root-surefire-reports"
ROOT_DEPENDENCY_REPORTS_SNAPSHOT="$TEMP_DIR/root-dependency-reports"
FRONTEND_BUILD_DIR="src/main/resources/static"
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
    if [ -d "$ROOT_DEPENDENCY_REPORTS_SNAPSHOT" ]; then
        destination_dir="$artifacts_run_dir/target"
        mkdir -p "$destination_dir" || return 1
        rsync -a "$ROOT_DEPENDENCY_REPORTS_SNAPSHOT/" "$destination_dir/" \
            || return 1
        for report_name in \
                dependency-check-report.html \
                dependency-check-report.json; do
            [ -s "$destination_dir/$report_name" ] || return 1
        done
    elif [ "$exit_code" -eq 0 ]; then
        echo "ERROR: root dependency audit snapshot is missing" >&2
        return 1
    fi
    for relative_path in \
            target/sensitive-scan-report.json \
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
    if [ -f "$TEMP_DIR/python-supply-chain/pip-audit-report.json" ]; then
        destination_dir="$artifacts_run_dir/python-resource-server"
        mkdir -p "$destination_dir" || return 1
        rsync -a \
            "$TEMP_DIR/python-supply-chain/pip-audit-report.json" \
            "$destination_dir/" \
            || return 1
    fi
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

capture_root_dependency_reports() {
    local report_name

    rm -rf "$ROOT_DEPENDENCY_REPORTS_SNAPSHOT"
    mkdir -p "$ROOT_DEPENDENCY_REPORTS_SNAPSHOT"
    for report_name in \
            dependency-check-report.html \
            dependency-check-report.json; do
        [ -s "$PROJECT_DIR/target/$report_name" ] \
            || {
                echo "ERROR: root dependency audit report is missing: $report_name" >&2
                return 1
            }
        rsync -a \
            "$PROJECT_DIR/target/$report_name" \
            "$ROOT_DEPENDENCY_REPORTS_SNAPSHOT/" \
            || return 1
    done
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

echo "Verification 1/15: shell syntax and security guard self-tests"
bash -n \
    build-frontend.sh \
    start.sh \
    start-with-frontend.sh \
    scripts/*.sh \
    scripts/email-login-e2e/*.sh \
    reference/email-service/start.sh \
    reference/email-service/scripts/*.sh
bash scripts/test-verification-artifacts-guard.sh
bash scripts/test-supply-chain-guards.sh
bash scripts/test-sensitive-artifact-scan.sh

echo "Verification 2/15: frontend clean dependency install"
(
    cd frontend
    npm ci --registry="$NPM_REGISTRY"
)

echo "Verification 3/15: frontend dependency audit"
(
    cd frontend
    npm audit --registry="$NPM_REGISTRY" --audit-level=moderate
)

echo "Verification 4/15: Python hash locks, dependency audit, and contracts"
PYTHON_SUPPLY_CHAIN_TEMP_DIR="$TEMP_DIR/python-supply-chain" \
    scripts/verify-python-supply-chain.sh
VERIFIED_PYTHON_BIN="$(
    cat "$TEMP_DIR/python-supply-chain/runtime-python-path"
)"
[ -x "$VERIFIED_PYTHON_BIN" ] \
    || { echo "ERROR: verified Python runtime is unavailable" >&2; exit 1; }

echo "Verification 5/15: Java compilation and test compilation"
mvn clean compile test-compile

echo "Verification 6/15: root Maven dependency audit"
"$VERIFIED_PYTHON_BIN" scripts/check-dependency-suppressions.py \
    config/dependency-check-suppressions.xml
mvn -DskipTests dependency-check:check
"$VERIFIED_PYTHON_BIN" scripts/check-dependency-audit-report.py \
    target/dependency-check-report.json
[ -s target/dependency-check-report.html ] \
    || { echo "ERROR: root dependency audit HTML report is missing" >&2; exit 1; }
capture_root_dependency_reports

echo "Verification 7/15: Java integration tests"
mvn test
capture_root_surefire_reports

echo "Verification 8/15: reference email-service verification"
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
    email_dependency_report="$(
        find "$EMAIL_SERVICE_ARTIFACTS_DIR" \
            -name dependency-check-report.json \
            -type f \
            -print \
            -quit
    )"
    [ -n "$email_dependency_report" ] \
        || { echo "ERROR: email dependency audit artifact is missing" >&2; exit 1; }
fi

echo "Verification 9/15: HTTP and Flyway shell E2E"
scripts/test-email-shared-schema-e2e.sh
scripts/test-http-e2e.sh
scripts/test-flyway-baseline-guard.sh
scripts/test-auth-backup-restore-rehearsal.sh

echo "Verification 10/15: frontend lint, typecheck, and production build"
(
    cd frontend
    npm run lint
    npx tsc --noEmit
    npm run build
)
[ -f "$FRONTEND_BUILD_DIR/index.html" ] \
    || { echo "ERROR: frontend build output is missing: $FRONTEND_BUILD_DIR/index.html" >&2; exit 1; }

echo "Verification 11/15: Mock Playwright"
(
    cd frontend
    PLAYWRIGHT_PORT="$(
        "$VERIFIED_PYTHON_BIN" - <<'PY'
import socket

with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
    )"
    export PLAYWRIGHT_PORT
    npm run test:e2e
    PLAYWRIGHT_PORT="$(
        "$VERIFIED_PYTHON_BIN" - <<'PY'
import socket

with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
    )"
    export PLAYWRIGHT_PORT
    npm run test:e2e:production
)

echo "Verification 12/15: real email-login browser E2E"
PYTHON_BIN="$VERIFIED_PYTHON_BIN" scripts/test-email-login-browser-e2e.sh

echo "Verification 13/15: Python email-service stub contract"
"$VERIFIED_PYTHON_BIN" scripts/test_email_service_stub.py

echo "Verification 14/15: source and candidate-build sensitive artifact scan"
"$VERIFIED_PYTHON_BIN" scripts/scan-sensitive-artifacts.py \
    --repository . \
    --path target/classes \
    --path reference/email-service/target/classes \
    --path "$FRONTEND_BUILD_DIR" \
    --exceptions config/sensitive-scan-exceptions.json \
    --report target/sensitive-scan-report.json
"$VERIFIED_PYTHON_BIN" scripts/check-sensitive-scan-report.py \
    target/sensitive-scan-report.json

echo "Verification 15/15: documentation links and patch hygiene"
"$VERIFIED_PYTHON_BIN" .agents/skills/project-docs/scripts/check_relative_links.py \
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
