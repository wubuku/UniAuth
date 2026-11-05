#!/usr/bin/env bash
# Self-test for scripts/check-test-rot.py.
#
# Builds disposable fixture trees and proves the guard:
#   - reports path:line for every rule,
#   - fails closed on a new smell,
#   - passes once the smell is recorded in the baseline,
#   - still fails when another smell is added on top of the baseline.
#
# It never touches the real repository baseline.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/uniauth-test-rot.XXXXXX")"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT INT TERM

ROOT="$TEMP_DIR/fixture"
mkdir -p \
    "$ROOT/src/test/java/example" \
    "$ROOT/frontend/tests" \
    "$ROOT/python-resource-server"

cat >"$ROOT/src/test/java/example/FixtureTest.java" <<'JAVA'
package example;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixtureTest {
    @Disabled
    @Test
    void skippedTest() {
        assertTrue(true);
    }

    @Test
    void testWithoutAssertions() {
        System.out.println("no assertion here");
    }

    @Test
    void emptyTest() { }

    @Test
    void weakAssertDoesNotThrow() {
        assertDoesNotThrow(() -> Integer.parseInt("1"));
    }

    @Test
    void weakAssertThatCodeDoesNotThrow() {
        assertThatCode(() -> Integer.parseInt("1"))
                .doesNotThrowAnyException();
    }

    @Test
    void strongAssertThatCodeIsAnAssertion() {
        assertThatCode(() -> Integer.parseInt("not-a-number"))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void strongAssertionCounts() {
        assertThat(1 + 1).isEqualTo(2);
    }
}
JAVA

cat >"$ROOT/frontend/tests/fixture.spec.ts" <<'TS'
import { test, expect } from '@playwright/test';

test.only('focused test', async () => {
    expect(true).toBe(true);
});

test('empty test', async () => {});

test('fixed wait', async () => {
    await new Promise((resolve) => setTimeout(resolve, 100));
});
TS

cat >"$ROOT/python-resource-server/test_app.py" <<'PY'
import time
import unittest


class FixtureTest(unittest.TestCase):
    @unittest.skip("skip")
    def test_skipped(self):
        assert True

    def test_sleep(self):
        time.sleep(1)
PY

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

echo "1/4: violations are reported with file and line"
if "$PYTHON_BIN" "$PROJECT_DIR/scripts/check-test-rot.py" \
        --root "$ROOT" 2>"$TEMP_DIR/report.txt"; then
    fail "guard accepted a tree full of smells"
fi
grep -Eq "FixtureTest.java:[0-9]+ \[disabled-test\]" "$TEMP_DIR/report.txt" \
    || fail "missing disabled-test hit with line number"
grep -Eq "FixtureTest.java:[0-9]+ \[constant-assertion\]" "$TEMP_DIR/report.txt" \
    || fail "missing constant-assertion hit"
grep -Eq "FixtureTest.java:[0-9]+ \[no-assertion\] void testWithoutAssertions" \
    "$TEMP_DIR/report.txt" \
    || fail "missing no-assertion hit"
grep -Eq "FixtureTest.java:[0-9]+ \[no-assertion\] void weakAssertDoesNotThrow" \
    "$TEMP_DIR/report.txt" \
    || fail "assertDoesNotThrow was accepted as an assertion"
grep -Eq "FixtureTest.java:[0-9]+ \[no-assertion\] void weakAssertThatCodeDoesNotThrow" \
    "$TEMP_DIR/report.txt" \
    || fail "doesNotThrowAnyException was accepted as an assertion"
if grep -q "strongAssertThatCodeIsAnAssertion" "$TEMP_DIR/report.txt"; then
    fail "assertThatCode(...).isInstanceOf was falsely flagged as no-assertion"
fi
if grep -q "strongAssertionCounts" "$TEMP_DIR/report.txt"; then
    fail "a real AssertJ assertion was falsely flagged as no-assertion"
fi
grep -Eq "FixtureTest.java:[0-9]+ \[empty-test\]" "$TEMP_DIR/report.txt" \
    || fail "missing empty-test hit"
grep -Eq "fixture.spec.ts:[0-9]+ \[focused-test\]" "$TEMP_DIR/report.txt" \
    || fail "missing focused-test hit"
grep -Eq "test_app.py:[0-9]+ \[skipped-test\]" "$TEMP_DIR/report.txt" \
    || fail "missing python skipped-test hit"
grep -Eq "test_app.py:[0-9]+ \[fixed-wait\]" "$TEMP_DIR/report.txt" \
    || fail "missing python fixed-wait hit"

echo "2/4: baseline records the current inventory"
"$PYTHON_BIN" "$PROJECT_DIR/scripts/check-test-rot.py" --root "$ROOT" --update \
    >/dev/null

echo "3/4: the recorded inventory passes"
"$PYTHON_BIN" "$PROJECT_DIR/scripts/check-test-rot.py" --root "$ROOT" \
    || fail "guard rejected its own baseline"

echo "4/4: a new smell on top of the baseline fails"
cat >>"$ROOT/frontend/tests/fixture.spec.ts" <<'TS'

test.skip('newly skipped', async () => {
    expect(true).toBe(true);
});
TS
if "$PYTHON_BIN" "$PROJECT_DIR/scripts/check-test-rot.py" \
        --root "$ROOT" 2>"$TEMP_DIR/new.txt"; then
    fail "guard accepted a newly skipped test"
fi
grep -q "newly skipped" "$TEMP_DIR/new.txt" \
    || fail "new violation was not reported by name"
if grep -q "disabled-test" "$TEMP_DIR/new.txt"; then
    fail "baselined violations must not be reported again"
fi

echo "PASS: test-rot guard self-test"
