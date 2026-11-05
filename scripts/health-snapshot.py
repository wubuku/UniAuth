#!/usr/bin/env python3
"""One-command health snapshot for the UniAuth test base.

The full gate returns a single PASS/FAIL line. That is enough to know *that*
something broke, but not *which layer* degraded. This script aggregates the
evidence the gate already produced and, where the evidence is cheap to
recompute, rechecks it live:

    python3 scripts/health-snapshot.py

It prints a human-readable table and writes machine-readable JSON to
`.local/uniauth/health/snapshot.json` (ignored by git).

The gate records its result (and the metrics it measured inside its temporary
snapshot) with:

    python3 scripts/health-snapshot.py --record-gate --project-dir <gate snapshot> \
        --output-dir .local/uniauth/health --exit-code <0|N> --run-id <id>

Exit codes:
    0  every required layer is healthy
    1  a required layer failed or its evidence is missing/inconsistent
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
HEALTH_DIR = Path(".local") / "uniauth" / "health"
DEFAULT_OUTPUT_DIR = REPO_ROOT / HEALTH_DIR
LAST_VERIFY_NAME = "last-verify.json"
SNAPSHOT_NAME = "snapshot.json"
READINESS_NAME = "readiness.tsv"

# Budget rationale lives in docs/TESTING.md (batch 9 calibration): measured
# cold-start median/max and the margin multiples for both budgets.
APPLICATION_READY_BUDGET_SECONDS = 90
APPLICATION_STARTUP_FAILURE_BUDGET_SECONDS = 60

# A gate record older than this is no longer evidence about the current tree.
# The mtime rule below catches the more common case (editing after a gate run).
STALE_AFTER_HOURS = 24
STALE_MTIME_TOLERANCE_SECONDS = 2

# Paths whose contents the gate actually exercises. A source file newer than the
# record means the recorded verdict cannot describe the current tree.
STALE_SOURCE_PATHS = (
    "scripts",
    "config",
    "contracts",
    "src",
    "frontend/src",
    "frontend/tests",
    "frontend/package.json",
    "frontend/package-lock.json",
    "frontend/vitest.config.ts",
    "frontend/playwright.config.ts",
    "frontend/playwright.production.config.ts",
    "reference/email-service/scripts",
    "reference/email-service/src",
    "pom.xml",
)

# The email service gate fingerprints exactly these paths in its own
# `source_fingerprint()` (reference/email-service/scripts/verify.sh). Recomputing
# the same subset here lets the snapshot detect a record that no longer describes
# the current tree instead of trusting the record's internal consistency alone.
COMPONENT_FINGERPRINT_PATHS = (
    "reference/email-service",
    "scripts/check-dependency-audit-report.py",
    "scripts/check-dependency-suppressions.py",
    "src/main/resources/db/migration/postgresql",
)

VITEST_REPORT = Path("frontend") / "reports" / "vitest-results.json"
PLAYWRIGHT_MOCK_REPORT = Path("frontend") / "reports" / "playwright-mock.json"
PLAYWRIGHT_PRODUCTION_REPORT = Path("frontend") / "reports" / "playwright-production.json"
PLAYWRIGHT_SKIP_DIRS = {".git", "node_modules", "target", "coverage", "test-results",
                        "playwright-report", "blob-report", "reports"}

TOLERANCE = {"backend": 1.0, "frontend": 2.0}


def now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def load_json(path: Path):
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def percent(covered: int, missed: int) -> float:
    total = covered + missed
    return 100.0 if total == 0 else round(covered * 100.0 / total, 2)


def read_backend_coverage(project: Path, csv_override: Path | None = None) -> dict[str, float] | None:
    path = csv_override or (project / "target" / "site" / "jacoco" / "jacoco.csv")
    if not path.is_file():
        return None
    line_missed = line_covered = branch_missed = branch_covered = 0
    with path.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            line_missed += int(row["LINE_MISSED"])
            line_covered += int(row["LINE_COVERED"])
            branch_missed += int(row["BRANCH_MISSED"])
            branch_covered += int(row["BRANCH_COVERED"])
    return {
        "line": percent(line_covered, line_missed),
        "branch": percent(branch_covered, branch_missed),
    }


def read_frontend_coverage(project: Path) -> dict[str, float] | None:
    path = project / "frontend" / "coverage" / "coverage-summary.json"
    if not path.is_file():
        return None
    summary = load_json(path)
    total = summary.get("total")
    if not isinstance(total, dict):
        return None
    return {
        "line": round(float(total["lines"]["pct"]), 2),
        "branch": round(float(total["branches"]["pct"]), 2),
    }


def read_surefire(project: Path, reports_dir: Path | None = None) -> dict[str, int] | None:
    reports = reports_dir or (project / "target" / "surefire-reports")
    files = sorted(reports.glob("TEST-*.xml"))
    if not files:
        return None
    totals = {"total": 0, "failures": 0, "errors": 0, "skipped": 0, "records": 0}
    for path in files:
        try:
            root = ElementTree.parse(path).getroot()
        except ElementTree.ParseError:
            return None
        totals["total"] += int(root.get("tests", 0))
        totals["failures"] += int(root.get("failures", 0))
        totals["errors"] += int(root.get("errors", 0))
        totals["skipped"] += int(root.get("skipped", 0))
        # A report whose declared `tests` count disagrees with its recorded
        # testcase elements is truncated or hand-edited; never trust it.
        totals["records"] += len(root.findall(".//testcase"))
    totals["inconsistent"] = totals["records"] != totals["total"]
    return totals


def gate_readiness_events(events: list[dict[str, object]]) -> list[dict[str, object]]:
    """Keep only real gate samples; readiness self-tests use tiny budgets."""
    return [
        event
        for event in events
        if (event.get("event") == "application-health"
            and int(event.get("budgetSeconds", 0)) == APPLICATION_READY_BUDGET_SECONDS)
        or (event.get("event") == "process-exit"
            and int(event.get("budgetSeconds", 0)) == APPLICATION_STARTUP_FAILURE_BUDGET_SECONDS)
    ]


def read_readiness_metrics(output_dir: Path) -> list[dict[str, object]]:
    path = output_dir / READINESS_NAME
    if not path.is_file():
        return []
    events: list[dict[str, object]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) != 5:
            continue
        event, elapsed, attempts, budget = parts[1], parts[2], parts[3], parts[4]
        try:
            events.append(
                {
                    "at": parts[0],
                    "event": event,
                    "elapsedSeconds": float(elapsed),
                    "attempts": int(attempts),
                    "budgetSeconds": int(budget),
                }
            )
        except ValueError:
            continue
    return events


def _load_report(path: Path):
    """Load a report, treating truncated/non-JSON evidence as missing."""
    try:
        return load_json(path)
    except (OSError, json.JSONDecodeError):
        return None


def read_vitest_report(path: Path) -> dict[str, object] | None:
    """Read the Vitest JSON reporter output.

    `numTotalTests` is the runtime total (parameterized cases expanded). Every
    assertion result must also be present: a report whose declared total does
    not match its own records is evidence of a truncated or hand-edited file and
    is reported as inconsistent instead of trusted.
    """
    payload = _load_report(path)
    if not isinstance(payload, dict):
        return None
    total = payload.get("numTotalTests")
    failed = payload.get("numFailedTests", 0)
    results = payload.get("testResults")
    if not isinstance(total, int) or not isinstance(results, list):
        return None
    counted = 0
    for suite in results:
        assertions = suite.get("assertionResults") if isinstance(suite, dict) else None
        if not isinstance(assertions, list):
            return None
        counted += len(assertions)
    failed = int(failed) if isinstance(failed, int) else 0
    return {
        "total": total,
        "counted": counted,
        "failed": failed,
        "inconsistent": counted != total,
    }


def _count_playwright_specs(suites) -> int:
    count = 0
    if not isinstance(suites, list):
        return count
    for suite in suites:
        if not isinstance(suite, dict):
            continue
        specs = suite.get("specs")
        if isinstance(specs, list):
            count += len(specs)
        count += _count_playwright_specs(suite.get("suites"))
    return count


def read_playwright_report(path: Path) -> dict[str, object] | None:
    """Read a Playwright JSON reporter output.

    The `stats` block is the runtime summary; the recursive spec count is used
    to detect a report where the summary and the recorded specs disagree.
    """
    payload = _load_report(path)
    if not isinstance(payload, dict):
        return None
    stats = payload.get("stats")
    if not isinstance(stats, dict):
        return None
    try:
        total = sum(int(stats.get(key, 0)) for key in ("expected", "unexpected", "skipped", "flaky"))
        failed = int(stats.get("unexpected", 0))
    except (TypeError, ValueError):
        return None
    counted = _count_playwright_specs(payload.get("suites"))
    return {
        "total": total,
        "counted": counted,
        "failed": failed,
        "inconsistent": counted != total,
    }


def read_frontend_test_reports(project: Path) -> dict[str, dict[str, object] | None]:
    """Return runtime counts for every frontend suite, never declared counts."""
    return {
        "unit": read_vitest_report(project / VITEST_REPORT),
        "mock": read_playwright_report(project / PLAYWRIGHT_MOCK_REPORT),
        "production": read_playwright_report(project / PLAYWRIGHT_PRODUCTION_REPORT),
    }


def _newest_source_mtime(project: Path) -> float | None:
    newest = None
    for relative in STALE_SOURCE_PATHS:
        base = project / relative
        if not base.exists():
            continue
        if base.is_file():
            candidates = [base]
        else:
            candidates = []
            for root, directories, files in os.walk(base):
                directories[:] = [name for name in directories if name not in PLAYWRIGHT_SKIP_DIRS]
                candidates.extend(Path(root) / name for name in files)
        for path in candidates:
            try:
                mtime = path.stat().st_mtime
            except OSError:
                continue
            newest = mtime if newest is None else max(newest, mtime)
    return newest


def _parse_record_time(value) -> float | None:
    if not isinstance(value, str):
        return None
    try:
        return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc).timestamp()
    except ValueError:
        return None


def stale_reasons(record: dict, project: Path) -> list[str]:
    """Explain why the gate record no longer describes the current tree.

    Two independent rules, both fail closed:
      - age: the record is older than STALE_AFTER_HOURS;
      - mtime: a source path was modified after the record was written.
    """
    recorded = _parse_record_time(record.get("recordedAt"))
    if recorded is None:
        return ["recordedAt is missing or malformed"]
    reasons: list[str] = []
    age_hours = (datetime.now(timezone.utc).timestamp() - recorded) / 3600.0
    if age_hours > STALE_AFTER_HOURS:
        reasons.append(f"record age {age_hours:.1f}h > {STALE_AFTER_HOURS}h")
    newest = _newest_source_mtime(project)
    if newest is not None and newest > recorded + STALE_MTIME_TOLERANCE_SECONDS:
        reasons.append(
            "source mtime newer than record by "
            f"{newest - recorded:.0f}s"
        )
    return reasons


def _component_source_files(project: Path) -> list[bytes] | None:
    """List the exact files the component gate fingerprints.

    Both the fingerprint and the freshness scan use this one list, so an ignored
    file such as `.env` can never make the record look stale while the fingerprint
    (which excludes it) stays unchanged.
    """
    try:
        completed = subprocess.run(
            ["git", "-C", str(project), "ls-files", "-co", "--exclude-standard", "-z", "--",
             *COMPONENT_FINGERPRINT_PATHS],
            capture_output=True,
        )
    except OSError:
        return None
    if completed.returncode != 0:
        return None
    return sorted(path for path in completed.stdout.split(b"\0") if path)


def component_source_mtime(project: Path) -> float | None:
    """Newest mtime among the fingerprint's file set; None when git is unavailable."""
    listed = _component_source_files(project)
    if listed is None:
        return None
    newest = None
    for relative in listed:
        try:
            mtime = (project / relative.decode("utf-8")).stat().st_mtime
        except (OSError, UnicodeDecodeError):
            continue
        newest = mtime if newest is None else max(newest, mtime)
    return newest


