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

expect_guard_success() {
    local database="$1"
    local mode="$2"
    local artifact_name="$3"
    shift 3
    local output_file="$TEMP_DIR/${artifact_name}.log"

    if ! run_guard "$database" "$mode" "$artifact_name" "$@" \
        >"$output_file" 2>&1; then
        cat "$output_file" >&2
        fail "$artifact_name did not complete successfully"
    fi
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
        if ! grep -Fq "$expected_message" "$output_file"; then
            cat "$output_file" >&2
            fail "$artifact_name did not report the expected guard failure"
        fi
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
    baseline_invalid_login_methods_test \
    baseline_invalid_entity_schema_test \
    baseline_invalid_email_state_test \
    baseline_invalid_token_blacklist_test \
    baseline_apply_test \
    baseline_apply_data_race_test \
    baseline_apply_entity_race_test \
    baseline_apply_migration_race_test \
    baseline_cleanup_test; do
    create_source_database "$database"
done

echo "1/14 Accept an exact approved schema in rehearsal mode"
valid_output="$TEMP_DIR/valid.log"
config_capture_bin="$TEMP_DIR/config-capture-bin"
config_capture_file="$TEMP_DIR/flyway-config-paths.txt"
real_mvn="$(command -v mvn)"
mkdir -p "$config_capture_bin"
cat >"$config_capture_bin/mvn" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

for argument in "$@"; do
    case "$argument" in
        -Dflyway.configFiles=*)
            printf '%s\n' "${argument#*=}" >> "$UNIAUTH_CONFIG_CAPTURE"
            ;;
    esac
done
exec "$UNIAUTH_REAL_MVN" "$@"
EOF
chmod 700 "$config_capture_bin/mvn"
expect_guard_success \
    baseline_valid_test \
    rehearse \
    valid \
    PATH="$config_capture_bin:$PATH" \
    UNIAUTH_CONFIG_CAPTURE="$config_capture_file" \
    UNIAUTH_REAL_MVN="$real_mvn"
grep -Fq "Flyway baseline rehearsal passed." "$valid_output" \
    || fail "exact approved schema did not complete rehearsal"
grep -Fq "Apply confirmation token: baseline:baseline_valid_test:" "$valid_output" \
    || fail "rehearsal did not emit a database-bound confirmation token"
[ "$(wc -l < "$config_capture_file" | tr -d ' ')" -eq 5 ] \
    || fail "rehearsal did not execute the expected five Flyway commands"
[ "$(LC_ALL=C sort -u "$config_capture_file" | wc -l | tr -d ' ')" -eq 5 ] \
    || fail "Flyway commands reused a temporary configuration file"
while IFS= read -r config_path; do
    [ ! -e "$config_path" ] \
        || fail "temporary Flyway configuration remained after a successful command"
done < "$config_capture_file"
grep -Fq "restored_v2_history_type=SQL" \
    "$TEMP_DIR/artifacts/valid/rehearsal-result.txt" \
    || fail "existing-schema rehearsal did not apply Flyway V2"
grep -Fq "fresh_v2_history_type=SQL" \
    "$TEMP_DIR/artifacts/valid/rehearsal-result.txt" \
    || fail "fresh rehearsal did not apply Flyway V2"
grep -Fq "restored_v3_history_type=SQL" \
    "$TEMP_DIR/artifacts/valid/rehearsal-result.txt" \
    || fail "existing-schema rehearsal did not apply Flyway V3"
grep -Fq "fresh_v3_history_type=SQL" \
    "$TEMP_DIR/artifacts/valid/rehearsal-result.txt" \
    || fail "fresh rehearsal did not apply Flyway V3"
grep -Fq "restored_v4_history_type=SQL" \
    "$TEMP_DIR/artifacts/valid/rehearsal-result.txt" \
    || fail "existing-schema rehearsal did not apply Flyway V4"
grep -Fq "fresh_v4_history_type=SQL" \
    "$TEMP_DIR/artifacts/valid/rehearsal-result.txt" \
    || fail "fresh rehearsal did not apply Flyway V4"
grep -Fq "restored_v5_history_type=SQL" \
    "$TEMP_DIR/artifacts/valid/rehearsal-result.txt" \
    || fail "existing-schema rehearsal did not apply Flyway V5"
