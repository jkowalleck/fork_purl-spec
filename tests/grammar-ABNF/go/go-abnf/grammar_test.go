// SPDX-License-Identifier: MIT
// Copyright (c) the purl authors
// Visit https://github.com/package-url/purl-spec and https://packageurl.org for support

// Package grammar_test validates PURL strings from the JSON test suites
// against the ABNF grammar defined in docs/standard/grammar.md.
//
// It uses github.com/pandatix/go-abnf for direct ABNF parsing.
package grammar_test

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"testing"

	goabnf "github.com/pandatix/go-abnf"
)

// reABNFBlock matches a fenced code block with language "abnf".
var reABNFBlock = regexp.MustCompile("(?s)```abnf\r?\n(.*?)\r?\n```")

// extractABNF reads docs/standard/grammar.md (resolved relative to this
// source file) and returns the raw bytes of the first ```abnf …``` block.
func extractABNF(t *testing.T) []byte {
	t.Helper()

	_, thisFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	// go up: go-abnf/ -> go/ -> grammar-ABNF/ -> tests/ -> repo root
	repoRoot := filepath.Join(filepath.Dir(thisFile), "..", "..", "..", "..")
	grammarMD := filepath.Join(repoRoot, "docs", "standard", "grammar.md")

	data, err := os.ReadFile(grammarMD)
	if err != nil {
		t.Fatalf("read grammar.md: %v", err)
	}

	m := reABNFBlock.FindSubmatch(data)
	if m == nil {
		t.Fatalf("no ```abnf block found in %s", grammarMD)
	}

	// RFC 5234 requires CRLF line endings; normalise to CRLF and ensure
	// the rule-list ends with a CRLF as required by the grammar.
	content := strings.ReplaceAll(string(m[1]), "\r\n", "\n")
	content = strings.ReplaceAll(content, "\n", "\r\n")
	if !strings.HasSuffix(content, "\r\n") {
		content += "\r\n"
	}
	return []byte(content)
}

// parseGrammar parses the extracted ABNF text and returns a *goabnf.Grammar.
func parseGrammar(t *testing.T) *goabnf.Grammar {
	t.Helper()
	raw := extractABNF(t)
	g, err := goabnf.ParseABNF(raw)
	if err != nil {
		t.Fatalf("parse ABNF grammar: %v", err)
	}
	return g
}

// testSuite is the top-level structure of every *-test.json file.
type testSuite struct {
	Tests []testCase `json:"tests"`
}

// testCase represents one entry inside $.tests[].
// input and expected_output are kept as raw JSON so we can distinguish
// string, null, and object values without a separate pass.
type testCase struct {
	ExpectedFailure *bool           `json:"expected_failure"`
	Input           json.RawMessage `json:"input"`
	ExpectedOutput  json.RawMessage `json:"expected_output"`
}

// shouldFail returns true when expected_failure is explicitly true.
func (tc testCase) shouldFail() bool {
	return tc.ExpectedFailure != nil && *tc.ExpectedFailure
}

// stringValue returns (value, true) when raw JSON is a JSON string,
// otherwise ("", false).
func stringValue(raw json.RawMessage) (string, bool) {
	if len(raw) == 0 {
		return "", false
	}
	var s string
	if err := json.Unmarshal(raw, &s); err != nil {
		return "", false
	}
	return s, true
}

// findTestSuites returns all *-test.json paths under tests/ in the repo root,
// together with the immediate sub-folder name and base filename (no extension).
func findTestSuites(t *testing.T) []struct{ path, folder, base string } {
	t.Helper()

	_, thisFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	repoRoot := filepath.Join(filepath.Dir(thisFile), "..", "..", "..", "..")
	testsDir := filepath.Join(repoRoot, "tests")

	var suites []struct{ path, folder, base string }

	err := filepath.WalkDir(testsDir, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		if !strings.HasSuffix(d.Name(), ".json") {
			return nil
		}

		// Determine the immediate sub-folder relative to tests/.
		rel, _ := filepath.Rel(testsDir, filepath.Dir(path))
		folder := filepath.ToSlash(rel)

		base := strings.TrimSuffix(d.Name(), ".json")
		suites = append(suites, struct{ path, folder, base string }{path, folder, base})
		return nil
	})
	if err != nil {
		t.Fatalf("walk tests dir: %v", err)
	}
	if len(suites) == 0 {
		t.Fatal("no test suites found under tests/")
	}
	return suites
}

// safeSubtestName replaces characters that are problematic in Go subtest
// names (slashes would be interpreted as nested subtests).
func safeSubtestName(s string) string {
	return strings.NewReplacer("/", "%2F", " ", "%20").Replace(s)
}

// TestGrammar is the main test: for every JSON test suite it validates
// string expected_output values against the "purl-canonical" ABNF rule, and
// also validates inputs known to violate the general PURL structure from the
// specification test suite.
//
// Specifically:
//   - For every test suite: string expected_output with expected_failure=false
//     is validated against "purl-canonical" and must be ACCEPTED.
//   - For the specification test suite only: string input with
//     expected_failure=true is validated against "purl" and must be REJECTED.
//     (Type-specific test suites have type-scoped failure rules that are
//     intentionally outside the scope of the general ABNF grammar, so their
//     expected_failure inputs are skipped.)
func TestGrammar(t *testing.T) {
	grammar := parseGrammar(t)

	for _, suite := range findTestSuites(t) {
		suite := suite // capture
		t.Run(fmt.Sprintf("grammar.%s.%s", suite.folder, suite.base), func(t *testing.T) {
			t.Parallel()

			raw, err := os.ReadFile(suite.path)
			if err != nil {
				t.Fatalf("read %s: %v", suite.path, err)
			}

			var ts testSuite
			if err := json.Unmarshal(raw, &ts); err != nil {
				t.Fatalf("parse %s: %v", suite.path, err)
			}

			for i, tc := range ts.Tests {
				tc := tc
				i := i

				// ── 1. Input rejection test (spec suite, expected_failure=true) ──
				// Only the specification test suite tests general PURL structure
				// violations that the grammar covers.  Type-specific test suites use
				// type-scoped rules that go beyond the grammar, so their failure cases
				// are skipped here.
				if suite.folder == "spec" && tc.shouldFail() {
					if input, ok := stringValue(tc.Input); ok {
						name := fmt.Sprintf("input[%d].%s", i, safeSubtestName(input))
						t.Run(name, func(t *testing.T) {
							t.Parallel()
							valid, err := grammar.IsValid("purl", []byte(input))
							if err != nil {
								t.Fatalf("IsValid error: %v", err)
							}
							if valid {
								t.Errorf("expected ABNF rule 'purl' to reject %q (expected_failure=true), but it was accepted", input)
							}
						})
					}
				}

				// ── 2. Expected-output validation (all suites, expected_failure=false) ──
				if !tc.shouldFail() {
					if expectedOutput, ok := stringValue(tc.ExpectedOutput); ok {
						name := fmt.Sprintf("expected_output[%d].%s", i, safeSubtestName(expectedOutput))
						t.Run(name, func(t *testing.T) {
							t.Parallel()
							valid, err := grammar.IsValid("purl-canonical", []byte(expectedOutput))
							if err != nil {
								t.Fatalf("IsValid error: %v", err)
							}
							if !valid {
								t.Errorf("expected ABNF rule 'purl-canonical' to accept %q, but it was rejected", expectedOutput)
							}
						})
					}
				}
			}
		})
	}
}
