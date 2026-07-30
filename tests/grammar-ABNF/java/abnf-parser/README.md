# ABNF Grammar Validation Tests – Java (self-contained ABNF compiler)

This directory contains Java tests that validate string values from the
JSON test suites under `tests/` against the
[ABNF grammar](../../../docs/standard/grammar.md) defined for the PURL
specification.

## How it works

1. The ABNF grammar is **extracted at runtime** from the fenced ````abnf`
   code block in `docs/standard/grammar.md`.  No grammar text is
   hard-coded here.

2. The extracted grammar is compiled to Java `Pattern` objects by a
   self-contained ABNF-to-regex compiler (`AbnfGrammar`).  The compiler
   handles the subset of RFC 5234 ABNF used by the PURL grammar (terminal
   strings, hex values, alternation, concatenation, repetition, optional
   and group constructs, rule references, and comments).

3. Every JSON file under `tests/` is treated as a test suite.  For each
   entry in `$.tests[]` the following rules apply:

   | Field | Type | Validated against | Expected result |
   |---|---|---|---|
   | `input` | string | ABNF rule `purl` | **fail** when `expected_failure == true`, **pass** otherwise |
   | `expected_output` | string | ABNF rule `purl-canonical` | always expected to **pass** (only checked when `expected_failure` is not `true`) |

   Non-string values (e.g. component objects or `null`) are skipped.

4. Test IDs follow the scheme
   `grammar.<subfolder>.<stem>.input.<value>` and
   `grammar.<subfolder>.<stem>.expected_output.<value>`, where
   `<subfolder>` is the immediate folder under `tests/` and `<stem>` is
   the JSON filename without `.json`.
   Duplicate IDs receive a numeric suffix (`.1`, `.2`, …).

## Interpreting failures

A failing test means that the grammar verdict for a string **does not
match** the `expected_failure` flag in the JSON suite:

* **`expected: <true> but was: <false>`** – the grammar rejected a string
  that `expected_failure` marks as valid.  This typically means the
  test-data string contains characters that are not permitted by the PURL
  grammar (e.g. an unencoded `/` inside a qualifier value or an unencoded
  `@` inside a namespace segment).

* **`expected: <false> but was: <true>`** – the grammar accepted a string
  that `expected_failure` marks as invalid.  This usually indicates a
  type-specific constraint (e.g. a required namespace, or a maximum field
  length) that the generic ABNF grammar does not capture.

These findings can be used to:

* identify test-data entries that contain unencoded characters that should
  be percent-encoded, **or**
* identify areas where the grammar may need refinement.

## Prerequisites

* Java 11 or newer
* Apache Maven 3.6 or newer

## Running the tests

From this directory:

```bash
mvn test
```

Or from the repository root:

```bash
mvn test -f tests/grammar-ABNF/java/abnf-parser/pom.xml
```

Add `-Dsurefire.useFile=false` for inline output (no separate report
files):

```bash
mvn test -Dsurefire.useFile=false
```

## Continuous integration

The tests run automatically on every push or pull request that modifies:

* `docs/standard/grammar.md`
* `tests/**/*.json`
* `tests/grammar-ABNF/java/abnf-parser/**`

See [`.github/workflows/test-grammar-abnf-java.yml`](../../../.github/workflows/test-grammar-abnf-java.yml).