grep -Fq "fresh_v5_history_type=SQL" \
    "$TEMP_DIR/artifacts/valid/rehearsal-result.txt" \
    || fail "fresh rehearsal did not apply Flyway V5"
grep -Fq "restored_web3_message_column=text:NO" \
    "$TEMP_DIR/artifacts/valid/rehearsal-result.txt" \
    || fail "existing-schema rehearsal did not require the Web3 SIWE message"
grep -Fq "fresh_web3_message_column=text:NO" \
    "$TEMP_DIR/artifacts/valid/rehearsal-result.txt" \
    || fail "fresh rehearsal did not require the Web3 SIWE message"
valid_fingerprint="$(awk '/^Schema fingerprint: / {print $3}' "$valid_output")"
[ -n "$valid_fingerprint" ] || fail "rehearsal did not report a schema fingerprint"

echo "2/14 Reject a source missing one managed table"
source_psql "$SOURCE_PORT" baseline_missing_test \
    -c "DROP TABLE token_blacklist;" >/dev/null
expect_guard_failure \
    baseline_missing_test \
    rehearse \
    missing-table \
    "source database does not contain all eight approved UniAuth tables"

echo "3/14 Reject additional structure inside a managed auth table"
source_psql "$SOURCE_PORT" baseline_extra_test \
    -c "ALTER TABLE users ADD COLUMN unexpected_auth_state text;" >/dev/null
expect_guard_failure \
    baseline_extra_test \
    rehearse \
    extra-auth-structure \
    "baselined and fresh migrated schema fingerprints differ"

echo "4/14 Reject a source that already has Flyway history"
source_psql "$SOURCE_PORT" baseline_history_test \
    -c "CREATE TABLE uniauth_flyway_schema_history (installed_rank integer);" >/dev/null
expect_guard_failure \
    baseline_history_test \
    rehearse \
    existing-history \
    "a Flyway history table already exists"

echo "5/14 Reject source data that cannot satisfy Flyway V2"
source_psql "$SOURCE_PORT" baseline_invalid_login_methods_test \
    -c "
        INSERT INTO users (id, username, email)
        VALUES (
            '00000000-0000-0000-0000-000000000001',
            'invalid-primary',
            'invalid-primary@example.invalid'
        );
        INSERT INTO user_login_methods (
            id,
            user_id,
            auth_provider,
            local_username,
            is_primary,
            is_verified
        ) VALUES (
            '00000000-0000-0000-0000-000000000002',
            '00000000-0000-0000-0000-000000000001',
            'LOCAL',
            'invalid-primary',
            false,
            false
        );
    " >/dev/null
expect_guard_failure \
    baseline_invalid_login_methods_test \
    rehearse \
    invalid-login-method-data \
    "source data is not compatible with pending login-method migration: users_without_exactly_one_primary"

echo "6/14 Reject source data that cannot satisfy Flyway V4"
source_psql "$SOURCE_PORT" baseline_invalid_entity_schema_test \
    -c "
        INSERT INTO users (
            id,
            username,
            email,
            email_verified
        ) VALUES (
            '00000000-0000-0000-0000-000000000041',
            'invalid-entity-schema',
            'invalid-entity-schema@example.invalid',
            NULL
        );
        INSERT INTO user_login_methods (
            id,
            user_id,
            auth_provider,
            local_username,
            is_primary,
            is_verified
        ) VALUES (
            '00000000-0000-0000-0000-000000000042',
            '00000000-0000-0000-0000-000000000041',
            'LOCAL',
            'invalid-entity-schema',
            true,
            false
        );
    " >/dev/null
expect_guard_failure \
    baseline_invalid_entity_schema_test \
    rehearse \
    invalid-entity-schema-data \
    "source data is not compatible with pending entity-schema migration: null_user_runtime_fields"
[ "$(source_psql "$SOURCE_PORT" baseline_invalid_entity_schema_test -qAt \
    -c "SELECT to_regclass('public.uniauth_flyway_schema_history') IS NULL;")" = "t" ] \
    || fail "V4 preflight failure created Flyway history"

