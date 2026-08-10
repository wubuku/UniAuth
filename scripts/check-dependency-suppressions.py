#!/usr/bin/env python3

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def parse_until(value: str, source: Path) -> datetime:
    normalized = value.strip()
    if not normalized:
        raise ValueError(f"{source}: suppression has an empty until value")
    if normalized.endswith("Z") and len(normalized) == 11:
        return datetime.fromisoformat(normalized[:-1]).replace(tzinfo=timezone.utc)
    if normalized.endswith("Z"):
        normalized = f"{normalized[:-1]}+00:00"
    parsed = datetime.fromisoformat(normalized)
    if parsed.tzinfo is None:
        raise ValueError(f"{source}: suppression until must include a timezone")
    return parsed.astimezone(timezone.utc)


def validate(source: Path, now: datetime) -> None:
    root = ET.parse(source).getroot()
    for suppression in root.findall("{*}suppress"):
        until = suppression.get("until")
        if until is None:
            raise ValueError(f"{source}: every suppression must have an until attribute")
        expires_at = parse_until(until, source)
        if expires_at <= now:
            raise ValueError(
                f"{source}: suppression expired at {expires_at.isoformat()}"
            )
        notes = suppression.find("{*}notes")
        note_text = "" if notes is None or notes.text is None else notes.text.strip()
        if len(note_text) < 80:
            raise ValueError(
                f"{source}: every suppression must include detailed notes"
            )
        selectors = [
            child
            for child in suppression
            if child.tag.rsplit("}", maxsplit=1)[-1] != "notes"
        ]
        if not selectors:
            raise ValueError(
                f"{source}: every suppression must include a vulnerability selector"
            )


def main() -> int:
    if len(sys.argv) < 2:
        print(
            "usage: check-dependency-suppressions.py SUPPRESSION_FILE [...]",
            file=sys.stderr,
        )
        return 2

    now = datetime.now(timezone.utc)
    try:
        for argument in sys.argv[1:]:
            source = Path(argument)
            if not source.is_file():
                raise ValueError(f"{source}: suppression file is missing")
            validate(source, now)
    except (ET.ParseError, OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print("PASS: dependency-check suppressions are present and unexpired")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
