package io.github.jkowalleck.purl.grammar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.siisise.abnf.ABNFReg;
import net.siisise.abnf.parser5234.ABNF5234;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Validates PURL string values from JSON test suites against the ABNF grammar
 * defined in {@code docs/standard/grammar.md}.
 *
 * <p>Two start rules are exercised:
 * <ul>
 *   <li>{@code purl}           – used to validate {@code $.tests[].input} values</li>
 *   <li>{@code purl-canonical} – used to validate {@code $.tests[].expected_output} values</li>
 * </ul>
 *
 * <p>Only the structural specification test suite ({@code tests/spec/}) is
 * used as input. Type-specific test suites ({@code tests/types/}) are
 * deliberately excluded because their {@code expected_failure} flags reflect
 * type-level rules (e.g. "swift requires a namespace") that are above the ABNF
 * grammar level; including them would cause incorrect grammar failures.
 *
 * <p>Expected-failure semantics (per spec):
 * <ul>
 *   <li>If {@code expected_failure == true} the {@code input} validation must <em>fail</em>.</li>
 *   <li>Otherwise the validation must <em>pass</em>.</li>
 *   <li>{@code expected_output} is only validated when {@code expected_failure != true}.</li>
 *   <li>Non-string {@code input} / {@code expected_output} values are silently ignored.</li>
 * </ul>
 */
class GrammarValidationTest {

    /** Pattern that captures the content of the first {@code ```abnf} fenced block. */
    private static final Pattern ABNF_FENCE =
            Pattern.compile("```abnf\\r?\\n(.*?)\\r?\\n```", Pattern.DOTALL);

    private static ABNFReg grammarReg;
    private static Path repoRoot;

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    @BeforeAll
    static void loadGrammar() throws IOException {
        repoRoot = resolveRepoRoot();

        Path grammarFile = repoRoot.resolve("docs/standard/grammar.md");
        String mdContent = Files.readString(grammarFile, StandardCharsets.UTF_8);

        String abnfText = extractAbnfBlock(mdContent);

        // RFC 5234 ABNF requires CRLF line endings (comments are terminated by CRLF).
        String abnfCrlf = abnfText.replace("\r\n", "\n").replace("\n", "\r\n");

        // Build a registry that inherits RFC 5234 core rules (ALPHA, DIGIT, HEXDIG, …).
        grammarReg = new ABNFReg(ABNF5234.BASE);
        List<?> parsed = grammarReg.rulelist(abnfCrlf);
        if (parsed == null || parsed.isEmpty()) {
            throw new IllegalStateException(
                    "Failed to parse ABNF grammar from " + grammarFile);
        }
    }

    // -------------------------------------------------------------------------
    // Test factories
    // -------------------------------------------------------------------------

    /**
     * Generates one dynamic test per string {@code input} value found across
     * all JSON test suites.
     *
     * <p>Test name pattern: {@code grammar.<folder>.<file-base-name>.input.<value>}
     */
    @TestFactory
    Stream<DynamicTest> inputTests() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        Map<String, Integer> nameCount = new HashMap<>();

