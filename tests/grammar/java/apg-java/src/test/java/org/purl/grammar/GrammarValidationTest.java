/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) the purl authors
 */
package org.purl.grammar;

import apg.Generator;
import apg.Grammar;
import apg.Parser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * ABNF grammar validation tests for purl-spec using apg-java.
 *
 * <p>The grammar is extracted at test time from {@code docs/standard/grammar.md}.
 * RFC 5234 core rules (ALPHA, DIGIT, HEXDIG) are appended since they are not
 * defined in the purl grammar file but are referenced by it.
 *
 * <p>The {@code purl} and {@code purl-canonical} rules are rewritten for
 * PEG-safe parsing (APG uses PEG ordered-choice semantics), replacing
 * the backtracking-dependent optional-namespace pattern with an equivalent
 * formulation that commits only when a path separator follows a namespace
 * segment.
 *
 * <p>Tests are driven by all {@code tests/**}{@code /*.json} files in the
 * repository, validated against both {@code purl} (inputs) and
 * {@code purl-canonical} (expected_outputs) start rules.
 */
public class GrammarValidationTest {

    /** Package used for the generated grammar class. */
    private static final String GRAMMAR_PACKAGE = "org.purl.grammar";
    /** Simple name of the generated grammar class. */
    private static final String GRAMMAR_CLASS_NAME = "PurlGrammar";

    private static Grammar grammar;
    private static int purlRuleId;
    private static int purlCanonicalRuleId;

    // -----------------------------------------------------------------------
    // Setup: extract grammar, generate & compile grammar class
    // -----------------------------------------------------------------------

    @BeforeAll
    static void setUpGrammar() throws Exception {
        Path projectBase = resolveProjectBase();
        Path grammarMd   = projectBase.resolve("../../../../docs/standard/grammar.md").normalize();

        // 1. Extract and adapt the ABNF
        String abnfContent = extractAndAdaptAbnf(grammarMd);

        // 2. Write ABNF to a temp directory
        Path tempDir = Files.createTempDirectory("purl-grammar-apg-");
        Path abnfFile = tempDir.resolve("purl.abnf");
        Files.writeString(abnfFile, abnfContent);

        // 3. Run APG Generator (suppress its stdout)
        Path generatedDir = tempDir.resolve("generated");
        Files.createDirectories(generatedDir);

        PrintStream savedOut = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            Generator.main(new String[]{
                "/in=" + abnfFile.toAbsolutePath(),
                "/java=" + GRAMMAR_CLASS_NAME,
                "/package=" + GRAMMAR_PACKAGE,
                "/dir=" + generatedDir.toAbsolutePath() + "/"
            });
        } finally {
            System.setOut(savedOut);
        }

        // 4. Compile the generated grammar class
        Path generatedJava = generatedDir.resolve(GRAMMAR_CLASS_NAME + ".java");
        Path classOutputDir = tempDir.resolve("classes");
        Files.createDirectories(classOutputDir);
        compileJavaFile(generatedJava, classOutputDir);

        // 5. Load the grammar class dynamically
        URLClassLoader classLoader = new URLClassLoader(
            new URL[]{ classOutputDir.toUri().toURL() },
            GrammarValidationTest.class.getClassLoader()
        );
        Class<?> grammarClass = classLoader.loadClass(GRAMMAR_PACKAGE + "." + GRAMMAR_CLASS_NAME);
        Method getInstance = grammarClass.getMethod("getInstance");
        grammar = (Grammar) getInstance.invoke(null);

        // 6. Resolve rule IDs for "purl" and "purl-canonical"
        Class<?> ruleNamesClass = Class.forName(
            GRAMMAR_PACKAGE + "." + GRAMMAR_CLASS_NAME + "$RuleNames", true, classLoader);
        Object[] enumConstants = ruleNamesClass.getEnumConstants();
        Method ruleName = ruleNamesClass.getMethod("ruleName");
        Method ruleID   = ruleNamesClass.getMethod("ruleID");

        purlRuleId          = -1;
        purlCanonicalRuleId = -1;
        for (Object e : enumConstants) {
            String name = (String) ruleName.invoke(e);
            int    id   = (Integer) ruleID.invoke(e);
            if ("purl".equals(name))           purlRuleId          = id;
            if ("purl-canonical".equals(name)) purlCanonicalRuleId = id;
        }
        if (purlRuleId < 0 || purlCanonicalRuleId < 0) {
            throw new IllegalStateException(
                "Could not locate 'purl' or 'purl-canonical' rules in generated grammar");
        }
    }

    // -----------------------------------------------------------------------
    // Test factory: dynamic tests from JSON suites
    // -----------------------------------------------------------------------

    @TestFactory
    Stream<DynamicTest> grammarTests() throws Exception {
        Path projectBase = resolveProjectBase();
        Path testsRoot   = projectBase.resolve("../../../../tests").normalize();

        List<DynamicTest> tests = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        // Walk all *.json under tests/
        List<Path> jsonFiles = Files.walk(testsRoot)
            .filter(p -> p.toString().endsWith(".json"))
            .sorted()
            .toList();

        // Track display names for uniqueness
        Map<String, Integer> nameCount = new HashMap<>();

        for (Path jsonFile : jsonFiles) {
            Path rel = testsRoot.relativize(jsonFile);
            // folder = immediate sub-directory under tests/  (e.g. "spec", "types")
            String folder   = rel.getNameCount() > 1 ? rel.getName(0).toString() : "";
            String fileBase = jsonFile.getFileName().toString().replaceFirst("\\.json$", "");

            JsonNode root  = mapper.readTree(jsonFile.toFile());
            JsonNode suite = root.get("tests");
            if (suite == null || !suite.isArray()) continue;

            for (JsonNode testEntry : suite) {
                boolean expectedFailure = testEntry.has("expected_failure")
                    && testEntry.get("expected_failure").asBoolean();

                // --- 1) Input validation (against "purl" rule) ---
                // If input exists and is a string, validate against "purl".
                // If expected_failure=true → expect ABNF validation to fail.
                // Otherwise → expect ABNF validation to pass.
                JsonNode inputNode = testEntry.get("input");
                if (inputNode != null && inputNode.isTextual()) {
                    String inputVal = inputNode.asText();
                    String displayName = uniqueName(
                        nameCount,
                        "grammar." + folder + "." + fileBase + ".input." + inputVal);
                    tests.add(buildTest(displayName, inputVal, purlRuleId, expectedFailure));
                }

                // --- 2) Expected-output validation (against "purl-canonical" rule) ---
                // Only when expected_failure is false/absent and value is a string.
                // This validation is always expected to pass.
                if (!expectedFailure) {
                    JsonNode outputNode = testEntry.get("expected_output");
                    if (outputNode != null && outputNode.isTextual()) {
                        String outputVal = outputNode.asText();
                        String displayName = uniqueName(
                            nameCount,
                            "grammar." + folder + "." + fileBase + ".expected_output." + outputVal);
                        tests.add(buildTest(displayName, outputVal, purlCanonicalRuleId, false));
                    }
                }
            }
        }
        return tests.stream();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Validates {@code value} against the specified grammar rule.
     * Returns {@code true} if the entire input is matched successfully.
     */
    static boolean validates(String value, int ruleId) throws Exception {
        Parser parser = new Parser(grammar);
        parser.setStartRule(ruleId);
        parser.setInputString(value);
        return parser.parse().success();
    }

    /**
     * Builds a {@link DynamicTest} that asserts the grammar matches/fails as expected.
     * The {@code displayName} is included in the assertion message for easy identification
     * in CI logs.
     */
    private static DynamicTest buildTest(
            String displayName, String value, int ruleId, boolean expectFailure) {
        return dynamicTest(displayName, () -> {
            boolean result = validates(value, ruleId);
            if (expectFailure) {
                assertFalse(result,
                    "[" + displayName + "] "
                    + "Expected grammar NOT to match (expected_failure=true): " + value);
            } else {
                assertTrue(result,
                    "[" + displayName + "] "
                    + "Expected grammar to match: " + value);
            }
        });
    }

    /**
     * Returns a display name that is unique across all tests generated so far.
     * When a name is seen for the first time it is returned unchanged; on
     * subsequent occurrences a {@code [N]} suffix is appended.
     */
    private static String uniqueName(Map<String, Integer> seen, String base) {
        int count = seen.merge(base, 1, Integer::sum);
        return count == 1 ? base : base + "[" + count + "]";
    }

    /**
     * Extracts the {@code ```abnf} fenced block from the given Markdown file,
     * rewrites the {@code purl} and {@code purl-canonical} rules for APG/PEG
     * compatibility, and appends RFC 5234 core rules.
     */
    static String extractAndAdaptAbnf(Path grammarMd) throws IOException {
        String mdContent = Files.readString(grammarMd);

        // Extract the first ```abnf ... ``` block
        Pattern fencePattern = Pattern.compile(
            "```abnf\\r?\\n(.*?)\\r?\\n```", Pattern.DOTALL);
        Matcher m = fencePattern.matcher(mdContent);
        if (!m.find()) {
            throw new IllegalStateException(
                "No ```abnf code block found in " + grammarMd);
        }
        String abnf = m.group(1);

        // Rewrite purl rule for PEG-safe parsing.
        //
        // Original (requires backtracking):
        //   purl = scheme ":" *"/" type
        //          [ 1*"/" namespace ] 1*"/" name *"/"
        //          ...
        //
        // Fixed (PEG-safe): each namespace-segment must be followed by "/"
        // so the parser commits only when a separator is present, leaving
        // the last (name) segment without a trailing "/".
        //   purl = scheme ":" *"/" type
        //          1*"/" *( namespace-segment 1*"/" ) name *"/"
        //          ...
        abnf = replaceRule(abnf, "purl",
            "purl                      = scheme \":\" *\"/\" type\n"
            + "                           1*\"/\" *( namespace-segment 1*\"/\" ) name *\"/\"\n"
            + "                           [ \"@\" version ] [ \"?\" qualifiers           ]\n"
            + "                           [ \"#\" *\"/\" subpath      *\"/\" ]\n"
            + "                           ; leading/trailing slashes allowed here and there");

        // Rewrite purl-canonical rule for PEG-safe parsing.
        //
        // Original (requires backtracking):
        //   purl-canonical = scheme ":" type-canonical
        //                    [ "/" namespace-canonical ] "/" name
        //                    ...
        //
        // Fixed (PEG-safe): same strategy as for purl.
        //   purl-canonical = scheme ":" type-canonical
        //                    "/" *( namespace-segment "/" ) name
        //                    ...
        abnf = replaceRule(abnf, "purl-canonical",
            "purl-canonical            = scheme \":\"      type-canonical\n"
            + "                           \"/\" *( namespace-segment \"/\" ) name\n"
            + "                           [ \"@\" version ] [ \"?\" qualifiers-canonical ]\n"
            + "                           [ \"#\"      subpath-canonical ]");

        // Append RFC 5234 Appendix B.1 core rules that the purl grammar
        // references but does not define.
        abnf = abnf
            + "\n\n; RFC 5234 Appendix B.1 Core Rules (required by purl grammar)\n"
            + "ALPHA  = %x41-5A / %x61-7A\n"
            + "DIGIT  = %x30-39\n"
            + "HEXDIG = DIGIT / \"A\" / \"B\" / \"C\" / \"D\" / \"E\" / \"F\"\n";

        return abnf;
    }

    /**
     * Replaces the definition of a named ABNF rule (including its continuation
     * lines) with {@code newDefinition}.
     *
     * <p>In the purl grammar, rule headers may have leading whitespace and are
     * identified by the pattern: optional-whitespace + rule-name + optional-whitespace + "=".
     * Continuation lines are those that do not match this rule-header pattern
     * (i.e., blank lines, comment-only lines, or lines that start a different
     * syntactic element).
     */
    private static String replaceRule(String abnf, String ruleName, String newDefinition) {
        // Pattern that recognises any ABNF rule header line:
        //   optional leading whitespace + identifier (letters/digits/hyphens) + spaces + "="
        Pattern anyRuleHeader = Pattern.compile("^\\s*[A-Za-z][A-Za-z0-9-]*\\s*=");

        // Pattern for the specific target rule header: same but locked to ruleName
        Pattern targetRuleHeader = Pattern.compile(
            "^\\s*" + Pattern.quote(ruleName) + "\\s*=");

        String[] lines = abnf.split("\n", -1);
        StringBuilder result = new StringBuilder();
        boolean inTargetRule = false;
        boolean replaced     = false;

        for (String line : lines) {
            if (!inTargetRule) {
                if (targetRuleHeader.matcher(line).find() && !replaced) {
                    // Start of the target rule: emit the replacement and begin skipping
                    inTargetRule = true;
                    replaced     = true;
                    result.append(newDefinition).append("\n");
                } else {
                    result.append(line).append("\n");
                }
            } else {
                // Inside the target rule's body: skip lines until a new rule header appears.
                // Blank lines between rules are also skipped (they'll be re-emitted by the
                // next rule's non-blank content, or simply dropped — APG doesn't care).
                boolean isNewRuleHeader = anyRuleHeader.matcher(line).find();
                if (isNewRuleHeader) {
                    // A new rule starts: stop skipping and emit this line normally
                    inTargetRule = false;
                    result.append(line).append("\n");
                }
                // else: continuation / blank / comment line inside target rule → skip
            }
        }

        if (!replaced) {
            throw new IllegalStateException(
                "Could not find rule '" + ruleName + "' in ABNF to replace");
        }
        return result.toString();
    }

    /**
     * Compiles a single Java source file using the system Java compiler.
     * The compile classpath includes the current test classpath so that
     * vendored APG classes are visible.
     */
    private static void compileJavaFile(Path sourceFile, Path outputDir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                "No Java compiler available; run with a JDK, not a JRE");
        }
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units =
                fm.getJavaFileObjectsFromFiles(Arrays.asList(sourceFile.toFile()));
            String classpath = System.getProperty("java.class.path");
            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, null,
                Arrays.asList("-classpath", classpath,
                              "-d", outputDir.toAbsolutePath().toString()),
                null, units);
            if (!task.call()) {
                throw new IllegalStateException(
                    "Failed to compile generated grammar class: " + sourceFile);
            }
        }
    }

    /**
     * Resolves the Maven project base directory from the system property
     * {@code project.basedir} set by the Surefire plugin.
     */
    private static Path resolveProjectBase() {
        String prop = System.getProperty("project.basedir");
        if (prop != null && !prop.isEmpty()) {
            return Path.of(prop);
        }
        // Fallback: use current working directory (works for IDE runs too)
        return Path.of(System.getProperty("user.dir"));
    }
}
