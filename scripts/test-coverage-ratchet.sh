#!/usr/bin/env bash
# Self-test for scripts/coverage-ratchet.py.
#
# The ratchet's dangerous failure mode is silently treating a missing scope or
# metric as a 0.0% baseline. Every negative case below deletes evidence and
# asserts that the ratchet fails closed instead of passing.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"
FIXTURE="$(mktemp -d "${TMPDIR:-/tmp}/uniauth-coverage-ratchet.XXXXXX")"
BASELINE="$FIXTURE/coverage-baseline.json"
CSV="$FIXTURE/jacoco.csv"

cleanup() {
    rm -rf "$FIXTURE"
}
trap cleanup EXIT INT TERM

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

write_csv() {
    local covered="$1"
    local missed="${2:-10}"
    printf 'LINE_MISSED,LINE_COVERED,BRANCH_MISSED,BRANCH_COVERED\n' >"$CSV"
    printf '%s,%s,%s,%s\n' "$missed" "$covered" "$missed" "$covered" >>"$CSV"
}

write_baseline() {
    cat >"$BASELINE" <<JSON
{
  "version": 1,
  "backend": $1,
  "tolerancePercentagePoints": {"backend": 1.0, "frontend": 2.0}
}
JSON
}

ratchet() {
    "$PYTHON_BIN" "$PROJECT_DIR/scripts/coverage-ratchet.py" \
        --baseline "$BASELINE" \
        --backend-csv "$CSV" \
        --backend-only "$@"
}

echo "1/8: matching baseline passes (control case)"
write_baseline '{"line": 90.0, "branch": 90.0}'
write_csv 90
ratchet >"$FIXTURE/ok.txt" 2>&1 \
    || fail "matching baseline did not pass"
grep -q "Coverage ratchet passed" "$FIXTURE/ok.txt" \
    || fail "control case did not print the pass line"

echo "2/8: a missing scope fails closed instead of becoming 0.0"
write_baseline '{"line": 90.0, "branch": 90.0}'
"$PYTHON_BIN" - "$BASELINE" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    payload = json.load(handle)
del payload["backend"]
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PY
write_csv 90
if ratchet >"$FIXTURE/missing-scope.txt" 2>&1; then
    fail "ratchet accepted a baseline with no backend scope"
fi
grep -q "backend baseline entry is missing" "$FIXTURE/missing-scope.txt" \
    || fail "missing scope did not name the failure"

echo "3/8: a missing metric fails closed instead of becoming 0.0"
write_baseline '{"line": 90.0, "branch": 90.0}'
"$PYTHON_BIN" - "$BASELINE" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    payload = json.load(handle)
del payload["backend"]["branch"]
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PY
write_csv 90
if ratchet >"$FIXTURE/missing-metric.txt" 2>&1; then
    fail "ratchet accepted a baseline without the branch metric"
fi
grep -q "backend branch baseline is missing" "$FIXTURE/missing-metric.txt" \
    || fail "missing metric did not name the failure"

echo "4/8: a drop beyond the tolerance fails"
write_baseline '{"line": 90.0, "branch": 90.0}'
write_csv 80 20
if ratchet >"$FIXTURE/drop.txt" 2>&1; then
    fail "ratchet accepted a 10pp drop with a 1pp tolerance"
fi
grep -q "coverage dropped" "$FIXTURE/drop.txt" \
    || fail "drop did not explain the shortfall"

echo "5/8: a drop inside the tolerance passes"
write_baseline '{"line": 90.0, "branch": 90.0}'
write_csv 89 11
ratchet >"$FIXTURE/within.txt" 2>&1 \
    || fail "ratchet rejected a drop inside the tolerance"
grep -q "drop=+1.00pp" "$FIXTURE/within.txt" \
    || fail "within-tolerance measurement was not reported"

echo "6/8: --update migrates a missing metric explicitly"
write_baseline '{"line": 90.0, "branch": 90.0}'
"$PYTHON_BIN" - "$BASELINE" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    payload = json.load(handle)
del payload["backend"]["branch"]
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PY
write_csv 90
ratchet --update >"$FIXTURE/update-missing.txt" 2>&1 \
    || fail "--update did not migrate a missing metric"
grep -q "baseline unset ->" "$FIXTURE/update-missing.txt" \
    || fail "explicit migration was not reported"
"$PYTHON_BIN" - "$BASELINE" <<'PY' \
    || fail "--update did not write the missing metric"
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if "branch" not in payload["backend"]:
    raise SystemExit("branch metric was not migrated")
PY

echo "7/8: --update refuses to lower beyond the tolerance without opt-in"
write_baseline '{"line": 90.0, "branch": 90.0}'
write_csv 80 20
if ratchet --update >"$FIXTURE/update-lower.txt" 2>&1; then
    fail "--update lowered the baseline without --allow-lower"
fi
grep -q "refusing to lower" "$FIXTURE/update-lower.txt" \
    || fail "lowering refusal was not explained"
"$PYTHON_BIN" - "$BASELINE" <<'PY' \
    || fail "blocked --update still mutated the baseline"
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if float(payload["backend"]["line"]) != 90.0:
    raise SystemExit("blocked --update changed the line baseline")
PY

echo "8/8: --update --allow-lower accepts the explicit opt-in"
write_baseline '{"line": 90.0, "branch": 90.0}'
write_csv 80 20
ratchet --update --allow-lower >"$FIXTURE/update-allow.txt" 2>&1 \
    || fail "--update --allow-lower was rejected"
"$PYTHON_BIN" - "$BASELINE" <<'PY' \
    || fail "--update --allow-lower did not lower the baseline"
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
if float(payload["backend"]["line"]) != 80.0:
    raise SystemExit("line baseline was not lowered")
PY

echo "PASS: coverage ratchet self-test"
