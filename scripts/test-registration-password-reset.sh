#!/usr/bin/env bash

# Disposable API contract checks for registration and forgot-password behavior.
# Response bodies and identity values are intentionally not printed.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
TEST_PASSWORD="${TEST_PASSWORD:-Test123456}"
EMAIL_NOT_REGISTERED="${EMAIL_NOT_REGISTERED:-notregistered-$(date +%s)@test.invalid}"

if [ "${DISPOSABLE_TEST_ENVIRONMENT:-}" != "true" ]; then
  echo "ERROR: set DISPOSABLE_TEST_ENVIRONMENT=true for an isolated test environment"
  exit 1
fi

if [ -z "${EMAIL_EXISTS:-}" ]; then
  echo "ERROR: EMAIL_EXISTS must identify an account in the disposable database"
  exit 1
fi

for command_name in curl jq; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "ERROR: required command is unavailable: ${command_name}"
    exit 1
  fi
done

fail() {
  echo "FAIL: $1"
  exit 1
}

post_json() {
  local path="$1"
  local payload="$2"
  curl -sS -X POST "${BASE_URL}${path}" \
    -H "Content-Type: application/json" \
    --data "${payload}"
}

value_or_empty() {
  local response="$1"
  local filter="$2"
  jq -r "(${filter}) | if . == null then empty else . end" \
    <<<"${response}" 2>/dev/null
}

echo "Registration and password-reset API contract check"
echo "Waiting for backend..."
for attempt in {1..30}; do
  if curl -fsS "${BASE_URL}/oauth2/jwks" >/dev/null 2>&1; then
    break
  fi
  if [ "${attempt}" -eq 30 ]; then
    fail "backend did not become ready"
  fi
  sleep 1
done

echo "1/5 Existing email registration is rejected"
existing_payload=$(jq -n \
  --arg email "${EMAIL_EXISTS}" \
  --arg password "${TEST_PASSWORD}" \
  '{username: $email, email: $email, password: $password, displayName: "Integration Test"}')
existing_response=$(post_json "/api/auth/register" "${existing_payload}")
existing_error=$(value_or_empty "${existing_response}" '.errorCode')
case "${existing_error}" in
  EMAIL_EXISTS|EMAIL_ALREADY_REGISTERED) ;;
  *) fail "existing email returned an unexpected error code" ;;
esac

echo "2/5 New email enters verification flow"
new_payload=$(jq -n \
  --arg email "${EMAIL_NOT_REGISTERED}" \
  --arg password "${TEST_PASSWORD}" \
  '{username: $email, email: $email, password: $password, displayName: "Integration Test"}')
new_response=$(post_json "/api/auth/register" "${new_payload}")
[ "$(value_or_empty "${new_response}" '.requireEmailVerification')" = "true" ] \
  || fail "new email did not enter the verification flow"

echo "3/5 Registered email can request password reset"
existing_forgot_payload=$(jq -n --arg email "${EMAIL_EXISTS}" '{email: $email}')
existing_forgot_response=$(post_json "/api/auth/forgot-password" "${existing_forgot_payload}")
[ "$(value_or_empty "${existing_forgot_response}" '.success')" = "true" ] \
  || fail "registered email could not request password reset"

echo "4/5 Unknown email follows the current rejection contract"
unknown_forgot_payload=$(jq -n --arg email "${EMAIL_NOT_REGISTERED}" '{email: $email}')
unknown_forgot_response=$(post_json "/api/auth/forgot-password" "${unknown_forgot_payload}")
[ "$(value_or_empty "${unknown_forgot_response}" '.success')" = "false" ] \
  || fail "unknown email unexpectedly reported password-reset success"
[ "$(value_or_empty "${unknown_forgot_response}" '.errorCode')" = "EMAIL_NOT_REGISTERED" ] \
  || fail "unknown email returned an unexpected error code"

echo "5/5 Invalid input and repeat-request paths return structured results"
empty_response=$(post_json "/api/auth/forgot-password" '{"email": ""}')
invalid_response=$(post_json "/api/auth/forgot-password" '{"email": "invalid-email"}')
jq -e 'type == "object"' <<<"${empty_response}" >/dev/null \
  || fail "empty email response was not JSON"
jq -e 'type == "object"' <<<"${invalid_response}" >/dev/null \
  || fail "invalid email response was not JSON"

for _attempt in {1..3}; do
  repeat_response=$(post_json "/api/auth/forgot-password" "${existing_forgot_payload}")
  jq -e 'type == "object" and has("success")' <<<"${repeat_response}" >/dev/null \
    || fail "repeat request response was not structured JSON"
done

echo "PASS: registration and password-reset API contract check completed"
