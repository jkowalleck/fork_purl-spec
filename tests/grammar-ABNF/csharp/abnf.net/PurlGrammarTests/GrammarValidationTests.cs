using System.Text.Json;
using System.Text.Json.Nodes;
using Abnf;

namespace PurlGrammarTests;

/// <summary>
/// NUnit parameterised tests that validate PURL strings found in the JSON test
/// suites under <c>tests/</c> against the ABNF grammar from
/// <c>docs/standard/grammar.md</c>.
///
/// Validation rules (per the spec issue):
/// <list type="bullet">
///   <item>Every string value of <c>$.tests[].input</c> is validated against
///         ABNF rule <c>purl</c>; it is expected to <em>fail</em> when
///         <c>expected_failure == true</c>, otherwise to <em>pass</em>.</item>
///   <item>When <c>expected_failure</c> is not <c>true</c>, every string value
///         of <c>$.tests[].expected_output</c> is validated against ABNF rule
///         <c>purl-canonical</c> and expected to <em>pass</em>.</item>
/// </list>
/// </summary>
[TestFixture]
public sealed class GrammarValidationTests
{
    // ── Test-case data sources ───────────────────────────────────────────────

    public static IEnumerable<TestCaseData> InputTestCases()
        => BuildTestCases(kind: "input");

    public static IEnumerable<TestCaseData> ExpectedOutputTestCases()
        => BuildTestCases(kind: "expected_output");

    // ── Tests ────────────────────────────────────────────────────────────────

    [TestCaseSource(nameof(InputTestCases))]
    public void Input_ValidatesAgainst_Purl(
        string value, bool shouldFail, string suiteFile, int index)
    {
        var grammar = GrammarLoader.Get();
        var result = grammar.Validate(value, "purl");

        if (shouldFail)
            Assert.That(result.IsSuccess, Is.False,
                $"[{suiteFile}#{index}] input \"{value}\" was expected to FAIL " +
                $"against rule 'purl', but it passed.");
        else
            Assert.That(result.IsSuccess, Is.True,
                $"[{suiteFile}#{index}] input \"{value}\" was expected to PASS " +
                $"against rule 'purl', but it failed: {result.ErrorMessage}");
    }

    [TestCaseSource(nameof(ExpectedOutputTestCases))]
    public void ExpectedOutput_ValidatesAgainst_PurlCanonical(
        string value, bool shouldFail, string suiteFile, int index)
    {
        // Only run when not expected_failure (callers enforce this, but guard defensively)
        Assert.That(shouldFail, Is.False,
            "expected_output tests must never be generated with shouldFail=true.");

        var grammar = GrammarLoader.Get();
        var result = grammar.Validate(value, "purl-canonical");

        Assert.That(result.IsSuccess, Is.True,
            $"[{suiteFile}#{index}] expected_output \"{value}\" was expected to PASS " +
            $"against rule 'purl-canonical', but it failed: {result.ErrorMessage}");
    }

    // ── Test-case builder ────────────────────────────────────────────────────

    /// <param name="kind">"input" or "expected_output"</param>
    private static IEnumerable<TestCaseData> BuildTestCases(string kind)
    {
        var repoRoot = FindRepoRoot();
        var testsuiteRoot = Path.Combine(repoRoot, "tests");

        foreach (var jsonFile in Directory.EnumerateFiles(
                     testsuiteRoot, "*.json", SearchOption.AllDirectories))
        {
            // Derive folder (immediate subfolder of tests/) and file base name
            var relativePath = Path.GetRelativePath(testsuiteRoot, jsonFile);
            var folder = relativePath.Split(Path.DirectorySeparatorChar)[0];
            var fileBase = Path.GetFileNameWithoutExtension(jsonFile);

            JsonObject suite;
            try
            {
                suite = JsonNode.Parse(File.ReadAllText(jsonFile))!.AsObject();
            }
            catch
            {
                continue; // skip non-parseable files
            }

            var tests = suite["tests"]?.AsArray();
            if (tests is null) continue;

            for (int i = 0; i < tests.Count; i++)
            {
                var testItem = tests[i]?.AsObject();
                if (testItem is null) continue;

                bool shouldFail = testItem["expected_failure"]?.GetValue<bool>() == true;

                if (kind == "input")
                {
                    // Validate string `input` values only
                    var inputNode = testItem["input"];
                    if (inputNode?.GetValueKind() != JsonValueKind.String)
                        continue;
                    var value = inputNode.GetValue<string>();

                    var testName = $"grammar.{folder}.{fileBase}.input.{i}";
                    yield return new TestCaseData(value, shouldFail, $"{folder}/{fileBase}.json", i)
                        .SetName(testName);
                }
                else // expected_output
                {
                    // Only when NOT expected_failure
                    if (shouldFail) continue;

                    var outputNode = testItem["expected_output"];
                    if (outputNode?.GetValueKind() != JsonValueKind.String)
                        continue;
                    var value = outputNode.GetValue<string>();

                    var testName = $"grammar.{folder}.{fileBase}.expected_output.{i}";
                    yield return new TestCaseData(value, false, $"{folder}/{fileBase}.json", i)
                        .SetName(testName);
                }
            }
        }
    }

    // ── Repo-root discovery (mirrors GrammarLoader) ──────────────────────────

    private static string FindRepoRoot()
    {
        var envRoot = Environment.GetEnvironmentVariable("PURL_SPEC_REPO_ROOT");
        if (!string.IsNullOrWhiteSpace(envRoot) && Directory.Exists(envRoot))
            return envRoot;

        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            if (File.Exists(Path.Combine(dir.FullName, "docs", "standard", "grammar.md")))
                return dir.FullName;
            dir = dir.Parent;
        }

        throw new InvalidOperationException(
            "Cannot locate repository root. Set the PURL_SPEC_REPO_ROOT environment variable.");
    }
}
