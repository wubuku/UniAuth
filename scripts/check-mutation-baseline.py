#!/usr/bin/env python3
"""Compare scoped PIT mutation results against the committed baseline.

The probe itself is `mvn -Pmutation-testing test-compile
org.pitest:pitest-maven:mutationCoverage` (see docs/TESTING.md). This script
only reads `target/pit-reports/mutations.csv` and fails when mutation strength
regresses, so the weekly probe cannot silently become weaker.

Deliberately not wired into `scripts/verify.sh`: PIT needs JDK 21 while the main
gate runs on the local default JDK, and the probe is low-frequency by design.

Usage:
    python3 scripts/check-mutation-baseline.py            # check
    python3 scripts/check-mutation-baseline.py --update    # rewrite baseline
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_BASELINE = REPO_ROOT / "config" / "mutation-baseline.json"
DEFAULT_CSV = REPO_ROOT / "target" / "pit-reports" / "mutations.csv"

EXPECTED_CLASSES = {
    "org.dddml.uniauth.service.TokenValidationService",
    "org.dddml.uniauth.service.CanonicalEmailService",
    "org.dddml.uniauth.util.Web3SignatureUtils",
}


def read_csv(path: Path) -> list[list[str]]:
    if not path.is_file():
        raise SystemExit(
            f"ERROR: PIT CSV is missing: {path}\n"
            "Run the mutation probe first:\n"
            "  JAVA_HOME=<jdk21> mvn -Pmutation-testing test-compile "
            "org.pitest:pitest-maven:mutationCoverage"
        )
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.reader(handle))
    if rows and rows[0][:2] == ["File", "Class"]:
        rows = rows[1:]
    return rows


def summarize(rows: list[list[str]]) -> dict:
    """PIT writes positional columns: File, Class, Mutator, Method, Line, Status, Test."""
    per_class: dict[str, Counter[str]] = {}
    for row in rows:
        if len(row) < 6:
            raise SystemExit(f"ERROR: unexpected PIT CSV row: {row}")
        status = row[5]
        per_class.setdefault(row[1], Counter())[status] += 1
    classes = {
        name: {
            "generated": sum(status_counts.values()),
            "killed": status_counts.get("KILLED", 0),
            "noCoverage": status_counts.get("NO_COVERAGE", 0),
        }
        for name, status_counts in sorted(per_class.items())
    }
    generated = sum(item["generated"] for item in classes.values())
    killed = sum(item["killed"] for item in classes.values())
    no_coverage = sum(item["noCoverage"] for item in classes.values())
    score = round(killed * 100.0 / generated, 2) if generated else 0.0
    return {
        "version": 1,
        "generated": generated,
        "killed": killed,
        "noCoverage": no_coverage,
        "mutationScore": score,
        "classes": classes,
    }


def compare(measured: dict, baseline: dict) -> list[str]:
    failures: list[str] = []
    required = ("generated", "killed", "noCoverage", "mutationScore")
    missing = [key for key in required if key not in baseline]
    if missing:
        # Missing evidence is a broken baseline, not a zero target. Defaulting
        # here would let a deleted metric disable the ratchet silently.
        failures.append(
            "mutation baseline is missing required metric(s): " + ", ".join(missing)
        )
        return failures
    # `classes` is also required evidence. An empty or deleted section would
    # otherwise skip every class-level ratchet while still looking valid.
    if "classes" not in baseline:
        failures.append("mutation baseline is missing the required classes section")
        return failures
    if not isinstance(baseline["classes"], dict):
        failures.append("mutation baseline classes section is malformed")
        return failures
    if set(measured["classes"]) != EXPECTED_CLASSES:
        failures.append(
            "probe scope changed: expected "
            f"{sorted(EXPECTED_CLASSES)}, got {sorted(measured['classes'])}"
        )
    if measured["generated"] < int(baseline["generated"]):
        failures.append(
            f"generated mutants dropped from {baseline['generated']} "
            f"to {measured['generated']} (deleting production code shrinks the "
            "probe; use --update only for a reviewed equivalent change)"
        )
    if measured["killed"] < int(baseline["killed"]):
        failures.append(
            f"killed mutants dropped from {baseline['killed']} "
            f"to {measured['killed']}"
        )
    if measured["noCoverage"] > int(baseline["noCoverage"]):
        failures.append(
            f"no-coverage mutants grew from {baseline['noCoverage']} "
            f"to {measured['noCoverage']}"
        )
    minimum_score = float(baseline["mutationScore"]) - 0.5
    if measured["mutationScore"] < minimum_score:
        failures.append(
            f"mutation score dropped from {baseline['mutationScore']}% "
            f"to {measured['mutationScore']}%"
        )
    for name, expected in baseline["classes"].items():
        if not isinstance(expected, dict):
            failures.append(f"{name}: baseline class entry is malformed")
            continue
        actual = measured["classes"].get(name)
        if actual is None:
            failures.append(f"baseline class disappeared from the report: {name}")
            continue
        if "generated" not in expected:
            failures.append(f"{name}: baseline is missing the generated count")
        elif actual["generated"] < int(expected["generated"]):
            failures.append(
                f"{name}: generated mutants dropped from {expected['generated']} "
                f"to {actual['generated']}"
            )
        # Class-level counts are evidence too: defaulting a missing field to 0
        # would silently weaken the ratchet, so every key is required.
        if "killed" not in expected:
            failures.append(f"{name}: baseline is missing the killed count")
        elif actual["killed"] < int(expected["killed"]):
            failures.append(
                f"{name}: killed mutants dropped from {expected['killed']} "
                f"to {actual['killed']}"
            )
        if "noCoverage" not in expected:
            failures.append(f"{name}: baseline is missing the noCoverage count")
        elif actual["noCoverage"] > int(expected["noCoverage"]):
            failures.append(
                f"{name}: no-coverage mutants grew from "
                f"{expected['noCoverage']} to {actual['noCoverage']}"
            )
    return failures


def print_summary(measured: dict) -> None:
    print(
        f"PIT: {measured['generated']} mutants, {measured['killed']} killed "
        f"({measured['mutationScore']}%), "
        f"{measured['noCoverage']} no-coverage"
    )
    for name, item in measured["classes"].items():
        print(
            f"  {name}: {item['killed']}/{item['generated']} killed, "
            f"{item['noCoverage']} no-coverage"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    parser.add_argument("--csv", type=Path, default=DEFAULT_CSV)
    parser.add_argument("--update", action="store_true")
    args = parser.parse_args()

    measured = summarize(read_csv(args.csv))
    print_summary(measured)

    if args.update:
        args.baseline.parent.mkdir(parents=True, exist_ok=True)
        with args.baseline.open("w", encoding="utf-8") as handle:
            json.dump(measured, handle, indent=2, sort_keys=True)
            handle.write("\n")
        print(f"Mutation baseline updated: {args.baseline}")
        return 0

    if not args.baseline.is_file():
        raise SystemExit(
            f"ERROR: mutation baseline is missing: {args.baseline}\n"
            "Create it explicitly with: python3 scripts/check-mutation-baseline.py --update"
        )
    with args.baseline.open(encoding="utf-8") as handle:
        baseline = json.load(handle)

    failures = compare(measured, baseline)
    if failures:
        print("ERROR: mutation baseline regressed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        print(
            "\nKill the surviving mutant with a real assertion. Only update the "
            "baseline when the change is a reviewed equivalent mutant:\n"
            "  python3 scripts/check-mutation-baseline.py --update",
            file=sys.stderr,
        )
        return 1
    print("Mutation baseline passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
