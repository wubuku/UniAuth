#!/usr/bin/env bash
# Self-test for scripts/health-snapshot.py.
#
# The snapshot is a *reporting* layer, so its most dangerous failure mode is
# reporting HEALTHY when evidence is missing, inconsistent, stale, or regressed.
# Every case below is a negative one except the control case. Frontend counts
# are read from runtime JSON reports only; declared listings are never trusted.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"
FIXTURE="$(mktemp -d "${TMPDIR:-/tmp}/uniauth-health-snapshot.XXXXXX")"
OUT_DIR="$FIXTURE/.local/uniauth/health"

cleanup() {
    rm -rf "$FIXTURE"
}
trap cleanup EXIT INT TERM

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

snapshot() {
    "$PYTHON_BIN" "$PROJECT_DIR/scripts/health-snapshot.py" \
        --repo-root "$FIXTURE" \
        --output-dir "$OUT_DIR" \
        --skip-live
}

write_baseline() {
    mkdir -p "$FIXTURE/config"
    cat >"$FIXTURE/config/coverage-baseline.json" <<'JSON'
{
  "version": 1,
  "backend": {"line": 90.0, "branch": 90.0},
  "frontend": {"line": 90.0, "branch": 90.0},
  "tolerancePercentagePoints": {"backend": 1.0, "frontend": 2.0}
}
JSON
    cat >"$FIXTURE/config/mutation-baseline.json" <<'JSON'
{
  "version": 1,
  "generated": 3,
  "killed": 3,
  "noCoverage": 0,
  "mutationScore": 100.0,
  "classes": {}
}
JSON
}

write_surefire() {
    local failures="$1"
    mkdir -p "$FIXTURE/target/surefire-reports"
    "$PYTHON_BIN" - "$FIXTURE/target/surefire-reports/TEST-fixture.xml" "$failures" <<'PY'
import sys

path, failures = sys.argv[1], int(sys.argv[2])
cases = "".join(f'<testcase name="case{i}"/>' for i in range(10))
with open(path, "w", encoding="utf-8") as handle:
    handle.write(
        f'<testsuite name="fixture" tests="10" failures="{failures}" '
        f'errors="0" skipped="0">{cases}</testsuite>'
    )
PY
}

write_coverage() {
    local covered="$1"
    mkdir -p "$FIXTURE/target/site/jacoco" "$FIXTURE/frontend/coverage"
    printf 'LINE_MISSED,LINE_COVERED,BRANCH_MISSED,BRANCH_COVERED\n' \
        >"$FIXTURE/target/site/jacoco/jacoco.csv"
    printf '5,%s,5,%s\n' "$covered" "$covered" \
        >>"$FIXTURE/target/site/jacoco/jacoco.csv"
    local percent
    percent="$("$PYTHON_BIN" -c "print(round($covered * 100 / ($covered + 5), 2))")"
    cat >"$FIXTURE/frontend/coverage/coverage-summary.json" <<JSON
{"total": {"lines": {"pct": $percent}, "branches": {"pct": $percent}}}
JSON
}

# Runtime reports: unit=2, mock=2, production=1. The first argument overrides
# the Vitest declared total so an inconsistent report can be simulated.
write_frontend_reports() {
    local vitest_total="${1:-2}"
    local vitest_records=2
    mkdir -p "$FIXTURE/frontend/reports"
    "$PYTHON_BIN" - "$FIXTURE/frontend/reports" "$vitest_total" "$vitest_records" <<'PY'
import json
import sys
from pathlib import Path

reports = Path(sys.argv[1])
vitest_total = int(sys.argv[2])
vitest_records = int(sys.argv[3])
assertions = [{"status": "passed"} for _ in range(vitest_records)]
(reports / "vitest-results.json").write_text(
    json.dumps({
        "numTotalTests": vitest_total,
        "numFailedTests": 0,
        "numPassedTests": vitest_records,
        "testResults": [{"assertionResults": assertions}],
    }),
    encoding="utf-8",
)
(reports / "playwright-mock.json").write_text(
    json.dumps({
        "stats": {"expected": 2, "unexpected": 0, "skipped": 0, "flaky": 0},
        "suites": [{"specs": [{}, {}], "suites": []}],
    }),
    encoding="utf-8",
)
(reports / "playwright-production.json").write_text(
    json.dumps({
        "stats": {"expected": 1, "unexpected": 0, "skipped": 0, "flaky": 0},
        "suites": [{"specs": [{}], "suites": []}],
    }),
    encoding="utf-8",
)
PY
}

