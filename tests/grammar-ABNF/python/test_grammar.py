# SPDX-License-Identifier: MIT
# Copyright (c) the purl authors
# Visit https://github.com/package-url/purl-spec and https://packageurl.org for support

"""ABNF grammar validation tests for the PURL specification.

Extracts the ABNF grammar from ``docs/standard/grammar.md`` and validates
string values from every JSON test suite under ``tests/`` against the
``purl`` and ``purl-canonical`` ABNF rules.

Expected-failure semantics mirror the ``expected_failure`` field in the
JSON suites:

* ``input`` strings are validated against rule ``purl``.
  Validation is expected to **fail** when ``expected_failure == true``,
  and to **pass** otherwise.

* ``expected_output`` strings (only when ``expected_failure`` is not
  ``true``) are validated against rule ``purl-canonical`` and expected
  to **pass**.

Non-string values for ``input`` / ``expected_output`` are silently
skipped (they represent parsed component objects, not PURL strings).
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Generator

import pytest
from abnf.parser import Node
from abnf.parser import ParseError
from abnf.parser import Rule as _Rule
import abnf.parser as _ap

# ---------------------------------------------------------------------------
# Monkey-patch: fix abnf library handling of comment-only rule continuations
# ---------------------------------------------------------------------------
# The PURL grammar has one rule (PERM-ESCAPED-2D-2F) whose body starts with
# comment lines before the first actual element:
#
#   PERM-ESCAPED-2D-2F = ; except punctuation: "-"   (2D)
#                        ; except punctuation: "."   (2E)
#                        %x32 "F"   ; 2F
#
# The stock ABNFGrammarNodeVisitor.visit_defined_as returns node.value.strip()
# which, for that rule, includes the trailing comment text, so the result is
# not equal to "=" and the library raises AttributeError instead of loading
# the rule correctly.  The fix below extracts only the operator ("=" or "=/").
# ---------------------------------------------------------------------------


@staticmethod  # type: ignore[misc]  # mypy: assigning staticmethod to instance method via monkey-patch
def _visit_defined_as(node: Node) -> str:
    """Return the defined-as operator (``"="`` or ``"=/"``), ignoring trailing comments."""
    value = node.value.strip()
    if value.startswith("=/"):
        return "=/"
    if value.startswith("="):
        return "="
    return value  # pragma: no cover – should never happen for valid ABNF


_ap.ABNFGrammarNodeVisitor.visit_defined_as = _visit_defined_as

# ---------------------------------------------------------------------------
# Locate repository root, grammar source, and test suites
# ---------------------------------------------------------------------------

_REPO_ROOT: Path = Path(__file__).parents[3]
_GRAMMAR_MD: Path = _REPO_ROOT / "docs" / "standard" / "grammar.md"
_TESTS_DIR: Path = _REPO_ROOT / "tests"


# ---------------------------------------------------------------------------
# Extract ABNF grammar and build the parser
# ---------------------------------------------------------------------------


def _extract_abnf_grammar(path: Path) -> str:
    """Return the content of the first ``abnf`` fenced code block in *path*."""
    content = path.read_text(encoding="utf-8")
    match = re.search(r"```abnf\n(.*?)```", content, re.DOTALL)
    if not match:
        raise ValueError(f"No ABNF fenced code block found in {path}")
    return match.group(1)


class PurlRule(_Rule):
    """PURL ABNF grammar rules, loaded once at module import time."""


PurlRule.load_grammar(_extract_abnf_grammar(_GRAMMAR_MD))


# ---------------------------------------------------------------------------
# Collect parametrize arguments from JSON test suites
# ---------------------------------------------------------------------------


def _iter_json_suites() -> Generator[tuple[str, str, dict], None, None]:
    """Yield ``(subfolder, stem, data)`` for every JSON suite under *tests/*."""
    for json_file in sorted(_TESTS_DIR.glob("**/*.json")):
        # Skip files that live inside this directory (grammar-ABNF/).
        if "grammar-ABNF" in json_file.parts:
            continue
        subfolder = json_file.parent.name
        stem = json_file.stem
        with json_file.open(encoding="utf-8") as fp:
            data = json.load(fp)
        yield subfolder, stem, data


def _collect_parameters() -> (
    tuple[list[pytest.param], list[pytest.param]]
):
    """Return ``(input_params, output_params)`` for pytest parametrize.

    *input_params*
        Each element carries ``(value, should_fail)`` and an id of the form
        ``grammar.<subfolder>.<stem>.input.<value>``.

    *output_params*
        Each element carries ``(value,)`` and an id of the form
        ``grammar.<subfolder>.<stem>.expected_output.<value>``.
    """
    input_params: list[pytest.param] = []
    output_params: list[pytest.param] = []

    # Track how many times each base id has been used so that duplicates
    # get a numeric disambiguator (e.g. ``.1``, ``.2`` …).
    input_id_counts: dict[str, int] = {}
    output_id_counts: dict[str, int] = {}

    for subfolder, stem, data in _iter_json_suites():
        for test in data.get("tests", []):
            should_fail: bool = test.get("expected_failure") is True

            # --- input validation ---
            inp = test.get("input")
            if isinstance(inp, str):
                base_id = f"grammar.{subfolder}.{stem}.input.{inp}"
                count = input_id_counts.get(base_id, 0)
                test_id = base_id if count == 0 else f"{base_id}.{count}"
                input_id_counts[base_id] = count + 1
                input_params.append(pytest.param(inp, should_fail, id=test_id))

            # --- expected_output validation (only when not expected to fail) ---
            if not should_fail:
                out = test.get("expected_output")
                if isinstance(out, str):
                    base_id = f"grammar.{subfolder}.{stem}.expected_output.{out}"
                    count = output_id_counts.get(base_id, 0)
                    test_id = base_id if count == 0 else f"{base_id}.{count}"
                    output_id_counts[base_id] = count + 1
                    output_params.append(pytest.param(out, id=test_id))

    return input_params, output_params


_INPUT_PARAMS, _OUTPUT_PARAMS = _collect_parameters()


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("value,should_fail", _INPUT_PARAMS)
def test_purl_input(value: str, should_fail: bool) -> None:
    """Validate a PURL input string against the ``purl`` ABNF rule.

    The test passes when the grammar result matches *should_fail*:

    * ``should_fail=True``  → ``ParseError`` expected (grammar must reject)
    * ``should_fail=False`` → no exception expected (grammar must accept)
    """
    if should_fail:
        with pytest.raises(ParseError):
            PurlRule("purl").parse_all(value)
    else:
        PurlRule("purl").parse_all(value)


@pytest.mark.parametrize("value", _OUTPUT_PARAMS)
def test_purl_canonical_output(value: str) -> None:
    """Validate a canonical PURL output string against the ``purl-canonical`` ABNF rule.

    Only strings from entries whose ``expected_failure`` is not ``true`` are
    tested here; validation is always expected to **pass**.
    """
    PurlRule("purl-canonical").parse_all(value)
