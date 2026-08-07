#!/usr/bin/env bash

# Disposable integration check for email registration, login, and password reset.
# The script reads verification codes from the isolated test database but never
# prints passwords, codes, tokens, response bodies, or user identifiers.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
DISPLAY_NAME="${DISPLAY_NAME:-Integration Test User}"

if [ "${DISPOSABLE_TEST_ENVIRONMENT:-}" != "true" ]; then
  echo "ERROR: set DISPOSABLE_TEST_ENVIRONMENT=true for an isolated test environment"
  exit 1
fi

required_variables=(
  EMAIL
  PASSWORD
  NEW_PASSWORD
  POSTGRES_HOST
  POSTGRES_PORT
  POSTGRES_DATABASE
  POSTGRES_USER
  POSTGRES_PASSWORD
)

for variable_name in "${required_variables[@]}"; do
  if [ -z "${!variable_name:-}" ]; then
    echo "ERROR: required environment variable is missing: ${variable_name}"
    exit 1
  fi
done

case "${POSTGRES_DATABASE}" in
  *test*|*demo*|*tmp*|*temp*|*ci*|*local*) ;;
  *)
    echo "ERROR: POSTGRES_DATABASE must be an explicitly disposable test database"
    exit 1
    ;;
esac

for command_name in curl jq psql; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "ERROR: required command is unavailable: ${command_name}"
    exit 1
  fi
done

fail() {
  echo "FAIL: $1"
  exit 1
}

json_value() {
  local response="$1"
  local filter="$2"
  jq -er "${filter}" <<<"${response}" 2>/dev/null
}

get_verification_code() {
  local purpose="$1"
  PGPASSWORD="${POSTGRES_PASSWORD}" psql \
    -h "${POSTGRES_HOST}" \
    -p "${POSTGRES_PORT}" \
    -U "${POSTGRES_USER}" \
    -d "${POSTGRES_DATABASE}" \
    -v ON_ERROR_STOP=1 \
    -v "email=${EMAIL}" \
    -v "purpose=${purpose}" \
    -Atc "SELECT verification_code
          FROM email_verification_codes
          WHERE email = :'email'
            AND purpose = :'purpose'
            AND is_used = false
          ORDER BY created_at DESC
          LIMIT 1;" 2>/dev/null | tr -d '[:space:]'
}

post_json() {
  local path="$1"
  local payload="$2"
  curl -sS -X POST "${BASE_URL}${path}" \
    -H "Content-Type: application/json" \
    --data "${payload}"
}

echo "Email authentication integration check"
echo "Waiting for backend..."
for attempt in {1..30}; do
  if curl -fsS "${BASE_URL}/" >/dev/null 2>&1; then
    break
  fi
  if [ "${attempt}" -eq 30 ]; then
    fail "backend did not become ready"
  fi
  sleep 1
done

echo "1/8 Request registration verification code"
send_payload=$(jq -n \
  --arg email "${EMAIL}" \
  '{email: $email, purpose: "REGISTRATION"}')
send_response=$(post_json "/api/auth/send-verification-code" "${send_payload}")
[ "$(json_value "${send_response}" '.success')" = "true" ] \
  || fail "registration verification request was rejected"

registration_code=$(get_verification_code "REGISTRATION")
[ -n "${registration_code}" ] || fail "registration verification code was not persisted"

echo "2/8 Verify rejection and acceptance paths"
wrong_code="000000"
if [ "${registration_code}" = "${wrong_code}" ]; then
  wrong_code="111111"
fi
wrong_payload=$(jq -n \
  --arg email "${EMAIL}" \
  --arg code "${wrong_code}" \
  '{email: $email, verificationCode: $code, purpose: "REGISTRATION"}')
wrong_response=$(post_json "/api/auth/check-verification-code" "${wrong_payload}")
[ "$(json_value "${wrong_response}" '.valid')" = "false" ] \
  || fail "incorrect verification code was accepted"

check_payload=$(jq -n \
  --arg email "${EMAIL}" \
  --arg code "${registration_code}" \
  '{email: $email, verificationCode: $code, purpose: "REGISTRATION"}')
check_response=$(post_json "/api/auth/check-verification-code" "${check_payload}")
[ "$(json_value "${check_response}" '.valid')" = "true" ] \
  || fail "persisted verification code was rejected"

echo "3/8 Register using the persisted verification code"
register_payload=$(jq -n \
  --arg email "${EMAIL}" \
  --arg password "${PASSWORD}" \
  --arg displayName "${DISPLAY_NAME}" \
  --arg code "${registration_code}" \
  '{
    username: $email,
    email: $email,
    password: $password,
    displayName: $displayName,
    verificationCode: $code
  }')
register_response=$(post_json "/api/auth/register" "${register_payload}")
access_token=$(json_value "${register_response}" '.accessToken')
[ -n "${access_token}" ] || fail "registration response did not contain an access token"

echo "4/8 Call the protected current-user endpoint"
user_status=$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer ${access_token}" \
  "${BASE_URL}/api/user")
[ "${user_status}" = "200" ] || fail "protected current-user endpoint returned ${user_status}"

echo "5/8 Login with the original password"
login_response=$(curl -sS -X POST "${BASE_URL}/api/auth/login" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "username=${EMAIL}" \
  --data-urlencode "password=${PASSWORD}")
[ "$(json_value "${login_response}" '.authenticated')" = "true" ] \
  || fail "login with the original password failed"

echo "6/8 Request password reset"
forgot_payload=$(jq -n --arg email "${EMAIL}" '{email: $email}')
forgot_response=$(post_json "/api/auth/forgot-password" "${forgot_payload}")
[ "$(json_value "${forgot_response}" '.success')" = "true" ] \
  || fail "password reset request failed"

reset_code=$(get_verification_code "PASSWORD_RESET")
[ -n "${reset_code}" ] || fail "password reset code was not persisted"

echo "7/8 Reset password using the persisted code"
reset_payload=$(jq -n \
  --arg email "${EMAIL}" \
  --arg code "${reset_code}" \
  --arg password "${NEW_PASSWORD}" \
  '{email: $email, verificationCode: $code, newPassword: $password}')
reset_response=$(post_json "/api/auth/verify-reset-code" "${reset_payload}")
[ "$(json_value "${reset_response}" '.success')" = "true" ] \
  || fail "password reset failed"

echo "8/8 Login with the new password"
new_login_response=$(curl -sS -X POST "${BASE_URL}/api/auth/login" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "username=${EMAIL}" \
  --data-urlencode "password=${NEW_PASSWORD}")
[ "$(json_value "${new_login_response}" '.authenticated')" = "true" ] \
  || fail "login with the new password failed"

echo "PASS: email authentication integration check completed"
