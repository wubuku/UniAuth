#!/usr/bin/env python3
"""Readiness parity guard: keep the two PostgreSQL readiness helpers in lockstep.

The root helper (`scripts/lib/postgres-readiness.sh`) and the component copy
(`reference/email-service/scripts/lib/postgres-readiness.sh`) cannot be one file:
the component's `scripts/verify.sh` only snapshots that directory, so a path into
the repository root would not exist inside the verification snapshot. That local
copy is exactly how two implementations silently drift apart.

Two rules are enforced:

1. **Region parity** — code between the explicit markers must be byte-identical
   after normalizing line endings and trailing whitespace:

       # === BEGIN SHARED POSTGRES READINESS ===
       ...functions...
       # === END SHARED POSTGRES READINESS ===

2. **No shadow copies** — every function defined inside the region is part of the
   shared contract. Redefining a function with the same name OUTSIDE the markers
   (in either file) is the obvious way to "fix" one side locally while the guard
   keeps passing, so it fails closed. Component-only helpers with their own names
   are fine.

Usage:
    python3 scripts/check-readiness-parity.py
    python3 scripts/check-readiness-parity.py --root-script PATH --component-script PATH
"""

from __future__ import annotations

import argparse
import difflib
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ROOT_SCRIPT = REPO_ROOT / "scripts" / "lib" / "postgres-readiness.sh"
DEFAULT_COMPONENT_SCRIPT = (
    REPO_ROOT / "reference" / "email-service" / "scripts" / "lib" / "postgres-readiness.sh"
)

BEGIN_MARKER = "# === BEGIN SHARED POSTGRES READINESS ==="
END_MARKER = "# === END SHARED POSTGRES READINESS ==="

# Bash accepts two spellings, with optional spaces and an optional `function`
# keyword: `name () {`, `name(){`, `function name {`, `function name() {`.
# The patterns require a line-start definition so ordinary calls are not matches.
BARE_DEFINITION = re.compile(
    r"^(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*\(\s*\)\s*(?:\{|$)"
)
KEYWORD_DEFINITION = re.compile(
    r"^function\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*(?:\(\s*\))?\s*(?:\{|$)"
)
HEREDOC_START = re.compile(r"<<-?\s*(?P<quote>['\"]?)(?P<delimiter>[A-Za-z_][A-Za-z0-9_]*)\1")


class MarkerError(Exception):
    """A file does not contain exactly one well-formed marker region."""


class ShadowDefinitionError(Exception):
    """A shared function is redefined outside the shared region."""


def load_lines(path: Path) -> list[str]:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise MarkerError(f"cannot read {path}: {exc}") from exc
    return text.replace("\r\n", "\n").replace("\r", "\n").split("\n")


def locate_region(lines: list[str], path: Path) -> tuple[int, int]:
    """Return (begin_index, end_index) for the marker pair, validating the layout."""
    begin_indexes = [index for index, line in enumerate(lines) if line.strip() == BEGIN_MARKER]
    end_indexes = [index for index, line in enumerate(lines) if line.strip() == END_MARKER]

    if len(begin_indexes) != 1 or len(end_indexes) != 1:
        raise MarkerError(
            f"{path}: expected exactly one BEGIN and one END marker, "
            f"found {len(begin_indexes)} BEGIN / {len(end_indexes)} END"
        )
    if end_indexes[0] <= begin_indexes[0]:
        raise MarkerError(f"{path}: END marker appears before BEGIN marker")
    return begin_indexes[0], end_indexes[0]


def normalize(lines: list[str]) -> list[str]:
    # Trailing whitespace is a formatting choice, not behavior; ignore it so an
    # editor that strips spaces does not look like a semantic divergence.
    normalized = [line.rstrip() for line in lines]
    while normalized and not normalized[0]:
        normalized.pop(0)
    while normalized and not normalized[-1]:
        normalized.pop()
    return normalized


def extract_region(path: Path) -> list[str]:
    """Return the normalized lines inside the shared region of `path`."""
    lines = load_lines(path)
    begin_index, end_index = locate_region(lines, path)
    return normalize(lines[begin_index + 1 : end_index])


