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
   - `input` strings → validated against ABNF rule `purl` (**informational only**)
     - Discrepancies between the grammar and the input are expected and never
       fail the test run — see [Informational discrepancies](#informational-discrepancies)
       below.
   - `expected_output` strings → validated against ABNF rule `purl-canonical`
     (**strict**)
     - Only when `expected_failure` is not `true`
     - Always expected to **pass** — failures here indicate a real grammar issue

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

View informational discrepancies between the grammar and input strings:

```sh
cargo test -- --nocapture 2>&1 | grep grammar-informational
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

## Informational discrepancies

`input` tests always pass; they print `grammar-informational:` messages to
stderr when the grammar disagrees with the test-suite expectation.  To view
these messages:

```sh
cargo test -- --nocapture 2>&1 | grep grammar-informational
```

Two kinds of discrepancy are expected:

- **Grammar accepted a type-specifically-invalid PURL** — the JSON test suite
  marks the input as invalid (`expected_failure === true`) because it violates
  a **type-specific rule** (e.g. chrome-extension name-format constraints,
  cpan double-colon notation).  The general PURL grammar does not encode
  type-specific constraints, so it correctly accepts these structurally valid
  PURLs.

- **Grammar rejected a loosely-encoded valid PURL** — the JSON test suite marks
  the input as valid (`expected_failure` is not `true`) but the grammar rejects
  it.  This happens because `input` strings may use **loose encoding** (e.g.
  literal `/` or `@` in positions where the grammar requires percent-encoding
  such as `%2F` or `%40`).  The canonical `expected_output` always uses proper
  encoding and always passes the grammar.

Both discrepancies are intentional and useful — they reveal gaps between the
general grammar and real-world PURL usage, and can guide future grammar
improvements.

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

- **`input` trials** run grammar validation and always report success.  When
  the grammar disagrees with the test-suite expectation a `grammar-informational:`
  message is printed to stderr (visible with `--nocapture`).

- **`expected_output` trials** run grammar validation and **fail** if the
  canonical PURL string is rejected by `purl-canonical`.  All canonical forms
  in the test suites are expected to be grammar-valid.
