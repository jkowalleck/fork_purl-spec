# PURL ABNF Grammar Tests (Rust / static-regular-grammar)

This directory contains automated tests that validate PURL strings from the
JSON test suites against the [ABNF grammar](../../../docs/standard/grammar.md)
using the [`static-regular-grammar`](https://crates.io/crates/static-regular-grammar)
crate, which compiles the grammar into a deterministic finite automaton (DFA)
at compile time.

## Overview

The tests:

1. **Extract** the ABNF grammar at **compile time** from
   `docs/standard/grammar.md` (the fenced ` ```abnf ` code block) — this is
   the single source of truth.  The `build.rs` script reads the grammar file,
   extracts the ABNF block, and embeds it into the generated `Purl` and
   `PurlCanonical` validator types via doc comments.

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

- [Rust](https://www.rust-lang.org/) ≥ 1.65 (2021 edition)
- Cargo (comes with Rust)

## Setup

Install Rust via [rustup](https://rustup.rs/):

```sh
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

## Running the tests

From this directory:

```sh
cargo test
```

Or from the repository root:

```sh
cargo test --manifest-path tests/grammar/rust/static_regular_grammar/Cargo.toml
```

Run with verbose output to see individual test names:

```sh
cargo test -- --test-threads=1
```

Filter tests by name pattern:

```sh
cargo test -- input
cargo test -- expected_output
cargo test -- spec
cargo test -- types
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
  (non-alphanumeric characters replaced with `_`)

When the same value appears multiple times within a file, a numeric suffix is
appended to keep test names unique (e.g. `.1`, `.2`, …).

## Interpreting failures

A test failure means the PURL string and the ABNF grammar disagree:

- **"Expected to PASS but FAILED"** — the JSON test suite marks the string as
  valid (`expected_failure` is not `true`) but the grammar rejects it.
  This may indicate a gap in the grammar or a test-data issue (e.g. an input
  containing unencoded characters that the grammar requires to be
  percent-encoded).

- **"Expected to FAIL but PASSED"** — the JSON test suite marks the string as
  invalid (`expected_failure === true`) but the grammar accepts it.
  This typically means the failure is due to a **type-specific rule** that the
  general PURL ABNF grammar does not encode (e.g. name-format constraints for
  a particular package type).

Both types of failures are intentional and useful — they reveal discrepancies
between the general grammar and the test data, which can guide grammar or
test-suite improvements.

## How it works

### Compile-time grammar extraction (`build.rs`)

`build.rs` reads `docs/standard/grammar.md`, extracts the ` ```abnf ` fenced
code block, and writes a Rust source file (`$OUT_DIR/purl_types.rs`) that
wraps the grammar in doc comments — the format expected by the
`static-regular-grammar` proc-macro.  This means the grammar automaton is
compiled once and cached; it only needs recompilation when `grammar.md`
changes.

### Type-based validation (`src/lib.rs`)

The `include!` macro pulls in the generated source, which defines:

- `Purl([u8])` — validates against the `purl` start rule
- `PurlCanonical([u8])` — validates against the `purl-canonical` start rule

Both expose a `new(bytes: &[u8]) -> Result<&Self, _>` constructor that
accepts a byte slice and returns `Ok` if valid or `Err` if not.

### Dynamic test discovery (`tests/grammar.rs`)

The custom test harness (using
[`libtest-mimic`](https://crates.io/crates/libtest-mimic)) walks all JSON
files under `tests/`, parses them, and builds one `Trial` per test case.
Each trial validates the relevant string value and reports success or failure
with an informative message.
