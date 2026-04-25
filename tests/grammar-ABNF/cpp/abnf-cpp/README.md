# PURL ABNF Grammar Tests — C++ / abnf-cpp

**Ecosystem / Library: C++ (self-contained ABNF parser)**

Validates PURL strings from the JSON test suites in `tests/` against the ABNF
grammar defined in `docs/standard/grammar.md`, using a self-contained
recursive-descent ABNF parser with set-based position tracking written in C++17.

---

## How it works

1. Extracts the fenced ` ```abnf ` block from `docs/standard/grammar.md`.
2. Parses that text into an in-memory rule map at runtime (no hard-coded ABNF).
3. Walks every `*.json` file under `tests/` (including subdirectories).
4. For each test entry in `$.tests[]`:
   - If `input` is a string → validate against ABNF rule **`purl`**.
     - `expected_failure == true` → expect the validation to **fail**.
     - Otherwise → expect the validation to **pass**.
   - If `expected_failure` is not `true` and `expected_output` is a string →
     validate against ABNF rule **`purl-canonical`**; expect **pass**.
5. Prints `PASS` / `FAIL` lines and exits with code `1` if any test failed.

Test name format:
```
grammar.<folder>.<file-base>.input.<value>
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

PASS  grammar.spec.specification-test.input.EnterpriseLibrary.Common@6.0.1304
PASS  grammar.types.maven-test.input.pkg:maven/org.apache.commons/io@1.3.4
FAIL  grammar.types.docker-test.input.pkg:docker/...
      ABNF rule `purl`: expected PASS but got FAIL for: ...

=== 688 tests: 615 passed, 73 failed ===
```

Exit code `0` = all tests passed; `1` = at least one failure; `2` = setup error.

---

## Known failures

Some test entries currently fail because the strict ABNF grammar on this branch
(`spec/grammar-ABNF`) is stricter than the existing test data:

- **Unencoded `/` in qualifier values** — e.g. `repository_url=https://…`
  (the grammar requires `%2F` for `/` inside qualifier values).
- **Type-specific constraints** — e.g. chrome-extension ID format, CPAN `::`,
  Swift namespace requirements — which the grammar does not (yet) enforce.

These failures are expected and serve as a tracking signal for alignment work
between the grammar spec and the test suites.

---

## CI

The GitHub Actions workflow
[ABNF TEST C++/abnf-cpp](.github/workflows/test-grammar-abnf-cpp.yml) runs
automatically on every push and pull request that touches:

- `docs/standard/grammar.md`
- `tests/**/*.json`
- `tests/grammar-ABNF/cpp/abnf-cpp/**`
- `.github/workflows/test-grammar-abnf-cpp.yml`
