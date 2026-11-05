#!/usr/bin/env bash
# Self-test for scripts/check-readiness-parity.py.
#
# Proves the guard actually protects the shared PostgreSQL readiness region:
#   - the real repository pair passes,
#   - identical fixtures pass,
#   - a single-side edit inside the markers fails (both directions),
#   - a component-only helper added outside the markers still passes,
#   - a malformed marker layout fails closed.
#
# It never modifies the real helper scripts.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/uniauth-readiness-parity.XXXXXX")"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT INT TERM

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

check() {
    "$PYTHON_BIN" "$PROJECT_DIR/scripts/check-readiness-parity.py" "$@"
}

ROOT_FIXTURE="$TEMP_DIR/root.sh"
COMPONENT_FIXTURE="$TEMP_DIR/component.sh"

make_pair() {
    cat >"$ROOT_FIXTURE" <<'SH'
#!/usr/bin/env bash
# Root header differs by design.
# === BEGIN SHARED POSTGRES READINESS ===
wait_for_disposable_postgres() {
    echo "root shared body"
}
# === END SHARED POSTGRES READINESS ===
SH
    cat >"$COMPONENT_FIXTURE" <<'SH'
#!/usr/bin/env bash
# Component header differs by design.
# === BEGIN SHARED POSTGRES READINESS ===
wait_for_disposable_postgres() {
    echo "root shared body"
}
# === END SHARED POSTGRES READINESS ===
SH
}

echo "1/11: the real repository helpers are in parity"
check || fail "real helpers are already drifted"

echo "2/11: identical fixtures with different headers pass"
make_pair
check --root-script "$ROOT_FIXTURE" --component-script "$COMPONENT_FIXTURE" \
    || fail "differing headers outside the markers should be allowed"

echo "3/11: a root-only edit inside the markers fails"
sed -i.bak 's/root shared body/root only body/' "$ROOT_FIXTURE"
rm -f "$ROOT_FIXTURE.bak"
if check --root-script "$ROOT_FIXTURE" --component-script "$COMPONENT_FIXTURE" \
        2>"$TEMP_DIR/root-drift.txt"; then
    fail "guard accepted a root-only edit inside the shared region"
fi
grep -q "root only body" "$TEMP_DIR/root-drift.txt" \
    || fail "root drift diff did not show the edited line"

echo "4/11: a component-only edit inside the markers fails"
make_pair
sed -i.bak 's/root shared body/component only body/' "$COMPONENT_FIXTURE"
rm -f "$COMPONENT_FIXTURE.bak"
if check --root-script "$ROOT_FIXTURE" --component-script "$COMPONENT_FIXTURE" \
        2>"$TEMP_DIR/component-drift.txt"; then
    fail "guard accepted a component-only edit inside the shared region"
fi
grep -q "component only body" "$TEMP_DIR/component-drift.txt" \
    || fail "component drift diff did not show the edited line"

echo "5/11: component-only code outside the markers is allowed"
make_pair
cat >>"$COMPONENT_FIXTURE" <<'SH'

# Component-specific helper: intentionally outside the shared region.
wait_for_application() {
    echo "component only"
}
SH
check --root-script "$ROOT_FIXTURE" --component-script "$COMPONENT_FIXTURE" \
    || fail "component helper outside the markers must not be treated as drift"

echo "6/11: copying a shared function outside the markers fails"
make_pair
cat >>"$COMPONENT_FIXTURE" <<'SH'

# Bypass attempt: same shared name, locally edited copy.
wait_for_disposable_postgres() {
    echo "component shadow copy"
}
SH
if check --root-script "$ROOT_FIXTURE" --component-script "$COMPONENT_FIXTURE" \
        2>"$TEMP_DIR/shadow.txt"; then
    fail "guard accepted a shared function copied outside the markers"
fi
grep -q "redefined outside the markers" "$TEMP_DIR/shadow.txt" \
    || fail "shadow definition failure did not name the bypass rule"
grep -q "wait_for_disposable_postgres" "$TEMP_DIR/shadow.txt" \
    || fail "shadow definition failure did not name the function"

echo "7/11: a malformed marker layout fails closed"
make_pair
sed -i.bak '/# === END SHARED POSTGRES READINESS ===/d' "$COMPONENT_FIXTURE"
rm -f "$COMPONENT_FIXTURE.bak"
if check --root-script "$ROOT_FIXTURE" --component-script "$COMPONENT_FIXTURE" \
        2>"$TEMP_DIR/malformed.txt"; then
    fail "guard accepted a file without an END marker"
fi
grep -q "expected exactly one BEGIN and one END marker" "$TEMP_DIR/malformed.txt" \
    || fail "malformed layout did not report the missing marker"

echo "8/11: the 'function name {' spelling is also a shadow"
make_pair
cat >>"$COMPONENT_FIXTURE" <<'SH'

# Same shared name, different accepted spelling.
function wait_for_disposable_postgres {
    echo "component shadow via keyword"
}
SH
if check --root-script "$ROOT_FIXTURE" --component-script "$COMPONENT_FIXTURE" \
        2>"$TEMP_DIR/shadow-keyword.txt"; then
    fail "guard accepted a keyword-style shared function copy"
fi
grep -q "redefined outside the markers" "$TEMP_DIR/shadow-keyword.txt" \
    || fail "keyword-style shadow was not reported"

echo "9/11: the 'name () {' spelling with spaces is also a shadow"
make_pair
cat >>"$COMPONENT_FIXTURE" <<'SH'

# Same shared name, spaced parentheses.
wait_for_disposable_postgres () {
    echo "component shadow via spaces"
}
SH
if check --root-script "$ROOT_FIXTURE" --component-script "$COMPONENT_FIXTURE" \
        2>"$TEMP_DIR/shadow-spaces.txt"; then
    fail "guard accepted a spaced-parens shared function copy"
fi
grep -q "redefined outside the markers" "$TEMP_DIR/shadow-spaces.txt" \
    || fail "spaced-parens shadow was not reported"

echo "10/11: comments, strings and heredocs do not look like shadows"
make_pair
cat >>"$COMPONENT_FIXTURE" <<'SH'

# Example only: wait_for_disposable_postgres() { ... }
echo "wait_for_disposable_postgres () {"
MULTILINE="
wait_for_disposable_postgres() {
"
cat <<'EOF'
wait_for_disposable_postgres() {
EOF
SH
check --root-script "$ROOT_FIXTURE" --component-script "$COMPONENT_FIXTURE" \
    || fail "guard treated comment/string/heredoc text as a definition"

echo "11/11: the keyword spelling inside both markers is still recognized"
make_pair
"$PYTHON_BIN" - "$ROOT_FIXTURE" "$COMPONENT_FIXTURE" <<'PY'
import sys
from pathlib import Path

for path in sys.argv[1:]:
    source = Path(path).read_text(encoding="utf-8")
    source = source.replace(
        "wait_for_disposable_postgres() {",
        "function wait_for_disposable_postgres {",
    )
    Path(path).write_text(source, encoding="utf-8")
PY
check --root-script "$ROOT_FIXTURE" --component-script "$COMPONENT_FIXTURE" \
    || fail "identical keyword-style regions should pass"

echo "PASS: readiness parity guard self-test"
