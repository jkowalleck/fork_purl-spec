# SPDX-License-Identifier: MIT
# Copyright (c) the purl authors
"""
ABNF grammar validation tests for PURL strings.

For every ``$.tests[]`` entry in the JSON test suites under ``tests/``:

- If ``input`` is a string: validate it against the ABNF ``purl`` rule.
  Expect the validation to **fail** iff ``expected_failure is true``;
  otherwise expect it to **pass**.
- If ``expected_output`` is a string **and** ``expected_failure`` is not
  ``true``: validate it against the ABNF ``purl-canonical`` rule and
  expect it to **pass**.

The ABNF grammar is extracted dynamically from
``docs/standard/grammar.md`` (the fenced ``abnf`` code block).
"""

from __future__ import annotations

import pytest

from conftest import INPUT_CASES, OUTPUT_CASES, validate


# ---------------------------------------------------------------------------
# Input tests  →  validated against ABNF rule ``purl``
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "value,should_fail",
    [(v, f) for _id, v, f in INPUT_CASES],
    ids=[_id for _id, _v, _f in INPUT_CASES],
)
def test_input(value: str, should_fail: bool) -> None:
    """Validate ``input`` strings against the ``purl`` ABNF rule."""
    is_valid = validate(value, "purl")
    if should_fail:
        assert not is_valid, (
            f"Expected ABNF validation of {value!r} to FAIL against rule "
            f"'purl', but it passed."
        )
    else:
        assert is_valid, (
            f"Expected ABNF validation of {value!r} to PASS against rule "
            f"'purl', but it failed."
        )


# ---------------------------------------------------------------------------
# Expected-output tests  →  validated against ABNF rule ``purl-canonical``
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
