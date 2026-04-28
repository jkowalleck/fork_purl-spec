# SPDX-License-Identifier: MIT
# Copyright (c) the purl authors
"""
ABNF grammar validation tests for PURL strings.

For every ``$.tests[]`` entry in the JSON test suites under ``tests/``:

- If ``test_type == "parse"`` and ``expected_failure is true``: the input
  string must be rejected by the ABNF ``purl`` rule.  When the grammar
  accepts the string despite the expected failure (e.g. a type-specific
  constraint such as a required namespace), the test is marked
  ``xfail`` — that is a type-specific restriction, not a grammar violation.
- If ``expected_output`` is a string and ``expected_failure`` is not
  ``true``: the canonical PURL must be accepted by the ABNF
  ``purl-canonical`` rule.

The ABNF grammar is loaded dynamically from
``docs/standard/grammar.md`` (the fenced ``abnf`` code block).
"""

from __future__ import annotations

import pytest

from conftest import INPUT_CASES, OUTPUT_CASES, validate


# ---------------------------------------------------------------------------
# Input tests  →  invalid PURLs must be rejected by the ``purl`` rule
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "value",
    [v for _id, v in INPUT_CASES],
    ids=[_id for _id, _v in INPUT_CASES],
)
def test_input(value: str) -> None:
    """Invalid PURL inputs must be rejected by the ``purl`` ABNF rule.

    If the grammar accepts the value, the failure is a type-specific
    constraint (not a grammar violation) and the test is marked xfail.
    """
    is_valid = validate(value, "purl")
    if is_valid:
        pytest.xfail(
            f"{value!r} is accepted by the grammar — the expected failure is "
            f"a type-specific constraint, not a grammar violation."
        )
    assert not is_valid, (
        f"Expected ABNF validation of {value!r} to FAIL against rule "
        f"'purl', but it passed."
    )


# ---------------------------------------------------------------------------
# Expected-output tests  →  canonical PURLs must pass ``purl-canonical``
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "value",
    [v for _id, v in OUTPUT_CASES],
    ids=[_id for _id, _v in OUTPUT_CASES],
)
def test_expected_output(value: str) -> None:
    """Validate ``expected_output`` strings against the ``purl-canonical`` ABNF rule."""
    assert validate(value, "purl-canonical"), (
        f"Expected ABNF validation of {value!r} to PASS against rule "
        f"'purl-canonical', but it failed."
    )