echo "7/14 Reject invalid email verification state before Flyway history"
source_psql "$SOURCE_PORT" baseline_invalid_email_state_test \
    -c "
        INSERT INTO email_verification_codes (
            id,
            created_at,
            email,
            expires_at,
            is_used,
            purpose,
            retry_count,
            updated_at,
            verification_code
        ) VALUES (
            '00000000-0000-0000-0000-000000000051',
            CURRENT_TIMESTAMP,
            'invalid-email-state@example.invalid',
            CURRENT_TIMESTAMP + INTERVAL '10 minutes',
            false,
            'REGISTRATION',
            -1,
            CURRENT_TIMESTAMP,
            '123456'
        );
    " >/dev/null
expect_guard_failure \
    baseline_invalid_email_state_test \
    rehearse \
    invalid-email-state \
    "source data is not compatible with pending entity-schema migration: invalid_email_verification_state"
[ "$(source_psql "$SOURCE_PORT" baseline_invalid_email_state_test -qAt \
    -c "SELECT to_regclass('public.uniauth_flyway_schema_history') IS NULL;")" = "t" ] \
    || fail "invalid email verification state created Flyway history"
[ "$(source_psql "$SOURCE_PORT" baseline_invalid_email_state_test -qAt \
    -c "SELECT retry_count FROM email_verification_codes WHERE id = '00000000-0000-0000-0000-000000000051';")" = "-1" ] \
    || fail "email verification preflight changed source data"

echo "8/14 Reject invalid token blacklist state before Flyway history"
source_psql "$SOURCE_PORT" baseline_invalid_token_blacklist_test \
    -c "
        INSERT INTO token_blacklist (
            id,
            jti,
            token_type,
            expires_at,
            blacklisted_at,
            reason
        ) VALUES (
            '00000000-0000-0000-0000-000000000061',
            'invalid-token-blacklist-state',
            'UNKNOWN',
            CURRENT_TIMESTAMP + INTERVAL '10 minutes',
            CURRENT_TIMESTAMP,
            'BASELINE_GUARD_TEST'
        );
    " >/dev/null
expect_guard_failure \
    baseline_invalid_token_blacklist_test \
    rehearse \
    invalid-token-blacklist-state \
    "source data is not compatible with pending entity-schema migration: invalid_token_blacklist_state"
[ "$(source_psql "$SOURCE_PORT" baseline_invalid_token_blacklist_test -qAt \
    -c "SELECT to_regclass('public.uniauth_flyway_schema_history') IS NULL;")" = "t" ] \
    || fail "invalid token blacklist state created Flyway history"
[ "$(source_psql "$SOURCE_PORT" baseline_invalid_token_blacklist_test -qAt \
    -c "SELECT token_type FROM token_blacklist WHERE id = '00000000-0000-0000-0000-000000000061';")" = "UNKNOWN" ] \
    || fail "token blacklist preflight changed source data"

echo "9/14 Reject PostgreSQL versions outside the approved major"
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

echo "10/14 Require the exact confirmation token before apply"
expect_guard_failure \
    baseline_apply_test \
    apply \
    apply-without-confirmation \
    "UNIAUTH_BASELINE_CONFIRM does not match the rehearsal token"
[ "$(source_psql "$SOURCE_PORT" baseline_apply_test -qAt \
    -c "SELECT to_regclass('public.uniauth_flyway_schema_history') IS NULL;")" = "t" ] \
    || fail "apply without confirmation created Flyway history"

echo "11/14 Recheck login-method data immediately before apply"
race_bin="$TEMP_DIR/race-bin"
race_counter="$TEMP_DIR/race-mvn-count.txt"
real_psql="$(command -v psql)"
mkdir -p "$race_bin"
cat >"$race_bin/mvn" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

count=0
if [ -f "$UNIAUTH_RACE_COUNTER" ]; then
    count="$(cat "$UNIAUTH_RACE_COUNTER")"
fi
count=$((count + 1))
printf '%s\n' "$count" > "$UNIAUTH_RACE_COUNTER"

"$UNIAUTH_REAL_MVN" "$@"

