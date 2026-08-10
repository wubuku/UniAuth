#!/usr/bin/env python3

from __future__ import annotations

import json
from pathlib import Path
import sys


FAIL_BUILD_SEVERITIES = {"HIGH", "CRITICAL"}
KNOWN_SEVERITIES = {"LOW", "MEDIUM", "MODERATE", *FAIL_BUILD_SEVERITIES}


def vulnerability_score(vulnerability: dict[str, object]) -> float | None:
    scores: list[float] = []
    for metric_name in ("cvssv4", "cvssv3", "cvssv2"):
        metric = vulnerability.get(metric_name)
        if not isinstance(metric, dict):
            continue
        score = metric.get("baseScore")
        if isinstance(score, (int, float)):
            scores.append(float(score))
    return max(scores) if scores else None


def blocking_vulnerabilities(
    dependencies: list[object],
) -> list[str]:
    blocking: list[str] = []
    for dependency in dependencies:
        if not isinstance(dependency, dict):
            continue
        file_name = dependency.get("fileName", "<unknown dependency>")
        vulnerabilities = dependency.get("vulnerabilities")
        if not isinstance(vulnerabilities, list):
            continue
        for vulnerability in vulnerabilities:
            if not isinstance(vulnerability, dict):
                continue
            if vulnerability.get("isSuppressed") is True:
                continue
            name = vulnerability.get("name", "<unknown advisory>")
            severity = str(vulnerability.get("severity", "")).strip().upper()
            score = vulnerability_score(vulnerability)
            if score is not None and score >= 7.0:
                blocking.append(f"{name} in {file_name} (CVSS {score:g})")
                continue
            if severity in FAIL_BUILD_SEVERITIES:
                blocking.append(
                    f"{name} in {file_name} (unscored severity {severity})"
                )
                continue
            if score is None and severity not in KNOWN_SEVERITIES:
                blocking.append(
                    f"{name} in {file_name} "
                    "(unscored vulnerability with unknown severity)"
                )
    return blocking


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

    blocking = blocking_vulnerabilities(dependencies)
    if blocking:
        print(
            "ERROR: dependency audit report contains blocking vulnerabilities:",
            file=sys.stderr,
        )
        for vulnerability in blocking:
            print(f"  - {vulnerability}", file=sys.stderr)
        return 1

    print(
        f"PASS: dependency audit report contains {len(dependencies)} dependencies"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