write_gate() {
    local exit_code="$1"
    local passed="$2"
    local age_hours="${3:-0}"
    mkdir -p "$OUT_DIR"
    # Exercise the real record path so fixture metrics stay in sync with the
    # implementation; then patch only the fields a test needs to corrupt.
    "$PYTHON_BIN" "$PROJECT_DIR/scripts/health-snapshot.py" \
        --record-gate \
        --project-dir "$FIXTURE" \
        --output-dir "$OUT_DIR" \
        --surefire-dir "$FIXTURE/target/surefire-reports" \
        --backend-csv "$FIXTURE/target/site/jacoco/jacoco.csv" \
        --exit-code "$exit_code" \
        --run-id fixture >/dev/null
    "$PYTHON_BIN" - "$OUT_DIR/last-verify.json" "$passed" "$age_hours" <<'PY'
import json
import sys
from datetime import datetime, timedelta, timezone

path, passed, age_hours = sys.argv[1:4]
with open(path, encoding="utf-8") as handle:
    payload = json.load(handle)
recorded = datetime.now(timezone.utc) - timedelta(hours=float(age_hours))
payload["recordedAt"] = recorded.strftime("%Y-%m-%dT%H:%M:%SZ")
payload["passed"] = passed == "true"
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PY
}

# Move every fixture source file into the past so the age rule is the only
# stale reason under test.
age_sources() {
    local hours="$1"
    "$PYTHON_BIN" - "$FIXTURE" "$hours" <<'PY'
import os
import sys
import time
from pathlib import Path

root = Path(sys.argv[1])
hours = float(sys.argv[2])
target = time.time() - hours * 3600
for path in root.rglob("*"):
    if path.is_file() and ".local" not in path.parts:
        os.utime(path, (target, target))
PY
}

write_component_record() {
    local age_hours="${1:-0}"
    local fingerprint="${2:-}"
    mkdir -p "$OUT_DIR"
    "$PYTHON_BIN" - "$OUT_DIR/email-last-verify.json" "$age_hours" "$fingerprint" <<'PY'
import json
import sys
from datetime import datetime, timedelta, timezone

path, age_hours, fingerprint = sys.argv[1:4]
recorded = datetime.now(timezone.utc) - timedelta(hours=float(age_hours))
payload = {
    "version": 1,
    "recordedAt": recorded.strftime("%Y-%m-%dT%H:%M:%SZ"),
    "runId": "fixture-component",
    "exitCode": 0,
    "passed": True,
    "sourceFingerprint": fingerprint,
    "metrics": {
        "componentTests": {
            "declared": 10,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "records": 10,
            "total": 10,
            "inconsistent": False,
            "reportFiles": 1,
        }
    },
}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PY
}

# Independent shell implementation of the component gate's fingerprint, used to
# prove the Python recomputation agrees with reference/email-service/scripts/verify.sh.
component_fingerprint() {
    (
        cd "$FIXTURE"
        git ls-files -co --exclude-standard -z -- \
            reference/email-service \
            scripts/check-dependency-audit-report.py \
            scripts/check-dependency-suppressions.py \
            src/main/resources/db/migration/postgresql \
            | LC_ALL=C sort -z \
            | xargs -0 shasum -a 256 \
            | shasum -a 256 \
            | awk '{print $1}'
    )
}

make_healthy_fixture() {
    write_baseline
    write_surefire 0
    write_coverage 95
    write_frontend_reports
    write_gate 0 true 0
    # The component record is required evidence now: without it the snapshot
    # must report MISSING, so the healthy control case has to include one.
    write_component_record 0
}

echo "1/20: an empty fixture is DEGRADED, never HEALTHY"
mkdir -p "$FIXTURE"
if snapshot >"$FIXTURE/empty.txt" 2>&1; then
    fail "snapshot reported HEALTHY without any gate, coverage, or mutation evidence"