def component_source_fingerprint(project: Path) -> str | None:
    """Reproduce the email service gate's `source_fingerprint()` exactly.

    `git ls-files -co --exclude-standard -z -- <paths>` is sorted bytewise and
    each file is hashed as `sha256  path`; the final hash covers those lines.
    Returns None when git or the tree cannot be read, so callers can fall back to
    the age/mtime rules instead of inventing a fingerprint.
    """
    listed = _component_source_files(project)
    if listed is None:
        return None
    lines = bytearray()
    for relative in listed:
        try:
            data = (project / relative.decode("utf-8")).read_bytes()
        except (OSError, UnicodeDecodeError):
            continue
        lines.extend(hashlib.sha256(data).hexdigest().encode("ascii"))
        lines.extend(b"  ")
        lines.extend(relative)
        lines.extend(b"\n")
    return hashlib.sha256(bytes(lines)).hexdigest()


def component_stale_reasons(record: dict, project: Path) -> list[str]:
    """Explain why a component record does not describe the current tree.

    Uses the same age and mtime rules as the root gate record, plus the gate's
    own source fingerprint when it can be recomputed.
    """
    recorded = _parse_record_time(record.get("recordedAt"))
    if recorded is None:
        return ["recordedAt is missing or malformed"]
    reasons: list[str] = []
    age_hours = (datetime.now(timezone.utc).timestamp() - recorded) / 3600.0
    if age_hours > STALE_AFTER_HOURS:
        reasons.append(f"record age {age_hours:.1f}h > {STALE_AFTER_HOURS}h")
    newest = component_source_mtime(project)
    if newest is not None and newest > recorded + STALE_MTIME_TOLERANCE_SECONDS:
        reasons.append(
            "component source mtime newer than record by "
            f"{newest - recorded:.0f}s"
        )
    current = component_source_fingerprint(project)
    if current is not None:
        fingerprint = record.get("sourceFingerprint")
        if not isinstance(fingerprint, str) or not fingerprint:
            reasons.append("sourceFingerprint is missing")
        elif fingerprint != current:
            reasons.append("component source fingerprint changed since the record")
    return reasons


