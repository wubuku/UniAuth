#!/usr/bin/env python3
"""Coverage ratchet for the UniAuth test base.

Coverage stays report-only for individual reports: nothing here enforces a hard
absolute threshold. Instead this script compares the freshly measured line and
branch percentages against a committed baseline and fails only when coverage
*drops* by more than the configured tolerance. That keeps the suite from quietly
rotting while still allowing deliberate baselines to move up.

Usage:
    python3 scripts/coverage-ratchet.py            # check (default)
    python3 scripts/coverage-ratchet.py --update   # raise the baseline
    python3 scripts/coverage-ratchet.py --update --allow-lower
    python3 scripts/coverage-ratchet.py --backend-only
    python3 scripts/coverage-ratchet.py --frontend-only

Backend input:  target/site/jacoco/jacoco.csv (JaCoCo `report` goal, CSV format)
Frontend input: frontend/coverage/coverage-summary.json (vitest v8 reporter)

Both inputs are produced with `mvn test` and `npm run test:unit:coverage`.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_BASELINE = REPO_ROOT / "config" / "coverage-baseline.json"
DEFAULT_BACKEND_CSV = REPO_ROOT / "target" / "site" / "jacoco" / "jacoco.csv"
DEFAULT_FRONTEND_SUMMARY = (
    REPO_ROOT / "frontend" / "coverage" / "coverage-summary.json"
)

# Tolerances are deliberately loose enough to absorb measurement noise from a
# single added class or an equivalent reordering, but tight enough to catch a
# test module being dropped. Frontend gets a wider window because page-level
# components are only exercised through the browser gate, not Vitest.
DEFAULT_TOLERANCE = {"backend": 1.0, "frontend": 2.0}


def _pct(covered: int, missed: int) -> float:
    total = covered + missed
    if total == 0:
        # Nothing to cover (e.g. an empty class). Treat as fully covered so it
        # cannot drag the aggregate down.
        return 100.0
    return covered * 100.0 / total


def read_backend(path: Path) -> dict[str, float]:
    if not path.is_file():
        raise SystemExit(
            f"ERROR: backend JaCoCo CSV is missing: {path}\n"
            "Run `mvn test` first; the report goal writes "
            "target/site/jacoco/jacoco.csv."
        )
    line_missed = line_covered = 0
    branch_missed = branch_covered = 0
    with path.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            line_missed += int(row["LINE_MISSED"])
            line_covered += int(row["LINE_COVERED"])
            branch_missed += int(row["BRANCH_MISSED"])
            branch_covered += int(row["BRANCH_COVERED"])
    return {
        "line": round(_pct(line_covered, line_missed), 2),
        "branch": round(_pct(branch_covered, branch_missed), 2),
    }


def read_frontend(path: Path) -> dict[str, float]:
    if not path.is_file():
        raise SystemExit(
            f"ERROR: frontend coverage summary is missing: {path}\n"
            "Run `npm run test:unit:coverage` in frontend/ first."
        )
    with path.open(encoding="utf-8") as handle:
        summary = json.load(handle)
    total = summary.get("total")
    if not isinstance(total, dict):
        raise SystemExit(
            f"ERROR: frontend coverage summary has no total entry: {path}"
        )
    return {
        "line": round(float(total["lines"]["pct"]), 2),
        "branch": round(float(total["branches"]["pct"]), 2),
    }


def load_baseline(path: Path) -> dict:
    if not path.is_file():
        raise SystemExit(
            f"ERROR: coverage baseline is missing: {path}\n"
            "Create it explicitly with:\n"
            "  python3 scripts/coverage-ratchet.py --update"
        )
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def save_baseline(path: Path, baseline: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(baseline, handle, indent=2, sort_keys=True)
        handle.write("\n")


def compare(
    scope: str,
    measured: dict[str, float],
    baseline: dict,
    tolerance: float,
) -> list[str]:
    failures: list[str] = []
    recorded = baseline.get(scope)
    # A missing scope/metric is a broken baseline, not a 0% baseline. Silently
    # defaulting here would let a deleted entry disable the ratchet while the
    # gate still reports PASS.
    if not isinstance(recorded, dict):
        message = (
            f"{scope} baseline entry is missing; refusing to treat it as 0.0"
        )
        print(f"  {scope:8s} baseline=MISSING [{message}]")
        return [message]
    for metric in ("line", "branch"):
        if metric not in recorded:
            message = (
                f"{scope} {metric} baseline is missing; refusing to treat it "
                "as 0.0"
            )
            print(f"  {scope:8s} {metric:6s} baseline=MISSING [{message}]")
            failures.append(message)
            continue
        expected = float(recorded[metric])
        actual = measured[metric]
        drop = expected - actual
        status = "OK" if drop <= tolerance else "FAIL"
        print(
            f"  {scope:8s} {metric:6s} baseline={expected:6.2f}% "
            f"current={actual:6.2f}% drop={drop:+5.2f}pp "
            f"tolerance={tolerance:.2f}pp [{status}]"
        )
        if drop > tolerance:
            failures.append(
                f"{scope} {metric} coverage dropped {drop:.2f}pp "
                f"(baseline {expected:.2f}%, current {actual:.2f}%, "
                f"tolerance {tolerance:.2f}pp)"
            )
    return failures


def update_scope(
    scope: str,
    measured: dict[str, float],
    baseline: dict,
    tolerance: float,
    allow_lower: bool,
) -> bool:
    recorded = baseline.get(scope)
    if not isinstance(recorded, dict):
        recorded = {}
    blocked = False
    new_scope: dict[str, float] = {}
    for metric in ("line", "branch"):
        new = measured[metric]
        if metric not in recorded:
            # Explicit migration: `--update` writes the missing metric without
            # pretending the old value was 0.0 (which would bypass the lower
            # guard for a first measurement).
            new_scope[metric] = new
            print(
                f"  {scope:8s} {metric:6s} baseline unset -> {new:.2f}% "
                "(explicit migration)"
            )
            continue
        old = float(recorded[metric])
        if new < old and not allow_lower and (old - new) > tolerance:
            blocked = True
            new_scope[metric] = old
            print(
                f"ERROR: refusing to lower {scope} {metric} baseline from "
                f"{old:.2f}% to {new:.2f}% without --allow-lower",
                file=sys.stderr,
            )
            continue
        new_scope[metric] = new
        print(
            f"  {scope:8s} {metric:6s} baseline {old:.2f}% -> {new:.2f}%"
        )
    baseline[scope] = new_scope
    return not blocked


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    parser.add_argument("--backend-csv", type=Path, default=DEFAULT_BACKEND_CSV)
    parser.add_argument(
        "--frontend-summary", type=Path, default=DEFAULT_FRONTEND_SUMMARY
    )
    parser.add_argument("--update", action="store_true")
    parser.add_argument("--allow-lower", action="store_true")
    parser.add_argument("--backend-only", action="store_true")
    parser.add_argument("--frontend-only", action="store_true")
    args = parser.parse_args()

    if args.backend_only and args.frontend_only:
        parser.error("--backend-only and --frontend-only are mutually exclusive")

    scopes: list[str] = []
    if args.backend_only:
        scopes = ["backend"]
    elif args.frontend_only:
        scopes = ["frontend"]
    else:
        scopes = ["backend", "frontend"]

    if args.baseline.is_file():
        baseline = load_baseline(args.baseline)
    elif args.update:
        baseline = {}
    else:
        baseline = load_baseline(args.baseline)
    tolerances = {
        scope: float(
            baseline.get("tolerancePercentagePoints", {}).get(
                scope, DEFAULT_TOLERANCE[scope]
            )
        )
        for scope in scopes
    }

    measured: dict[str, dict[str, float]] = {}
    if "backend" in scopes:
        measured["backend"] = read_backend(args.backend_csv)
    if "frontend" in scopes:
        measured["frontend"] = read_frontend(args.frontend_summary)

    if args.update:
        if not isinstance(baseline, dict) or not baseline:
            baseline = {
                "version": 1,
                "tolerancePercentagePoints": dict(DEFAULT_TOLERANCE),
            }
        baseline.setdefault("version", 1)
        baseline.setdefault("tolerancePercentagePoints", dict(DEFAULT_TOLERANCE))
        print(f"Updating coverage baseline: {args.baseline}")
        ok = True
        for scope in scopes:
            ok = update_scope(
                scope,
                measured[scope],
                baseline,
                tolerances[scope],
                args.allow_lower,
            ) and ok
        if not ok:
            return 1
        save_baseline(args.baseline, baseline)
        print("Coverage baseline updated.")
        return 0

    print(f"Checking coverage ratchet against {args.baseline}")
    failures: list[str] = []
    for scope in scopes:
        failures.extend(
            compare(scope, measured[scope], baseline, tolerances[scope])
        )
    if failures:
        print("", file=sys.stderr)
        print("ERROR: coverage ratchet failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        print(
            "", file=sys.stderr
        )
        print(
            "If the drop is intentional (e.g. an equivalent refactor moved "
            "code between covered and uncovered classes), update the baseline "
            "explicitly and commit config/coverage-baseline.json:\n"
            "  python3 scripts/coverage-ratchet.py --update\n"
            "To lower a baseline beyond the tolerance you must opt in:\n"
            "  python3 scripts/coverage-ratchet.py --update --allow-lower",
            file=sys.stderr,
        )
        return 1
    print("Coverage ratchet passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
