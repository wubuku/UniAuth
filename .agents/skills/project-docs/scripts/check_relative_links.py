#!/usr/bin/env python3
"""Check repository-relative Markdown links without network access."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from urllib.parse import unquote


LINK_PATTERN = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
SCHEMES = (
    "http://",
    "https://",
    "mailto:",
    "tel:",
    "data:",
    "javascript:",
)
SKIP_DIRS = {".git", "node_modules", "target", "build", "dist"}


def markdown_files(path: Path) -> list[Path]:
    if path.is_file():
        return [path] if path.suffix.lower() == ".md" else []
    if not path.is_dir():
        return []

    files = []
    for candidate in path.rglob("*.md"):
        if not any(part in SKIP_DIRS for part in candidate.parts):
            files.append(candidate)
    return sorted(files)


def strip_fenced_code(text: str) -> str:
    lines = []
    in_fence = False
    fence = ""

    for line in text.splitlines():
        stripped = line.lstrip()
        if stripped.startswith(("```", "~~~")):
            marker = stripped[:3]
            if not in_fence:
                in_fence = True
                fence = marker
            elif marker == fence:
                in_fence = False
                fence = ""
            lines.append("")
            continue
        lines.append("" if in_fence else line)

    return "\n".join(lines)


def normalize_target(raw_target: str) -> str | None:
    target = raw_target.strip()
    if not target or target.startswith("#"):
        return None
    if target.lower().startswith(SCHEMES):
        return None

    if target.startswith("<") and ">" in target:
        target = target[1 : target.index(">")]
    elif " " in target:
        target = target.split(" ", 1)[0]

    target = unquote(target).split("#", 1)[0].split("?", 1)[0]
    if not target or target.startswith("/"):
        return None
    return target


def check_file(path: Path) -> list[tuple[str, str]]:
    text = strip_fenced_code(path.read_text(encoding="utf-8"))
    failures = []

    for match in LINK_PATTERN.finditer(text):
        raw_target = match.group(1)
        target = normalize_target(raw_target)
        if target is None:
            continue

        resolved = (path.parent / target).resolve()
        if not resolved.exists():
            failures.append((raw_target, str(resolved)))

    return failures


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate repository-relative links in Markdown files."
    )
    parser.add_argument(
        "paths",
        nargs="+",
        help="Markdown files or directories to scan.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    files: list[Path] = []
    missing_inputs = []

    for raw_path in args.paths:
        path = Path(raw_path)
        if not path.exists():
            missing_inputs.append(raw_path)
            continue
        files.extend(markdown_files(path))

    failures = []
    for path in sorted(set(files)):
        for raw_target, resolved in check_file(path):
            failures.append((path, raw_target, resolved))

    for raw_path in missing_inputs:
        print(f"missing input: {raw_path}", file=sys.stderr)

    for path, raw_target, resolved in failures:
        print(
            f"{path}: broken link {raw_target!r} -> {resolved}",
            file=sys.stderr,
        )

    if missing_inputs or failures:
        print(
            f"checked {len(set(files))} Markdown files: "
            f"{len(failures)} broken links, {len(missing_inputs)} missing inputs",
            file=sys.stderr,
        )
        return 1

    print(f"checked {len(set(files))} Markdown files: all relative links resolve")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
