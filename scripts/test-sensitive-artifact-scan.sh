#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sensitive-scan-test.XXXXXX")"
PYTHON_BIN="${PYTHON_BIN:-python3}"
SCANNER="$SCRIPT_DIR/scan-sensitive-artifacts.py"
REPORT_CHECKER="$SCRIPT_DIR/check-sensitive-scan-report.py"
EMPTY_EXCEPTIONS="$TEMP_DIR/empty-exceptions.json"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT INT TERM

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

expect_failure() {
    local expected="$1"
    shift
    local output="$TEMP_DIR/failure.log"

    if "$@" >"$output" 2>&1; then
        fail "command unexpectedly succeeded: $*"
    fi
    grep -Fq "$expected" "$output" \
        || fail "failure did not contain expected text: $expected"
}

cat >"$EMPTY_EXCEPTIONS" <<'JSON'
{
  "version": 1,
  "exceptions": []
}
JSON

echo "1/8 Accept a clean scan and report"
mkdir "$TEMP_DIR/clean"
printf 'ordinary test fixture\n' >"$TEMP_DIR/clean/input.txt"
"$PYTHON_BIN" "$SCANNER" \
    --path "$TEMP_DIR/clean" \
    --exceptions "$EMPTY_EXCEPTIONS" \
    --report "$TEMP_DIR/clean-report.json"
"$PYTHON_BIN" "$REPORT_CHECKER" "$TEMP_DIR/clean-report.json"

echo "2/8 Reject a missing report"
expect_failure \
    "sensitive scan report is missing or empty" \
    "$PYTHON_BIN" "$REPORT_CHECKER" "$TEMP_DIR/missing-report.json"

echo "3/8 Reject a missing scan target"
expect_failure \
    "No such file or directory" \
    "$PYTHON_BIN" "$SCANNER" \
    --path "$TEMP_DIR/does-not-exist" \
    --exceptions "$EMPTY_EXCEPTIONS" \
    --report "$TEMP_DIR/missing-target-report.json"

echo "4/8 Detect a private key marker"
mkdir "$TEMP_DIR/private-key"
printf '%s\n' \
    '-----BEGIN '"PRIVATE KEY"'-----' \
    'not-a-real-key' \
    '-----END '"PRIVATE KEY"'-----' \
    >"$TEMP_DIR/private-key/key.pem"
expect_failure \
    "private-key-pem" \
    "$PYTHON_BIN" "$SCANNER" \
    --path "$TEMP_DIR/private-key" \
    --exceptions "$EMPTY_EXCEPTIONS" \
    --report "$TEMP_DIR/private-key-report.json"

echo "5/8 Detect a complete JWT"
mkdir "$TEMP_DIR/jwt"
jwt_header='eyJhbGciOiJSUzI1NiJ9'
jwt_payload='eyJzdWIiOiJzZW5zaXRpdmUtdGVzdCJ9'
jwt_signature='abcdefghijklmnopqrstuvwxyzABCDE'
printf '%s.%s.%s\n' \
    "$jwt_header" "$jwt_payload" "$jwt_signature" \
    >"$TEMP_DIR/jwt/token.txt"
expect_failure \
    "full-jwt" \
    "$PYTHON_BIN" "$SCANNER" \
    --path "$TEMP_DIR/jwt" \
    --exceptions "$EMPTY_EXCEPTIONS" \
    --report "$TEMP_DIR/jwt-report.json"

echo "6/8 Detect a provider access token"
mkdir "$TEMP_DIR/provider"
token_prefix='github_pat_'
token_body='ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijk'
printf '%s%s\n' "$token_prefix" "$token_body" \
    >"$TEMP_DIR/provider/token.txt"
expect_failure \
    "github-token" \
    "$PYTHON_BIN" "$SCANNER" \
    --path "$TEMP_DIR/provider" \
    --exceptions "$EMPTY_EXCEPTIONS" \
    --report "$TEMP_DIR/provider-report.json"

fingerprint="$(
    "$PYTHON_BIN" - "$TEMP_DIR/provider-report.json" <<'PY'
import json
import sys

report = json.load(open(sys.argv[1], encoding="utf-8"))
print(report["findings"][0]["fingerprint"])
PY
)"

echo "7/8 Reject an expired exception"
cat >"$TEMP_DIR/expired-exceptions.json" <<JSON
{
  "version": 1,
  "exceptions": [
    {
      "id": "expired-test-fixture",
      "fingerprint": "$fingerprint",
      "owner": "verification maintainers",
      "reason": "This deliberately expired test entry verifies fail-closed expiry handling.",
      "expires": "2000-01-01"
    }
  ]
}
JSON
expect_failure \
    "expired on 2000-01-01" \
    "$PYTHON_BIN" "$SCANNER" \
    --path "$TEMP_DIR/provider" \
    --exceptions "$TEMP_DIR/expired-exceptions.json" \
    --report "$TEMP_DIR/expired-report.json"

echo "8/8 Accept one precise unexpired exception"
cat >"$TEMP_DIR/valid-exceptions.json" <<JSON
{
  "version": 1,
  "exceptions": [
    {
      "id": "bounded-test-fixture",
      "fingerprint": "$fingerprint",
      "owner": "verification maintainers",
      "reason": "This synthetic token exists only to verify exact fingerprint exception handling.",
      "expires": "2099-01-01"
    }
  ]
}
JSON
"$PYTHON_BIN" "$SCANNER" \
    --path "$TEMP_DIR/provider" \
    --exceptions "$TEMP_DIR/valid-exceptions.json" \
    --report "$TEMP_DIR/valid-exception-report.json"
"$PYTHON_BIN" "$REPORT_CHECKER" "$TEMP_DIR/valid-exception-report.json"

echo "PASS: sensitive artifact scan fail-closed guards"
