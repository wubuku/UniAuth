#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/verification-artifacts-guard.XXXXXX")"
PYTHON_BIN="${PYTHON_BIN:-python3}"

source "$SCRIPT_DIR/verification-artifacts-guard.sh"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT INT TERM

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

expect_rejection() {
    local requested_dir="$1"
    local expected_message="$2"
    local output_file="$TEMP_DIR/rejection.log"

    if validate_verification_artifacts_dir \
            "$PYTHON_BIN" \
            "$requested_dir" \
            "$PROJECT_DIR" >"$output_file" 2>&1; then
        fail "artifact path was unexpectedly accepted: $requested_dir"
    fi
    grep -Fq "$expected_message" "$output_file" \
        || fail "artifact path rejection did not explain the failure"
}

echo "1/8 Reject a relative artifact directory"
expect_rejection \
    "verification-artifacts" \
    "VERIFICATION_ARTIFACTS_DIR must be an absolute path"

echo "2/8 Reject an artifact directory inside the repository"
expect_rejection \
    "$PROJECT_DIR/.verification-artifacts-guard-direct" \
    "VERIFICATION_ARTIFACTS_DIR must be outside the source repository"

echo "3/8 Reject a symlink path that resolves inside the repository"
probe_name=".verification-artifacts-guard-symlink-$$"
ln -s "$PROJECT_DIR" "$TEMP_DIR/repository-link"
expect_rejection \
    "$TEMP_DIR/repository-link/$probe_name" \
    "VERIFICATION_ARTIFACTS_DIR must be outside the source repository"
[ ! -e "$PROJECT_DIR/$probe_name" ] \
    || fail "symlink-path validation created an artifact directory in the repository"

echo "4/8 Accept and normalize an external artifact directory"
external_dir="$TEMP_DIR/external/artifacts"
resolved_temp_dir="$(
    "$PYTHON_BIN" - "$TEMP_DIR" <<'PY'
from pathlib import Path
import sys

print(Path(sys.argv[1]).resolve(strict=True))
PY
)"
resolved_dir="$(
    validate_verification_artifacts_dir \
        "$PYTHON_BIN" \
        "$external_dir" \
        "$PROJECT_DIR"
)"
[ "$resolved_dir" = "$resolved_temp_dir/external/artifacts" ] \
    || fail "external artifact directory was not normalized as expected"
[ ! -e "$external_dir" ] \
    || fail "artifact validation created the external directory"

echo "5/8 Skip duplicate preservation after a clean successful exit"
if verification_artifacts_need_preservation true true 0; then
    fail "clean success requested duplicate artifact preservation"
fi

echo "6/8 Re-preserve artifacts when a later failure follows recorded success"
verification_artifacts_need_preservation true true 23 \
    || fail "later failure would leave a previously recorded success status"

run_signal_test() {
    local signal_name="$1"
    local expected_exit_code="$2"
    local artifacts_dir="$TEMP_DIR/signal-$signal_name"
    local process_log="$TEMP_DIR/signal-$signal_name.log"
    local result_file="$TEMP_DIR/signal-$signal_name.result"

    "$PYTHON_BIN" - \
        "$PROJECT_DIR/scripts/verify.sh" \
        "$artifacts_dir" \
        "$process_log" \
        "$signal_name" \
        "$result_file" <<'PY'
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

script, artifacts_dir, log_path, signal_name, result_path = sys.argv[1:]
environment = os.environ.copy()
environment["PYTHON_BIN"] = sys.executable
environment["UNIAUTH_VERIFICATION_SIGNAL_TEST_MODE"] = "true"
environment["VERIFICATION_ARTIFACTS_DIR"] = artifacts_dir

with Path(log_path).open("w", encoding="utf-8") as log_file:
    process = subprocess.Popen(
        [script],
        cwd=str(Path(script).resolve().parent.parent),
        env=environment,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        text=True,
    )
    deadline = time.monotonic() + 15
    marker = "Verification signal self-test ready"
    while time.monotonic() < deadline:
        log_file.flush()
        if marker in Path(log_path).read_text(encoding="utf-8"):
            break
        if process.poll() is not None:
            raise SystemExit(
                f"verification process exited before signal marker: {process.returncode}"
            )
        time.sleep(0.1)
    else:
        process.terminate()
        process.wait(timeout=5)
        raise SystemExit("verification signal self-test did not become ready")

    process.send_signal(getattr(signal, signal_name))
    return_code = process.wait(timeout=15)

Path(result_path).write_text(str(return_code), encoding="utf-8")
PY

    [ "$(cat "$result_file")" = "$expected_exit_code" ] \
        || fail "$signal_name did not return $expected_exit_code"
    status_file="$(
        find "$artifacts_dir" \
            -name verification-status.txt \
            -type f \
            -print \
            -quit
    )"
    [ -n "$status_file" ] \
        || fail "$signal_name did not preserve a verification status artifact"
    grep -Fxq "exit_code=$expected_exit_code" "$status_file" \
        || fail "$signal_name artifact recorded the wrong exit code"
}

echo "7/8 Record SIGINT as exit code 130"
run_signal_test SIGINT 130

echo "8/8 Record SIGTERM as exit code 143"
run_signal_test SIGTERM 143

echo "PASS: verification artifact path guard"
