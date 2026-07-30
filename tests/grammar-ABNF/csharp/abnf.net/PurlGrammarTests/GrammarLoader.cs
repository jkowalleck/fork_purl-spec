using Abnf;
using System.Text.RegularExpressions;

namespace PurlGrammarTests;

/// <summary>
/// Extracts and parses the ABNF grammar from docs/standard/grammar.md.
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
        var repoRoot = RepoRoot.Find();
        var grammarMdPath = Path.Combine(repoRoot, "docs", "standard", "grammar.md");
        var grammarMd = File.ReadAllText(grammarMdPath);

        var abnfRaw = ExtractAbnfBlock(grammarMd);
        var abnfNorm = RemoveCommentOnlyContinuations(abnfRaw);
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
    /// Removes comment-only ABNF continuation lines (lines that begin with
    /// whitespace and contain only a <c>;</c> comment).  Abnf.Net handles
    /// inline comments and real multi-line continuation rules natively, but
    /// chokes on continuation lines whose only content is a comment.
    /// </summary>
    private static string RemoveCommentOnlyContinuations(string abnfText)
    {
        var lines = abnfText
            .Split('\n')
            .Where(line =>
            {
                if (line.Length == 0) return true;
                bool isContinuation = line[0] == ' ' || line[0] == '\t';
                return !isContinuation || !line.TrimStart().StartsWith(';');
            });
        return string.Join("\n", lines);
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
}
