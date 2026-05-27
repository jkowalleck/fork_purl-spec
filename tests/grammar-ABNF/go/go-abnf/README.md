# PURL ABNF Grammar Validation Tests — Go / go-abnf

Validates PURL strings from the JSON test suites against the ABNF grammar
using the Go ecosystem and the
[`github.com/pandatix/go-abnf`](https://github.com/pandatix/go-abnf) library
for direct ABNF parsing.

## What the tests do

1. **Extract the ABNF grammar** from `docs/standard/grammar.md` (the fenced
   `` ```abnf `` block) at test-run time — no hardcoded grammar.
2. **Discover every JSON test suite** under `tests/` (including
   `tests/spec/` and `tests/types/`).
3. For every test case in each suite:
   - If `input` is a string **and** `expected_failure: true` → validate against
     ABNF rule **`purl`**; the grammar must **reject** the input (all suites).
     When the grammar **accepts** the input the test is **skipped** — the
     failure is a type-specific constraint (e.g. a required namespace or a
     particular name format) that the general PURL grammar does not enforce.
   - If `expected_output` is a string **and** `expected_failure` is not
     `true` → validate against ABNF rule **`purl-canonical`**, which must
     **pass** (all suites).
   - Non-string (object / null) values are skipped.

## Prerequisites

- [Go](https://go.dev/dl/) 1.21 or later

## Running the tests locally

```bash
# from the repository root
cd tests/grammar-ABNF/go/go-abnf
go test ./...
```

For verbose output (shows every sub-test name and pass/fail):

```bash
go test -v ./...
```

With a longer timeout (useful on slow machines):

```bash
go test -v -timeout 300s ./...
```

## Interpreting results

- **`PASS`** — the grammar correctly rejected an invalid input or accepted a
  canonical output.
- **`SKIP`** — an `input` test where the grammar **accepted** a string that the
  suite marks as `expected_failure: true`.  This is a type-specific constraint
  (e.g. a required namespace for a particular PURL type) that the general
  grammar does not enforce; it is not a grammar defect.
- **`FAIL`** — an `expected_output` string was rejected by the `purl-canonical`
  rule.  This indicates a gap in the grammar or a test-data issue.

## Module layout

```
tests/grammar-ABNF/go/go-abnf/
├── go.mod            # module declaration + dependencies
├── go.sum            # dependency checksums
├── grammar_test.go   # all test logic
└── README.md         # this file
```
