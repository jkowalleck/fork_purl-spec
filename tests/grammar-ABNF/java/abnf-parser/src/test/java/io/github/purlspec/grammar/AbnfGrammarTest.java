// SPDX-License-Identifier: MIT
// Copyright (c) the purl authors
// Visit https://github.com/package-url/purl-spec and https://packageurl.org for support

package io.github.purlspec.grammar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ABNF grammar validation tests for the PURL specification.
 *
 * <p>Extracts the ABNF grammar from {@code docs/standard/grammar.md} and validates
 * string values from every JSON test suite under {@code tests/} against the
 * {@code purl} and {@code purl-canonical} ABNF rules.
 *
 * <h2>Validation rules</h2>
 * <ul>
 *   <li>{@code $.tests[].input} (string only): validated against rule {@code purl}.
 *       Expected to <em>fail</em> when {@code expected_failure == true};
 *       expected to <em>pass</em> otherwise.</li>
 *   <li>{@code $.tests[].expected_output} (string only): validated against rule
 *       {@code purl-canonical}. Only checked when {@code expected_failure} is not
 *       {@code true}; always expected to <em>pass</em>.</li>
 *   <li>Non-string values for {@code input} / {@code expected_output} are silently
 *       skipped (they represent parsed component objects, not PURL strings).</li>
 * </ul>
 *
 * <h2>Test naming</h2>
 * Names follow the pattern
 * {@code grammar.<folder>.<stem>.input.<value>} /
 * {@code grammar.<folder>.<stem>.expected_output.<value>},
 * where {@code <folder>} is the immediate subdirectory of {@code tests/} and
 * {@code <stem>} is the JSON filename without {@code .json}.
 * A numeric suffix ({@code .1}, {@code .2}, …) is appended when the same
 * value appears more than once.
 */
@DisplayName("ABNF grammar validation")
public class AbnfGrammarTest {

    // ─────────────────────────────────────────────────────────────────────────
    // One-time initialisation
    // ─────────────────────────────────────────────────────────────────────────

    /** Repository root directory (contains {@code docs/} and {@code tests/}). */
    private static final Path REPO_ROOT = findRepoRoot();

    /** Compiled {@code purl} rule pattern. */
    private static final Pattern PURL_PATTERN;

    /** Compiled {@code purl-canonical} rule pattern. */
    private static final Pattern PURL_CANONICAL_PATTERN;

