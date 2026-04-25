// SPDX-License-Identifier: MIT
// Copyright (c) the purl authors
'use strict';
/**
 * ABNF grammar validation tests for PURL strings.
 *
 * Ecosystem / Library: JavaScript/Node.js — apg-js
 *
 * For every `$.tests[]` entry in the JSON test suites under `tests/`:
 *
 * - If `input` is a string: validate it against the ABNF `purl` rule.
 *   Expect the validation to FAIL iff `expected_failure === true`;
 *   otherwise expect it to PASS.
 * - If `expected_output` is a string AND `expected_failure` is not `true`:
 *   validate it against the ABNF `purl-canonical` rule and expect it to PASS.
 *
 * The ABNF grammar is extracted dynamically from
 * `docs/standard/grammar.md` (the fenced `abnf` code block).
 *
 * APG Compatibility Note
 * ----------------------
 * APG (apg-js) is a non-backtracking recursive descent parser.  The PURL
 * grammar has an optional namespace before a mandatory name, which requires
 * backtracking with standard ABNF semantics.  This file applies minimal SABNF
 * look-ahead annotations (`&(...)`) programmatically to the extracted grammar
 * so that APG's greedy parser produces the correct parse for all PURL forms.
 * The source of truth is still `docs/standard/grammar.md`; no ABNF is
 * hard-coded here.
 */

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { apgApi, apgLib } = require('apg-js');

// ---------------------------------------------------------------------------
// Repository layout
// ---------------------------------------------------------------------------

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const GRAMMAR_MD = path.join(REPO_ROOT, 'docs', 'standard', 'grammar.md');
const TESTS_DIR = path.join(REPO_ROOT, 'tests');

// ---------------------------------------------------------------------------
// RFC 5234 Appendix B.1 core rules used by the PURL grammar
// ---------------------------------------------------------------------------

const RFC5234_CORE = `
ALPHA  =  %x41-5A / %x61-7A
DIGIT  =  %x30-39
HEXDIG =  DIGIT / "A" / "B" / "C" / "D" / "E" / "F"
`;

// ---------------------------------------------------------------------------
// ABNF extraction and APG compatibility patching
// ---------------------------------------------------------------------------

/**
 * Extract the first ```abnf fenced code block from a Markdown file.
 * @param {string} mdPath
 * @returns {string}
 */
function extractAbnf(mdPath) {
  const content = fs.readFileSync(mdPath, 'utf8');
  const m = content.match(/```abnf\n([\s\S]*?)```/);
  if (!m) throw new Error(`No ABNF code block found in ${mdPath}`);
  return m[1];
}

/**
 * Apply minimal SABNF look-ahead patches to make the grammar work with APG's
 * non-backtracking (greedy) parser.
 *
 * Problem: `[ 1*"/" namespace ] 1*"/" name` — APG greedily matches the
 * optional namespace, leaving nothing for the mandatory name.
 *
 * Fix: add `&(...)` (positive look-ahead) so that:
 *   1. Each additional namespace segment is consumed only when another "/"
 *      follows (keeping the final segment free for `name`).
 *   2. The optional namespace group itself succeeds only when `1*"/"` follows
 *      it (confirming there is a mandatory name separator still available).
 *
 * @param {string} abnf  Raw ABNF text extracted from grammar.md
 * @returns {string}     Patched ABNF text
 */
function apgCompatPatch(abnf) {
  let text = abnf;

  // 1. namespace rule: consume extra segments only when another "/" follows
  text = text.replace(
    /^(namespace\s+=\s+namespace-segment \*\(\s*)1\*"\/"(\s+namespace-segment\s*\))/m,
    '$1&("/" namespace-segment "/") 1*"/"$2',
  );

  // 2. namespace-canonical rule: same treatment
  text = text.replace(
    /^(namespace-canonical\s+=\s+namespace-segment \*\(\s*)"\/"(\s+namespace-segment\s*\))/m,
    '$1&("/" namespace-segment "/") "/"$2',
  );

  // 3. purl rule: optional namespace must be followed by 1*"/" (for the name)
  text = text.replace(
    /(\[\s*1\*"\/"\s+namespace\s+\])/,
    '[ 1*"/" namespace &(1*"/") ]',
  );

  // 4. purl-canonical rule: optional namespace-canonical must be followed by "/"
  text = text.replace(
    /(\[\s*"\/"\s+namespace-canonical\s*\])/,
    '[ "/" namespace-canonical &("/") ]',
  );

  return text;
}

// ---------------------------------------------------------------------------
// Grammar compilation (done once at module load)
// ---------------------------------------------------------------------------