def baseline_metric(baseline: dict, scope: str, metric: str) -> float | None:
    """Return a committed baseline value, or None when the evidence is missing."""
    recorded = baseline.get(scope)
    if not isinstance(recorded, dict):
        return None
    value = recorded.get(metric)
    if isinstance(value, (int, float)):
        return float(value)
    return None


def format_baseline(baseline: dict, scope: str, metric: str) -> str:
    value = baseline_metric(baseline, scope, metric)
    return "MISSING" if value is None else f"{value:.2f}"


def compare_coverage(scope: str, measured, baseline: dict) -> list[str]:
    failures: list[str] = []
    if measured is None:
        return failures
    tolerance = float(baseline.get("tolerancePercentagePoints", {}).get(scope, TOLERANCE[scope]))
    for metric in ("line", "branch"):
        # A missing scope/metric must fail closed: `.get(metric, 0.0)` would
        # silently turn a deleted baseline entry into a 0% target.
        expected = baseline_metric(baseline, scope, metric)
        if expected is None:
            failures.append(
                f"{scope} {metric} baseline is MISSING; refusing to treat it as 0.0"
            )
            continue
        actual = float(measured[metric])
        drop = expected - actual
        if drop > tolerance:
            failures.append(
                f"{scope} {metric} {actual:.2f}% is {drop:.2f}pp below baseline "
                f"{expected:.2f}% (tolerance {tolerance:.2f}pp)"
            )
    return failures


