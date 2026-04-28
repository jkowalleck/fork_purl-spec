namespace PurlGrammarTests;

/// <summary>
/// Locates the repository root directory at runtime.
/// </summary>
internal static class RepoRoot
{
    private static string? _root;

    public static string Find()
    {
        if (_root is not null) return _root;

        // Allow CI / local overrides via environment variable
        var envRoot = Environment.GetEnvironmentVariable("PURL_SPEC_REPO_ROOT");
        if (!string.IsNullOrWhiteSpace(envRoot) && Directory.Exists(envRoot))
            return _root = envRoot;

        // Walk up from the test-assembly directory until grammar.md is found
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            if (File.Exists(Path.Combine(dir.FullName, "docs", "standard", "grammar.md")))
                return _root = dir.FullName;
            dir = dir.Parent;
        }

        throw new InvalidOperationException(
            "Cannot locate repository root (docs/standard/grammar.md not found). " +
            "Set the PURL_SPEC_REPO_ROOT environment variable.");
    }
}
