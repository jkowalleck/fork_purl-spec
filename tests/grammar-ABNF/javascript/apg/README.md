# PURL ABNF Grammar Tests — JavaScript/Node.js (apg-js)

**Ecosystem / Library:** JavaScript/Node.js — [apg-js](https://www.npmjs.com/package/apg-js)

This directory contains automated tests that validate PURL strings from the
JSON test suites against the [ABNF grammar](../../../../docs/standard/grammar.md).

## Overview

The tests:

1. **Extract** the ABNF grammar from `docs/standard/grammar.md` (the fenced
   ` ```abnf ` code block) — this is the single source of truth.
2. **Patch** the grammar with SABNF look-ahead annotations so it works with
   APG's non-backtracking parser (see [APG Compatibility](#apg-compatibility)).
3. **Collect** every `$.tests[]` entry from all JSON files under `tests/`
   (including `tests/spec/` and `tests/types/`).
4. **Validate** string values:
   - `input` strings → validated against ABNF rule `purl`
     - Expected to **fail** when `expected_failure === true`
     - Expected to **pass** otherwise
   - `expected_output` strings → validated against ABNF rule `purl-canonical`
     - Only when `expected_failure` is not `true`
     - Always expected to **pass**

## Requirements

- Node.js ≥ 18
- [`apg-js`](https://www.npmjs.com/package/apg-js) ≥ 4.4.0 — JavaScript
  APG, an ABNF Parser Generator implementing SABNF (RFC 5234 superset)

## Setup

```sh
cd tests/grammar-ABNF/javascript/apg
npm install
```

## Running the tests

```sh
npm test
```

Or directly from the repository root:

```sh
node --test tests/grammar-ABNF/javascript/apg/test-purl-grammar.js
```

## Test naming

Each test is identified using the following scheme:

```
grammar.<folder>.<file-base>.input.<sanitised-value>
grammar.<folder>.<file-base>.expected_output.<sanitised-value>
```

Where:

- `<folder>` is the immediate sub-folder under `tests/` (e.g. `spec`, `types`)
- `<file-base>` is the JSON file name without `.json`
- `<sanitised-value>` is the PURL string with every character outside
  `[A-Za-z0-9._-]` replaced by `_`

When the same sanitised value appears more than once within a file a numeric
suffix is appended (`.1`, `.2`, …) to keep IDs unique.

## APG Compatibility

[apg-js](https://github.com/ldthomas/apg-js) is a **non-backtracking**
recursive-descent parser.  The PURL grammar contains an optional namespace
component placed *before* the mandatory name:

```abnf
purl = … [ 1*"/" namespace ] 1*"/" name …
```

A backtracking ABNF parser would attempt the empty namespace alternative when
the greedy match leaves no `1*"/"` for `name`.  APG's greedy parser does not
backtrack, so without modification it fails to parse most valid PURL strings.

### Applied fix

`test-purl-grammar.js` programmatically adds minimal **SABNF positive
look-ahead** annotations (`&(…)`) to the extracted grammar text before
compiling it with APG.  No ABNF is hard-coded; the source of truth is always
`docs/standard/grammar.md`.  The four targeted substitutions are:

| Rule | Change |
|------|--------|
| `namespace` | Each additional `1*"/" namespace-segment` is consumed only when a subsequent `"/"` exists (keeps the final segment for `name`). |
| `namespace-canonical` | Same treatment for canonical form. |
| `purl` | Optional `[ 1*"/" namespace ]` succeeds only when `1*"/"` immediately follows (confirming a name separator is still available). |
| `purl-canonical` | Same treatment for the canonical rule. |

## Interpreting failures

A test failure means the PURL string and the ABNF grammar disagree:

- **"Expected to PASS but FAILED"** — the JSON test suite marks the string as
  valid but the grammar rejects it.  This may indicate a gap or inconsistency
  in the grammar or test data (for example, a qualifier value containing an
  unencoded `/` that the grammar requires to be percent-encoded).

- **"Expected to FAIL but PASSED"** — the JSON test suite marks the string as
  invalid but the grammar accepts it.  This usually means the failure is
  enforced by a *type-specific* rule that the general ABNF grammar does not
  encode (e.g. chrome-extension ID format constraints).
