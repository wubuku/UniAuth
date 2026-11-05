#!/usr/bin/env bash
# Self-test for the component mutation baseline guard.
#
# The component guard must use the same fail-closed semantics as the root one:
# a deleted metric, a deleted `classes` section, a dropped generated count, or
# any class-level regression must exit non-zero.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/email-mutation-baseline.XXXXXX")"
CSV="$TEMP_DIR/mutations.csv"
BASELINE="$TEMP_DIR/mutation-baseline.json"
SCRIPT="$PROJECT_DIR/scripts/check-mutation-baseline.py"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT INT TERM

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

write_csv() {
    local status_a="$1"
    local status_b="$2"
    local status_c="$3"
    local include_third_class="${4:-yes}"
    {
        printf 'File,Class,Mutator,Method,Line,Status,Test\n'
        printf 'EmailApiKeyFilter.java,org.dddml.email.config.EmailApiKeyFilter,Mutator,method,1,%s,\n' "$status_a"
        printf 'EmailResponseSecurityFilter.java,org.dddml.email.config.EmailResponseSecurityFilter,Mutator,method,2,%s,\n' "$status_b"
        if [ "$include_third_class" = "yes" ]; then
            printf 'EmailRateLimiter.java,org.dddml.email.service.EmailRateLimiter,Mutator,method,3,%s,\n' "$status_c"
        fi
    } >"$CSV"
}

write_csv_extra() {
    {
        printf 'File,Class,Mutator,Method,Line,Status,Test\n'
        printf 'EmailApiKeyFilter.java,org.dddml.email.config.EmailApiKeyFilter,Mutator,method,1,KILLED,\n'
        printf 'EmailApiKeyFilter.java,org.dddml.email.config.EmailApiKeyFilter,Mutator,method,2,KILLED,\n'
        printf 'EmailResponseSecurityFilter.java,org.dddml.email.config.EmailResponseSecurityFilter,Mutator,method,3,KILLED,\n'
        printf 'EmailRateLimiter.java,org.dddml.email.service.EmailRateLimiter,Mutator,method,4,KILLED,\n'
    } >"$CSV"
}

check() {
    "$PYTHON_BIN" "$SCRIPT" --baseline "$BASELINE" --csv "$CSV"
}

