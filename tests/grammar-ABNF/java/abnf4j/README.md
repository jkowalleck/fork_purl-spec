# Grammar ABNF Validation Tests (Java / SoftLibABNF)

Validates the PURL [ABNF grammar][grammar] against the [JSON test suites][tests]
using [net.siisise:softlib-abnf][softlib-abnf] (a Java RFC 5234 ABNF parser).

## What it does

For every JSON file under [`tests/`][tests]:

| Check | Start rule | When run |
|-------|-----------|----------|
| `$.tests[].input` (string) | `purl` | Always |
| `$.tests[].expected_output` (string) | `purl-canonical` | Only when `expected_failure` ≠ `true` |

* **`expected_failure: true`** → the `input` value must *not* match the rule.
* **`expected_failure: false / absent`** → the `input` value must match the rule.
* Non-string `input` / `expected_output` values are skipped silently.

Test names follow the pattern:

```
grammar.<folder>.<file-base-name>.input.<value>
grammar.<folder>.<file-base-name>.expected_output.<value>
```

If the same value appears more than once in a file, a numeric suffix (`.2`,
`.3`, …) is appended to keep names unique.

## Prerequisites

* Java 11+
* Maven 3.6+

## Running locally

```sh
# From the repository root:
cd tests/grammar-ABNF/java/abnf4j
mvn test
```

## Interpreting failures

A test failure means the ABNF grammar in [`docs/standard/grammar.md`][grammar]
and the JSON test-suite disagree on whether the value is a valid PURL.  
Failures should be investigated and resolved either in the grammar or in the
test data.

[grammar]: ../../../../docs/standard/grammar.md
[tests]: ../../../
[softlib-abnf]: https://github.com/okomeki/SoftLibABNF
