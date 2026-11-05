#!/usr/bin/env bash
# Shared bounded readiness polling for disposable PostgreSQL containers.
#
# Callers source this file and use `wait_for_disposable_postgres`. The helper
# never retries past a fixed budget and never hides a real failure: a timeout
# prints the last `pg_isready` output, the container state and the container
# log tail, then returns non-zero so the caller can fail closed.
#
# The delay between attempts starts at 0.25s and backs off to a 2s cap. Polling
# adapts to a slow start without burning CPU or flooding Docker while the host
# is saturated, and a permanently broken container still fails at the budget
# instead of looping forever.
#
# Environment overrides:
#   POSTGRES_READY_TIMEOUT_SECONDS  total budget in seconds (default 60)
#   POSTGRES_READY_LOG_LINES        container log lines on failure (default 100)

# The code between the BEGIN/END markers must stay byte-identical (after line-ending
# normalization) to `reference/email-service/scripts/lib/postgres-readiness.sh`. Those
# headers may differ because the component is a self-contained project.
# `scripts/check-readiness-parity.py` enforces the region in the verification gate;
# any component-only helper belongs outside the markers.
# === BEGIN SHARED POSTGRES READINESS ===
_wait_for_postgres_diagnostics() {
    local container_name="$1"
    local budget="$2"
    local attempts="$3"
    local last_output="$4"
    local log_lines="${POSTGRES_READY_LOG_LINES:-100}"
    local state
    local logs

    state="$(
        docker inspect --format \
            'status={{.State.Status}} running={{.State.Running}} exit={{.State.ExitCode}} error={{.State.Error}}' \
            "$container_name" 2>/dev/null || true
    )"
    if [ -z "$state" ]; then
        state="<container not found>"
    fi
    if [ "$state" = "<container not found>" ]; then
        logs=""
    else
        logs="$(docker logs --tail "$log_lines" "$container_name" 2>&1 || true)"
    fi

    {
        echo "ERROR: PostgreSQL container ${container_name} was not ready after ${budget}s (${attempts} attempts)"
        echo "  last pg_isready: ${last_output}"
        echo "  docker state: ${state}"
        if [ "$state" = "<container not found>" ]; then
            echo "  container logs: <container was removed before diagnostics>"
        else
            echo "  last ${log_lines} container log lines:"
            if [ -n "$logs" ]; then
                printf '%s\n' "$logs" | sed 's/^/    /'
            else
                echo "    <no container logs available>"
            fi
        fi
    } >&2
}

# wait_for_disposable_postgres <container> <port> <user> <password> <database> [budget]
#
# Returns 0 once `pg_isready` accepts connections, 1 on budget exhaustion.
wait_for_disposable_postgres() {
    local container_name="$1"
    local port="$2"
    local user="$3"
    local password="$4"
    local database="$5"
    local budget="${6:-${POSTGRES_READY_TIMEOUT_SECONDS:-60}}"
    local start_epoch
    local elapsed
    local attempts=0
    local delay=0.25
    local next_report=5
    local last_output="pg_isready was not executed"

    start_epoch="$(date +%s)"
    while :; do
        attempts=$((attempts + 1))
        elapsed=$(( $(date +%s) - start_epoch ))
        if last_output="$(
            PGPASSWORD="$password" pg_isready \
                -h 127.0.0.1 \
                -p "$port" \
                -U "$user" \
                -d "$database" 2>&1
        )"; then
            echo "READY PostgreSQL 127.0.0.1:${port} after ${elapsed}s (${attempts} attempts)"
            return 0
        fi
        if [ "$elapsed" -ge "$next_report" ]; then
            echo "waiting for PostgreSQL 127.0.0.1:${port} container=${container_name} elapsed=${elapsed}s attempts=${attempts} last=${last_output}" >&2
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

    _wait_for_postgres_diagnostics \
        "$container_name" "$budget" "$attempts" "$last_output"
    return 1
}
# === END SHARED POSTGRES READINESS ===
