#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"
TEMP_DIR="${PYTHON_SUPPLY_CHAIN_TEMP_DIR:-}"
REMOVE_TEMP_DIR=false

if [ -z "$TEMP_DIR" ]; then
    TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/uniauth-python-supply-chain.XXXXXX")"
    REMOVE_TEMP_DIR=true
else
    mkdir -p "$TEMP_DIR"
fi

cleanup() {
    if [ "$REMOVE_TEMP_DIR" = "true" ]; then
        rm -rf "$TEMP_DIR"
    fi
}
trap cleanup EXIT INT TERM

for required_file in \
        python-resource-server/requirements.in \
        python-resource-server/requirements.lock \
        python-resource-server/requirements-tools.in \
        python-resource-server/requirements-tools.lock; do
    if [ ! -f "$PROJECT_DIR/$required_file" ]; then
        echo "ERROR: Python supply-chain input is missing: $required_file" >&2
        exit 1
    fi
done

"$PYTHON_BIN" - <<'PY'
import sys

if sys.version_info[:2] != (3, 10):
    raise SystemExit(
        "ERROR: Python supply-chain locks require Python 3.10; "
        f"received {sys.version_info.major}.{sys.version_info.minor}"
    )
PY

RUNTIME_VENV="$TEMP_DIR/runtime-venv"
TOOLS_VENV="$TEMP_DIR/tools-venv"
LOCK_PROJECT="$TEMP_DIR/lock-project"
AUDIT_REPORT="$TEMP_DIR/pip-audit-report.json"
AUDIT_EXCEPTIONS="$PROJECT_DIR/python-resource-server/pip-audit-exceptions.json"

if [ ! -f "$AUDIT_EXCEPTIONS" ]; then
    echo "ERROR: pip-audit exception manifest is missing" >&2
    exit 1
fi

"$PYTHON_BIN" -m venv "$RUNTIME_VENV"
"$PYTHON_BIN" -m venv "$TOOLS_VENV"

"$RUNTIME_VENV/bin/python" -m pip install \
    --disable-pip-version-check \
    --quiet \
    --require-hashes \
    -r "$PROJECT_DIR/python-resource-server/requirements.lock"
"$TOOLS_VENV/bin/python" -m pip install \
    --disable-pip-version-check \
    --quiet \
    --require-hashes \
    -r "$PROJECT_DIR/python-resource-server/requirements-tools.lock"

mkdir -p "$LOCK_PROJECT/python-resource-server"
cp "$PROJECT_DIR/python-resource-server/requirements.in" \
    "$LOCK_PROJECT/python-resource-server/requirements.in"
cp "$PROJECT_DIR/python-resource-server/requirements-tools.in" \
    "$LOCK_PROJECT/python-resource-server/requirements-tools.in"
cp "$PROJECT_DIR/python-resource-server/requirements.lock" \
    "$LOCK_PROJECT/python-resource-server/requirements.lock.expected"
cp "$PROJECT_DIR/python-resource-server/requirements-tools.lock" \
    "$LOCK_PROJECT/python-resource-server/requirements-tools.lock.expected"

(
    cd "$LOCK_PROJECT"
    "$TOOLS_VENV/bin/pip-compile" \
        --allow-unsafe \
        --generate-hashes \
        --quiet \
        --output-file=python-resource-server/requirements.lock \
        --strip-extras \
        python-resource-server/requirements.in
    "$TOOLS_VENV/bin/pip-compile" \
        --allow-unsafe \
        --generate-hashes \
        --quiet \
        --output-file=python-resource-server/requirements-tools.lock \
        --strip-extras \
        python-resource-server/requirements-tools.in
)

cmp \
    "$LOCK_PROJECT/python-resource-server/requirements.lock.expected" \
    "$LOCK_PROJECT/python-resource-server/requirements.lock" \
    || {
        echo "ERROR: Python runtime lock is stale; regenerate requirements.lock" >&2
        exit 1
    }
cmp \
    "$LOCK_PROJECT/python-resource-server/requirements-tools.lock.expected" \
    "$LOCK_PROJECT/python-resource-server/requirements-tools.lock" \
    || {
        echo "ERROR: Python tools lock is stale; regenerate requirements-tools.lock" >&2
        exit 1
    }