remove_top_key() {
    local key="$1"
    "$PYTHON_BIN" - "$BASELINE" "$key" <<'PY'
import json
import sys

baseline_path, key = sys.argv[1:3]
with open(baseline_path, encoding="utf-8") as handle:
    payload = json.load(handle)
del payload[key]
with open(baseline_path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PY
}

remove_class_key() {
    local class_name="$1"
    local key="$2"
    "$PYTHON_BIN" - "$BASELINE" "$class_name" "$key" <<'PY'
import json
import sys

baseline_path, class_name, key = sys.argv[1:4]
with open(baseline_path, encoding="utf-8") as handle:
    payload = json.load(handle)
del payload["classes"][class_name][key]
with open(baseline_path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PY
}

echo "1/11: a matching report passes"
write_csv KILLED KILLED KILLED
"$PYTHON_BIN" "$SCRIPT" --baseline "$BASELINE" --csv "$CSV" --update >/dev/null \
    || fail "could not record the baseline fixture"
check >"$TEMP_DIR/pass.txt" 2>&1 \
    || fail "a matching mutation report was rejected"

echo "2/11: a lower kill count fails"
write_csv SURVIVED KILLED KILLED
if check >"$TEMP_DIR/killed.txt" 2>&1; then
    fail "guard accepted a dropped kill count"
fi
grep -q "killed mutants dropped" "$TEMP_DIR/killed.txt" \
    || fail "dropped kill count did not name the regression"

echo "3/11: growing no-coverage mutants fails"
write_csv NO_COVERAGE KILLED KILLED
if check >"$TEMP_DIR/coverage.txt" 2>&1; then
    fail "guard accepted grown no-coverage mutants"
fi
grep -q "no-coverage mutants grew" "$TEMP_DIR/coverage.txt" \
    || fail "grown no-coverage mutants did not name the regression"

echo "4/11: a changed probe scope fails"
write_csv KILLED KILLED KILLED no
if check >"$TEMP_DIR/scope.txt" 2>&1; then
    fail "guard accepted a report that dropped a scoped class"
fi
grep -q "probe scope changed" "$TEMP_DIR/scope.txt" \
    || fail "scope change was not reported"

echo "5/11: a missing report fails"
rm -f "$CSV"
if check >"$TEMP_DIR/missing-csv.txt" 2>&1; then
    fail "guard accepted a missing PIT report"
fi
grep -q "PIT CSV is missing" "$TEMP_DIR/missing-csv.txt" \
    || fail "missing PIT report did not explain the problem"

echo "6/11: a missing baseline fails"
write_csv KILLED KILLED KILLED
rm -f "$BASELINE"
if check >"$TEMP_DIR/missing-baseline.txt" 2>&1; then
    fail "guard accepted a missing baseline"
fi
grep -q "mutation baseline is missing" "$TEMP_DIR/missing-baseline.txt" \
    || fail "missing baseline did not explain the problem"

echo "7/11: a dropped generated count fails even when every remaining mutant dies"
write_csv_extra
"$PYTHON_BIN" "$SCRIPT" --baseline "$BASELINE" --csv "$CSV" --update >/dev/null \
    || fail "could not record the wider baseline fixture"
write_csv KILLED KILLED KILLED
if check >"$TEMP_DIR/generated.txt" 2>&1; then
    fail "guard accepted a smaller generated mutant set"
fi
grep -q "generated mutants dropped" "$TEMP_DIR/generated.txt" \
    || fail "generated drop was not reported"

echo "8/11: a baseline missing a top-level metric fails closed"
write_csv KILLED KILLED KILLED
"$PYTHON_BIN" "$SCRIPT" --baseline "$BASELINE" --csv "$CSV" --update >/dev/null \
    || fail "could not record the baseline fixture"
remove_top_key generated
if check >"$TEMP_DIR/missing-metric.txt" 2>&1; then
    fail "guard accepted a baseline without the generated metric"
fi
grep -q "missing required metric" "$TEMP_DIR/missing-metric.txt" \
    || fail "missing metric was not reported"

echo "9/11: a baseline missing the whole classes section fails closed"
write_csv KILLED KILLED KILLED
"$PYTHON_BIN" "$SCRIPT" --baseline "$BASELINE" --csv "$CSV" --update >/dev/null \
    || fail "could not record the baseline fixture"
remove_top_key classes
if check >"$TEMP_DIR/missing-classes.txt" 2>&1; then
    fail "guard accepted a baseline without the classes section"
fi
grep -q "missing the required classes section" "$TEMP_DIR/missing-classes.txt" \
    || fail "missing classes section was not reported"

echo "10/11: a class baseline missing the killed count fails closed"
write_csv KILLED KILLED KILLED
"$PYTHON_BIN" "$SCRIPT" --baseline "$BASELINE" --csv "$CSV" --update >/dev/null \
    || fail "could not record the baseline fixture"
remove_class_key org.dddml.email.config.EmailApiKeyFilter killed
if check >"$TEMP_DIR/class-killed.txt" 2>&1; then
    fail "guard accepted a class baseline without its killed count"
fi
grep -q "EmailApiKeyFilter: baseline is missing the killed count" \
    "$TEMP_DIR/class-killed.txt" \
    || fail "missing class-level killed count was not reported"

echo "11/11: a class baseline missing the noCoverage count fails closed"
write_csv KILLED KILLED KILLED
"$PYTHON_BIN" "$SCRIPT" --baseline "$BASELINE" --csv "$CSV" --update >/dev/null \
    || fail "could not record the baseline fixture"
remove_class_key org.dddml.email.config.EmailApiKeyFilter noCoverage
if check >"$TEMP_DIR/class-no-coverage.txt" 2>&1; then
    fail "guard accepted a class baseline without its noCoverage count"
fi
grep -q "EmailApiKeyFilter: baseline is missing the noCoverage count" \
    "$TEMP_DIR/class-no-coverage.txt" \
    || fail "missing class-level noCoverage count was not reported"

echo "PASS: component mutation baseline guard self-test"