    static {
        try {
            AbnfGrammar grammar = AbnfGrammar.fromMarkdown(
                    REPO_ROOT.resolve("docs/standard/grammar.md"));
            PURL_PATTERN           = grammar.compile("purl");
            PURL_CANONICAL_PATTERN = grammar.compile("purl-canonical");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test data
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Provides arguments for {@link #testPurlInput}:
     * {@code (testId, value, shouldFail)}.
     */
    static Stream<Arguments> inputCases() {
        return collectCases().inputArgs.stream();
    }

    /**
     * Provides arguments for {@link #testPurlCanonicalOutput}:
     * {@code (testId, value)}.
     */
    static Stream<Arguments> outputCases() {
        return collectCases().outputArgs.stream();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validate a PURL {@code input} string against the {@code purl} ABNF rule.
     *
     * <ul>
     *   <li>{@code shouldFail=true}  → grammar must <em>reject</em> the input</li>
     *   <li>{@code shouldFail=false} → grammar must <em>accept</em> the input</li>
     * </ul>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("inputCases")
    @DisplayName("purl input")
    void testPurlInput(String testId, String value, boolean shouldFail) {
        boolean matches = PURL_PATTERN.matcher(value).matches();
        if (shouldFail) {
            assertFalse(matches,
                    "Grammar should have rejected input '" + value + "' for test: " + testId);
        } else {
            assertTrue(matches,
                    "Grammar should have accepted input '" + value + "' for test: " + testId);
        }
    }

    /**
     * Validate a PURL {@code expected_output} string against the
     * {@code purl-canonical} ABNF rule.
     * Always expected to <em>pass</em>.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("outputCases")
    @DisplayName("purl-canonical expected_output")
    void testPurlCanonicalOutput(String testId, String value) {
        assertTrue(PURL_CANONICAL_PATTERN.matcher(value).matches(),
                "Grammar should have accepted expected_output '" + value
                + "' for test: " + testId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test-case collection
    // ─────────────────────────────────────────────────────────────────────────

    private static final class Cases {
        final List<Arguments> inputArgs  = new ArrayList<>();
        final List<Arguments> outputArgs = new ArrayList<>();
    }

    /** Cached result so the JSON files are read only once. */
    private static Cases CASES_CACHE = null;

    private static synchronized Cases collectCases() {
        if (CASES_CACHE != null) {
            return CASES_CACHE;
        }
        Cases cases = new Cases();
        ObjectMapper mapper = new ObjectMapper();

        // Track occurrence counts for id de-duplication
        Map<String, Integer> inputIdCount  = new LinkedHashMap<>();
        Map<String, Integer> outputIdCount = new LinkedHashMap<>();

        Path testsDir = REPO_ROOT.resolve("tests");

        List<Path> jsonFiles = new ArrayList<>();
        try {
            Files.walk(testsDir)
                 .filter(p -> p.toString().endsWith(".json"))
                 .sorted()
                 .forEach(jsonFiles::add);
        } catch (IOException e) {
            throw new RuntimeException("Failed to list test suites under " + testsDir, e);
        }

        for (Path jsonFile : jsonFiles) {
            // Skip files inside this grammar-ABNF directory
            if (jsonFile.toString().contains("grammar-ABNF")) {
                continue;
            }

            // Determine <folder> and <stem>
            String folder = jsonFile.getParent().getFileName().toString();
            String stem   = jsonFile.getFileName().toString().replaceFirst("\\.json$", "");

            JsonNode root;
            try {
                root = mapper.readTree(jsonFile.toFile());
            } catch (IOException e) {
                throw new RuntimeException("Failed to read " + jsonFile, e);
            }

            JsonNode tests = root.path("tests");
            if (!tests.isArray()) {
                continue;
            }

            for (JsonNode test : tests) {
                boolean shouldFail = test.path("expected_failure").asBoolean(false);

                // ── input validation ────────────────────────────────────────
                JsonNode inputNode = test.path("input");
                if (inputNode.isTextual()) {
                    String value  = inputNode.asText();
                    String baseId = "grammar." + folder + "." + stem + ".input." + value;
                    String id     = uniqueId(inputIdCount, baseId);
                    cases.inputArgs.add(Arguments.of(id, value, shouldFail));
                }

                // ── expected_output validation (only when not expected-to-fail) ──
                if (!shouldFail) {
                    JsonNode outputNode = test.path("expected_output");
                    if (outputNode.isTextual()) {
                        String value  = outputNode.asText();
                        String baseId = "grammar." + folder + "." + stem
                                        + ".expected_output." + value;
                        String id     = uniqueId(outputIdCount, baseId);
                        cases.outputArgs.add(Arguments.of(id, value));
                    }
                }
            }
        }

        CASES_CACHE = cases;
        return cases;
    }

    /**
     * Return a unique id based on {@code base}.
     * The first occurrence keeps the base name; subsequent ones get a {@code .N} suffix.
     */
    private static String uniqueId(Map<String, Integer> counts, String base) {
        int count = counts.getOrDefault(base, 0);
        counts.put(base, count + 1);
        return (count == 0) ? base : base + "." + count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Repository root discovery
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Walk upward from the current working directory (Maven project base dir)
     * until the repository root is found (identified by
     * {@code docs/standard/grammar.md}).
     */
    private static Path findRepoRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("docs/standard/grammar.md"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Cannot locate repository root (searched upward from '"
                + Paths.get(System.getProperty("user.dir")).toAbsolutePath()
                + "'; looking for docs/standard/grammar.md)");
    }
}