def summarize_mutation(project: Path) -> dict[str, object] | None:
    path = project / "target" / "pit-reports" / "mutations.csv"
    if not path.is_file():
        return None
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.reader(handle))
    if rows and rows[0][:2] == ["File", "Class"]:
        rows = rows[1:]
    generated = killed = no_coverage = 0
    for row in rows:
        if len(row) < 6:
            return None
        generated += 1
        if row[5] == "KILLED":
            killed += 1
        elif row[5] == "NO_COVERAGE":
            no_coverage += 1
    return {
        "generated": generated,
        "killed": killed,
        "noCoverage": no_coverage,
        "mutationScore": round(killed * 100.0 / generated, 2) if generated else 0.0,
    }


def compare_mutation(measured: dict, baseline: dict) -> list[str]:
    failures: list[str] = []
    required = ("generated", "killed", "noCoverage", "mutationScore")
    missing = [key for key in required if key not in baseline]
    if missing:
        failures.append(
            "mutation baseline is missing required metric(s): " + ", ".join(missing)
        )
        return failures
    if measured["killed"] < int(baseline["killed"]):
        failures.append(
            f"killed mutants {measured['killed']} < baseline {baseline['killed']}"
        )
    if measured["noCoverage"] > int(baseline["noCoverage"]):
        failures.append(
            f"no-coverage mutants {measured['noCoverage']} > baseline {baseline['noCoverage']}"
        )
    minimum = float(baseline["mutationScore"]) - 0.5
    if measured["mutationScore"] < minimum:
        failures.append(
            f"mutation score {measured['mutationScore']}% < baseline {baseline['mutationScore']}%"
        )
    return failures