fi
grep -q "DEGRADED" "$FIXTURE/empty.txt" \
    || fail "empty fixture did not report DEGRADED"
grep -q "MISSING" "$FIXTURE/empty.txt" \
    || fail "empty fixture did not name missing evidence"

echo "2/20: complete healthy evidence is HEALTHY (control case)"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
if ! snapshot >"$FIXTURE/healthy.txt" 2>&1; then
    fail "complete healthy fixture was not reported as HEALTHY"
fi
grep -q "Overall: HEALTHY" "$FIXTURE/healthy.txt" \
    || fail "HEALTHY fixture did not print Overall: HEALTHY"
grep -q "unit=2 mock=2 prod=1 runtime" "$FIXTURE/healthy.txt" \
    || fail "frontend totals did not come from the runtime JSON reports"

echo "3/20: coverage below the ratchet tolerance fails"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
write_coverage 1
write_gate 0 true 0
if snapshot >"$FIXTURE/coverage.txt" 2>&1; then
    fail "snapshot accepted a coverage regression below the tolerance"
fi
grep -q "FAIL" "$FIXTURE/coverage.txt" \
    || fail "coverage regression was not reported as FAIL"
grep -q "below baseline" "$FIXTURE/coverage.txt" \
    || fail "coverage regression did not explain the shortfall"

echo "4/20: failing tests fail even when coverage looks fine"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
write_surefire 1
write_gate 0 true 0
if snapshot >"$FIXTURE/failures.txt" 2>&1; then
    fail "snapshot accepted a Surefire failure count"
fi
grep -q "backend-tests" "$FIXTURE/failures.txt" \
    || fail "Surefire failure was not attributed to backend-tests"
grep -q "FAIL" "$FIXTURE/failures.txt" \
    || fail "Surefire failure was not reported as FAIL"

echo "5/20: an inconsistent gate record cannot look healthy"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
write_gate 1 true 0
if snapshot >"$FIXTURE/inconsistent.txt" 2>&1; then
    fail "snapshot trusted passed=true while exitCode=1"
fi
grep -q "inconsistent gate record" "$FIXTURE/inconsistent.txt" \
    || fail "inconsistent gate record was not called out"

echo "6/20: a missing mutation baseline fails"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
rm -f "$FIXTURE/config/mutation-baseline.json"
write_gate 0 true 0
if snapshot >"$FIXTURE/mutation.txt" 2>&1; then
    fail "snapshot reported healthy without a mutation baseline"
fi
grep -q "mutation" "$FIXTURE/mutation.txt" \
    || fail "missing mutation baseline was not attributed to the mutation layer"
grep -q "MISSING" "$FIXTURE/mutation.txt" \
    || fail "missing mutation baseline was not reported as MISSING"

echo "7/20: a missing runtime Vitest report fails instead of falling back"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
rm -f "$FIXTURE/frontend/reports/vitest-results.json"
write_gate 0 true 0
if snapshot >"$FIXTURE/no-vitest.txt" 2>&1; then
    fail "snapshot reported healthy without a Vitest runtime report"
fi
grep -q "frontend-tests" "$FIXTURE/no-vitest.txt" \
    || fail "missing Vitest report was not attributed to frontend-tests"
grep -q "MISSING" "$FIXTURE/no-vitest.txt" \
    || fail "missing Vitest report was not reported as MISSING"

echo "8/20: a Vitest report whose totals disagree with its records fails"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
write_frontend_reports 3
write_gate 0 true 0
if snapshot >"$FIXTURE/inconsistent-vitest.txt" 2>&1; then
    fail "snapshot trusted a Vitest report with 3 declared but 2 recorded tests"
fi
grep -q "total=3 records=2" "$FIXTURE/inconsistent-vitest.txt" \
    || fail "inconsistent Vitest report did not explain the mismatch"

echo "9/20: a gate record older than the stale threshold fails"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
write_gate 0 true 30
age_sources 40
if snapshot >"$FIXTURE/stale-age.txt" 2>&1; then
    fail "snapshot reported healthy from a 30-hour-old gate record"