const rawAbnf = extractAbnf(GRAMMAR_MD);
const patchedAbnf = apgCompatPatch(rawAbnf) + RFC5234_CORE;

const grammarApi = new apgApi(patchedAbnf);
grammarApi.generate();
if (grammarApi.errors.length > 0) {
  throw new Error(
    `ABNF grammar compilation failed:\n${grammarApi.errors.map((e) => JSON.stringify(e)).join('\n')}`,
  );
}
const grammarObject = grammarApi.toObject();
const purlParser = new apgLib.parser();
const charUtils = apgLib.utils;

/**
 * Validate `value` fully against ABNF rule `ruleName`.
 * @param {string} value
 * @param {string} ruleName
 * @returns {boolean}
 */
function validate(value, ruleName) {
  const chars = charUtils.stringToChars(value);
  const result = purlParser.parse(grammarObject, ruleName, chars);
  return result.success;
}

// ---------------------------------------------------------------------------
// Test-case collection from JSON suites
// ---------------------------------------------------------------------------

/**
 * Produce a test-ID-safe version of `value` (replace special chars with `_`).
 * @param {string} value
 * @returns {string}
 */
function safeId(value) {
  return value.replace(/[^A-Za-z0-9._-]/g, '_');
}

/**
 * Walk `testsDir` recursively for JSON files and collect test cases.
 *
 * @returns {{ inputCases: Array<{id,value,shouldFail}>, outputCases: Array<{id,value}> }}
 */
function collectCases() {
  const inputCases = [];
  const outputCases = [];
  const seenInput = Object.create(null);
  const seenOutput = Object.create(null);

  const jsonFiles = [];
  for (const entry of fs.readdirSync(TESTS_DIR, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const subDir = path.join(TESTS_DIR, entry.name);
    for (const file of fs.readdirSync(subDir)) {
      if (file.endsWith('.json')) jsonFiles.push(path.join(subDir, file));
    }
  }
  jsonFiles.sort();

  for (const jsonFile of jsonFiles) {
    const folder = path.basename(path.dirname(jsonFile));
    const fileBase = path.basename(jsonFile, '.json');

    let data;
    try {
      data = JSON.parse(fs.readFileSync(jsonFile, 'utf8'));
    } catch {
      continue;
    }

    const tests = data.tests;
    if (!Array.isArray(tests)) continue;

    for (const t of tests) {
      if (!t || typeof t !== 'object') continue;

      const shouldFail = t.expected_failure === true;

      // --- input case ---
      if (typeof t.input === 'string') {
        const baseId = `grammar.${folder}.${fileBase}.input.${safeId(t.input)}`;
        const count = seenInput[baseId] ?? 0;
        seenInput[baseId] = count + 1;
        const id = count === 0 ? baseId : `${baseId}.${count}`;
        inputCases.push({ id, value: t.input, shouldFail });
      }

      // --- expected_output case (only when not expected to fail) ---
      if (!shouldFail && typeof t.expected_output === 'string') {
        const baseId = `grammar.${folder}.${fileBase}.expected_output.${safeId(t.expected_output)}`;
        const count = seenOutput[baseId] ?? 0;
        seenOutput[baseId] = count + 1;
        const id = count === 0 ? baseId : `${baseId}.${count}`;
        outputCases.push({ id, value: t.expected_output });
      }
    }
  }

  return { inputCases, outputCases };
}

const { inputCases, outputCases } = collectCases();

// ---------------------------------------------------------------------------
// Input tests  →  validated against ABNF rule `purl`
// ---------------------------------------------------------------------------

for (const { id, value, shouldFail } of inputCases) {
  test(id, () => {
    const isValid = validate(value, 'purl');
    if (shouldFail) {
      assert.equal(
        isValid,
        false,
        `Expected ABNF validation of ${JSON.stringify(value)} to FAIL against rule 'purl', but it passed.`,
      );
    } else {
      assert.equal(
        isValid,
        true,
        `Expected ABNF validation of ${JSON.stringify(value)} to PASS against rule 'purl', but it failed.`,
      );
    }
  });
}

// ---------------------------------------------------------------------------
// Expected-output tests  →  validated against ABNF rule `purl-canonical`
// ---------------------------------------------------------------------------

for (const { id, value } of outputCases) {
  test(id, () => {
    assert.equal(
      validate(value, 'purl-canonical'),
      true,
      `Expected ABNF validation of ${JSON.stringify(value)} to PASS against rule 'purl-canonical', but it failed.`,
    );
  });
}
