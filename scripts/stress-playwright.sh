#!/usr/bin/env bash

# Repeat the Mock Playwright suite (or a grep-selected subset) N times and
# report how many iterations passed. This is the reproducible entry point for
# flake triage; it is intentionally not part of scripts/verify.sh.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ITERATIONS="${1:-5}"
PLAYWRIGHT_WORKERS="${PLAYWRIGHT_WORKERS:-3}"
CPU_BURNERS="${CPU_BURNERS:-0}"
PLAYWRIGHT_GREP="${PLAYWRIGHT_GREP:-}"
LOG_DIR="${PLAYWRIGHT_LOG_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/uniauth-playwright-stress.XXXXXX")}"

if ! [[ "$ITERATIONS" =~ ^[1-9][0-9]*$ ]]; then
    echo "usage: $0 [iterations] (iterations must be a positive integer)" >&2
    exit 2
fi
if ! [[ "$PLAYWRIGHT_WORKERS" =~ ^[1-9][0-9]*$ ]]; then
    echo "PLAYWRIGHT_WORKERS must be a positive integer" >&2
    exit 2
fi
if ! [[ "$CPU_BURNERS" =~ ^[0-9]+$ ]]; then
    echo "CPU_BURNERS must be a non-negative integer" >&2
    exit 2
fi
if ! command -v node >/dev/null 2>&1; then
    echo "node is required" >&2
    exit 1
fi

burner_pids=()
cleanup() {
    local exit_code=$?
    for pid in "${burner_pids[@]:-}"; do
        if [ -n "$pid" ]; then
            kill -TERM "$pid" >/dev/null 2>&1 || true
            wait "$pid" >/dev/null 2>&1 || true
        fi
    done
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

if [ "$CPU_BURNERS" -gt 0 ]; then
    echo "Starting $CPU_BURNERS CPU burners"
    for _ in $(seq 1 "$CPU_BURNERS"); do
        node -e 'while (true) {}' &
        burner_pids+=("$!")
    done
fi

passed=0
failed=0
started_at="$(date +%s)"
mkdir -p "$LOG_DIR"

for iteration in $(seq 1 "$ITERATIONS"); do
    log_file="$LOG_DIR/run-${iteration}.log"
    echo "Playwright stress ${iteration}/${ITERATIONS} (workers=${PLAYWRIGHT_WORKERS}${PLAYWRIGHT_GREP:+, grep=${PLAYWRIGHT_GREP}})"
    command=(npx playwright test --workers="$PLAYWRIGHT_WORKERS")
    if [ -n "$PLAYWRIGHT_GREP" ]; then
        command+=(-g "$PLAYWRIGHT_GREP")
    fi
    if (cd "$PROJECT_DIR/frontend" && "${command[@]}") >"$log_file" 2>&1; then
        passed=$((passed + 1))
        echo "  PASS"
    else
        failed=$((failed + 1))
        echo "  FAIL (log: $log_file)"
        tail -20 "$log_file" >&2 || true
    fi
done

elapsed=$(( $(date +%s) - started_at ))
echo "Playwright stress result: passed=${passed} failed=${failed} iterations=${ITERATIONS} elapsed=${elapsed}s"
echo "Logs: $LOG_DIR"

if [ "$failed" -gt 0 ]; then
    exit 1
fi
