#!/usr/bin/env bash
# Self-test for scripts/lib/application-readiness.sh.
#
# The point of this file is the semantic that bit the previous fixed-loop design:
# a startup that only times out must NEVER be accepted as "it failed as expected".
# These checks fail if a timeout is conflated with a successful exit.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/application-readiness.sh
source "$PROJECT_DIR/scripts/lib/application-readiness.sh"
PYTHON_BIN="${PYTHON_BIN:-python3}"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/email-app-readiness.XXXXXX")"

cleanup() {
    if [ -n "${HTTP_PID:-}" ]; then
        kill "$HTTP_PID" >/dev/null 2>&1 || true
        wait "$HTTP_PID" >/dev/null 2>&1 || true
    fi
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT INT TERM

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

echo "1/6: an exited process is detected"
true &
EXIT_PID=$!
wait_for_process_exit "$EXIT_PID" 5 \
    || fail "an already-exited process was not detected"

echo "2/6: a still-running process is a timeout failure, not an exit"
sleep 3 &
RUNNING_PID=$!
if wait_for_process_exit "$RUNNING_PID" 1; then
    fail "timeout was reported as a successful exit (expect_startup_failure would pass)"
fi
kill "$RUNNING_PID" >/dev/null 2>&1 || true
wait "$RUNNING_PID" >/dev/null 2>&1 || true

echo "3/6: the health probe succeeds against a real HTTP endpoint"
HTTP_PORT="$(
    "$PYTHON_BIN" - <<'PY'
import socket

with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
)"
"$PYTHON_BIN" -m http.server "$HTTP_PORT" --bind 127.0.0.1 \
    >"$TEMP_DIR/http.log" 2>&1 &
HTTP_PID=$!
wait_for_application_health \
    "$HTTP_PID" "$TEMP_DIR/application.log" \
    "http://127.0.0.1:${HTTP_PORT}/" "" 10 \
    || fail "health probe did not accept a reachable endpoint"

echo "4/6: the health probe fails for a dead process instead of waiting out the budget"
true &
DEAD_PID=$!
wait "$DEAD_PID" >/dev/null 2>&1 || true
if wait_for_application_health \
        "$DEAD_PID" "$TEMP_DIR/application.log" \
        "http://127.0.0.1:1/" "" 10 2>"$TEMP_DIR/dead.txt"; then
    fail "health probe accepted a dead process"
fi
grep -q "exited before becoming healthy" "$TEMP_DIR/dead.txt" \
    || fail "dead-process diagnostics were not reported"

echo "5/6: a successful probe records its timing when asked"
: >"$TEMP_DIR/readiness.tsv"
(
    export APPLICATION_READINESS_METRICS_FILE="$TEMP_DIR/readiness.tsv"
    wait_for_application_health \
        "$HTTP_PID" "$TEMP_DIR/application.log" \
        "http://127.0.0.1:${HTTP_PORT}/" "" 10
) >/dev/null \
    || fail "health probe failed while recording metrics"
grep -q "application-health" "$TEMP_DIR/readiness.tsv" \
    || fail "successful probe did not record a readiness metric"

echo "6/6: an unwritable metrics path cannot turn success into failure"
mkdir -p "$TEMP_DIR/not-a-file"
(
    export APPLICATION_READINESS_METRICS_FILE="$TEMP_DIR/not-a-file"
    wait_for_application_health \
        "$HTTP_PID" "$TEMP_DIR/application.log" \
        "http://127.0.0.1:${HTTP_PORT}/" "" 10 2>/dev/null
) >/dev/null \
    || fail "metrics write failure changed a healthy probe result"

echo "PASS: application readiness self-test"
