#!/usr/bin/env python3

from __future__ import annotations

from pathlib import Path
import re
import sys


ALLOWED_TAGS = {"16.13", "16.13-alpine"}
IMAGE_PATTERN = re.compile(
    r"\bpostgres:([A-Za-z0-9][A-Za-z0-9._-]*(?:@sha256:[0-9a-f]{64})?)"
)
TEXT_SUFFIXES = {".java", ".md", ".sh", ".yaml", ".yml"}
IGNORED_PARTS = {".git", "dist", "node_modules", "target"}


def source_files(path: Path):
    if path.is_file():
        yield path
        return
    if not path.is_dir():
        raise ValueError(f"scan path does not exist: {path}")
    for candidate in path.rglob("*"):
        if not candidate.is_file():
            continue
        if any(part in IGNORED_PARTS for part in candidate.parts):
            continue
        if candidate.suffix in TEXT_SUFFIXES or candidate.name.startswith("Dockerfile"):
            yield candidate


def main() -> int:
    if len(sys.argv) < 2:
        print(
            "usage: check-postgres-image-pins.py PATH [...]",
            file=sys.stderr,
        )
        return 2

    references = 0
    errors: list[str] = []
    try:
        for argument in sys.argv[1:]:
            for source in source_files(Path(argument)):
                text = source.read_text(encoding="utf-8")
                for line_number, line in enumerate(text.splitlines(), start=1):
                    for match in IMAGE_PATTERN.finditer(line):
                        references += 1
                        image_reference = match.group(1)
                        tag = image_reference.split("@", maxsplit=1)[0]
                        if tag not in ALLOWED_TAGS:
                            errors.append(
                                f"{source}:{line_number}: "
                                f"unapproved PostgreSQL image tag postgres:{tag}"
                            )
    except (OSError, UnicodeDecodeError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    if references == 0:
        print("ERROR: no PostgreSQL image references were found", file=sys.stderr)
        return 1

    print(
        f"PASS: {references} PostgreSQL image references use approved 16.13 tags"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
