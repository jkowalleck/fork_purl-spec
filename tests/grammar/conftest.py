# SPDX-License-Identifier: MIT
# Copyright (c) the purl authors
"""
Pytest configuration and shared fixtures for PURL ABNF grammar tests.

Extracts the ABNF grammar from docs/standard/grammar.md and builds
Rule validators for the ``purl`` and ``purl-canonical`` start rules.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest
from abnf import Rule
from abnf.grammars.misc import load_grammar_rules

# ---------------------------------------------------------------------------
# Repository layout
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).parent.parent.parent
GRAMMAR_MD = REPO_ROOT / "docs" / "standard" / "grammar.md"
TESTS_DIR = REPO_ROOT / "tests"

# ---------------------------------------------------------------------------
# ABNF grammar helpers
# ---------------------------------------------------------------------------


def _extract_abnf(path: Path) -> str:
    """Extract the first ```abnf fenced code block from a Markdown file."""
    content = path.read_text(encoding="utf-8")
    match = re.search(r"```abnf\n(.*?)```", content, re.DOTALL)
    if not match:
        raise ValueError(f"No ABNF code block found in {path}")
    return match.group(1)


def _strip_comment(line: str) -> str:
    """Strip a trailing ABNF comment (``; …``) from a line."""
    in_quote = False
    for i, ch in enumerate(line):
        if ch == '"':
            in_quote = not in_quote
        elif ch == ";" and not in_quote:
            return line[:i].rstrip()
    return line


def _normalize_abnf(text: str) -> list[str]:
    """
    Convert raw multi-line ABNF text into a list of single-line rule strings,
    stripping comments so that the ``abnf`` library can parse them.

    Rules with indented continuation lines (RFC 5234 §2.1) are joined into
    one flat string.  Comment-only lines and blank lines are discarded.
    """
    rules: list[str] = []
    current: str | None = None

    for raw_line in text.split("\n"):
        stripped = raw_line.rstrip()

        # Blank line → flush
        if not stripped:
            if current is not None:
                rules.append(current)
                current = None
            continue

        # Comment-only line
        if stripped.lstrip().startswith(";"):
            # Top-level comment → flush current rule
            if not raw_line[0].isspace() and current is not None:
                rules.append(current)
                current = None
            continue

        line_no_comment = _strip_comment(stripped)
        if not line_no_comment.strip():
            continue

        if not raw_line[0].isspace():
            # Start of a new rule definition
            if current is not None:
                rules.append(current)
            current = line_no_comment.rstrip()
        else:
            # Continuation of the current rule
            if current is not None:
                current = current + " " + line_no_comment.strip()

    if current is not None:
        rules.append(current)

    return [r for r in rules if r.strip()]


def _build_rule_class(abnf_rules: list[str]) -> type[Rule]:
    """Return a Rule subclass with all PURL grammar rules loaded."""

    @load_grammar_rules()
    class PurlRule(Rule):
        grammar = abnf_rules

    return PurlRule


# ---------------------------------------------------------------------------
# Module-level grammar initialisation (loaded once per session)
# ---------------------------------------------------------------------------

_ABNF_RULES: list[str] = _normalize_abnf(_extract_abnf(GRAMMAR_MD))
_PURL_RULE_CLASS: type[Rule] = _build_rule_class(_ABNF_RULES)


def validate(value: str, rule_name: str) -> bool:
    """
    Return ``True`` if *value* fully matches the ABNF rule *rule_name*,
    ``False`` otherwise.
    """
    from abnf import ParseError

    try:
        _PURL_RULE_CLASS(rule_name).parse_all(value)
        return True
    except ParseError:
        return False


# ---------------------------------------------------------------------------
# Test-case collection
# ---------------------------------------------------------------------------


def _safe_id(value: str) -> str:
    """Return a pytest-safe version of *value* (replace special chars)."""
    return re.sub(r"[^A-Za-z0-9._-]", "_", value)


def collect_input_cases() -> list[tuple[str, str, bool]]:
    """
    Yield ``(test_id, input_value, should_fail)`` triples for every
    ``$.tests[].input`` string found under *TESTS_DIR*.
    """
    seen_ids: dict[str, int] = {}
    cases: list[tuple[str, str, bool]] = []

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

            should_fail: bool = test.get("expected_failure") is True
            input_val = test.get("input")
            if not isinstance(input_val, str):
                continue

            base_id = f"grammar.{folder}.{file_base}.input.{_safe_id(input_val)}"
            count = seen_ids.get(base_id, 0)
            seen_ids[base_id] = count + 1
            test_id = base_id if count == 0 else f"{base_id}.{count}"

            cases.append((test_id, input_val, should_fail))

    return cases


def collect_output_cases() -> list[tuple[str, str]]:
    """
    Yield ``(test_id, expected_output_value)`` pairs for every
    ``$.tests[].expected_output`` string found under *TESTS_DIR* where
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

            # Skip expected_output validation when failure is expected
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