def run_test_rot(project: Path) -> dict[str, object]:
    script = project / "scripts" / "check-test-rot.py"
    if not script.is_file():
        return {"status": "missing", "detail": f"{script} not found"}
    completed = subprocess.run(
        [sys.executable, str(script), "--root", str(project)],
        capture_output=True,
        text=True,
        timeout=120,
        check=False,
    )
    match = re.search(
        r"Test-rot guard: (\d+) hit\(s\), (\d+) baselined, (\d+) new",
        completed.stdout,
    )
    hits = int(match.group(1)) if match else None
    new_hits = int(match.group(3)) if match else None
    return {
        "status": "passed" if completed.returncode == 0 else "failed",
        "hits": hits,
        "newHits": new_hits,
        "detail": (completed.stdout + completed.stderr).strip().splitlines()[-1]
        if (completed.stdout or completed.stderr)
        else "",
    }


def record_gate(args) -> int:
    project = args.project_dir.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    passed = args.exit_code == 0
    payload = {
        "version": 1,
        "recordedAt": now(),
        "runId": args.run_id,
        "exitCode": args.exit_code,
        "passed": passed,
        "sourceFingerprint": args.source_fingerprint,
        "metrics": {
            "backendTests": read_surefire(project, args.surefire_dir),
            "backendCoverage": read_backend_coverage(project, args.backend_csv),
            "frontendCoverage": read_frontend_coverage(project),
            "frontendTests": read_frontend_test_reports(project),
            "readiness": gate_readiness_events(read_readiness_metrics(output_dir)),
            "testRot": {"status": "passed" if passed else "unknown"},
        },
    }
    path = output_dir / LAST_VERIFY_NAME
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)
    print(f"Health gate status recorded: {path}")
    return 0


