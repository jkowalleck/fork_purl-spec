# SPDX-License-Identifier: MIT
# Copyright (c) the purl authors
"""
Pytest configuration and shared fixtures for PURL ABNF grammar tests.

Loads the ABNF grammar from docs/standard/grammar.md and builds a
Rule validator for the ``purl`` and ``purl-canonical`` start rules.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

from abnf import ParseError, Rule

# ---------------------------------------------------------------------------
# Repository layout
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).parent.parent.parent
GRAMMAR_MD = REPO_ROOT / "docs" / "standard" / "grammar.md"
TESTS_DIR = REPO_ROOT / "tests"

# ---------------------------------------------------------------------------
# ABNF grammar loading
# ---------------------------------------------------------------------------


def _extract_abnf(path: Path) -> str:
    """Extract the first ```abnf fenced code block from a Markdown file."""
    content = path.read_text(encoding="utf-8")
    match = re.search(r"```abnf\n(.*?)```", content, re.DOTALL)
    if not match:
        raise ValueError(f"No ABNF code block found in {path}")
    return match.group(1)


# ---------------------------------------------------------------------------
# Module-level grammar initialisation (loaded once per session)
# ---------------------------------------------------------------------------


class PurlRule(Rule):
    """ABNF rule class populated with the PURL grammar."""


PurlRule.load_grammar(_extract_abnf(GRAMMAR_MD))


def validate(value: str, rule_name: str) -> bool:
    """
    Return ``True`` if *value* fully matches the ABNF rule *rule_name*,
    ``False`` otherwise.
    """
    try:
        PurlRule(rule_name).parse_all(value)
        return True
    except ParseError:
        return False


# ---------------------------------------------------------------------------
# Test-case collection
# ---------------------------------------------------------------------------


def _safe_id(value: str) -> str:
    """Return a pytest-safe version of *value* (replace special chars)."""
    return re.sub(r"[^A-Za-z0-9._-]", "_", value)


def collect_input_cases() -> list[tuple[str, str]]:
    """
    Return ``(test_id, input_value)`` pairs for every ``$.tests[].input``
    string from ``test_type == "parse"`` entries where
    ``expected_failure is true`` under `TESTS_DIR`.

    These are inputs that PURL implementations must reject; the grammar
    should likewise reject them.  Type-specific constraints (e.g. a
    required namespace for a particular type) may cause the grammar to
    accept a string that an implementation rejects — those cases are
    handled with ``pytest.xfail`` in the test.
    """
    seen_ids: dict[str, int] = {}
    cases: list[tuple[str, str]] = []

    for json_file in sorted(TESTS_DIR.rglob("*.json")):
        folder = json_file.parent.name
        file_base = json_file.stem

        try:
            data = json.loads(json_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue

        tests = data.get("tests")
        if not isinstance(tests, list):
            continue

        for test in tests:
            if not isinstance(test, dict):
                continue

            if test.get("test_type") != "parse":
                continue
            if test.get("expected_failure") is not True:
                continue

            input_val = test.get("input")
            if not isinstance(input_val, str):
                continue

            base_id = f"grammar.{folder}.{file_base}.input.{_safe_id(input_val)}"
            count = seen_ids.get(base_id, 0)
            seen_ids[base_id] = count + 1
            test_id = base_id if count == 0 else f"{base_id}.{count}"

            cases.append((test_id, input_val))

    return cases


def collect_output_cases() -> list[tuple[str, str]]:
    """
    Return ``(test_id, expected_output_value)`` pairs for every
    ``$.tests[].expected_output`` string found under `TESTS_DIR` where
    ``expected_failure`` is not ``true``.
    """
    seen_ids: dict[str, int] = {}
    cases: list[tuple[str, str]] = []

    for json_file in sorted(TESTS_DIR.rglob("*.json")):
        folder = json_file.parent.name
        file_base = json_file.stem

        try:
            data = json.loads(json_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue

        tests = data.get("tests")
        if not isinstance(tests, list):
            continue

        for test in tests:
            if not isinstance(test, dict):
                continue

            if test.get("expected_failure") is True:
                continue

            output_val = test.get("expected_output")
            if not isinstance(output_val, str):
                continue

            base_id = (
                f"grammar.{folder}.{file_base}"
                f".expected_output.{_safe_id(output_val)}"
            )
            count = seen_ids.get(base_id, 0)
            seen_ids[base_id] = count + 1
            test_id = base_id if count == 0 else f"{base_id}.{count}"

            cases.append((test_id, output_val))

    return cases


# ---------------------------------------------------------------------------
# Pre-collect at import time so pytest parametrize can use them
# ---------------------------------------------------------------------------

INPUT_CASES = collect_input_cases()
OUTPUT_CASES = collect_output_cases()
