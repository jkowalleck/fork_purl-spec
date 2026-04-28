// SPDX-License-Identifier: MIT
// Copyright (c) the purl authors
//
// ABNF grammar validation tests for PURL strings.
//
// For every `$.tests[]` entry in the JSON test suites under `tests/`:
//
//   • If `input` is a string: validate it against the ABNF `purl` rule.
//     The result is **informational only** — discrepancies between the grammar
//     and the test data are expected and never fail the test run.  Two common
//     sources of discrepancy are:
//       - The grammar does not encode type-specific constraints
//         (e.g. chrome-extension name format, cpan double-colon notation).
//         The grammar correctly accepts these structurally valid PURLs even
//         though a type-specific parser would reject them.
//       - The grammar is stricter than the spec's loose input format.
//         Inputs may contain characters that must be percent-encoded in the
//         canonical form (e.g. literal `/` in qualifier values, `@` in
//         namespace segments).  The canonical `expected_output` always uses
//         proper encoding and is accepted by the grammar.
//
//   • If `expected_output` is a string AND `expected_failure` is not `true`:
//     validate it against the ABNF `purl-canonical` rule and expect PASS.
//     This is the **strict** check — canonical forms must always satisfy the
//     grammar.
//
// Test names follow the scheme:
//   grammar.<folder>.<file-base>.input.<value>
//   grammar.<folder>.<file-base>.expected_output.<value>
//
// The ABNF grammar is extracted at compile time from
// `docs/standard/grammar.md` (the fenced `abnf` code block).

use libtest_mimic::{Arguments, Failed, Trial};
use purl_grammar_abnf_tests::{Purl, PurlCanonical};
use serde_json::Value;
use std::{
    collections::HashMap,
    fs,
    path::PathBuf,
};

// ---------------------------------------------------------------------------
// Helper: sanitise a PURL string for use in a test name
// ---------------------------------------------------------------------------

fn safe_id(value: &str) -> String {
    value
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == '_' {
                c
            } else {
                '_'
            }
        })
        .collect()
}

// ---------------------------------------------------------------------------
// Helper: recursively collect *.json files under a directory
// ---------------------------------------------------------------------------

fn collect_json_files(dir: &PathBuf) -> Vec<PathBuf> {
    let mut result = Vec::new();
    if let Ok(entries) = fs::read_dir(dir) {
        for entry in entries.filter_map(|e| e.ok()) {
            let path = entry.path();
            if path.is_dir() {
                result.extend(collect_json_files(&path));
            } else if path.extension().and_then(|e| e.to_str()) == Some("json") {
                result.push(path);
            }
        }
    }
    result
}

// ---------------------------------------------------------------------------
// Collect trials from all JSON test suites under `tests/`
// ---------------------------------------------------------------------------

fn collect_trials() -> Vec<Trial> {
    // Repository root is four levels above the crate root.
    let manifest_dir = env!("CARGO_MANIFEST_DIR");
    let tests_dir = PathBuf::from(manifest_dir).join("../../../../tests");
    let tests_dir = tests_dir
        .canonicalize()
        .expect("tests/ directory not found (expected four levels above crate root)");

    let mut json_files: Vec<PathBuf> = collect_json_files(&tests_dir);
    json_files.sort();

    let mut trials: Vec<Trial> = Vec::new();
    let mut seen_input: HashMap<String, usize> = HashMap::new();
    let mut seen_output: HashMap<String, usize> = HashMap::new();

    for json_file in &json_files {
        let folder = json_file
            .parent()
            .and_then(|p| p.file_name())
            .and_then(|n| n.to_str())
            .unwrap_or("unknown");
        let file_base = json_file
            .file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or("unknown");

        let content = match std::fs::read_to_string(json_file) {
            Ok(c) => c,
            Err(_) => continue,
        };
        let data: Value = match serde_json::from_str(&content) {
            Ok(v) => v,
            Err(_) => continue,
        };

        let tests = match data.get("tests").and_then(Value::as_array) {
            Some(t) => t,
            None => continue,
        };

        for test in tests {
            if !test.is_object() {
                continue;
            }

            let should_fail = test.get("expected_failure") == Some(&Value::Bool(true));

            // ----- input validation (against `purl` rule) -----
            if let Some(Value::String(input_val)) = test.get("input") {
                let base_id = format!(
                    "grammar.{}.{}.input.{}",
                    folder,
                    file_base,
                    safe_id(input_val)
                );
                let count = seen_input.entry(base_id.clone()).or_insert(0);
                let test_id = if *count == 0 {
                    base_id.clone()
                } else {
                    format!("{}.{}", base_id, count)
                };
                *count += 1;

                let value = input_val.clone();
                let trial = Trial::test(test_id, move || {
                    let valid = Purl::new(value.as_bytes()).is_ok();
                    // Input validation is informational only.
                    // The grammar does not model type-specific constraints, and the
                    // `purl` rule does not accept all loose-encoded inputs that a PURL
                    // implementation would normalise.  Both discrepancies are expected;
                    // they reveal gaps between the grammar and real-world usage and guide
                    // future grammar improvements.
                    if should_fail && valid {
                        eprintln!(
                            "grammar-informational: {:?} accepted by grammar despite type-specific invalidity",
                            value
                        );
                    } else if !should_fail && !valid {
                        eprintln!(
                            "grammar-informational: {:?} rejected by grammar (loose encoding not accepted by strict grammar)",
                            value
                        );
                    }
                    Ok(())
                });
                trials.push(trial);
            }

            // ----- expected_output validation (against `purl-canonical` rule) -----
            // Only when expected_failure is not true.
            if !should_fail {
                if let Some(Value::String(output_val)) = test.get("expected_output") {
                    let base_id = format!(
                        "grammar.{}.{}.expected_output.{}",
                        folder,
                        file_base,
                        safe_id(output_val)
                    );
                    let count = seen_output.entry(base_id.clone()).or_insert(0);
                    let test_id = if *count == 0 {
                        base_id.clone()
                    } else {
                        format!("{}.{}", base_id, count)
                    };
                    *count += 1;

                    let value = output_val.clone();
                    let trial = Trial::test(test_id, move || {
                        let valid = PurlCanonical::new(value.as_bytes()).is_ok();
                        if valid {
                            Ok(())
                        } else {
                            Err(Failed::from(format!(
                                "Expected ABNF validation of {:?} to PASS against rule `purl-canonical`, but it failed",
                                value
                            )))
                        }
                    });
                    trials.push(trial);
                }
            }
        }
    }

    trials
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

fn main() {
    let args = Arguments::from_args();
    let trials = collect_trials();
    libtest_mimic::run(&args, trials).exit();
}
