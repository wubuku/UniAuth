#!/usr/bin/env python3
"""Test-rot guard: block *new* weak or disabled tests.

The gate is ratcheted: `config/test-rot-baseline.json` records the smells that
already existed when the guard was introduced, and only additions fail. That
avoids a flag-day rewrite while still stopping the suite from rotting further.

Every hit is reported as `path:line rule text` followed by the reason the
pattern is dangerous. `--update` rewrites the baseline and is the explicit
step required before a new occurrence is allowed.

Usage:
    python3 scripts/check-test-rot.py             # check (gate mode)
    python3 scripts/check-test-rot.py --list      # print every hit, no gating
    python3 scripts/check-test-rot.py --update    # rewrite the baseline
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
_ROOT = REPO_ROOT
DEFAULT_BASELINE = REPO_ROOT / "config" / "test-rot-baseline.json"

SKIP_DIR_NAMES = {
    "node_modules",
    "target",
    "coverage",
    "test-results",
    "playwright-report",
    "blob-report",
    ".git",
}

JAVA_TOKEN = re.compile(
    # Strong assertions. `assertDoesNotThrow` is excluded: a test whose only
    # "assertion" is "no exception was thrown" proves nothing about the result.
    r"\bassert(?!DoesNotThrow\b)(?!ThatCode\b)[A-Za-z]*\b"
    r"|\bverify(?:NoInteractions|NoMoreInteractions|ZeroInteractions)?\s*\("
    r"|\bfail\s*\(|\.andExpect\s*\("
    # `assertThatCode(...)` is only strong when it ends in a real check. A bare
    # `.doesNotThrowAnyException()` chain is the same weak pattern as
    # `assertDoesNotThrow`.
    r"|assertThatCode\s*\([^;]*?\.\s*(?:isInstanceOf|isNotInstanceOf|hasMessage"
    r"|hasCause|hasRootCause|hasNoCause|isNotNull|isNull|satisfies|isEqualTo)",
    re.S,
)

# (rule, regex, reason). The reasons are user-facing: they explain why the
# pattern hides a real gap instead of just restating the regex.
JAVA_RULES: list[tuple[str, re.Pattern[str], str]] = [
    (
        "disabled-test",
        re.compile(r"^\s*@(?:Disabled|Ignore)\b"),
        "被禁用的测试仍然让 CI 变绿，对应行为实际无人保护。应修复或删除。",
    ),
    (
        "constant-assertion",
        re.compile(
            r"assertTrue\s*\(\s*true\s*\)|assertFalse\s*\(\s*false\s*\)",
        ),
        "恒真断言永远不会失败，等于没有断言。",
    ),
    (
        "fixed-wait",
        re.compile(r"Thread\.sleep\s*\("),
        "固定睡眠与机器速度耦合：负载下产生假失败，或掩盖真实竞态。改用轮询/超时。",
    ),
    (
        "unfinished-marker",
        re.compile(r"\b(?:TODO|FIXME)\b"),
        "测试中的 TODO/FIXME 通常意味着断言或覆盖欠账，不能静默留在门禁里。",
    ),
    (
        "empty-test",
        re.compile(
            r"^\s*(?:public|protected|private)?\s*(?:static\s+)?void\s+\w+\s*"
            r"\([^)]*\)\s*(?:throws[^{]*)?\{\s*\}",
        ),
        "空测试方法不验证任何行为，只增加数字。",
    ),
]

TS_RULES: list[tuple[str, re.Pattern[str], str]] = [
    (
        "skipped-test",
        re.compile(r"\b(?:test|it|describe)(?:\.\w+)?\.skip\b"),
        "跳过的测试让 CI 变绿但行为无人保护；应修复、删除或显式记入基线。",
    ),
    (
        "focused-test",
        re.compile(r"\b(?:test|it|describe)(?:\.\w+)?\.only\b"),
        "`.only` 会静默丢弃其余用例——这是最危险的假绿：本地看起来通过，套件实际大幅缩水。",
    ),
    (
        "constant-assertion",
        re.compile(
            r"expect\s*\(\s*true\s*\)\s*\.\s*(?:toBe|toEqual|toStrictEqual)\s*\(\s*true\s*\)"
            r"|expect\s*\(\s*false\s*\)\s*\.\s*(?:toBe|toEqual|toStrictEqual)\s*\(\s*false\s*\)"
            r"|expect\s*\(\s*1\s*\)\s*\.\s*(?:toBe|toEqual|toStrictEqual)\s*\(\s*1\s*\)"
            r"|expect\s*\(\s*true\s*\)\s*\.\s*toBeTruthy\s*\("
            r"|expect\s*\(\s*false\s*\)\s*\.\s*toBeFalsy\s*\(",
        ),
        "恒真断言永远不会失败，等于没有断言。",
    ),
    (
        "empty-test",
        re.compile(
            r"\b(?:test|it)(?:\.\w+)?\s*\(\s*(['\"`])[^'\"`]*\1"
            r"\s*,\s*(?:async\s*)?\(\s*\)\s*=>\s*\{\s*\}\s*\)",
        ),
        "空测试回调不验证任何行为，只增加数字。",
    ),
    (
        "fixed-wait",
        re.compile(r"waitForTimeout\s*\(|new Promise[^\n]*setTimeout\s*\("),
        "固定等待不观察真实状态；负载下要么假失败要么掩盖竞态。改用 locator/expect 轮询。",
    ),
    (
        "unfinished-marker",
        re.compile(r"\b(?:TODO|FIXME)\b"),
        "测试中的 TODO/FIXME 通常意味着断言或覆盖欠账，不能静默留在门禁里。",
    ),
]

PY_RULES: list[tuple[str, re.Pattern[str], str]] = [
    (
        "skipped-test",
        re.compile(r"@(?:unittest\.)?skip(?:If|Unless)?\b|pytest\.mark\.skip\b"),
        "跳过的测试让 CI 变绿但行为无人保护。",
    ),
    (
        "constant-assertion",
        re.compile(r"^\s*assert\s+True\s*$"),
        "恒真断言永远不会失败，等于没有断言。",
    ),
    (
        "fixed-wait",
        re.compile(r"\btime\.sleep\s*\("),
        "固定睡眠与机器速度耦合：负载下产生假失败，或掩盖真实竞态。改用轮询/超时。",
    ),
    (
        "unfinished-marker",
        re.compile(r"\b(?:TODO|FIXME)\b"),
        "测试中的 TODO/FIXME 通常意味着断言或覆盖欠账，不能静默留在门禁里。",
    ),
]

JAVA_TEST_METHOD = re.compile(
    r"^(?P<indent>\s*)(?:public|protected|private)?\s*(?:static\s+)?"
    r"(?:void|[\w<>\[\],.? ]+)\s+\w+\s*\([^;{]*\)\s*(?:throws[^{]*)?\{",
)


@dataclass(frozen=True)
class Hit:
    path: str
    line: int
    rule: str
    text: str
    reason: str

    @property
    def key(self) -> str:
        return f"{self.path}|{self.rule}|{normalize(self.text)}"


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text.strip())


def iter_test_files() -> list[tuple[Path, str]]:
    """Return (path, language) pairs for the files this guard owns."""
    files: list[tuple[Path, str]] = []
    for root_text, language in (
        ("src/test", "java"),
        ("reference/email-service/src/test", "java"),
        ("frontend/tests", "typescript"),
        ("python-resource-server/test_app.py", "python"),
    ):
        root = _ROOT / root_text
        if root.is_file():
            files.append((root, language))
            continue
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*")):
            if not path.is_file():
                continue
            if any(part in SKIP_DIR_NAMES for part in path.parts):
                continue
            if language == "java" and path.suffix == ".java":
                files.append((path, language))
            elif language == "typescript" and path.suffix in {".ts", ".tsx"}:
                files.append((path, language))
    return files


def rules_for(language: str) -> list[tuple[str, re.Pattern[str], str]]:
    if language == "java":
        return JAVA_RULES
    if language == "typescript":
        return TS_RULES
    return PY_RULES


def strip_java_literals(line: str) -> str:
    """Remove string/char literals and trailing line comments before brace counting."""
    output: list[str] = []
    quote: str | None = None
    escaped = False
    for index, char in enumerate(line):
        if quote is not None:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char in "\"'":
            quote = char
            continue
        if char == "/" and index + 1 < len(line) and line[index + 1] == "/":
            break
        output.append(char)
    return "".join(output)


def is_annotated_test(lines: list[str], index: int) -> bool:
    """True when the method is directly preceded by a JUnit test annotation."""
    cursor = index - 1
    while cursor >= 0:
        stripped = lines[cursor].strip()
        if not stripped or stripped.startswith(("//", "*", "/*")):
            cursor -= 1
            continue
        if stripped.startswith("@"):
            if re.match(r"@(?:Test|ParameterizedTest|RepeatedTest)\b", stripped):
                return True
            cursor -= 1
            continue
        return False
    return False


def scan_java_no_assertion(relative: str, lines: list[str]) -> list[Hit]:
    """Flag @Test methods whose body contains no assertion at all.

    This is deliberately heuristic (brace counting, no parser). It runs only
    over java test sources and is ratcheted through the baseline, so a false
    positive is reviewed once instead of blocking unrelated work.
    """
    hits: list[Hit] = []
    total = len(lines)
    index = 0
    while index < total:
        match = JAVA_TEST_METHOD.match(lines[index])
        if not match:
            index += 1
            continue
        if re.match(r"\s*private\b", lines[index]) or not is_annotated_test(lines, index):
            index += 1
            continue
        depth = 0
        body_end = index
        started = False
        for cursor in range(index, total):
            cleaned = strip_java_literals(lines[cursor])
            depth += cleaned.count("{") - cleaned.count("}")
            if "{" in cleaned:
                started = True
            if started and depth == 0:
                body_end = cursor
                break
        body = "\n".join(
            strip_java_literals(line) for line in lines[index:body_end + 1]
        )
        if not JAVA_TOKEN.search(body):
            hits.append(
                Hit(
                    path=relative,
                    line=index + 1,
                    rule="no-assertion",
                    text=lines[index],
                    reason="测试方法没有任何断言或 verify，只能证明“没抛异常”，不能证明行为正确。",
                )
            )
        index = body_end + 1
    return hits


def scan_file(path: Path, language: str) -> list[Hit]:
    relative = str(path.relative_to(_ROOT))
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    hits: list[Hit] = []
    for number, line in enumerate(lines, start=1):
        for rule, pattern, reason in rules_for(language):
            if pattern.search(line):
                hits.append(
                    Hit(
                        path=relative,
                        line=number,
                        rule=rule,
                        text=line,
                        reason=reason,
                    )
                )
    if language == "java":
        hits.extend(scan_java_no_assertion(relative, lines))
    return hits


def collect() -> list[Hit]:
    hits: list[Hit] = []
    for path, language in iter_test_files():
        hits.extend(scan_file(path, language))
    return hits


def load_baseline(path: Path) -> set[str]:
    if not path.is_file():
        return set()
    with path.open(encoding="utf-8") as handle:
        data = json.load(handle)
    entries = data.get("entries", [])
    if not isinstance(entries, list):
        raise SystemExit(f"ERROR: malformed baseline entries: {path}")
    return {str(entry) for entry in entries}


def save_baseline(path: Path, hits: list[Hit]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    entries = sorted({hit.key for hit in hits})
    with path.open("w", encoding="utf-8") as handle:
        json.dump({"version": 1, "entries": entries}, handle, indent=2)
        handle.write("\n")


def format_hit(hit: Hit) -> str:
    return f"{hit.path}:{hit.line} [{hit.rule}] {normalize(hit.text)}\n    {hit.reason}"


def main() -> int:
    global _ROOT

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--update", action="store_true")
    parser.add_argument("--list", dest="list_only", action="store_true")
    args = parser.parse_args()

    _ROOT = args.root.resolve()
    baseline_path = args.baseline or (_ROOT / "config" / "test-rot-baseline.json")

    hits = collect()

    if args.list_only:
        for hit in hits:
            print(format_hit(hit))
        print(f"\n{len(hits)} hit(s) across {len({hit.path for hit in hits})} file(s)")
        return 0

    if args.update:
        save_baseline(baseline_path, hits)
        print(f"Test-rot baseline updated: {baseline_path} ({len(hits)} hit(s))")
        return 0

    known = load_baseline(baseline_path)
    new_hits = [hit for hit in hits if hit.key not in known]
    stale = sorted(known - {hit.key for hit in hits})

    print(
        f"Test-rot guard: {len(hits)} hit(s), {len(hits) - len(new_hits)} baselined, "
        f"{len(new_hits)} new"
    )
    for entry in stale:
        print(f"NOTE: baseline entry no longer matches any hit (prune with --update): {entry}")
    if new_hits:
        print("", file=sys.stderr)
        print("ERROR: new test-rot pattern(s) detected:", file=sys.stderr)
        for hit in new_hits:
            print(format_hit(hit), file=sys.stderr)
        print(
            "\nFix the pattern. If it is genuinely intended, record it explicitly:\n"
            "  python3 scripts/check-test-rot.py --update\n"
            "and commit config/test-rot-baseline.json with a justification.",
            file=sys.stderr,
        )
        return 1
    print("Test-rot guard passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