def _quote_state(line: str, state: str | None) -> str | None:
    """Track a multi-line shell quote so string content is never parsed as code."""
    index = 0
    while index < len(line):
        character = line[index]
        if character == "\\" and state != "'":
            index += 2
            continue
        if state:
            if character == state:
                state = None
        elif character == "#" and (index == 0 or line[index - 1].isspace()):
            # An inline comment (e.g. "don't") must not open a shell quote.
            break
        elif character in ("'", '"'):
            state = character
        index += 1
    return state


def scan_function_names(lines: list[str]) -> list[tuple[int, str]]:
    """Return (line_number, name) for real definitions, skipping comments,
    heredoc bodies and multi-line strings.

    A guard that only matches one spelling is easy to bypass by writing
    `function name {` or `name () {`; over-matching is just as bad because a
    commented example or a heredoc would be reported as a shadow.
    """
    definitions: list[tuple[int, str]] = []
    heredoc: str | None = None
    quote: str | None = None
    for index, line in enumerate(lines, start=1):
        stripped = line.strip()
        if heredoc is not None:
            if stripped == heredoc:
                heredoc = None
            continue
        if quote is not None:
            quote = _quote_state(line, quote)
            continue
        if not stripped or stripped.startswith("#"):
            continue

        match = KEYWORD_DEFINITION.match(stripped) or BARE_DEFINITION.match(stripped)
        if match:
            definitions.append((index, match.group("name")))

        heredoc_match = HEREDOC_START.search(line)
        if heredoc_match:
            heredoc = heredoc_match.group("delimiter")
            continue
        quote = _quote_state(line, None)
    return definitions


def shared_function_names(region: list[str]) -> set[str]:
    return {name for _, name in scan_function_names(region)}


def find_shadow_definitions(path: Path, shared_names: set[str]) -> list[tuple[int, str]]:
    """Return (line_number, name) for shared names redefined outside the markers."""
    if not shared_names:
        return []
    lines = load_lines(path)
    begin_index, end_index = locate_region(lines, path)
    shadows: list[tuple[int, str]] = []
    for index, name in scan_function_names(lines):
        if begin_index <= index - 1 <= end_index:
            continue
        if name in shared_names:
            shadows.append((index, name))
    return shadows


def describe(lines: list[str]) -> str:
    return f"{len(lines)} line(s)"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root-script", type=Path, default=DEFAULT_ROOT_SCRIPT)
    parser.add_argument("--component-script", type=Path, default=DEFAULT_COMPONENT_SCRIPT)
    args = parser.parse_args()

    root_script = args.root_script.resolve()
    component_script = args.component_script.resolve()

    try:
        root_region = extract_region(root_script)
        component_region = extract_region(component_script)
    except MarkerError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    if root_region != component_region:
        diff = list(
            difflib.unified_diff(
                root_region,
                component_region,
                fromfile=str(root_script),
                tofile=str(component_script),
                lineterm="",
                n=3,
            )
        )
        print("ERROR: shared PostgreSQL readiness region has drifted:", file=sys.stderr)
        for line in diff:
            print(line, file=sys.stderr)
        print(
            "\nBoth sides must stay identical between the BEGIN/END markers.\n"
            "If the component genuinely needs extra behavior, add it OUTSIDE the markers\n"
            "and keep the shared region unchanged. Otherwise edit both copies together.",
            file=sys.stderr,
        )
        return 1

    shared_names = shared_function_names(root_region)
    try:
        root_shadows = find_shadow_definitions(root_script, shared_names)
        component_shadows = find_shadow_definitions(component_script, shared_names)
    except MarkerError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    if root_shadows or component_shadows:
        print(
            "ERROR: shared readiness functions are redefined outside the markers:",
            file=sys.stderr,
        )
        for path, shadows in ((root_script, root_shadows), (component_script, component_shadows)):
            for line_number, name in shadows:
                print(f"  {path}:{line_number}: {name}()", file=sys.stderr)
        print(
            "\nA function defined inside the shared region is shared contract. Copying it\n"
            "outside the markers and editing that copy is exactly the drift this guard\n"
            "must block. Delete the local copy, or give the component-only helper a new\n"
            "name and keep it outside the markers.",
            file=sys.stderr,
        )
        return 1

    print(
        "Readiness parity guard passed: "
        f"{describe(root_region)} identical in both helpers; "
        f"{len(shared_names)} shared function(s) defined once"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
