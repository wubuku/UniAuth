#!/usr/bin/env bash

validate_verification_artifacts_dir() {
    local python_bin="$1"
    local requested_dir="$2"
    local source_project_dir="$3"

    if [ -z "$requested_dir" ]; then
        return 0
    fi
    if [[ "$requested_dir" != /* ]]; then
        echo "ERROR: VERIFICATION_ARTIFACTS_DIR must be an absolute path" >&2
        return 1
    fi

    "$python_bin" - "$requested_dir" "$source_project_dir" <<'PY'
import os
from pathlib import Path
import sys

artifacts_dir = Path(sys.argv[1]).resolve(strict=False)
source_dir = Path(sys.argv[2]).resolve(strict=True)

try:
    is_inside_source = os.path.commonpath(
        (str(artifacts_dir), str(source_dir))
    ) == str(source_dir)
except ValueError:
    is_inside_source = False

if is_inside_source:
    print(
        "ERROR: VERIFICATION_ARTIFACTS_DIR must be outside the source repository",
        file=sys.stderr,
    )
    raise SystemExit(1)

print(artifacts_dir)
PY
}

verification_artifacts_need_preservation() {
    local enabled="$1"
    local success_already_preserved="$2"
    local exit_code="$3"

    [ "$enabled" = "true" ] || return 1
    [ "$success_already_preserved" != "true" ] || [ "$exit_code" -ne 0 ]
}