        for (TestSuiteEntry suite : loadTestSuites()) {
            for (JsonNode testCase : suite.tests()) {
                JsonNode inputNode = testCase.get("input");
                if (inputNode == null || !inputNode.isTextual()) {
                    continue;
                }
                String inputValue = inputNode.asText();
                boolean expectedFailure = isExpectedFailure(testCase);

                String baseName = "grammar." + suite.folder() + "." + suite.fileBaseName()
                        + ".input." + inputValue;
                String testName = uniqueName(baseName, nameCount);

                tests.add(dynamicTest(testName, () -> {
                    boolean matches = grammarReg.href("purl").eq(inputValue);
                    if (expectedFailure) {
                        assertFalse(matches,
                                "Expected ABNF 'purl' validation to FAIL for: " + inputValue);
                    } else {
                        assertTrue(matches,
                                "Expected ABNF 'purl' validation to PASS for: " + inputValue);
                    }
                }));
            }
        }
        return tests.stream();
    }

    /**
     * Generates one dynamic test per string {@code expected_output} value found
     * across all JSON test suites where {@code expected_failure} is not {@code true}.
     *
     * <p>Test name pattern: {@code grammar.<folder>.<file-base-name>.expected_output.<value>}
     */
    @TestFactory
    Stream<DynamicTest> expectedOutputTests() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        Map<String, Integer> nameCount = new HashMap<>();

        for (TestSuiteEntry suite : loadTestSuites()) {
            for (JsonNode testCase : suite.tests()) {
                if (isExpectedFailure(testCase)) {
                    continue;
                }
                JsonNode outputNode = testCase.get("expected_output");
                if (outputNode == null || !outputNode.isTextual()) {
                    continue;
                }
                String outputValue = outputNode.asText();

                String baseName = "grammar." + suite.folder() + "." + suite.fileBaseName()
                        + ".expected_output." + outputValue;
                String testName = uniqueName(baseName, nameCount);

                tests.add(dynamicTest(testName, () -> {
                    boolean matches = grammarReg.href("purl-canonical").eq(outputValue);
                    assertTrue(matches,
                            "Expected ABNF 'purl-canonical' validation to PASS for: " + outputValue);
                }));
            }
        }
        return tests.stream();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Resolves the repository root directory from the {@code repoRoot} system property. */
    private static Path resolveRepoRoot() {
        String prop = System.getProperty("repoRoot");
        if (prop != null && !prop.trim().isEmpty()) {
            return Paths.get(prop).toAbsolutePath().normalize();
        }
        // Fallback: navigate 4 levels up from the current working directory.
        // Expected layout: <repoRoot>/tests/grammar-ABNF/java/abnf4j/
        // The 'repoRoot' system property (set by maven-surefire-plugin) is strongly
        // preferred; this fallback exists only for IDE runs where the property may be absent.
        Path path = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            Path parent = path.getParent();
            if (parent == null) {
                throw new IllegalStateException(
                        "Could not determine the repository root by navigating up from '" + path
                        + "'. Set the 'repoRoot' system property to the absolute path of the "
                        + "repository root.");
            }
            path = parent;
        }
        return path.normalize();
    }

    /** Extracts the raw ABNF text from the first {@code ```abnf} fenced block in {@code md}. */
    private static String extractAbnfBlock(String md) {
        Matcher m = ABNF_FENCE.matcher(md);
        if (!m.find()) {
            throw new IllegalStateException("No ```abnf fenced block found in grammar.md");
        }
        return m.group(1);
    }

    /** Returns {@code true} if the test-case node has {@code expected_failure == true}. */
    private static boolean isExpectedFailure(JsonNode testCase) {
        JsonNode ef = testCase.get("expected_failure");
        return ef != null && ef.isBoolean() && ef.asBoolean();
    }

    /**
     * Returns a unique test name; if {@code baseName} has been seen before, appends
     * {@code .<n>} (n = 2, 3, …) to disambiguate.
     */
    private static String uniqueName(String baseName, Map<String, Integer> seen) {
        int count = seen.merge(baseName, 1, Integer::sum);
        return count == 1 ? baseName : baseName + "." + count;
    }

    /**
     * Walks the grammar-relevant JSON test suite directories and returns
     * metadata + parsed test-case arrays.
     *
     * <p>Only {@code tests/spec/} is scanned. {@code tests/types/} is
     * intentionally excluded because type test suites validate type-specific
     * rules (e.g. "swift requires a namespace") that lie above the ABNF grammar
     * level; mixing them in would cause spurious grammar failures.
     */
    private List<TestSuiteEntry> loadTestSuites() throws IOException {
        Path testsDir = repoRoot.resolve("tests");
        // Directories inside tests/ that contain grammar-level JSON test suites.
        List<Path> scanRoots = new ArrayList<>();
        scanRoots.add(testsDir.resolve("spec"));

        ObjectMapper mapper = new ObjectMapper();
        List<TestSuiteEntry> entries = new ArrayList<>();

        for (Path scanRoot : scanRoots) {
            if (!Files.isDirectory(scanRoot)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(scanRoot)) {
                List<Path> jsonFiles = walk
                        .filter(p -> p.toString().endsWith(".json"))
                        .sorted()
                        .collect(Collectors.toList());

                for (Path jsonFile : jsonFiles) {
                    // Determine folder (immediate child of tests/) and file base name.
                    Path relative = testsDir.relativize(jsonFile);
                    String folder = relative.getNameCount() > 1
                            ? relative.getName(0).toString()
                            : "";
                    String fileName = jsonFile.getFileName().toString();
                    String fileBaseName = fileName.endsWith(".json")
                            ? fileName.substring(0, fileName.length() - 5)
                            : fileName;

                    JsonNode root = mapper.readTree(jsonFile.toFile());
                    JsonNode testsNode = root.get("tests");
                    if (testsNode == null || !testsNode.isArray()) {
                        continue;
                    }
                    entries.add(new TestSuiteEntry(folder, fileBaseName, testsNode));
                }
            }
        }
        return entries;
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /** Holds metadata and test-case array for a single JSON test suite file. */
    private static final class TestSuiteEntry {
        private final String folder;
        private final String fileBaseName;
        private final JsonNode testsNode;

        TestSuiteEntry(String folder, String fileBaseName, JsonNode testsNode) {
            this.folder = folder;
            this.fileBaseName = fileBaseName;
            this.testsNode = testsNode;
        }

        String folder() {
            return folder;
        }

        String fileBaseName() {
            return fileBaseName;
        }

        Iterable<JsonNode> tests() {
            List<JsonNode> list = new ArrayList<>();
            testsNode.forEach(list::add);
            return list;
        }
    }
}
