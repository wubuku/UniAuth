#!/usr/bin/env bash

# Disposable UniAuth PostgreSQL backup/restore rehearsal. The archive contains
# only synthetic data and is restored into a separate empty database.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/uniauth-backup-restore.XXXXXX")"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
CONTAINER_NAME="uniauth-backup-restore-${RUN_ID}"
SOURCE_DATABASE="uniauth_backup_source"
RESTORE_DATABASE="uniauth_backup_restore"
DATABASE_USER="uniauth_backup"
DATABASE_PASSWORD="uniauth-backup-${RUN_ID}"
DATABASE_PORT=""
ARCHIVE="$TEMP_DIR/uniauth-auth.dump"
CHECKSUM="$ARCHIVE.sha256"
CORRUPTED_ARCHIVE="$TEMP_DIR/uniauth-auth-corrupted.dump"
FLYWAY_CONFIG="$TEMP_DIR/flyway.conf"

cleanup() {
    local exit_code=$?
    set +e
    if docker ps -a --format '{{.Names}}' 2>/dev/null \
            | grep -Fxq "$CONTAINER_NAME"; then
        docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
    fi
    rm -rf "$TEMP_DIR"
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

for command_name in chmod cut docker grep mvn pg_isready psql shasum stat; do
    command -v "$command_name" >/dev/null 2>&1 \
        || fail "required command is unavailable: $command_name"
done
docker info >/dev/null 2>&1 || fail "Docker is unavailable"

db_value() {
    local database="$1"
    local sql="$2"
    PGPASSWORD="$DATABASE_PASSWORD" psql \
        -X -qAt -v ON_ERROR_STOP=1 \
        -h 127.0.0.1 \
        -p "$DATABASE_PORT" \
        -U "$DATABASE_USER" \
        -d "$database" \
        -c "$sql"
}

echo "1/6 Start PostgreSQL 16.13 and migrate the source database"
docker run -d --rm \
    --name "$CONTAINER_NAME" \
    -e "POSTGRES_DB=$SOURCE_DATABASE" \
    -e "POSTGRES_USER=$DATABASE_USER" \
    -e "POSTGRES_PASSWORD=$DATABASE_PASSWORD" \
    -p 127.0.0.1::5432 \
    postgres:16.13 >/dev/null
DATABASE_PORT="$(
    docker port "$CONTAINER_NAME" 5432/tcp \
        | awk -F: 'NR == 1 {print $NF}'
)"
for _ in $(seq 1 60); do
    if PGPASSWORD="$DATABASE_PASSWORD" pg_isready \
            -h 127.0.0.1 \
            -p "$DATABASE_PORT" \
            -U "$DATABASE_USER" \
            -d "$SOURCE_DATABASE" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
PGPASSWORD="$DATABASE_PASSWORD" pg_isready \
    -h 127.0.0.1 \
    -p "$DATABASE_PORT" \
    -U "$DATABASE_USER" \
    -d "$SOURCE_DATABASE" >/dev/null 2>&1 \
    || fail "disposable PostgreSQL did not become ready"

{
    printf 'flyway.url=jdbc:postgresql://127.0.0.1:%s/%s\n' \
        "$DATABASE_PORT" "$SOURCE_DATABASE"
    printf 'flyway.user=%s\n' "$DATABASE_USER"
    printf 'flyway.password=%s\n' "$DATABASE_PASSWORD"
    printf 'flyway.locations=filesystem:%s\n' \
        "$PROJECT_DIR/src/main/resources/db/migration/postgresql"
    printf 'flyway.table=uniauth_flyway_schema_history\n'
    printf 'flyway.defaultSchema=public\n'
    printf 'flyway.schemas=public\n'
    printf 'flyway.baselineOnMigrate=false\n'
    printf 'flyway.cleanDisabled=true\n'
    printf 'flyway.validateOnMigrate=true\n'
    printf 'flyway.outOfOrder=false\n'
    printf 'flyway.group=true\n'
} >"$FLYWAY_CONFIG"
chmod 600 "$FLYWAY_CONFIG"
(
    cd "$PROJECT_DIR"
    mvn -q -Dflyway.configFiles="$FLYWAY_CONFIG" \
        flyway:migrate flyway:validate
)
[ "$(db_value "$SOURCE_DATABASE" \
    "SELECT count(*) FROM uniauth_flyway_schema_history;")" = "8" ] \
    || fail "source database did not reach Flyway V8"

echo "2/6 Seed synthetic identity, session, and token metadata"
db_value "$SOURCE_DATABASE" "
    INSERT INTO users (
        id,
        username,
        email,
        display_name,
        email_verified,
        enabled,
        email_identity_type,
        login_methods_revision,
        token_security_version
    ) VALUES (
        '10000000-0000-4000-8000-000000000001',
        'backup-user',
        'backup-user@example.invalid',
        'Backup User',
        true,
        true,
        'VERIFIED_CONTACT',
        3,
        0
    );

    INSERT INTO user_login_methods (
        id,
        user_id,
        auth_provider,
        local_username,
        local_password_hash,
        is_primary,
        is_verified
    ) VALUES (
        '20000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000001',
        'LOCAL',
        'backup-user',
        '\$2a\$10\$synthetic.backup.hash.not.for.authentication',
        true,
        true
    );

    INSERT INTO user_authorities (user_id, authority) VALUES (
        '10000000-0000-4000-8000-000000000001',
        'ROLE_USER'
    );

    INSERT INTO spring_session (
        primary_id,
        session_id,
        creation_time,
        last_access_time,
        max_inactive_interval,
        expiry_time,
        principal_name
    ) VALUES (
        '30000000-0000-4000-8000-000000000001',
        '40000000-0000-4000-8000-000000000001',
        1786305600000,
        1786305600000,
        1800,
        1786307400000,
        'backup-user'
    );

    INSERT INTO spring_session_attributes (
        session_primary_id,
        attribute_name,
        attribute_bytes
    ) VALUES (
        '30000000-0000-4000-8000-000000000001',
        'backup-fixture',
        decode('01020304', 'hex')
    );

    INSERT INTO token_families (
        id,
        user_id,
        security_version,
        current_generation,
        auth_time,
        expires_at
    ) VALUES (
        '50000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000001',
        0,
        2,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP + INTERVAL '7 days'
    );
