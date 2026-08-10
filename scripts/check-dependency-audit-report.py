#!/usr/bin/env python3

from __future__ import annotations

import json
from pathlib import Path
import sys


def main() -> int:
    if len(sys.argv) != 2:
        print(
            "usage: check-dependency-audit-report.py REPORT_JSON",
            file=sys.stderr,
        )
        return 2

    report_path = Path(sys.argv[1])
    if not report_path.is_file() or report_path.stat().st_size == 0:
        print(
            f"ERROR: dependency audit report is missing or empty: {report_path}",
            file=sys.stderr,
        )
        return 1

    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"ERROR: invalid dependency audit report: {error}", file=sys.stderr)
        return 1

    dependencies = report.get("dependencies")
    if not isinstance(dependencies, list) or not dependencies:
        print(
            "ERROR: dependency audit report has no dependency evidence",
            file=sys.stderr,
        )
        return 1
    if not isinstance(report.get("projectInfo"), dict):
        print(
            "ERROR: dependency audit report has no project metadata",
            file=sys.stderr,
        )
        return 1

    print(
        f"PASS: dependency audit report contains {len(dependencies)} dependencies"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