def build_rows(args) -> tuple[list[dict[str, object]], dict[str, object]]:
    repo = args.repo_root.resolve()
    output_dir = args.output_dir.resolve()
    rows: list[dict[str, object]] = []
    record_path = output_dir / LAST_VERIFY_NAME
    record = load_json(record_path) if record_path.is_file() else None
    metrics = (record or {}).get("metrics", {})

    def add(layer, status, value, detail, required=True):
        rows.append(
            {
                "layer": layer,
                "status": status,
                "value": value,
                "detail": detail,
                "required": required,
            }
        )

    # 1. Gate status. `passed` must agree with `exitCode`; a hand-edited or
    # partially written record must never be able to look healthy.
    if record is None:
        add("gate", "MISSING", "no record", f"run scripts/verify.sh (expected {record_path})")
    elif bool(record.get("passed")) != (int(record.get("exitCode", 1)) == 0):
        add(
            "gate",
            "FAIL",
            f"exit={record.get('exitCode')} passed={record.get('passed')}",
            "inconsistent gate record; cannot trust it",
        )
    elif int(record.get("exitCode", 1)) != 0:
        add("gate", "FAIL", f"exit={record.get('exitCode')}", f"last gate at {record.get('recordedAt')}")
    else:
        reasons = stale_reasons(record, repo)
        if reasons:
            # STALE is required: a record that does not describe the current
            # tree cannot support a HEALTHY claim. This is deliberately an exit
            # non-zero signal, not an annotation, so a post-edit snapshot never
            # looks healthier than the evidence allows.
            add("gate", "STALE", f"exit=0 @ {record.get('recordedAt')}", "; ".join(reasons))
        else:
            add("gate", "OK", f"exit=0 @ {record.get('recordedAt')}", f"run {record.get('runId')}")

    # 2. Backend test totals: the gate records the counts it measured inside its
    # own snapshot. When a gate record exists, its metrics are authoritative:
    # falling back to the working tree's `target/` would let a *failed* gate
    # look healthy by reusing reports from an older run.
    backend = metrics.get("backendTests") if record is not None else read_surefire(repo)
    source = "gate" if record is not None else "live"
    if backend is None:
        add("backend-tests", "MISSING", "no reports", "run mvn test")
    elif backend.get("inconsistent"):
        add(
            "backend-tests",
            "FAIL",
            f"declared {backend['total']} tests but {backend['records']} testcase records",
            f"{source} surefire report is inconsistent",
        )
    elif backend["failures"] or backend["errors"]:
        add(
            "backend-tests",
            "FAIL",
            f"{backend['total']} tests, {backend['failures']} failures, {backend['errors']} errors",
            f"{source} surefire",
        )
    else:
        add(
            "backend-tests",
            "OK",
            f"{backend['total']} tests, {backend['skipped']} skipped",
            f"{source} surefire",
        )

    # 3. Frontend test totals: only runtime JSON reports count. Declared listings
    # (`vitest list`, `playwright --list`) are not evidence: they undercount
    # parameterized cases and previously produced the 28-vs-29 gap. A gate
    # record's metrics are authoritative; when they are missing we report MISSING
    # instead of reading a stale working-tree report.
    suite_labels = {"unit": "unit", "mock": "mock", "production": "prod"}
    if record is not None:
        recorded_frontend = metrics.get("frontendTests")
        frontend_tests = {
            name: (recorded_frontend.get(name)
                   if isinstance(recorded_frontend, dict) else None)
            for name in suite_labels
        }
    else:
        frontend_tests = read_frontend_test_reports(repo)
    missing = [suite_labels[name] for name, report in frontend_tests.items() if report is None]
    inconsistent = [
        f"{suite_labels[name]} total={report['total']} records={report['counted']}"
        for name, report in frontend_tests.items()
        if report is not None and report["inconsistent"]
    ]
    failing = [
        f"{suite_labels[name]} failed={report['failed']}"
        for name, report in frontend_tests.items()
        if report is not None and report["failed"]
    ]
    if missing:
        add(
            "frontend-tests",
            "MISSING",
            f"missing {', '.join(missing)} report(s)",
            "run npm run test:unit, npm run test:e2e and npm run test:e2e:production",
        )
    elif inconsistent or failing:
        problems = inconsistent + failing
        add("frontend-tests", "FAIL", "; ".join(problems), "runtime JSON reports disagree")
    else:
        totals = {
            suite_labels[name]: report["total"] for name, report in frontend_tests.items()
        }
        add(
            "frontend-tests",
            "OK",
            f"unit={totals['unit']} mock={totals['mock']} prod={totals['prod']} runtime",
            "Vitest/Playwright JSON reporter; runtime totals",
        )

    # 4. Coverage against the committed ratchet.
    baseline_path = repo / "config" / "coverage-baseline.json"
    baseline = load_json(baseline_path) if baseline_path.is_file() else {}
    if not baseline:
        add("coverage", "MISSING", "no baseline", "config/coverage-baseline.json is required")
    else:
        # Same rule as backend-tests: a gate record's own measurements are
        # authoritative, and a missing metric from it is MISSING, not a
        # fallback to stale working-tree coverage.
        backend_cov = metrics.get("backendCoverage") if record is not None else read_backend_coverage(repo)
        frontend_cov = metrics.get("frontendCoverage") if record is not None else read_frontend_coverage(repo)
        if backend_cov is None:
            add("backend-coverage", "MISSING", "no jacoco.csv", "run mvn test")
        else:
            backend_failures = compare_coverage("backend", backend_cov, baseline)
            add(
                "backend-coverage",
                "OK" if not backend_failures else "FAIL",
                f"line={backend_cov['line']}% branch={backend_cov['branch']}% "
                f"baseline={format_baseline(baseline, 'backend', 'line')}%/"
                f"{format_baseline(baseline, 'backend', 'branch')}%",
                "; ".join(backend_failures),
            )
        if frontend_cov is None:
            add("frontend-coverage", "MISSING", "no coverage-summary.json", "run npm run test:unit:coverage")
        else:
            frontend_failures = compare_coverage("frontend", frontend_cov, baseline)
            add(
                "frontend-coverage",
                "OK" if not frontend_failures else "FAIL",
                f"line={frontend_cov['line']}% branch={frontend_cov['branch']}% "
                f"baseline={format_baseline(baseline, 'frontend', 'line')}%/"
                f"{format_baseline(baseline, 'frontend', 'branch')}%",
                "; ".join(frontend_failures),
            )

    # 5. Mutation baseline: the committed baseline is required; the live probe
    # is weekly, so a missing CSV is reported as N/A rather than a failure.
    mutation_baseline_path = repo / "config" / "mutation-baseline.json"
    if not mutation_baseline_path.is_file():
        add("mutation", "MISSING", "no baseline", "config/mutation-baseline.json is required")
    else:
        mutation_baseline = load_json(mutation_baseline_path)
        live_mutation = summarize_mutation(repo)
        if live_mutation is None:
            add(
                "mutation",
                "N/A",
                f"baseline {mutation_baseline.get('killed')}/{mutation_baseline.get('generated')} killed",
                "no live PIT CSV (weekly probe)",
                required=False,
            )
        else:
            mutation_failures = compare_mutation(live_mutation, mutation_baseline)
            add(
                "mutation",
                "FAIL" if mutation_failures else "OK",
                f"live {live_mutation['killed']}/{live_mutation['generated']} killed "
                f"({live_mutation['mutationScore']}%), baseline "
                f"{mutation_baseline.get('killed')}/{mutation_baseline.get('generated')}",
                "; ".join(mutation_failures),
            )

    # 5b. Reference email-service gate: the component gate writes its own
    # reviewable record (including runtime Surefire totals) next to the root
    # record. A missing record means the component layer has no evidence for the
    # current tree, so it is required and degrades Overall instead of an N/A
    # that would still print HEALTHY.
    component_path = output_dir / "email-last-verify.json"
    if not component_path.is_file():
        add(
            "component-tests",
            "MISSING",
            "no component record",
            "run reference/email-service/scripts/verify.sh",
        )
    else:
        component = None
        try:
            component = load_json(component_path)
        except (OSError, json.JSONDecodeError) as error:
            add("component-tests", "FAIL", "unreadable component record", str(error))
        if isinstance(component, dict):
            component_tests = (component.get("metrics") or {}).get("componentTests")
            component_exit = int(component.get("exitCode", 1))
            if bool(component.get("passed")) != (component_exit == 0):
                add("component-tests", "FAIL", "inconsistent component record", "passed/exitCode disagree")
            elif component_exit != 0:
                add("component-tests", "FAIL", f"exit={component_exit}", "component gate failed")
            elif stale := component_stale_reasons(component, repo):
                # A self-consistent record that predates the current tree (or was
                # produced by a different tree) cannot support a HEALTHY claim.
                add(
                    "component-tests",
                    "STALE",
                    f"exit=0 @ {component.get('recordedAt')}",
                    "; ".join(stale),
                )
            elif not isinstance(component_tests, dict):
                add("component-tests", "MISSING", "no test totals", "component record has no Surefire metrics")
            elif component_tests.get("inconsistent"):
                add(
                    "component-tests",
                    "FAIL",
                    f"declared {component_tests.get('total')} tests but {component_tests.get('records')} records",
                    "component Surefire report is inconsistent",
                )
            elif component_tests.get("failures") or component_tests.get("errors"):
                add(
                    "component-tests",
                    "FAIL",
                    f"{component_tests.get('total')} tests, "
                    f"{component_tests.get('failures')} failures, "
                    f"{component_tests.get('errors')} errors",
                    "component Surefire report",
                )
            else:
                add(
                    "component-tests",
                    "OK",
                    f"{component_tests.get('total')} tests, {component_tests.get('skipped')} skipped",
                    f"email-service gate {component.get('runId')}",
                )
        elif component is not None:
            add("component-tests", "FAIL", "malformed component record", str(component_path))

    # 6. Test-rot hits, rechecked live (cheap pure-Python scan).
    if args.skip_live:
        add("test-rot", "N/A", "skipped", "--skip-live", required=False)
    else:
        rot = run_test_rot(repo)
        if rot["status"] == "missing":
            add("test-rot", "MISSING", "script missing", str(rot["detail"]))
        elif rot["status"] == "failed":
            add(
                "test-rot",
                "FAIL",
                f"{rot.get('hits')} hit(s), {rot.get('newHits')} new",
                str(rot["detail"]),
            )
        else:
            add("test-rot", "OK", f"{rot.get('hits')} hit(s), 0 new", "guard passed")

    # 7. Readiness: budgets plus the last observed timings from the gate.
    readiness = gate_readiness_events(
        read_readiness_metrics(output_dir) or metrics.get("readiness") or []
    )
    health_events = [event for event in readiness if event.get("event") == "application-health"]
    exit_events = [event for event in readiness if event.get("event") == "process-exit"]
    latest_health = health_events[-1] if health_events else None
    latest_exit = exit_events[-1] if exit_events else None
    detail_parts = [
        f"budget app={APPLICATION_READY_BUDGET_SECONDS}s failure-exit={APPLICATION_STARTUP_FAILURE_BUDGET_SECONDS}s"
    ]
    if latest_health:
        detail_parts.append(
            f"last app READY {latest_health['elapsedSeconds']}s/{latest_health['budgetSeconds']}s"
        )
    if latest_exit:
        detail_parts.append(
            f"last failure-exit {latest_exit['elapsedSeconds']}s/{latest_exit['budgetSeconds']}s"
        )
    add(
        "readiness",
        "OK" if (latest_health or latest_exit) else "N/A",
        "observed" if (latest_health or latest_exit) else "no samples",
        "; ".join(detail_parts),
        required=False,
    )

    runtime_frontend = [
        report["total"]
        for report in frontend_tests.values()
        if isinstance(report, dict) and isinstance(report.get("total"), int)
    ]
    totals = {
        "backendTests": backend["total"] if backend else None,
        "frontendTests": sum(runtime_frontend) if len(runtime_frontend) == len(frontend_tests) else None,
    }
    return rows, {"record": record, "totals": totals}