"
[ "$(db_value "$SOURCE_DATABASE" "SELECT count(*) FROM users;")" = "1" ] \
    || fail "synthetic source user was not created"
[ "$(db_value "$SOURCE_DATABASE" "SELECT count(*) FROM spring_session;")" = "1" ] \
    || fail "synthetic source session was not created"
[ "$(db_value "$SOURCE_DATABASE" \
    "SELECT count(*) FROM token_families WHERE revoked_at IS NULL;")" = "1" ] \
    || fail "synthetic source token family was not created"

echo "3/6 Create an owner-only custom archive and checksum"
docker exec \
    -e "PGPASSWORD=$DATABASE_PASSWORD" \
    "$CONTAINER_NAME" \
    pg_dump \
        --username "$DATABASE_USER" \
        --dbname "$SOURCE_DATABASE" \
        --format custom \
        --no-owner \
        --no-privileges \
        --file /tmp/uniauth-auth.dump
docker cp "$CONTAINER_NAME:/tmp/uniauth-auth.dump" "$ARCHIVE" >/dev/null
chmod 600 "$ARCHIVE"
[ -s "$ARCHIVE" ] || fail "backup archive is missing or empty"
[ "$(stat -f '%Lp' "$ARCHIVE")" = "600" ] \
    || fail "backup archive is not owner-only"
shasum -a 256 "$ARCHIVE" >"$CHECKSUM"
chmod 600 "$CHECKSUM"
(
    cd "$TEMP_DIR"
    shasum -a 256 -c "$(basename "$CHECKSUM")" >/dev/null
)
docker cp "$ARCHIVE" "$CONTAINER_NAME:/tmp/restore.dump" >/dev/null
docker exec "$CONTAINER_NAME" pg_restore --list /tmp/restore.dump >/dev/null \
    || fail "custom archive listing failed"

echo "4/6 Reject a corrupted archive before restore"
cp "$ARCHIVE" "$CORRUPTED_ARCHIVE"
printf 'corrupted' >"$CORRUPTED_ARCHIVE"
docker cp "$CORRUPTED_ARCHIVE" \
    "$CONTAINER_NAME:/tmp/corrupted.dump" >/dev/null
if docker exec "$CONTAINER_NAME" \
        pg_restore --list /tmp/corrupted.dump >/dev/null 2>&1; then
    fail "pg_restore accepted a corrupted archive"
fi

echo "5/6 Restore into an isolated empty database and compare evidence"
db_value "$SOURCE_DATABASE" "CREATE DATABASE $RESTORE_DATABASE;"
docker exec \
    -e "PGPASSWORD=$DATABASE_PASSWORD" \
    "$CONTAINER_NAME" \
    pg_restore \
        --username "$DATABASE_USER" \
        --dbname "$RESTORE_DATABASE" \
        --exit-on-error \
        --single-transaction \
        --no-owner \
        --no-privileges \
        /tmp/restore.dump

[ "$(db_value "$RESTORE_DATABASE" \
    "SELECT count(*) FROM uniauth_flyway_schema_history;")" = "8" ] \
    || fail "restored Flyway history is incomplete"
[ "$(db_value "$RESTORE_DATABASE" \
    "SELECT username || '|' || email || '|' || login_methods_revision
     FROM users;")" = "backup-user|backup-user@example.invalid|3" ] \
    || fail "restored user identity evidence differs"
[ "$(db_value "$RESTORE_DATABASE" \
    "SELECT encode(attribute_bytes, 'hex')
     FROM spring_session_attributes
     WHERE attribute_name = 'backup-fixture';")" = "01020304" ] \
    || fail "restored session attribute evidence differs"
[ "$(db_value "$RESTORE_DATABASE" \
    "SELECT current_generation
     FROM token_families
     WHERE id = '50000000-0000-4000-8000-000000000001';")" = "2" ] \
    || fail "restored token-family evidence differs"

echo "6/6 Invalidate restored sessions and token metadata before use"
db_value "$RESTORE_DATABASE" "
    UPDATE users
    SET token_security_version = token_security_version + 1,
        updated_at = CURRENT_TIMESTAMP;

    UPDATE token_families
    SET revoked_at = CURRENT_TIMESTAMP,
        revoke_reason = 'RESTORE_REHEARSAL',
        updated_at = CURRENT_TIMESTAMP
    WHERE revoked_at IS NULL;

    DELETE FROM spring_session;
"
[ "$(db_value "$RESTORE_DATABASE" "SELECT count(*) FROM spring_session;")" = "0" ] \
    || fail "restored sessions were not invalidated"
[ "$(db_value "$RESTORE_DATABASE" \
    "SELECT count(*) FROM token_families WHERE revoked_at IS NULL;")" = "0" ] \
    || fail "restored token families were not revoked"
[ "$(db_value "$RESTORE_DATABASE" \
    "SELECT token_security_version FROM users;")" = "1" ] \
    || fail "restored user security version was not advanced"

echo "PASS: UniAuth PostgreSQL backup/restore rehearsal"
