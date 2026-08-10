#!/usr/bin/env python3

from __future__ import annotations

import json
from pathlib import Path
import sys


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check-sensitive-scan-report.py REPORT_JSON", file=sys.stderr)
        return 2

    report_path = Path(sys.argv[1])
    if not report_path.is_file() or report_path.stat().st_size == 0:
        print(
            f"ERROR: sensitive scan report is missing or empty: {report_path}",
            file=sys.stderr,
        )
        return 1
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"ERROR: invalid sensitive scan report: {error}", file=sys.stderr)
        return 1

    if report.get("version") != 1:
        print("ERROR: sensitive scan report version must be 1", file=sys.stderr)
        return 1
    if report.get("status") != "pass":
        print("ERROR: sensitive scan report did not record success", file=sys.stderr)
        return 1
    if not isinstance(report.get("scanned_files"), int) or report["scanned_files"] <= 0:
        print("ERROR: sensitive scan report has no file evidence", file=sys.stderr)
        return 1
    if report.get("findings") != [] or report.get("errors") != []:
        print("ERROR: sensitive scan report contains unresolved findings", file=sys.stderr)
        return 1
    if not isinstance(report.get("suppressed_findings"), list):
        print("ERROR: sensitive scan report has invalid exception evidence", file=sys.stderr)
        return 1

    print(
        "PASS: sensitive scan report contains "
        f"{report['scanned_files']} scanned files"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
