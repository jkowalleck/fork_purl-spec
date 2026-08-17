# PURL ABNF Grammar Tests — C++ / abnf-cpp

**Ecosystem / Library: C++ (self-contained ABNF parser)**

Validates canonical PURL strings from the JSON test suites in `tests/` against
the ABNF grammar defined in `docs/standard/grammar.md`, using a self-contained
recursive-descent ABNF parser with set-based position tracking written in C++17.

---

## How it works

1. Extracts the fenced ` ```abnf ` block from `docs/standard/grammar.md`.
2. Parses that text into an in-memory rule map at runtime (no hard-coded ABNF).
3. Walks every `*.json` file under `tests/` (including subdirectories).
4. For each test entry in `$.tests[]` where `expected_failure` is not `true`
   and `expected_output` is a string:
   - Validates the canonical PURL string against ABNF rule **`purl-canonical`**.
   - Expects the validation to **pass**.
5. Prints `PASS` / `FAIL` lines and exits with code `1` if any test failed.

Input values are intentionally skipped: they may be non-canonical (e.g. contain
unencoded slashes in qualifier values as accepted by lenient parsers) or subject
to type-specific constraints not expressed in the ABNF grammar.

Test name format:
```
grammar.<folder>.<file-base>.expected_output.<value>
```

---

## Requirements

| Tool    | Minimum version |
|---------|----------------|
| CMake   | 3.15           |
| C++ compiler | C++17 (GCC ≥ 9, Clang ≥ 9, MSVC 19.14) |
| [nlohmann/json](https://github.com/nlohmann/json) | 3.x |

`nlohmann/json` is resolved automatically by CMake (system package →
bare-header search → FetchContent download).

On Debian / Ubuntu:
```bash
sudo apt-get install cmake nlohmann-json3-dev
```

---

## Build

From the **repository root**:

```bash
cmake -B build tests/grammar-ABNF/cpp/abnf-cpp/ -DCMAKE_BUILD_TYPE=Release
cmake --build build
```

---

## Run

```bash
# From the repository root (paths auto-discovered):
./build/purl-grammar-tests

# Explicit paths:
./build/purl-grammar-tests docs/standard/grammar.md tests/
```

Sample output:

```
Grammar : docs/standard/grammar.md
Tests   : tests/

Loaded 47 ABNF rules.

PASS  grammar.types.maven-test.expected_output.pkg:maven/org.apache.commons/io@1.3.4
PASS  grammar.types.generic-test.expected_output.pkg:generic/openssl@1.1.10g?...
...

=== 326 tests: 326 passed, 0 failed ===
```

Exit code `0` = all tests passed; `1` = at least one failure; `2` = setup error.

---

## CI

The GitHub Actions workflow
[ABNF TEST C++/abnf-cpp](.github/workflows/test-grammar-abnf-cpp.yml) runs
automatically on every push and pull request that touches:

- `docs/standard/grammar.md`
- `tests/**/*.json`
- `tests/grammar-ABNF/cpp/abnf-cpp/**`
- `.github/workflows/test-grammar-abnf-cpp.yml`