fi
grep -q "STALE" "$FIXTURE/stale-age.txt" \
    || fail "stale age was not shown explicitly"
grep -q "record age" "$FIXTURE/stale-age.txt" \
    || fail "stale age did not explain the rule that fired"

echo "10/20: a source modified after the record fails even when it is recent"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
"$PYTHON_BIN" - "$FIXTURE/config/coverage-baseline.json" <<'PY'
import os
import sys
import time

path = sys.argv[1]
future = time.time() + 3600
os.utime(path, (future, future))
PY
if snapshot >"$FIXTURE/stale-mtime.txt" 2>&1; then
    fail "snapshot reported healthy after a source was modified post-record"
fi
grep -q "source mtime newer than record" "$FIXTURE/stale-mtime.txt" \
    || fail "mtime staleness rule did not fire"

echo "11/20: machine-readable JSON records the same verdict"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
snapshot >/dev/null
[ -s "$OUT_DIR/snapshot.json" ] \
    || fail "snapshot JSON was not written"
"$PYTHON_BIN" - "$OUT_DIR/snapshot.json" <<'PY' \
    || fail "snapshot JSON did not record HEALTHY"
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("overall") != "HEALTHY":
    raise SystemExit(f"unexpected overall: {payload.get('overall')}")
if not payload.get("layers"):
    raise SystemExit("snapshot JSON has no layers")
if payload.get("totals", {}).get("frontendTests") != 5:
    raise SystemExit(f"unexpected frontend total: {payload.get('totals')}")
PY

echo "12/20: a Surefire report whose count disagrees with its testcases fails"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
"$PYTHON_BIN" - "$FIXTURE/target/surefire-reports/TEST-fixture.xml" <<'PY'
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    payload = handle.read()
# Keep tests="10" but remove one recorded testcase.
payload = payload.replace('<testcase name="case9"/>', "")
with open(path, "w", encoding="utf-8") as handle:
    handle.write(payload)
PY
write_gate 0 true 0
if snapshot >"$FIXTURE/inconsistent-surefire.txt" 2>&1; then
    fail "snapshot trusted a Surefire report with 10 declared but 9 records"
fi
grep -q "10 tests but 9 testcase" "$FIXTURE/inconsistent-surefire.txt" \
    || fail "inconsistent Surefire report did not explain the mismatch"

echo "13/20: a missing coverage baseline metric fails instead of becoming 0.0"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
"$PYTHON_BIN" - "$FIXTURE/config/coverage-baseline.json" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    payload = json.load(handle)
del payload["backend"]["branch"]
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PY
if snapshot >"$FIXTURE/missing-baseline-metric.txt" 2>&1; then
    fail "snapshot treated a missing baseline metric as 0.0"
fi
grep -q "baseline is MISSING" "$FIXTURE/missing-baseline-metric.txt" \
    || fail "missing baseline metric was not named"
grep -q "backend-coverage" "$FIXTURE/missing-baseline-metric.txt" \
    || fail "missing baseline metric was not attributed to backend-coverage"

echo "14/20: a gate record with no frontend metrics is MISSING, not re-read live"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
# Live reports stay on disk; removing the recorded metrics must still fail.
"$PYTHON_BIN" - "$OUT_DIR/last-verify.json" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    payload = json.load(handle)
del payload["metrics"]["frontendTests"]
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PY
if snapshot >"$FIXTURE/missing-recorded-frontend.txt" 2>&1; then
    fail "snapshot fell back to working-tree frontend reports"
fi
grep -q "frontend-tests" "$FIXTURE/missing-recorded-frontend.txt" \
    || fail "missing recorded frontend metrics were not attributed to frontend-tests"
grep -q "MISSING" "$FIXTURE/missing-recorded-frontend.txt" \
    || fail "missing recorded frontend metrics were not reported as MISSING"

echo "15/20: a component record older than the stale threshold fails"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
write_component_record 30
if snapshot >"$FIXTURE/stale-component.txt" 2>&1; then
    fail "snapshot trusted a 30-hour-old component record"