STATUS_WIDTHS = {"layer": 18, "status": 8, "value": 62}


def print_table(rows: list[dict[str, object]], totals: dict[str, object]) -> None:
    header = (
        f"{'LAYER':<{STATUS_WIDTHS['layer']}} "
        f"{'STATUS':<{STATUS_WIDTHS['status']}} "
        f"{'VALUE':<{STATUS_WIDTHS['value']}} DETAIL"
    )
    print(header)
    print("-" * len(header))
    for row in rows:
        value = str(row["value"])
        if len(value) > STATUS_WIDTHS["value"]:
            value = value[: STATUS_WIDTHS["value"] - 1] + "…"
        print(
            f"{str(row['layer']):<{STATUS_WIDTHS['layer']}} "
            f"{str(row['status']):<{STATUS_WIDTHS['status']}} "
            f"{value:<{STATUS_WIDTHS['value']}} {row['detail']}"
        )
    backend = totals.get("backendTests")
    frontend = totals.get("frontendTests")
    print("")
    if backend is not None or frontend is not None:
        print(
            "Test totals: backend runtime="
            f"{backend if backend is not None else 'n/a'}, "
            f"frontend runtime={frontend if frontend is not None else 'n/a'}"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=REPO_ROOT)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--skip-live", action="store_true")
    parser.add_argument("--record-gate", action="store_true")
    parser.add_argument("--project-dir", type=Path, default=REPO_ROOT)
    parser.add_argument("--exit-code", type=int, default=0)
    parser.add_argument("--run-id", default="")
    parser.add_argument("--source-fingerprint", default="")
    parser.add_argument("--surefire-dir", type=Path, default=None)
    parser.add_argument("--backend-csv", type=Path, default=None)
    args = parser.parse_args()

    if args.record_gate:
        return record_gate(args)

    rows, context = build_rows(args)
    overall = "HEALTHY"
    if any(row["required"] and row["status"] in ("FAIL", "MISSING", "STALE") for row in rows):
        overall = "DEGRADED"
    print_table(rows, context["totals"])
    print(f"\nOverall: {overall}")

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    snapshot = {
        "version": 1,
        "generatedAt": now(),
        "overall": overall,
        "repoRoot": str(args.repo_root.resolve()),
        "totals": context["totals"],
        "layers": [
            {key: value for key, value in row.items() if key != "required"}
            for row in rows
        ],
        "requiredLayers": [row["layer"] for row in rows if row["required"]],
    }
    path = output_dir / SNAPSHOT_NAME
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(snapshot, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)
    print(f"Snapshot: {path}")
    return 0 if overall == "HEALTHY" else 1


if __name__ == "__main__":
    raise SystemExit(main())
