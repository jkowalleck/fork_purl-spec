# PURL ABNF Grammar Tests

This directory contains automated tests that validate PURL strings from the
JSON test suites against the [ABNF grammar](../../docs/standard/grammar.md).

## Overview

The tests:

1. **Load** the ABNF grammar from `docs/standard/grammar.md` (the fenced
   ` ```abnf ` code block) – this is the single source of truth.
2. **Collect** test cases from all JSON files under `tests/`
   (including `tests/spec/` and `tests/types/`).
3. **Validate** string values:
   - `input` strings from `test_type == "parse"` entries with
     `expected_failure === true` → validated against ABNF rule `purl`
     — expected to **fail**.  When the grammar accepts the string (i.e. the
     failure is a type-specific constraint, not a grammar violation), the test
     is marked `xfail`.
   - `expected_output` strings where `expected_failure` is not `true` →
     validated against ABNF rule `purl-canonical` — always expected to **pass**.

## Requirements

- Python ≥ 3.10
- [`abnf`](https://pypi.org/project/abnf/) ≥ 2.4.1 – RFC 5234 ABNF parser
- [`pytest`](https://pypi.org/project/pytest/) ≥ 8.0.0

## Setup

From the repository root:

```sh
pip install -r tests/grammar/requirements.txt
```

Or create a virtual environment first:

```sh
python3 -m venv .venv
source .venv/bin/activate
pip install -r tests/grammar/requirements.txt
```

## Running the tests

From the repository root:

```sh
python3 -m pytest tests/grammar/ -v
```

Run only input-validation tests:

```sh
python3 -m pytest tests/grammar/ -k "test_input" -v
```

Run only expected-output tests:

```sh
python3 -m pytest tests/grammar/ -k "test_expected_output" -v
```

## Test naming

Each test is named using the following scheme:

```
grammar.<folder>.<file-base>.input.<value>
grammar.<folder>.<file-base>.expected_output.<value>
```

Where:

- `<folder>` is the immediate sub-folder under `tests/` (e.g. `spec`, `types`)
- `<file-base>` is the JSON file name without `.json`
- `<value>` is a sanitised form of the PURL string being tested

When the same value appears multiple times within a file, a numeric suffix is
appended to keep test IDs unique (e.g. `.1`, `.2`, …).

## Interpreting results

- **`PASSED`** – the grammar behaves as expected.
- **`XFAIL`** – an `input` test where the grammar accepted a string that the
  test suite marks as invalid.  This is a type-specific constraint (e.g. a
  required namespace for a particular PURL type) that the general grammar does
  not encode; it is not a grammar defect.
- **`FAILED`** – an `expected_output` string was rejected by the grammar.  This
  indicates a gap in the grammar or a test-data issue.
