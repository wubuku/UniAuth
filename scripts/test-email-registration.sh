#!/usr/bin/env bash

# Disposable integration check for email registration, login, and password reset.
# The script reads verification codes from the isolated test database but never
# prints passwords, codes, tokens, response bodies, or user identifiers.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
DISPLAY_NAME="${DISPLAY_NAME:-Integration Test User}"
USERNAME="${USERNAME:-${EMAIL:-}}"
COOKIE_HEADERS_FILE="$(mktemp "${TMPDIR:-/tmp}/uniauth-email-cookie-headers.XXXXXX")"
trap 'rm -f "${COOKIE_HEADERS_FILE}"' EXIT

if [ "${DISPOSABLE_TEST_ENVIRONMENT:-}" != "true" ]; then
  echo "ERROR: set DISPOSABLE_TEST_ENVIRONMENT=true for an isolated test environment"
  exit 1
fi

required_variables=(
  EMAIL
  USERNAME
  PASSWORD
  NEW_PASSWORD
  POSTGRES_HOST
  POSTGRES_PORT
  POSTGRES_DATABASE
  POSTGRES_USER
  POSTGRES_PASSWORD
  EMAIL_POSTGRES_HOST
  EMAIL_POSTGRES_PORT
  EMAIL_POSTGRES_DATABASE
  EMAIL_POSTGRES_USER
  EMAIL_POSTGRES_PASSWORD
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

case "${EMAIL_POSTGRES_DATABASE}" in
  *test*|*demo*|*tmp*|*temp*|*ci*|*local*) ;;
  *)
    echo "ERROR: EMAIL_POSTGRES_DATABASE must be an explicitly disposable test database"
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
  jq -r "(${filter}) | if . == null then empty else . end" \
    <<<"${response}" 2>/dev/null || true
}

wait_for_active_challenge() {
  local challenge_handle="$1"
  local purpose="$2"
  local delivery_status

  for _attempt in {1..100}; do
    delivery_status="$(
      PGPASSWORD="${POSTGRES_PASSWORD}" psql \
        -X \
        -q \
        -h "${POSTGRES_HOST}" \
        -p "${POSTGRES_PORT}" \
        -U "${POSTGRES_USER}" \
        -d "${POSTGRES_DATABASE}" \
        -v ON_ERROR_STOP=1 \
        -v "challenge_handle=${challenge_handle}" \
        -v "email=${EMAIL}" \
        -v "purpose=${purpose}" \
        -At 2>/dev/null <<'SQL' | tr -d '[:space:]'
SELECT delivery_status
FROM email_verification_codes
WHERE id = :'challenge_handle'
  AND email = :'email'
  AND purpose = :'purpose'
  AND usage_status = 'UNUSED';
SQL
    )"
    if [ "${delivery_status}" = "ACTIVE" ]; then
      return
    fi
    if [ "${delivery_status}" = "FAILED" ]; then
      fail "${purpose} verification challenge entered FAILED delivery state"
    fi
    sleep 0.1
  done
  fail "${purpose} verification challenge did not become ACTIVE"
}

get_rendered_verification_code() {
  local challenge_handle="$1"
  local code

  PGPASSWORD="${POSTGRES_PASSWORD}" psql \
    -X \
    -q \
    -h "${POSTGRES_HOST}" \
    -p "${POSTGRES_PORT}" \
    -U "${POSTGRES_USER}" \
    -d "${POSTGRES_DATABASE}" \
    -v ON_ERROR_STOP=1 \
    -At 2>/dev/null <<'SQL' | grep -Fxq '0'
SELECT count(*)
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'email_verification_codes'
  AND column_name IN ('verification_code', 'metadata', 'is_used');
SQL

  code="$(
    PGPASSWORD="${EMAIL_POSTGRES_PASSWORD}" psql \
    -X \
    -q \
    -h "${EMAIL_POSTGRES_HOST}" \
    -p "${EMAIL_POSTGRES_PORT}" \
    -U "${EMAIL_POSTGRES_USER}" \
    -d "${EMAIL_POSTGRES_DATABASE}" \
    -v ON_ERROR_STOP=1 \
    -v "idempotency_key=email-challenge:${challenge_handle}" \
    -At 2>/dev/null <<'SQL' | tr -d '[:space:]'
SELECT (
  regexp_match(
    html_content,
    '<div class="code"[^>]*>[[:space:]]*([0-9]{6})[[:space:]]*</div>'
  )
)[1]
FROM email_queue
WHERE idempotency_key = :'idempotency_key'
  AND status IN ('PENDING', 'PROCESSING', 'COMPLETED')
LIMIT 1;
SQL
  )"
  [[ "${code}" =~ ^[0-9]{6}$ ]] \
    || fail "rendered verification code was unavailable"
  printf '%s' "${code}"
}

