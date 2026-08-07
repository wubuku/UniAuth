#!/usr/bin/env bash

# End-to-end guard checks for adopting an existing PostgreSQL schema into Flyway.
# Every source and rehearsal database is disposable; apply is never allowed to
# reach a shared database.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASELINE_SCRIPT="$PROJECT_DIR/scripts/flyway-baseline-existing.sh"
MIGRATION_FILE="$PROJECT_DIR/src/main/resources/db/migration/postgresql/V1__baseline_uniauth_auth_schema.sql"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/uniauth-baseline-guard.XXXXXX")"
RUN_ID="$(date +%s)-$$"
SOURCE_CONTAINER="uniauth-baseline-source-${RUN_ID}"
SOURCE_USER="uniauth"
SOURCE_PASSWORD="baseline-guard-${RUN_ID}"
SOURCE_PORT=""

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

cleanup() {
    local exit_code=$?
    set +e
    if docker ps -a --format '{{.Names}}' 2>/dev/null \
        | grep -Fxq "$SOURCE_CONTAINER"; then
        docker rm -f "$SOURCE_CONTAINER" >/dev/null 2>&1 || true
    fi
    rm -rf "$TEMP_DIR"
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

for command_name in docker grep mvn pg_dump pg_isready psql; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        fail "required command is unavailable: $command_name"
    fi
done
if ! docker info >/dev/null 2>&1; then
    fail "Docker is unavailable"
fi

container_port() {
    docker port "$1" 5432/tcp | awk -F: 'NR == 1 {print $NF}'
}

wait_for_postgres() {
    local port="$1"
    for _ in $(seq 1 60); do
        if PGPASSWORD="$SOURCE_PASSWORD" pg_isready \
            -h 127.0.0.1 \
            -p "$port" \
            -U "$SOURCE_USER" \
            -d postgres >/dev/null 2>&1; then
            return
        fi
        sleep 1
    done
    fail "PostgreSQL on port $port did not become ready"
}

source_psql() {
    local port="$1"
    local database="$2"
    shift 2
    PGPASSWORD="$SOURCE_PASSWORD" psql \
        -X -q -v ON_ERROR_STOP=1 \
        -h 127.0.0.1 \
        -p "$port" \
        -U "$SOURCE_USER" \
        -d "$database" \
        "$@"
}

create_source_database() {
    local database="$1"
    source_psql "$SOURCE_PORT" postgres \
        -c "CREATE DATABASE \"$database\";" >/dev/null
    source_psql "$SOURCE_PORT" "$database" \
        -f "$MIGRATION_FILE" >/dev/null
}

run_guard() {
    local database="$1"
    local mode="$2"
    local artifact_name="$3"
    shift 3
    env \
        POSTGRES_HOST=127.0.0.1 \
        POSTGRES_PORT="$SOURCE_PORT" \
        POSTGRES_DATABASE="$database" \
        POSTGRES_USER="$SOURCE_USER" \
        POSTGRES_PASSWORD="$SOURCE_PASSWORD" \
        UNIAUTH_BASELINE_ARTIFACT_DIR="$TEMP_DIR/artifacts/$artifact_name" \
        "$@" \
        "$BASELINE_SCRIPT" "$mode"
}

expect_guard_failure() {
    local database="$1"
    local mode="$2"
    local artifact_name="$3"
    local expected_message="$4"
    shift 4
    local output_file="$TEMP_DIR/${artifact_name}.log"

    if run_guard "$database" "$mode" "$artifact_name" "$@" \
        >"$output_file" 2>&1; then
        fail "$artifact_name unexpectedly passed"
    fi
    if [ -n "$expected_message" ]; then
        grep -Fq "$expected_message" "$output_file" \
            || fail "$artifact_name did not report the expected guard failure"
    fi
}

echo "Flyway baseline guard E2E: starting PostgreSQL 16 source"
docker run -d --rm \
    --name "$SOURCE_CONTAINER" \
    -e "POSTGRES_USER=$SOURCE_USER" \
    -e "POSTGRES_PASSWORD=$SOURCE_PASSWORD" \
    -p 127.0.0.1::5432 \
    postgres:16 >/dev/null
SOURCE_PORT="$(container_port "$SOURCE_CONTAINER")"
wait_for_postgres "$SOURCE_PORT"

for database in \
    baseline_valid_test \
    baseline_missing_test \
    baseline_extra_test \
    baseline_history_test \
    baseline_apply_test \
    baseline_cleanup_test; do
    create_source_database "$database"
done

echo "1/7 Accept an exact approved schema in rehearsal mode"
valid_output="$TEMP_DIR/valid.log"
run_guard baseline_valid_test rehearse valid >"$valid_output" 2>&1
grep -Fq "Flyway baseline rehearsal passed." "$valid_output" \
    || fail "exact approved schema did not complete rehearsal"
grep -Fq "Apply confirmation token: baseline:baseline_valid_test:" "$valid_output" \
    || fail "rehearsal did not emit a database-bound confirmation token"

echo "2/7 Reject a source missing one managed table"
source_psql "$SOURCE_PORT" baseline_missing_test \
    -c "DROP TABLE token_blacklist;" >/dev/null
expect_guard_failure \
    baseline_missing_test \
    rehearse \
    missing-table \
    "source database does not contain all eight approved UniAuth tables"

echo "3/7 Reject additional structure inside a managed auth table"
source_psql "$SOURCE_PORT" baseline_extra_test \
    -c "ALTER TABLE users ADD COLUMN unexpected_auth_state text;" >/dev/null
expect_guard_failure \
    baseline_extra_test \
    rehearse \
    extra-auth-structure \
    "baselined and fresh migrated schema fingerprints differ"

echo "4/7 Reject a source that already has Flyway history"
source_psql "$SOURCE_PORT" baseline_history_test \
    -c "CREATE TABLE uniauth_flyway_schema_history (installed_rank integer);" >/dev/null
expect_guard_failure \
    baseline_history_test \
    rehearse \
    existing-history \
    "a Flyway history table already exists"

echo "5/7 Reject PostgreSQL versions outside the approved major"
fake_version_bin="$TEMP_DIR/fake-version-bin"
real_psql="$(command -v psql)"
mkdir -p "$fake_version_bin"
cat >"$fake_version_bin/psql" <<'EOF'
#!/usr/bin/env bash
for argument in "$@"; do
    if [ "$argument" = "SHOW server_version_num;" ]; then
        printf '150000\n'
        exit 0
    fi
done
exec "$UNIAUTH_REAL_PSQL" "$@"
EOF
chmod 700 "$fake_version_bin/psql"
expect_guard_failure \
    baseline_valid_test \
    rehearse \
    old-major \
    "approved baseline was verified on PostgreSQL 16.x" \
    PATH="$fake_version_bin:$PATH" \
    UNIAUTH_REAL_PSQL="$real_psql"

echo "6/7 Require the exact confirmation token before apply"
expect_guard_failure \
    baseline_apply_test \
    apply \
    apply-without-confirmation \
    "UNIAUTH_BASELINE_CONFIRM does not match the rehearsal token"
[ "$(source_psql "$SOURCE_PORT" baseline_apply_test -qAt \
    -c "SELECT to_regclass('public.uniauth_flyway_schema_history') IS NULL;")" = "t" ] \
    || fail "apply without confirmation created Flyway history"

echo "7/7 Remove temporary Flyway credentials after Maven failure"
fake_bin="$TEMP_DIR/fake-bin"
capture_file="$TEMP_DIR/flyway-config-path.txt"
mkdir -p "$fake_bin"
cat >"$fake_bin/mvn" <<'EOF'
#!/usr/bin/env bash
for argument in "$@"; do
    case "$argument" in
        -Dflyway.configFiles=*)
            printf '%s\n' "${argument#*=}" > "$UNIAUTH_FAKE_MVN_CAPTURE"
            ;;
    esac
done
exit 42
EOF
chmod 700 "$fake_bin/mvn"
expect_guard_failure \
    baseline_cleanup_test \
    rehearse \
    maven-failure-cleanup \
    "" \
    PATH="$fake_bin:$PATH" \
    UNIAUTH_FAKE_MVN_CAPTURE="$capture_file"
[ -s "$capture_file" ] || fail "the Maven failure fixture did not capture a config path"
flyway_config_path="$(head -1 "$capture_file")"
[ ! -e "$flyway_config_path" ] \
    || fail "temporary Flyway credentials remained after Maven failure"

echo "PASS: Flyway baseline guard end-to-end checks completed"