if [ "$count" -eq 5 ]; then
    PGPASSWORD="$UNIAUTH_RACE_PASSWORD" "$UNIAUTH_REAL_PSQL" \
        -X -q -v ON_ERROR_STOP=1 \
        -h "$UNIAUTH_RACE_HOST" \
        -p "$UNIAUTH_RACE_PORT" \
        -U "$UNIAUTH_RACE_USER" \
        -d "$UNIAUTH_RACE_DATABASE" \
        -c "
            INSERT INTO users (id, username, email)
            VALUES (
                '00000000-0000-0000-0000-000000000011',
                'apply-data-race',
                'apply-data-race@example.invalid'
            );
            INSERT INTO user_login_methods (
                id,
                user_id,
                auth_provider,
                local_username,
                is_primary,
                is_verified
            ) VALUES (
                '00000000-0000-0000-0000-000000000012',
                '00000000-0000-0000-0000-000000000011',
                'LOCAL',
                'apply-data-race',
                false,
                false
            );
        " >/dev/null
fi
EOF
chmod 700 "$race_bin/mvn"
expect_guard_failure \
    baseline_apply_data_race_test \
    apply \
    apply-data-race \
    "source data changed during rehearsal; refusing baseline apply: users_without_exactly_one_primary" \
    PATH="$race_bin:$PATH" \
    UNIAUTH_BASELINE_CONFIRM="baseline:baseline_apply_data_race_test:$valid_fingerprint" \
    UNIAUTH_REAL_MVN="$real_mvn" \
    UNIAUTH_REAL_PSQL="$real_psql" \
    UNIAUTH_RACE_COUNTER="$race_counter" \
    UNIAUTH_RACE_HOST=127.0.0.1 \
    UNIAUTH_RACE_PORT="$SOURCE_PORT" \
    UNIAUTH_RACE_DATABASE=baseline_apply_data_race_test \
    UNIAUTH_RACE_USER="$SOURCE_USER" \
    UNIAUTH_RACE_PASSWORD="$SOURCE_PASSWORD"
[ "$(source_psql "$SOURCE_PORT" baseline_apply_data_race_test -qAt \
    -c "SELECT to_regclass('public.uniauth_flyway_schema_history') IS NULL;")" = "t" ] \
    || fail "apply created Flyway history after source data changed during rehearsal"

echo "12/14 Recheck entity-schema data immediately before apply"
entity_race_bin="$TEMP_DIR/entity-race-bin"
entity_race_counter="$TEMP_DIR/entity-race-mvn-count.txt"
mkdir -p "$entity_race_bin"
cat >"$entity_race_bin/mvn" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

count=0
if [ -f "$UNIAUTH_RACE_COUNTER" ]; then
    count="$(cat "$UNIAUTH_RACE_COUNTER")"
fi
count=$((count + 1))
printf '%s\n' "$count" > "$UNIAUTH_RACE_COUNTER"

"$UNIAUTH_REAL_MVN" "$@"

if [ "$count" -eq 5 ]; then
    PGPASSWORD="$UNIAUTH_RACE_PASSWORD" "$UNIAUTH_REAL_PSQL" \
        -X -q -v ON_ERROR_STOP=1 \
        -h "$UNIAUTH_RACE_HOST" \
        -p "$UNIAUTH_RACE_PORT" \
        -U "$UNIAUTH_RACE_USER" \
        -d "$UNIAUTH_RACE_DATABASE" \
        -c "
            INSERT INTO users (
                id,
                username,
                email,
                email_verified
            ) VALUES (
                '00000000-0000-0000-0000-000000000031',
                'apply-entity-race',
                'apply-entity-race@example.invalid',
                NULL
            );
            INSERT INTO user_login_methods (
                id,
                user_id,
                auth_provider,
                local_username,
                is_primary,
                is_verified
            ) VALUES (
                '00000000-0000-0000-0000-000000000032',
                '00000000-0000-0000-0000-000000000031',
                'LOCAL',
                'apply-entity-race',
                true,
                false
            );
        " >/dev/null