post_json() {
  local path="$1"
  local payload="$2"
  curl -sS -X POST "${BASE_URL}${path}" \
    -H "Content-Type: application/json" \
    --data "${payload}"
}

assert_auth_cookie_headers() {
  local cookie_name
  local expected_max_age
  local header_line

  for cookie_name in accessToken refreshToken; do
    if [ "${cookie_name}" = "accessToken" ]; then
      expected_max_age=3600
    else
      expected_max_age=604800
    fi
    header_line="$(
      tr -d '\r' <"${COOKIE_HEADERS_FILE}" \
        | grep -i "^set-cookie: ${cookie_name}=" \
        | tail -1 \
        || true
    )"
    [ -n "${header_line}" ] || fail "registration did not set ${cookie_name}"
    [[ "${header_line}" == *"; Path=/"* ]] \
      || fail "${cookie_name} cookie did not use Path=/"
    [[ "${header_line}" == *"; HttpOnly"* ]] \
      || fail "${cookie_name} cookie was not HttpOnly"
    [[ "${header_line}" == *"; SameSite=Lax"* ]] \
      || fail "${cookie_name} cookie did not use SameSite=Lax"
    [[ "${header_line}" == *"; Max-Age=${expected_max_age}"* ]] \
      || fail "${cookie_name} cookie used an unexpected Max-Age"
    [[ "${header_line}" != *"; Secure"* ]] \
      || fail "${cookie_name} cookie was Secure in the local HTTP test profile"
  done
}

echo "Email authentication integration check"
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

echo "1/9 Preview registration without creating an account"
preview_payload=$(jq -n \
  --arg username "${USERNAME}" \
  --arg email "${EMAIL}" \
  --arg password "${PASSWORD}" \
  --arg displayName "${DISPLAY_NAME}" \
  '{
    username: $username,
    email: $email,
    password: $password,
    displayName: $displayName
  }')
preview_response=$(post_json "/api/auth/register" "${preview_payload}")
[ "$(json_value "${preview_response}" '.requireEmailVerification')" = "true" ] \
  || fail "registration preview did not require email verification"
[ "$(json_value "${preview_response}" '.username')" = "${USERNAME}" ] \
  || fail "registration preview returned an unexpected username"
[ "$(json_value "${preview_response}" '.email')" = "${EMAIL}" ] \
  || fail "registration preview returned an unexpected email"

preview_user_count="$(
  PGPASSWORD="${POSTGRES_PASSWORD}" psql \
    -X -qAt -v ON_ERROR_STOP=1 \
    -h "${POSTGRES_HOST}" \
    -p "${POSTGRES_PORT}" \
    -U "${POSTGRES_USER}" \
    -d "${POSTGRES_DATABASE}" \
    -v "email=${EMAIL}" \
    2>/dev/null <<'SQL'
SELECT count(*)
FROM users
WHERE email = :'email';
SQL
)"
[ "${preview_user_count}" = "0" ] \
  || fail "registration preview created a user before verification"

echo "2/9 Request registration verification code"
send_payload=$(jq -n \
  --arg email "${EMAIL}" \
  '{email: $email, purpose: "REGISTRATION"}')
send_response=$(post_json "/api/auth/send-verification-code" "${send_payload}")
[ "$(json_value "${send_response}" '.success')" = "true" ] \
  || fail "registration verification request was rejected"
registration_handle=$(json_value "${send_response}" '.challengeHandle')
[ -n "${registration_handle}" ] \
  || fail "registration verification request omitted the challenge handle"

wait_for_active_challenge "${registration_handle}" "REGISTRATION"
registration_code=$(get_rendered_verification_code "${registration_handle}")

