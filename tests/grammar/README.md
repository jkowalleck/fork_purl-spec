# PURL ABNF Grammar Tests

This directory contains automated tests that validate PURL strings from the
JSON test suites against the [ABNF grammar](../../docs/standard/grammar.md).

## Overview

The tests:

1. **Extract** the ABNF grammar from `docs/standard/grammar.md` (the fenced
   ` ```abnf ` code block) – this is the single source of truth.
2. **Collect** every `$.tests[]` entry from all JSON files under `tests/`
   (including `tests/spec/` and `tests/types/`).
3. **Validate** string values:
   - `input` strings → validated against ABNF rule `purl`
     - Expected to **fail** when `expected_failure === true`
     - Expected to **pass** otherwise
   - `expected_output` strings → validated against ABNF rule `purl-canonical`
     - Only when `expected_failure` is not `true`
     - Always expected to **pass**

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

## Interpreting failures

A test failure means the PURL string and the ABNF grammar disagree:

- **"Expected to PASS but FAILED"** – the JSON test suite marks the string as
  valid (`expected_failure` is not `true`) but the grammar rejects it.  This
  may indicate a gap in the grammar or a test-data issue.

- **"Expected to FAIL but PASSED"** – the JSON test suite marks the string as
  invalid (`expected_failure === true`) but the grammar accepts it.  This may
  indicate the grammar is too permissive, or the failure is due to a
  type-specific rule that the general grammar does not encode.