fi
grep -q "component-tests" "$FIXTURE/stale-component.txt" \
    || fail "stale component record was not attributed to component-tests"
grep -q "STALE" "$FIXTURE/stale-component.txt" \
    || fail "stale component record was not reported as STALE"
grep -q "record age" "$FIXTURE/stale-component.txt" \
    || fail "stale component age did not explain the rule that fired"

echo "16/20: a component record from a different tree fails"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
mkdir -p "$FIXTURE/reference/email-service"
printf 'fixture\n' >"$FIXTURE/reference/email-service/README.md"
git -C "$FIXTURE" init -q
write_component_record 0 deadbeef
if snapshot >"$FIXTURE/stale-component-fingerprint.txt" 2>&1; then
    fail "snapshot trusted a component record from a different source tree"
fi
grep -q "component source fingerprint changed since the record" \
    "$FIXTURE/stale-component-fingerprint.txt" \
    || fail "component fingerprint mismatch was not explained"

echo "17/20: a component record matching the current tree is accepted (control)"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
mkdir -p "$FIXTURE/reference/email-service"
printf 'fixture\n' >"$FIXTURE/reference/email-service/README.md"
git -C "$FIXTURE" init -q
write_component_record 0 "$(component_fingerprint)"
if ! snapshot >"$FIXTURE/fresh-component.txt" 2>&1; then
    fail "snapshot rejected a component record whose fingerprint matches the tree"
fi
grep -q "Overall: HEALTHY" "$FIXTURE/fresh-component.txt" \
    || fail "fresh component record did not keep the snapshot HEALTHY"

echo "18/20: an ignored component file cannot make a matching record stale"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
mkdir -p "$FIXTURE/reference/email-service"
printf 'fixture\n' >"$FIXTURE/reference/email-service/README.md"
printf '.env\n' >"$FIXTURE/.gitignore"
git -C "$FIXTURE" init -q
write_component_record 0 "$(component_fingerprint)"
printf 'secret\n' >"$FIXTURE/reference/email-service/.env"
"$PYTHON_BIN" - "$FIXTURE/reference/email-service/.env" <<'PY'
import os
import sys
import time

path = sys.argv[1]
future = time.time() + 3600
os.utime(path, (future, future))
PY
if ! snapshot >"$FIXTURE/ignored-component.txt" 2>&1; then
    fail "an ignored .env file made the component record look stale"
fi
grep -q "Overall: HEALTHY" "$FIXTURE/ignored-component.txt" \
    || fail "ignored component file changed the HEALTHY verdict"

echo "19/20: a tracked component source newer than the record fails"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
mkdir -p "$FIXTURE/reference/email-service"
printf 'fixture\n' >"$FIXTURE/reference/email-service/README.md"
printf '.env\n' >"$FIXTURE/.gitignore"
git -C "$FIXTURE" init -q
"$PYTHON_BIN" - "$FIXTURE/reference/email-service/README.md" <<'PY'
import os
import sys
import time

path = sys.argv[1]
future = time.time() + 3600
os.utime(path, (future, future))
PY
# Record the new fingerprint after the touch, so only the mtime rule can fire.
write_component_record 0 "$(component_fingerprint)"
if snapshot >"$FIXTURE/stale-component-mtime.txt" 2>&1; then
    fail "snapshot ignored a tracked component source newer than the record"
fi
grep -q "component source mtime newer than record" \
    "$FIXTURE/stale-component-mtime.txt" \
    || fail "component mtime staleness rule did not fire"

echo "20/20: a missing component record is MISSING, not N/A"
rm -rf "$FIXTURE"/* "$FIXTURE"/.local
make_healthy_fixture
rm -f "$OUT_DIR/email-last-verify.json"
if snapshot >"$FIXTURE/missing-component.txt" 2>&1; then
    fail "snapshot reported HEALTHY without a component record"
fi
grep -q "component-tests" "$FIXTURE/missing-component.txt" \
    || fail "missing component record was not attributed to component-tests"
grep -q "MISSING" "$FIXTURE/missing-component.txt" \
    || fail "missing component record was not reported as MISSING"

echo "PASS: health snapshot self-test"
