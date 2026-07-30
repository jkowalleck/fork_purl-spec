# ABNF Grammar Validation Tests — C# .NET / Abnf.Net

This directory contains a C# .NET test project that validates PURL strings
from the JSON test suites in `tests/` against the ABNF grammar defined in
`docs/standard/grammar.md`.

**Ecosystem / Library:** C# .NET · NuGet package [`Abnf.Net`](https://www.nuget.org/packages/Abnf.Net)
(`0.1.0-preview.1`)

## Overview

| Item | Detail |
|---|---|
| Language | C# 13 / .NET 10 |
| Test framework | [NUnit](https://nunit.org/) 4 |
| ABNF library | [Abnf.Net](https://www.nuget.org/packages/Abnf.Net) 0.1.0-preview.1 |
| ABNF source | `docs/standard/grammar.md` (extracted at test runtime) |
| Test data source | `tests/**/*.json` (all suites, recursive) |

## What is tested

For every entry in every `$.tests[]` array across all JSON suites:

1. **Input validation** — if `input` is a string it is validated against the
   ABNF rule `purl`.  
   The validation is expected to **fail** when `expected_failure === true`,
   otherwise to **pass**.

2. **Expected-output validation** — if `expected_failure` is not `true` and
   `expected_output` is a string, it is validated against the ABNF rule
   `purl-canonical`, and the validation is expected to **pass**.

Non-string (e.g. `null` or object) values are ignored.

### Test naming

```
grammar.<folder>.<file-base-name>.input.<index>
grammar.<folder>.<file-base-name>.expected_output.<index>
```

Where `<folder>` is the immediate sub-folder under `tests/`
(e.g. `spec`, `types`) and `<index>` is the 0-based position of the entry
inside `$.tests[]`.

## Prerequisites

- [.NET 10 SDK](https://dotnet.microsoft.com/download) (or later)

## Running the tests locally

```bash
# From the repo root
cd tests/grammar-ABNF/csharp/abnf.net/PurlGrammarTests
dotnet test
```

Verbose output:

```bash
dotnet test --logger "console;verbosity=detailed"
```

The test project automatically locates the repository root by walking up the
directory tree from the test assembly until it finds `docs/standard/grammar.md`.
You can also set the `PURL_SPEC_REPO_ROOT` environment variable to point
directly at the repository root if needed.

## Known limitations

Abnf.Net (`0.1.0-preview.1`) uses a PEG-style (non-backtracking) parser.
The `purl` and `purl-canonical` rules in the ABNF grammar contain optional
namespace constructs that require backtracking to parse correctly
(`[ 1*"/" namespace ] 1*"/" name`).  This project works around the
limitation by injecting two lightweight helper rules at load time that use
the `1*(seg SEP)` repetition idiom, which is naturally non-backtracking.
See `GrammarLoader.cs` for details.

Some test-suite entries (qualifier values containing literal `/`, versions
containing `%3A`, etc.) are rejected by the strict ABNF grammar even though
the test data marks them as `expected_failure: false`.  These cases are
reported as test failures and highlight discrepancies between the current
grammar and the existing test data.

## CI

The workflow file is at
`.github/workflows/tests-grammar-abnf-csharp-abnf-net.yml`.
It runs on every push / pull-request that touches the grammar, the test
suites, or this project's source files.
