#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GUARD="$PROJECT_DIR/scripts/runtime-guard.sh"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/email-runtime-guard.XXXXXX")"

# shellcheck source=runtime-guard.sh
source "$GUARD"

cleanup() {
    local exit_code=$?
    rm -rf "$TEMP_DIR"
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

run_guard() {
    env -i \
        HOME="${HOME:-/tmp}" \
        PATH="$PATH" \
        SPRING_PROFILES_ACTIVE=dev \
        EMAIL_SERVICE_BIND_ADDRESS=127.0.0.1 \
        EMAIL_SERVICE_API_KEY= \
        EMAIL_POSTGRES_HOST=127.0.0.1 \
        EMAIL_POSTGRES_PORT=5432 \
        EMAIL_POSTGRES_DATABASE=email_service_test \
        EMAIL_POSTGRES_USER=email_test \
        EMAIL_POSTGRES_PASSWORD=test-secret \
        SMTP_HOST=127.0.0.1 \
        SMTP_PORT=2525 \
        SMTP_AUTH=false \
        SMTP_STARTTLS_ENABLE=false \
        SMTP_STARTTLS_REQUIRED=false \
        SMTP_SSL_ENABLE=false \
        SMTP_SSL_CHECK_SERVER_IDENTITY=true \
        EMAIL_FROM_ADDRESS=no-reply@example.test \
        "$@" \
        bash -c 'source "$1"; email_service_prepare_runtime' bash "$GUARD"
}

expect_failure() {
    local name="$1"
    local message="$2"
    local output="$TEMP_DIR/${name}.log"
    shift 2

    if "$@" >"$output" 2>&1; then
        fail "$name unexpectedly passed"
    fi
    grep -Fq "$message" "$output" \
        || fail "$name did not report the expected error"
}

echo "1/27 Reject an implicit profile"
expect_failure \
    missing-profile \
    "SPRING_PROFILES_ACTIVE must be exactly dev or prod" \
    run_guard SPRING_PROFILES_ACTIVE=

echo "2/27 Reject a shared UniAuth database"
expect_failure \
    shared-database \
    "email service database name must contain email or mail" \
    run_guard EMAIL_POSTGRES_DATABASE=uniauth_test

echo "3/27 Reject a non-disposable dev database name"
expect_failure \
    nondisposable-dev \
    "dev profile requires an email database named dev/test/demo/local" \
    run_guard EMAIL_POSTGRES_DATABASE=email_service_prod

echo "4/27 Reject non-loopback exposure without an API key"
expect_failure \
    exposed-without-key \
    "EMAIL_SERVICE_API_KEY is required for non-loopback binding" \
    run_guard EMAIL_SERVICE_BIND_ADDRESS=0.0.0.0

echo "5/27 Reject SMTP authentication without credentials"
expect_failure \
    missing-smtp-credentials \
    "one of SMTP_USERNAME or SPRING_MAIL_USERNAME must be set" \
    run_guard SMTP_AUTH=true

echo "6/27 Reject an SMTP host containing URI syntax"
expect_failure \
    invalid-smtp-host \
    "SMTP_HOST must be a host name or IP address without URI syntax or whitespace" \
    run_guard SMTP_HOST=smtp://mail.example.test

echo "7/27 Reject an SMTP host containing whitespace"
expect_failure \
    whitespace-smtp-host \
    "SMTP_HOST must be a host name or IP address without URI syntax or whitespace" \
    run_guard SMTP_HOST="mail host.example.test"

echo "8/27 Reject an oversized SMTP host"
oversized_smtp_host="$(printf '%0256d' 0)"
expect_failure \
    oversized-smtp-host \
    "SMTP_HOST must be a host name or IP address without URI syntax or whitespace" \
    run_guard SMTP_HOST="$oversized_smtp_host"

echo "9/27 Accept an IPv6 SMTP host token"
run_guard SMTP_HOST=::1 >/dev/null

echo "10/27 Reject a non-numeric SMTP port"
expect_failure \
    invalid-smtp-port \
    "SMTP_PORT must be an integer from 1 to 65535" \
    run_guard SMTP_PORT=not-a-port

echo "11/27 Reject an out-of-range SMTP port"
expect_failure \
    out-of-range-smtp-port \
    "SMTP_PORT must be an integer from 1 to 65535" \
    run_guard SMTP_PORT=65536

echo "12/27 Reject an invalid SMTP server identity flag"
expect_failure \
    invalid-server-identity-flag \
    "SMTP_SSL_CHECK_SERVER_IDENTITY must be exactly true or false" \
    run_guard SMTP_SSL_CHECK_SERVER_IDENTITY=TRUE

echo "13/27 Reject required STARTTLS when STARTTLS is disabled"
expect_failure \
    required-starttls-disabled \
    "SMTP_STARTTLS_REQUIRED=true requires SMTP_STARTTLS_ENABLE=true" \
    run_guard SMTP_STARTTLS_REQUIRED=true

echo "14/27 Reject simultaneous STARTTLS and implicit SSL"
expect_failure \
    conflicting-smtp-tls-modes \
    "SMTP_SSL_ENABLE=true cannot be combined with SMTP_STARTTLS_ENABLE=true" \
    run_guard SMTP_STARTTLS_ENABLE=true SMTP_SSL_ENABLE=true

echo "15/27 Reject optional STARTTLS in production"
expect_failure \
    optional-production-starttls \
    "production SMTP requires forced STARTTLS or implicit SSL" \
    run_guard \
        SPRING_PROFILES_ACTIVE=prod \
        EMAIL_POSTGRES_DATABASE=email_service_prod \
        SMTP_STARTTLS_ENABLE=true \
        SMTP_STARTTLS_REQUIRED=false

echo "16/27 Reject production without SMTP server identity verification"
expect_failure \
    production-without-server-identity \
    "production SMTP requires server identity verification" \
    run_guard \
        SPRING_PROFILES_ACTIVE=prod \
        EMAIL_POSTGRES_DATABASE=email_service_prod \
        SMTP_STARTTLS_ENABLE=true \
        SMTP_STARTTLS_REQUIRED=true \
        SMTP_SSL_CHECK_SERVER_IDENTITY=false

echo "17/27 Accept production implicit SSL with server identity verification"
run_guard \
    SPRING_PROFILES_ACTIVE=prod \
    EMAIL_POSTGRES_DATABASE=email_service_prod \
    SMTP_SSL_ENABLE=true \
    >/dev/null

echo "18/27 Reject production without recovery processing"
expect_failure \
    disabled-prod-delivery \
    "production email delivery requires recovery processing" \
    run_guard \
        SPRING_PROFILES_ACTIVE=prod \
        EMAIL_POSTGRES_DATABASE=email_service_prod \
        EMAIL_SERVICE_BIND_ADDRESS=0.0.0.0 \
        EMAIL_SERVICE_API_KEY=runtime-guard-test-key \
        SMTP_STARTTLS_ENABLE=true \
        SMTP_STARTTLS_REQUIRED=true \
        EMAIL_RECOVERY_ENABLED=false

echo "19/27 Reject an invalid recovery scan interval"
expect_failure \
    invalid-recovery-scan \
    "EMAIL_RECOVERY_SCAN_INTERVAL_MINUTES must be an integer from 1 to 10080" \
    run_guard EMAIL_RECOVERY_SCAN_INTERVAL_MINUTES=10081

echo "20/27 Reject a recovery window shorter than the SMTP timeout budget"
expect_failure \
    short-recovery-window \
    "EMAIL_STUCK_TIMEOUT_MINUTES must exceed the combined SMTP timeout budget" \
    run_guard \
        SMTP_CONNECTION_TIMEOUT_MS=30000 \
        SMTP_READ_TIMEOUT_MS=30000 \
        SMTP_WRITE_TIMEOUT_MS=30000 \
        EMAIL_STUCK_TIMEOUT_MINUTES=1

echo "21/27 Reject an environment file readable by group or others"
open_env="$TEMP_DIR/open.env"
printf '%s\n' 'SPRING_PROFILES_ACTIVE=dev' >"$open_env"
chmod 644 "$open_env"
expect_failure \
    open-env \
    "environment file must not be accessible by group or others" \
    email_service_validate_env_file "$open_env"

echo "22/27 Reject a symbolic-link environment file"
secure_env="$TEMP_DIR/secure.env"
linked_env="$TEMP_DIR/linked.env"
printf '%s\n' 'SPRING_PROFILES_ACTIVE=dev' >"$secure_env"
chmod 600 "$secure_env"
ln -s "$secure_env" "$linked_env"
expect_failure \
    linked-env \
    "environment file must not be a symbolic link" \
    email_service_validate_env_file "$linked_env"

echo "23/27 Accept an owner-only environment file"
email_service_validate_env_file "$secure_env"

echo "24/27 Reject an API key containing a line break"
expect_failure \
    invalid-api-key-line-break \
    "EMAIL_SERVICE_API_KEY must be at most 1024 characters without CR or LF" \
    run_guard EMAIL_SERVICE_API_KEY=$'first\nsecond'

echo "25/27 Reject an oversized API key"
oversized_api_key="$(printf '%01025d' 0)"
expect_failure \
    oversized-api-key \
    "EMAIL_SERVICE_API_KEY must be at most 1024 characters without CR or LF" \
    run_guard EMAIL_SERVICE_API_KEY="$oversized_api_key"

echo "26/27 Accept loopback development with plaintext SMTP"
run_guard >/dev/null

echo "27/27 Accept protected non-loopback production STARTTLS configuration"
run_guard \
    SPRING_PROFILES_ACTIVE=prod \
    EMAIL_POSTGRES_DATABASE=email_service_prod \
    EMAIL_SERVICE_BIND_ADDRESS=0.0.0.0 \
    EMAIL_SERVICE_API_KEY=runtime-guard-test-key \
    SMTP_STARTTLS_ENABLE=true \
    SMTP_STARTTLS_REQUIRED=true \
    >/dev/null

echo "PASS: email service runtime guard"
