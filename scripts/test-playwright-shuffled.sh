#!/usr/bin/env bash

# Order-independence probe for the Mock Playwright suite.
#
# Playwright has no `--shuffle` flag, so this script lists every test, shuffles
# the list with a reproducible seed, and replays it through `--test-list`.
# Running with one worker by default makes the execution order exactly the
# shuffled order, which is the strictest way to detect cross-file coupling
# (shared storage, leaked routes, module-level singletons).
#
# This probe is deliberately separate from scripts/verify.sh and from the
# required CI gate: a shuffled failure is a flake signal, not a delivery block.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/uniauth-playwright-shuffle.XXXXXX")"
SEED="${PLAYWRIGHT_SHUFFLE_SEED:-$(date +%s)}"
WORKERS="${PLAYWRIGHT_WORKERS:-1}"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

cd "$REPO_ROOT/frontend"

npx playwright test --list >"$TEMP_DIR/tests.txt"
python3 - "$SEED" "$TEMP_DIR" <<'PY'
import random
import sys

seed, temp_dir = int(sys.argv[1]), sys.argv[2]
lines = [
    line.strip()
    for line in open(f"{temp_dir}/tests.txt", encoding="utf-8")
    if line.strip().startswith("[")
]
random.seed(seed)
random.shuffle(lines)
with open(f"{temp_dir}/shuffled.txt", "w", encoding="utf-8") as handle:
    handle.write("\n".join(lines) + "\n")
print(f"Shuffled {len(lines)} tests with seed {seed}")
PY

npx playwright test \
    --test-list "$TEMP_DIR/shuffled.txt" \
    --workers="$WORKERS"
