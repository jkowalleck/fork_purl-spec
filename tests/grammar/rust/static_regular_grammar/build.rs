// SPDX-License-Identifier: MIT
// Copyright (c) the purl authors
//
// Build script: extracts the ABNF grammar from docs/standard/grammar.md
// and generates Rust source with the grammar embedded in doc comments so
// that the static_regular_grammar proc-macro can compile it into type
// validators for `Purl` (rule `purl`) and `PurlCanonical` (rule
// `purl-canonical`).

use std::{env, fs, path::PathBuf};

/// Extract the first ```abnf ... ``` fenced block from a Markdown string.
fn extract_abnf(markdown: &str) -> String {
    let start_marker = "```abnf\n";
    if let Some(start_pos) = markdown.find(start_marker) {
        let after = &markdown[start_pos + start_marker.len()..];
        if let Some(end_pos) = after.find("```") {
            return after[..end_pos].to_string();
        }
    }
    panic!("No ```abnf fenced code block found in grammar.md");
}

/// Wrap the ABNF text in Rust doc comments and `#[derive(RegularGrammar)]`
/// for the given type name and entry point rule.
fn grammar_type_source(abnf: &str, type_name: &str, entry_point: &str) -> String {
    let mut src = String::new();
    src.push_str(&format!(
        "/// Validates input against ABNF rule `{}`.\n///\n/// ```abnf\n",
        entry_point
    ));
    for line in abnf.lines() {
        src.push_str(&format!("/// {}\n", line));
    }
    src.push_str("/// ```\n");
    src.push_str("#[derive(RegularGrammar)]\n");
    src.push_str(&format!(
        "#[grammar(entry_point = \"{}\")]\n",
        entry_point
    ));
    src.push_str(&format!("pub struct {}([u8]);\n\n", type_name));
    src
}

fn main() {
    // The crate lives at tests/grammar/rust/static_regular_grammar/ inside
    // the repository, so grammar.md is four levels up.
    let manifest_dir = env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR not set");
    let grammar_md = PathBuf::from(&manifest_dir)
        .join("../../../../docs/standard/grammar.md");
    let grammar_md = grammar_md
        .canonicalize()
        .expect("docs/standard/grammar.md not found (expected four levels above crate root)");

    // Re-run if the grammar changes.
    println!("cargo:rerun-if-changed={}", grammar_md.display());

    let markdown = fs::read_to_string(&grammar_md)
        .expect("Failed to read grammar.md");
    let abnf = extract_abnf(&markdown);

    let out_dir = env::var("OUT_DIR").expect("OUT_DIR not set");
    let out_path = PathBuf::from(&out_dir).join("purl_types.rs");

    let mut src = String::new();
    src.push_str("// @generated — do not edit by hand\n");
    src.push_str("// Generated from docs/standard/grammar.md\n\n");
    src.push_str("use static_regular_grammar::RegularGrammar;\n\n");
    src.push_str(&grammar_type_source(&abnf, "Purl", "purl"));
    src.push_str(&grammar_type_source(&abnf, "PurlCanonical", "purl-canonical"));

    fs::write(&out_path, &src).expect("Failed to write generated purl_types.rs");
}
