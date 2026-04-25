# ABNF Grammar Validation Tests – Python / `abnf`

This directory contains Python tests that validate string values from the
JSON test suites under `tests/` against the
[ABNF grammar](../../../docs/standard/grammar.md) defined for the PURL
specification.

## How it works

1. The ABNF grammar is **extracted at runtime** from the fenced code block
   in `docs/standard/grammar.md`.  No grammar is hard-coded here.

2. Every JSON file under `tests/` is treated as a test suite.  For each
   entry in `$.tests[]` the following rules apply:

   | Field | Type | Validated against | Expected result |
   |---|---|---|---|
   | `input` | string | ABNF rule `purl` | **fail** when `expected_failure == true`, **pass** otherwise |
   | `expected_output` | string | ABNF rule `purl-canonical` | always expected to **pass** (only checked when `expected_failure` is not `true`) |

   Non-string values (e.g. component objects or `null`) are skipped.

3. Test ids follow the scheme
   `grammar.<subfolder>.<stem>.input.<value>` and
   `grammar.<subfolder>.<stem>.expected_output.<value>`, where
   `<subfolder>` is the immediate folder under `tests/` and `<stem>` is
   the JSON filename without `.json`.
   Duplicate ids receive a numeric suffix (`.1`, `.2`, …).

## Prerequisites

* Python 3.10 or newer
* Dependencies listed in `requirements.txt`

## Setup

```bash
python -m venv .venv
source .venv/bin/activate          # On Windows: .venv\Scripts\activate.bat
pip install -r tests/grammar-ABNF/python/requirements.txt
```

## Running the tests

From the repository root:

```bash
python -m pytest tests/grammar-ABNF/python/
```

Or, using the Makefile target (after running `make conf` once):

```bash
make test-grammar
```

Add `-v` for verbose output or `-k <expression>` to filter tests:

```bash
# Show every test name and result
python -m pytest tests/grammar-ABNF/python/ -v

# Only run tests for the npm type
python -m pytest tests/grammar-ABNF/python/ -k "npm-test"

# Only run input-validation tests
python -m pytest tests/grammar-ABNF/python/ -k "input"
```

## Interpreting failures

A failing test means that the grammar verdict for a string **does not
match** the `expected_failure` flag in the JSON suite:

* **`FAILED … DID NOT RAISE ParseError`** – the grammar accepted a string
  that `expected_failure == true` marks as invalid.  This usually indicates
  a type-specific constraint that the generic ABNF grammar does not
  capture.

* **`FAILED … ParseError`** – the grammar rejected a string that
  `expected_failure == false` (or absent) marks as valid.  This typically
  means the test-data string contains characters that are not permitted by
  the grammar (e.g. an unencoded `/` inside a qualifier value).

These findings can be used to:

* identify test-data entries that contain unencoded characters that should
  be percent-encoded, **or**
* identify areas where the grammar may need refinement.
