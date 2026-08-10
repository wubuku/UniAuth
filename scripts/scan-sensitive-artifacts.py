#!/usr/bin/env python3

from __future__ import annotations

import argparse
from datetime import date, datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Iterable


MAX_FILE_BYTES = 20 * 1024 * 1024
RULES = (
    (
        "private-key-pem",
        re.compile(rb"-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY-----"),
    ),
    (
        "full-jwt",
        re.compile(
            rb"\beyJ[A-Za-z0-9_-]{10,}\."
            rb"[A-Za-z0-9_-]{10,}\."
            rb"[A-Za-z0-9_-]{10,}\b"
        ),
    ),
    (
        "aws-access-key",
        re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    ),
    (
        "github-token",
        re.compile(
            rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|"
            rb"github_pat_[A-Za-z0-9_]{40,})\b"
        ),
    ),
    (
        "openai-api-key",
        re.compile(rb"\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\b"),
    ),
    (
        "google-api-key",
        re.compile(rb"\bAIza[A-Za-z0-9_-]{35}\b"),
    ),
    (
        "slack-token",
        re.compile(rb"\bxox[baprs]-[A-Za-z0-9-]{20,}\b"),
    ),
    (
        "wallet-private-key",
        re.compile(
            rb"(?i)(?:private[_-]?key|wallet[_-]?key)"
            rb"\s*[:=]\s*[\"']?(?:0x)?[0-9a-f]{64}\b"
        ),
    ),
)
SKIPPED_DIRECTORIES = {
    ".git",
    ".idea",
    ".vscode",
    "node_modules",
    "playwright-report",
    "test-results",
}


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Scan repository and candidate build files for sensitive values."
    )
    parser.add_argument(
        "--repository",
        type=Path,
        help="Git repository whose tracked and non-ignored files should be scanned.",
    )
    parser.add_argument(
        "--path",
        action="append",
        type=Path,
        default=[],
        help="Additional file or directory to scan. May be repeated.",
    )
    parser.add_argument("--exceptions", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    return parser.parse_args()


def load_exceptions(path: Path) -> dict[str, dict[str, str]]:
    if not path.is_file():
        raise ValueError(f"sensitive scan exception manifest is missing: {path}")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid sensitive scan exception manifest: {error}") from error
    if document.get("version") != 1:
        raise ValueError("sensitive scan exception manifest version must be 1")
    entries = document.get("exceptions")
    if not isinstance(entries, list):
        raise ValueError("sensitive scan exceptions must be a list")

    result: dict[str, dict[str, str]] = {}
    today = datetime.now(timezone.utc).date()
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError("every sensitive scan exception must be an object")
        exception_id = entry.get("id")
        fingerprint = entry.get("fingerprint")
        owner = entry.get("owner")
        reason = entry.get("reason")
        expires = entry.get("expires")
        if not isinstance(exception_id, str) or not exception_id.strip():
            raise ValueError("every sensitive scan exception must have an id")
        if not isinstance(fingerprint, str) or not re.fullmatch(
            r"[0-9a-f]{64}", fingerprint
        ):
            raise ValueError(
                f"sensitive scan exception {exception_id} has an invalid fingerprint"
            )
        if fingerprint in result:
            raise ValueError(
                f"duplicate sensitive scan exception fingerprint: {fingerprint}"
            )
        if not isinstance(owner, str) or not owner.strip():
            raise ValueError(
                f"sensitive scan exception {exception_id} must have an owner"
            )
        if not isinstance(reason, str) or len(reason.strip()) < 40:
            raise ValueError(
                f"sensitive scan exception {exception_id} needs a detailed reason"
            )
        try:
            expires_on = date.fromisoformat(expires)
        except (TypeError, ValueError) as error:
            raise ValueError(
                f"sensitive scan exception {exception_id} has an invalid expiry"
            ) from error
        if expires_on <= today:
            raise ValueError(
                f"sensitive scan exception {exception_id} expired on {expires_on}"
            )
        result[fingerprint] = {
            "id": exception_id,
            "owner": owner,
            "reason": reason,
            "expires": expires,
        }
    return result


def repository_files(repository: Path) -> Iterable[Path]:
    repository = repository.resolve(strict=True)
    if not (repository / ".git").exists():
        raise ValueError(f"not a Git repository: {repository}")
    try:
        output = subprocess.check_output(
            [
                "git",
                "-C",
                str(repository),
                "ls-files",
                "-z",
                "--cached",
                "--others",
                "--exclude-standard",
            ]
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise ValueError(f"failed to list repository files: {error}") from error
    for raw_path in output.split(b"\0"):
        if not raw_path:
            continue
        candidate = repository / os.fsdecode(raw_path)
        if candidate.exists() or candidate.is_symlink():
            yield candidate


def explicit_files(path: Path) -> Iterable[Path]:
    resolved = path.resolve(strict=True)
    if resolved.is_file() or resolved.is_symlink():
        yield resolved
        return
    for root, directories, files in os.walk(resolved, followlinks=False):
        directories[:] = sorted(
            directory
            for directory in directories
            if directory not in SKIPPED_DIRECTORIES
        )
        for filename in sorted(files):
            yield Path(root) / filename


def display_path(path: Path, repository: Path | None) -> str:
    resolved = path.resolve(strict=False)
    if repository is not None:
        try:
            return resolved.relative_to(repository.resolve(strict=True)).as_posix()
        except ValueError:
            pass
    return str(resolved)


def read_file(path: Path) -> bytes:
    if path.is_symlink():
        return os.readlink(path).encode("utf-8", errors="surrogateescape")
    size = path.stat().st_size
    if size > MAX_FILE_BYTES:
        raise ValueError(
            f"file exceeds the {MAX_FILE_BYTES}-byte scan limit: {path}"
        )
    return path.read_bytes()


def finding_fingerprint(rule: str, path: str, matched: bytes) -> str:
    match_digest = hashlib.sha256(matched).hexdigest()
    payload = f"{rule}\0{path}\0{match_digest}".encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def scan_file(
        path: Path,
        relative_path: str,
        exceptions: dict[str, dict[str, str]],
) -> tuple[list[dict[str, object]], list[dict[str, object]]]:
    data = read_file(path)
    findings: list[dict[str, object]] = []
    suppressed: list[dict[str, object]] = []
    for rule_name, pattern in RULES:
        for match in pattern.finditer(data):
            fingerprint = finding_fingerprint(
                rule_name,
                relative_path,
                match.group(0),
            )
            record: dict[str, object] = {
                "rule": rule_name,
                "path": relative_path,
                "line": data.count(b"\n", 0, match.start()) + 1,
                "fingerprint": fingerprint,
            }
            exception = exceptions.get(fingerprint)
            if exception is None:
                findings.append(record)
            else:
                suppressed.append(
                    {
                        **record,
                        "exception_id": exception["id"],
                        "expires": exception["expires"],
                    }
                )
    if (
        re.search(r"(?i)(?:signing|rsa|private)[-_]?(?:key|keys)", path.name)
        and len(data) > 256
        and len(data) >= 8
    ):
        private_length = int.from_bytes(data[:4], byteorder="big", signed=False)
        if 128 <= private_length < len(data) - 4 and data[4] == 0x30:
            matched = data[: min(len(data), 64)]
            fingerprint = finding_fingerprint(
                "serialized-rsa-private-key",
                relative_path,
                matched,
            )
            record = {
                "rule": "serialized-rsa-private-key",
                "path": relative_path,
                "line": 1,
                "fingerprint": fingerprint,
            }
            exception = exceptions.get(fingerprint)
            if exception is None:
                findings.append(record)
            else:
                suppressed.append(
                    {
                        **record,
                        "exception_id": exception["id"],
                        "expires": exception["expires"],
                    }
                )
    return findings, suppressed


def write_report(path: Path, report: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def main() -> int:
    arguments = parse_arguments()
    errors: list[str] = []
    findings: list[dict[str, object]] = []
    suppressed: list[dict[str, object]] = []
    scanned_files = 0
    repository = arguments.repository

    try:
        exceptions = load_exceptions(arguments.exceptions)
        if repository is None and not arguments.path:
            raise ValueError("at least one --repository or --path target is required")
        candidates: list[Path] = []
        if repository is not None:
            candidates.extend(repository_files(repository))
        for target in arguments.path:
            candidates.extend(explicit_files(target))

        unique_candidates = sorted(
            {candidate.resolve(strict=False): candidate for candidate in candidates}.values(),
            key=lambda candidate: display_path(candidate, repository),
        )
        if not unique_candidates:
            raise ValueError("sensitive scan did not discover any files")
        for candidate in unique_candidates:
            relative_path = display_path(candidate, repository)
            try:
                file_findings, file_suppressed = scan_file(
                    candidate,
                    relative_path,
                    exceptions,
                )
            except (OSError, ValueError) as error:
                errors.append(str(error))
                continue
            scanned_files += 1
            findings.extend(file_findings)
            suppressed.extend(file_suppressed)
    except (OSError, ValueError) as error:
        errors.append(str(error))

    report: dict[str, object] = {
        "version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "status": "pass" if not errors and not findings else "fail",
        "scanned_files": scanned_files,
        "findings": findings,
        "suppressed_findings": suppressed,
        "errors": errors,
    }
    try:
        write_report(arguments.report, report)
    except OSError as error:
        print(f"ERROR: failed to write sensitive scan report: {error}", file=sys.stderr)
        return 1

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    if findings:
        for finding in findings:
            print(
                "ERROR: sensitive value detected: "
                f"{finding['rule']} {finding['path']}:{finding['line']} "
                f"fingerprint={finding['fingerprint']}",
                file=sys.stderr,
            )
        return 1
    print(
        "PASS: sensitive artifact scan checked "
        f"{scanned_files} files with {len(suppressed)} active exceptions"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
