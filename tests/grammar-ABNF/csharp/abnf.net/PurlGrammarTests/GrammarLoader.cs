using Abnf;
using System.Text;
using System.Text.RegularExpressions;

namespace PurlGrammarTests;

/// <summary>
/// Extracts, normalises, and parses the ABNF grammar from docs/standard/grammar.md.
/// </summary>
internal static class GrammarLoader
{
    // RFC 5234 Appendix B core rules referenced by the purl grammar but not defined in it.
    private const string CoreRules = """
        ALPHA  = %x41-5A / %x61-7A
        DIGIT  = %x30-39
        HEXDIG = DIGIT / "A" / "B" / "C" / "D" / "E" / "F"
        """;

    // Helper rules injected to work around the PEG (non-backtracking) behaviour of
    // Abnf.Net for the optional-namespace constructs.  The trick: a repetition unit
    // of the form  1*(seg SEP)  stops naturally before the last segment because the
    // last segment is never followed by SEP (it is followed by "@", "?", "#" or EOS).
    //
    // Original:  [ 1*"/" namespace ] 1*"/" name
    // Rewritten: 1*"/" purl--ns-name
    //   purl--ns-name = 1*(namespace-segment 1*"/") name / name
    //
    // Original (canonical):  [ "/" namespace-canonical ] "/" name
    // Rewritten: "/" purl-canonical--ns-name
    //   purl-canonical--ns-name = 1*(namespace-segment "/") name / name
    private const string PegFixRules = """
        purl--ns-name           = 1*(namespace-segment 1*"/") name / name
        purl-canonical--ns-name = 1*(namespace-segment   "/") name / name
        """;

    private static Grammar? _grammar;
    private static readonly object Lock = new();

    public static Grammar Get()
    {
        if (_grammar is not null) return _grammar;
        lock (Lock)
        {
            if (_grammar is not null) return _grammar;
            _grammar = Load();
        }
        return _grammar;
    }

    private static Grammar Load()
    {
        var repoRoot = FindRepoRoot();
        var grammarMdPath = Path.Combine(repoRoot, "docs", "standard", "grammar.md");
        var grammarMd = File.ReadAllText(grammarMdPath);

        var abnfRaw = ExtractAbnfBlock(grammarMd);
        var abnfNorm = Normalize(abnfRaw);
        var abnfFinal = ApplyPegFixes(abnfNorm) + "\n" + CoreRules;

        return Abnf.Abnf.Parse(abnfFinal);
    }

    // ── ABNF extraction ──────────────────────────────────────────────────────

    private static string ExtractAbnfBlock(string markdown)
    {
        var match = Regex.Match(markdown, @"```abnf\s*\n(.*?)```", RegexOptions.Singleline);
        if (!match.Success)
            throw new InvalidOperationException(
                "No ```abnf fenced code block found in docs/standard/grammar.md");
        return match.Groups[1].Value;
    }

    // ── Normalisation ────────────────────────────────────────────────────────

    /// <summary>
    /// Joins continuation lines, strips inline ABNF comments, and collapses
    /// comment-only continuation lines so that the result is a flat list of
    /// single-line rules suitable for Abnf.Net.
    /// </summary>
    private static string Normalize(string abnfText)
    {
        var lines = abnfText.Split('\n');
        var result = new List<string>();
        var current = new StringBuilder();

        foreach (var rawLine in lines)
        {
            var line = rawLine.TrimEnd('\r', '\n');
            bool isContinuation = line.Length > 0 && (line[0] == ' ' || line[0] == '\t');
            string stripped = StripInlineComment(line).TrimEnd();

            if (isContinuation)
            {
                // Comment-only continuation → skip (avoids creating a spurious blank break)
                var content = stripped.TrimStart();
                if (content.Length > 0)
                    current.Append(' ').Append(content);
            }
            else if (string.IsNullOrWhiteSpace(stripped))
            {
                if (current.Length > 0) { result.Add(current.ToString()); current.Clear(); }
                result.Add(string.Empty);
            }
            else
            {
                if (current.Length > 0) { result.Add(current.ToString()); current.Clear(); }
                current.Append(stripped);
            }
        }

        if (current.Length > 0) result.Add(current.ToString());
        return string.Join("\n", result);
    }

    /// <summary>Strips ABNF inline comment (text after ";" outside a quoted string).</summary>
    private static string StripInlineComment(string line)
    {
        bool inQuote = false;
        for (int i = 0; i < line.Length; i++)
        {
            char c = line[i];
            if (c == '"') inQuote = !inQuote;
            else if (c == ';' && !inQuote) return line[..i].TrimEnd();
        }
        return line;
    }

    // ── PEG compatibility fixes ──────────────────────────────────────────────

    /// <summary>
    /// Rewrites the <c>purl</c> and <c>purl-canonical</c> rules to use the
    /// non-backtracking helper rules, then appends those helpers.
    /// </summary>
    private static string ApplyPegFixes(string abnf)
    {
        // Replace the namespace-optional construct in `purl`
        // Before: purl = scheme ":" *"/" type [ 1*"/" namespace ] 1*"/" name *"/" ...
        // After:  purl = scheme ":" *"/" type 1*"/" purl--ns-name *"/" ...
        abnf = Regex.Replace(
            abnf,
            @"(?m)^(purl\s*=\s*.+?)\s*\[\s*1\*""/""(?:\s*)namespace\s*\]\s*1\*""/""(?:\s*)name\b",
            "$1 1*\"/\" purl--ns-name");

        // Replace the namespace-optional construct in `purl-canonical`
        // Before: purl-canonical = scheme ":" type-canonical [ "/" namespace-canonical ] "/" name ...
        // After:  purl-canonical = scheme ":" type-canonical "/" purl-canonical--ns-name ...
        abnf = Regex.Replace(
            abnf,
            @"(?m)^(purl-canonical\s*=\s*.+?)\s*\[\s*""/""(?:\s*)namespace-canonical\s*\]\s*""/""(?:\s*)name\b",
            "$1 \"/\" purl-canonical--ns-name");

        return abnf + "\n" + PegFixRules;
    }

    // ── Repo-root discovery ──────────────────────────────────────────────────

    private static string FindRepoRoot()
    {
        // Allow CI / local overrides
        var envRoot = Environment.GetEnvironmentVariable("PURL_SPEC_REPO_ROOT");
        if (!string.IsNullOrWhiteSpace(envRoot) && Directory.Exists(envRoot))
            return envRoot;

        // Walk up from the test-assembly directory
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            if (File.Exists(Path.Combine(dir.FullName, "docs", "standard", "grammar.md")))
                return dir.FullName;
            dir = dir.Parent;
        }

        throw new InvalidOperationException(
            "Cannot locate repository root (docs/standard/grammar.md not found). " +
            "Set the PURL_SPEC_REPO_ROOT environment variable.");
    }
}
