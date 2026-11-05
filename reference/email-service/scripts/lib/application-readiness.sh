#!/usr/bin/env bash
# Bounded adaptive polling for this component's own application process.
#
# These helpers are component-only and intentionally live OUTSIDE the shared
# PostgreSQL readiness region. `scripts/check-readiness-parity.py` only compares
# the marked region of `lib/postgres-readiness.sh`; everything here is free to
# evolve with the component.
#
# Semantics that must never change:
#   - `wait_for_application_health` returns 0 only when a real health probe
#     succeeds. A timeout or a dead process is a failure.
#   - `wait_for_process_exit` returns 0 when the process has exited within the
#     budget and 1 when it is still running. A timeout is NOT "it exited", so
#     `expect_startup_failure` must fail closed on 1.
#
# Environment overrides:
#   APPLICATION_READY_TIMEOUT_SECONDS            health budget (default 90)
#   APPLICATION_STARTUP_FAILURE_TIMEOUT_SECONDS  exit budget (default 60)
#   APPLICATION_READINESS_METRICS_FILE           optional TSV timing log
#
# The budgets are empirical, not arbitrary: see docs/TESTING.md "就绪预算校准"
# for the measured cold-start distribution, the concurrency stress run, and the
# margin multiples. The optional metrics file lets `scripts/health-snapshot.py`
# report observed readiness timings instead of only the configured budgets.

_application_readiness_record() {
    local event="$1"
    local elapsed="$2"
    local attempts="$3"
    local budget="$4"
    local metrics_file="${APPLICATION_READINESS_METRICS_FILE:-}"

    [ -n "$metrics_file" ] || return 0
    printf '%s\t%s\t%s\t%s\t%s\n' \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        "$event" "$elapsed" "$attempts" "$budget" >>"$metrics_file" 2>/dev/null || true
}

_application_probe_diagnostics() {
    local pid="$1"
    local budget="$2"
    local attempts="$3"
    local elapsed="$4"
    local last_output="$5"
    local state

    if kill -0 "$pid" >/dev/null 2>&1; then
        state="still running (pid=${pid})"
    else
        state="exited or reaped (pid=${pid})"
    fi

    {
        echo "ERROR: application was not healthy after ${elapsed}s of a ${budget}s budget (${attempts} probes)"
        echo "  process state: ${state}"
        echo "  last health probe: ${last_output}"
    } >&2
}

# wait_for_application_health <pid> <log_file> <health_url> [api_key] [budget]
#
# Returns 0 on the first successful probe, 1 on process death or budget
# exhaustion. The caller prints the application log tail in its own `fail`.
wait_for_application_health() {
    local pid="$1"
    local log_file="$2"
    local health_url="$3"
    local api_key="${4:-}"
    local budget="${5:-${APPLICATION_READY_TIMEOUT_SECONDS:-90}}"
    local start_epoch
    local elapsed
    local attempts=0
    local delay=0.25
    local next_report=5
    local last_output="health probe was not executed"

    start_epoch="$(date +%s)"
    while :; do
        attempts=$((attempts + 1))
        elapsed=$(( $(date +%s) - start_epoch ))
        if [ -n "$api_key" ]; then
            if last_output="$(
                curl --connect-timeout 1 --max-time 2 -fsS \
                    -H "X-Email-Service-Key: $api_key" \
                    "$health_url" 2>&1
            )"; then
                echo "READY application ${health_url} after ${elapsed}s (${attempts} probes)"
                _application_readiness_record "application-health" "$elapsed" "$attempts" "$budget"
                return 0
            fi
        elif last_output="$(
            curl --connect-timeout 1 --max-time 2 -fsS "$health_url" 2>&1
        )"; then
            echo "READY application ${health_url} after ${elapsed}s (${attempts} probes)"
            _application_readiness_record "application-health" "$elapsed" "$attempts" "$budget"
            return 0
        fi

        if ! kill -0 "$pid" >/dev/null 2>&1; then
            echo "ERROR: application process ${pid} exited before becoming healthy" >&2
            _application_probe_diagnostics "$pid" "$budget" "$attempts" "$elapsed" "process exited"
            _application_readiness_record "application-health-failed" "$elapsed" "$attempts" "$budget"
            return 1
        fi

        if [ "$elapsed" -ge "$next_report" ]; then
            echo "waiting for application ${health_url} elapsed=${elapsed}s probes=${attempts} last=${last_output}" >&2
            next_report=$((next_report + 5))
        fi
        if [ "$elapsed" -ge "$budget" ]; then
            break
        fi
        sleep "$delay"
        case "$delay" in
            0.25) delay=0.5 ;;
            0.5) delay=1 ;;
            *) delay=2 ;;
        esac
    done

    _application_probe_diagnostics "$pid" "$budget" "$attempts" "$elapsed" "$last_output"
    _application_readiness_record "application-health-failed" "$elapsed" "$attempts" "$budget"
    return 1
}

# wait_for_process_exit <pid> [budget]
#
# Returns 0 when the process has exited within the budget, 1 when it is still
# running. Never conflates "still running" with "exited with failure".
wait_for_process_exit() {
    local pid="$1"
    local budget="${2:-${APPLICATION_STARTUP_FAILURE_TIMEOUT_SECONDS:-60}}"
    local start_epoch
    local elapsed
    local attempts=0
    local delay=0.25
    local next_report=5

    start_epoch="$(date +%s)"
    while :; do
        attempts=$((attempts + 1))
        elapsed=$(( $(date +%s) - start_epoch ))
        if ! kill -0 "$pid" >/dev/null 2>&1; then
            echo "process ${pid} exited after ${elapsed}s (${attempts} checks)"
            _application_readiness_record "process-exit" "$elapsed" "$attempts" "$budget"
            return 0
        fi
        if [ "$elapsed" -ge "$next_report" ]; then
            echo "waiting for process ${pid} to exit elapsed=${elapsed}s checks=${attempts}" >&2
            next_report=$((next_report + 5))
        fi
        if [ "$elapsed" -ge "$budget" ]; then
            echo "ERROR: process ${pid} was still running after ${budget}s (${attempts} checks)" >&2
            _application_readiness_record "process-exit-timeout" "$elapsed" "$attempts" "$budget"
            return 1
        fi
        sleep "$delay"
        case "$delay" in
            0.25) delay=0.5 ;;
            0.5) delay=1 ;;
            *) delay=2 ;;
        esac
    done
}