set +e
"$TOOLS_VENV/bin/pip-audit" \
    --strict \
    --require-hashes \
    --disable-pip \
    --progress-spinner off \
    --format json \
    --output "$AUDIT_REPORT" \
    --requirement "$PROJECT_DIR/python-resource-server/requirements.lock"
audit_exit_code=$?
set -e

"$PYTHON_BIN" - "$AUDIT_REPORT" "$AUDIT_EXCEPTIONS" "$audit_exit_code" <<'PY'
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

report_path = Path(sys.argv[1])
exceptions_path = Path(sys.argv[2])
audit_exit_code = int(sys.argv[3])
if not report_path.is_file() or report_path.stat().st_size == 0:
    raise SystemExit("ERROR: pip-audit report is missing or empty")

report = json.loads(report_path.read_text(encoding="utf-8"))
dependencies = report.get("dependencies")
if not isinstance(dependencies, list) or not dependencies:
    raise SystemExit("ERROR: pip-audit report has no dependency evidence")

reported = {
    vulnerability["id"]: (dependency["name"].lower(), dependency["version"])
    for dependency in dependencies
    for vulnerability in dependency.get("vulns", [])
}
manifest = json.loads(exceptions_path.read_text(encoding="utf-8"))
if manifest.get("schemaVersion") != 1:
    raise SystemExit("ERROR: unsupported pip-audit exception manifest schema")

now = datetime.now(timezone.utc)
exceptions = {}
for exception in manifest.get("exceptions", []):
    required_fields = {
        "id",
        "package",
        "version",
        "fixedVersion",
        "owner",
        "expiresAt",
        "reason",
        "evidence",
    }
    missing = sorted(required_fields - exception.keys())
    if missing:
        raise SystemExit(
            "ERROR: pip-audit exception is missing fields: " + ", ".join(missing)
        )
    expires_at = datetime.fromisoformat(
        exception["expiresAt"].replace("Z", "+00:00")
    ).astimezone(timezone.utc)
    if expires_at <= now:
        raise SystemExit(
            f"ERROR: pip-audit exception expired: {exception['id']}"
        )
    if not exception["owner"].strip() or not exception["reason"].strip():
        raise SystemExit(
            f"ERROR: pip-audit exception lacks ownership or rationale: "
            f"{exception['id']}"
        )
    if not isinstance(exception["evidence"], list) or not exception["evidence"]:
        raise SystemExit(
            f"ERROR: pip-audit exception lacks evidence: {exception['id']}"
        )
    exception_id = exception["id"]
    if exception_id in exceptions:
        raise SystemExit(
            f"ERROR: duplicate pip-audit exception: {exception_id}"
        )
    exceptions[exception_id] = (
        exception["package"].lower(),
        exception["version"],
    )

unapproved = sorted(reported.keys() - exceptions.keys())
stale = sorted(exceptions.keys() - reported.keys())
mismatched = sorted(
    advisory
    for advisory in reported.keys() & exceptions.keys()
    if reported[advisory] != exceptions[advisory]
)
if unapproved:
    raise SystemExit(
        "ERROR: pip-audit found unapproved vulnerabilities: "
        + ", ".join(unapproved)
    )
if stale:
    raise SystemExit(
        "ERROR: pip-audit exceptions are stale: " + ", ".join(stale)
    )
if mismatched:
    raise SystemExit(
        "ERROR: pip-audit exception package/version mismatch: "
        + ", ".join(mismatched)
    )
if reported and audit_exit_code == 0:
    raise SystemExit("ERROR: pip-audit returned success despite vulnerabilities")
if not reported and audit_exit_code != 0:
    raise SystemExit(
        f"ERROR: pip-audit failed without reporting vulnerabilities "
        f"(exit {audit_exit_code})"
    )
PY

(
    cd "$PROJECT_DIR/python-resource-server"
    "$RUNTIME_VENV/bin/python" -m unittest -v
)

printf '%s\n' "$RUNTIME_VENV/bin/python" >"$TEMP_DIR/runtime-python-path"
echo "Python supply-chain report: $AUDIT_REPORT"
echo "PASS: Python hash locks, audit, and contracts"
