#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/uniauth-supply-chain-guards.XXXXXX")"
PYTHON_BIN="${PYTHON_BIN:-python3}"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT INT TERM

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

expect_failure() {
    local expected_message="$1"
    shift
    local output_file="$TEMP_DIR/expected-failure.log"

    if "$@" >"$output_file" 2>&1; then
        fail "command unexpectedly succeeded: $*"
    fi
    grep -Fq "$expected_message" "$output_file" \
        || fail "failure did not contain expected message: $expected_message"
}

echo "1/11 Reject a missing dependency audit report"
expect_failure \
    "dependency audit report is missing or empty" \
    "$PYTHON_BIN" \
    "$SCRIPT_DIR/check-dependency-audit-report.py" \
    "$TEMP_DIR/missing.json"

echo "2/11 Accept a dependency audit report with project and dependency evidence"
printf '%s\n' \
    '{"projectInfo":{"name":"fixture"},"dependencies":[{"fileName":"fixture.jar"}]}' \
    >"$TEMP_DIR/report.json"
"$PYTHON_BIN" \
    "$SCRIPT_DIR/check-dependency-audit-report.py" \
    "$TEMP_DIR/report.json"

echo "3/11 Reject an unscored high-severity vulnerability"
printf '%s\n' \
    '{"projectInfo":{"name":"fixture"},"dependencies":[{"fileName":"fixture.jar","vulnerabilities":[{"name":"GHSA-test-high","unscored":"true","severity":"high"}]}]}' \
    >"$TEMP_DIR/unscored-high.json"
expect_failure \
    "unscored severity HIGH" \
    "$PYTHON_BIN" \
    "$SCRIPT_DIR/check-dependency-audit-report.py" \
    "$TEMP_DIR/unscored-high.json"

echo "4/11 Reject a scored vulnerability at the build threshold"
printf '%s\n' \
    '{"projectInfo":{"name":"fixture"},"dependencies":[{"fileName":"fixture.jar","vulnerabilities":[{"name":"CVE-test-scored","severity":"medium","cvssv3":{"baseScore":7.0}}]}]}' \
    >"$TEMP_DIR/scored-blocking.json"
expect_failure \
    "CVSS 7" \
    "$PYTHON_BIN" \
    "$SCRIPT_DIR/check-dependency-audit-report.py" \
    "$TEMP_DIR/scored-blocking.json"

echo "5/11 Accept an unscored medium-severity vulnerability as evidence"
printf '%s\n' \
    '{"projectInfo":{"name":"fixture"},"dependencies":[{"fileName":"fixture.jar","vulnerabilities":[{"name":"GHSA-test-medium","unscored":"true","severity":"medium"}]}]}' \
    >"$TEMP_DIR/unscored-medium.json"
"$PYTHON_BIN" \
    "$SCRIPT_DIR/check-dependency-audit-report.py" \
    "$TEMP_DIR/unscored-medium.json"

echo "6/11 Reject an expired dependency suppression"
cat >"$TEMP_DIR/expired.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<suppressions xmlns="https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.3.xsd">
  <suppress until="2000-01-01T00:00:00Z">
    <notes><![CDATA[test fixture]]></notes>
    <cve>CVE-2000-0001</cve>
  </suppress>
</suppressions>
XML
expect_failure \
    "suppression expired" \
    "$PYTHON_BIN" \
    "$SCRIPT_DIR/check-dependency-suppressions.py" \
    "$TEMP_DIR/expired.xml"

echo "7/11 Reject a suppression without an expiry"
cat >"$TEMP_DIR/no-expiry.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<suppressions xmlns="https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.3.xsd">
  <suppress>
    <notes><![CDATA[test fixture]]></notes>
    <cve>CVE-2000-0001</cve>
  </suppress>
</suppressions>
XML
expect_failure \
    "every suppression must have an until attribute" \
    "$PYTHON_BIN" \
    "$SCRIPT_DIR/check-dependency-suppressions.py" \
    "$TEMP_DIR/no-expiry.xml"

echo "8/11 Reject a suppression without detailed notes"
cat >"$TEMP_DIR/no-notes.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<suppressions xmlns="https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.3.xsd">
  <suppress until="2099-01-01T00:00:00Z">
    <notes><![CDATA[too short]]></notes>
    <cve>CVE-2099-0001</cve>
  </suppress>
</suppressions>
XML
expect_failure \
    "every suppression must include detailed notes" \
    "$PYTHON_BIN" \
    "$SCRIPT_DIR/check-dependency-suppressions.py" \
    "$TEMP_DIR/no-notes.xml"

echo "9/11 Accept the repository suppression manifests"
"$PYTHON_BIN" \
    "$SCRIPT_DIR/check-dependency-suppressions.py" \
    "$SCRIPT_DIR/../config/dependency-check-suppressions.xml" \
    "$SCRIPT_DIR/../reference/email-service/config/dependency-check-suppressions.xml"

echo "10/11 Reject a floating PostgreSQL image tag"
FLOATING_POSTGRES_TAG="16"
printf '%s\n' \
    "docker run --rm postgres:${FLOATING_POSTGRES_TAG}" \
    >"$TEMP_DIR/floating-postgres.sh"
expect_failure \
    "unapproved PostgreSQL image tag postgres:${FLOATING_POSTGRES_TAG}" \
    "$PYTHON_BIN" \
    "$SCRIPT_DIR/check-postgres-image-pins.py" \
    "$TEMP_DIR/floating-postgres.sh"

echo "11/11 Accept repository PostgreSQL 16.13 image pins"
"$PYTHON_BIN" \
    "$SCRIPT_DIR/check-postgres-image-pins.py" \
    "$SCRIPT_DIR/../README.md" \
    "$SCRIPT_DIR/../.github" \
    "$SCRIPT_DIR" \
    "$SCRIPT_DIR/../src/test" \
    "$SCRIPT_DIR/../reference/email-service/scripts" \
    "$SCRIPT_DIR/../reference/email-service/src/test"

echo "PASS: supply-chain fail-closed guards"