echo "3/9 Verify the handle-bound rejection path"
wrong_code="000000"
if [ "${registration_code}" = "${wrong_code}" ]; then
  wrong_code="111111"
fi
wrong_payload=$(jq -n \
  --arg handle "${registration_handle}" \
  --arg username "${USERNAME}" \
  --arg email "${EMAIL}" \
  --arg password "${PASSWORD}" \
  --arg displayName "${DISPLAY_NAME}" \
  --arg code "${wrong_code}" \
  '{
    challengeHandle: $handle,
    username: $username,
    email: $email,
    password: $password,
    displayName: $displayName,
    verificationCode: $code
  }')
wrong_status=$(curl -sS -o /dev/null -w '%{http_code}' \
  -X POST "${BASE_URL}/api/auth/verify-email" \
  -H "Content-Type: application/json" \
  --data "${wrong_payload}")
[ "${wrong_status}" = "400" ] \
  || fail "incorrect handle-bound verification code returned ${wrong_status}"

echo "4/9 Register using the rendered verification code"
register_payload=$(jq -n \
  --arg handle "${registration_handle}" \
  --arg username "${USERNAME}" \
  --arg email "${EMAIL}" \
  --arg password "${PASSWORD}" \
  --arg displayName "${DISPLAY_NAME}" \
  --arg code "${registration_code}" \
  '{
    challengeHandle: $handle,
    username: $username,
    email: $email,
    password: $password,
    displayName: $displayName,
    verificationCode: $code
  }')
register_response=$(curl -sS -D "${COOKIE_HEADERS_FILE}" \
  -X POST "${BASE_URL}/api/auth/verify-email" \
  -H "Content-Type: application/json" \
  --data "${register_payload}")
access_token=$(json_value "${register_response}" '.accessToken')
[ -n "${access_token}" ] || fail "registration response did not contain an access token"
assert_auth_cookie_headers

echo "5/9 Call the protected current-user endpoint"
user_status=$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer ${access_token}" \
  "${BASE_URL}/api/user")
[ "${user_status}" = "200" ] || fail "protected current-user endpoint returned ${user_status}"

echo "6/9 Login with the original password"
login_payload=$(jq -n \
  --arg username "${USERNAME}" \
  --arg password "${PASSWORD}" \
  '{username: $username, password: $password}')
login_response=$(post_json "/api/auth/login" "${login_payload}")
[ "$(json_value "${login_response}" '.authenticated')" = "true" ] \
  || fail "login with the original password failed"

echo "7/9 Request password reset"
forgot_payload=$(jq -n --arg email "${EMAIL}" '{email: $email}')
forgot_response=$(post_json "/api/auth/forgot-password" "${forgot_payload}")
[ "$(json_value "${forgot_response}" '.success')" = "true" ] \
  || fail "password reset request failed"
reset_handle=$(json_value "${forgot_response}" '.challengeHandle')
[ -n "${reset_handle}" ] || fail "password reset response omitted the challenge handle"

wait_for_active_challenge "${reset_handle}" "PASSWORD_RESET"
reset_code=$(get_rendered_verification_code "${reset_handle}")

echo "8/9 Reset password using the rendered code"
reset_payload=$(jq -n \
  --arg handle "${reset_handle}" \
  --arg email "${EMAIL}" \
  --arg code "${reset_code}" \
  --arg password "${NEW_PASSWORD}" \
  '{
    challengeHandle: $handle,
    email: $email,
    verificationCode: $code,
    newPassword: $password
  }')
reset_response=$(post_json "/api/auth/verify-reset-code" "${reset_payload}")
[ "$(json_value "${reset_response}" '.success')" = "true" ] \
  || fail "password reset failed"

echo "9/9 Login with the new password"
new_login_payload=$(jq -n \
  --arg username "${USERNAME}" \
  --arg password "${NEW_PASSWORD}" \
  '{username: $username, password: $password}')
new_login_response=$(post_json "/api/auth/login" "${new_login_payload}")
[ "$(json_value "${new_login_response}" '.authenticated')" = "true" ] \
  || fail "login with the new password failed"

echo "PASS: email authentication integration check completed"