fi
EOF
chmod 700 "$entity_race_bin/mvn"
expect_guard_failure \
    baseline_apply_entity_race_test \
    apply \
    apply-entity-race \
    "source entity-schema data changed during rehearsal; refusing baseline apply: null_user_runtime_fields" \
    PATH="$entity_race_bin:$PATH" \
    UNIAUTH_BASELINE_CONFIRM="baseline:baseline_apply_entity_race_test:$valid_fingerprint" \
    UNIAUTH_REAL_MVN="$real_mvn" \
    UNIAUTH_REAL_PSQL="$real_psql" \
    UNIAUTH_RACE_COUNTER="$entity_race_counter" \
    UNIAUTH_RACE_HOST=127.0.0.1 \
    UNIAUTH_RACE_PORT="$SOURCE_PORT" \
    UNIAUTH_RACE_DATABASE=baseline_apply_entity_race_test \
    UNIAUTH_RACE_USER="$SOURCE_USER" \
    UNIAUTH_RACE_PASSWORD="$SOURCE_PASSWORD"
[ "$(source_psql "$SOURCE_PORT" baseline_apply_entity_race_test -qAt \
    -c "SELECT to_regclass('public.uniauth_flyway_schema_history') IS NULL;")" = "t" ] \
    || fail "apply created Flyway history after entity-schema data changed during rehearsal"

echo "13/14 Remove incomplete baseline history if V2 rejects a later data race"
migration_race_bin="$TEMP_DIR/migration-race-bin"
migration_race_counter="$TEMP_DIR/migration-race-mvn-count.txt"
mkdir -p "$migration_race_bin"
cat >"$migration_race_bin/mvn" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

count=0
if [ -f "$UNIAUTH_RACE_COUNTER" ]; then
    count="$(cat "$UNIAUTH_RACE_COUNTER")"
fi
count=$((count + 1))
printf '%s\n' "$count" > "$UNIAUTH_RACE_COUNTER"

"$UNIAUTH_REAL_MVN" "$@"

if [ "$count" -eq 6 ]; then
    PGPASSWORD="$UNIAUTH_RACE_PASSWORD" "$UNIAUTH_REAL_PSQL" \
        -X -q -v ON_ERROR_STOP=1 \
        -h "$UNIAUTH_RACE_HOST" \
        -p "$UNIAUTH_RACE_PORT" \
        -U "$UNIAUTH_RACE_USER" \
        -d "$UNIAUTH_RACE_DATABASE" \
        -c "
            INSERT INTO users (id, username, email)
            VALUES (
                '00000000-0000-0000-0000-000000000021',
                'migration-data-race',
                'migration-data-race@example.invalid'
            );
            INSERT INTO user_login_methods (
                id,
                user_id,
                auth_provider,
                local_username,
                is_primary,
                is_verified
            ) VALUES (
                '00000000-0000-0000-0000-000000000022',
                '00000000-0000-0000-0000-000000000021',
                'LOCAL',
                'migration-data-race',
                false,
                false
            );
        " >/dev/null
fi
EOF
chmod 700 "$migration_race_bin/mvn"
expect_guard_failure \
    baseline_apply_migration_race_test \
    apply \
    apply-migration-race \
    "Flyway migrate failed after baseline; removed the incomplete baseline-only history table." \
    PATH="$migration_race_bin:$PATH" \
    UNIAUTH_BASELINE_CONFIRM="baseline:baseline_apply_migration_race_test:$valid_fingerprint" \
    UNIAUTH_REAL_MVN="$real_mvn" \
    UNIAUTH_REAL_PSQL="$real_psql" \
    UNIAUTH_RACE_COUNTER="$migration_race_counter" \
    UNIAUTH_RACE_HOST=127.0.0.1 \
    UNIAUTH_RACE_PORT="$SOURCE_PORT" \
    UNIAUTH_RACE_DATABASE=baseline_apply_migration_race_test \
    UNIAUTH_RACE_USER="$SOURCE_USER" \
    UNIAUTH_RACE_PASSWORD="$SOURCE_PASSWORD"
[ "$(source_psql "$SOURCE_PORT" baseline_apply_migration_race_test -qAt \
    -c "SELECT to_regclass('public.uniauth_flyway_schema_history') IS NULL;")" = "t" ] \
    || fail "failed V2 migration left an incomplete baseline history table"
[ "$(source_psql "$SOURCE_PORT" baseline_apply_migration_race_test -qAt \
    -c "SELECT count(*) FROM users WHERE username = 'migration-data-race';")" = "1" ] \
    || fail "migration race fixture did not reach the post-baseline window"

echo "14/14 Remove temporary Flyway credentials after Maven failure"
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
