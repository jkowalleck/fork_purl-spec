# ABNF Grammar Validation Tests — Java / apg-java

Validates purl strings from the JSON test suites against the ABNF grammar
defined in `docs/standard/grammar.md`, using
[apg-java](https://github.com/ldthomas/apg-java) (v1.1.0, 2-Clause BSD).

## How it works

1. The ABNF grammar is **extracted at test time** from the
   `````abnf` fenced block in `docs/standard/grammar.md`.
2. RFC 5234 Appendix B.1 core rules (`ALPHA`, `DIGIT`, `HEXDIG`) are
   appended, because the purl grammar references them but does not define
   them (they are standard ABNF core rules).
3. The `purl` and `purl-canonical` rules are rewritten to be
   **PEG-safe** — APG uses PEG ordered-choice semantics which does not
   backtrack after a successful optional match.  The rewrite replaces the
   ambiguous `[ 1*"/" namespace ] 1*"/" name` pattern with the equivalent
   `1*"/" *( namespace-segment 1*"/" ) name` which is unambiguous in PEG.
4. The adapted grammar is fed to the APG generator (bundled in
   `vendor/apg-java/`) which generates a `PurlGrammar.java` class.
5. That class is compiled at runtime using the system Java compiler.
6. JUnit 5 `@TestFactory` generates a dynamic test for every string value
   found at `$.tests[].input` and `$.tests[].expected_output` across all
   `tests/**/*.json` files.

## Start rules

| JSON field         | Start rule       |
|--------------------|------------------|
| `input`            | `purl`           |
| `expected_output`  | `purl-canonical` |

## Test naming

```
grammar.<folder>.<file-base>.input.<value>
grammar.<folder>.<file-base>.expected_output.<value>
```

Where `<folder>` is the immediate sub-directory under `tests/` and
`<file-base>` is the JSON filename without the `.json` extension.
Duplicate display names are made unique by appending `[N]`.

## Expected-failure semantics

When `expected_failure: true` the input is expected to **not** match the
grammar.  `expected_output` is ignored (skipped) for such tests.
Non-string values for `input` or `expected_output` are also skipped.

## Running the tests

Prerequisites: JDK 11+ and Maven 3.6+.

```bash
cd tests/grammar/java/apg-java
mvn test
```

## APG vendor source

The APG source files in `vendor/apg-java/src/` are copied verbatim from
the [ldthomas/apg-java](https://github.com/ldthomas/apg-java) repository
at tag `v1.1.0` and are subject to the
[2-Clause BSD License](https://opensource.org/licenses/BSD-2-Clause).
