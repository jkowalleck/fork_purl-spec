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
   - If `expected_output` is a string **and** `expected_failure` is not
     `true` → validate against ABNF rule **`purl-canonical`**, which must
     **pass** (all suites).
   - If `input` is a string **and** `expected_failure: true` **and** the suite
     is `tests/spec/` → validate against ABNF rule **`purl`**; the grammar must
     **reject** the input.
     Type-specific test suites enforce type-scoped rules that are outside the
     scope of the general ABNF grammar, so their `expected_failure` inputs are
     intentionally skipped.
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

## Interpreting failures

A **FAIL** line means there is a discrepancy between what the ABNF grammar
accepts/rejects and what the JSON test suite expects.  Two typical patterns:

| Failure message | Meaning |
|---|---|
| `expected ABNF rule 'purl' to accept "…", but it was rejected` | Grammar is stricter than the test data: the input looks valid to the test suite but the grammar rejects it (e.g. an unencoded character that should be percent-encoded). |
| `expected ABNF rule 'purl' to reject "…", but it was accepted` | Grammar is more permissive than the test data: the input is expected to be invalid (e.g. a type-specific constraint that the grammar cannot enforce). |

Each failure reports:
- the **suite file** (encoded in the sub-test path, e.g. `grammar.types.swift-test`)
- whether the failing value is `input` or `expected_output`
- the **exact string** that was (or was not) accepted

## Module layout

```
tests/grammar-ABNF/go/go-abnf/
├── go.mod            # module declaration + dependencies
├── go.sum            # dependency checksums
├── grammar_test.go   # all test logic
└── README.md         # this file
```
