using System.Text.Json;

namespace PocketForge.McpTools;

// Workspace roots + path validation. Every tool path argument must resolve
// inside one of the configured roots; anything else is denied.
public sealed class PathGuard
{
    private readonly List<string> _roots = new();

    public IReadOnlyList<string> Roots => _roots;

    public PathGuard(IEnumerable<string> roots)
    {
        foreach (var raw in roots ?? Array.Empty<string>())
        {
            if (string.IsNullOrWhiteSpace(raw)) continue;
            string full;
            try
            {
                full = Path.GetFullPath(Environment.ExpandEnvironmentVariables(raw.Trim()));
            }
            catch
            {
                continue; // skip malformed root entries
            }
            full = full.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            if (full.Length >= 2 && !_roots.Contains(full, StringComparer.OrdinalIgnoreCase))
                _roots.Add(full);
        }
        if (_roots.Count == 0)
            throw new InvalidOperationException("tools.config.json has no valid workspace roots");
    }

    // Canonicalizes and validates a model-supplied path argument.
    public bool TryResolve(string? raw, bool requireExists, out string full, out string error)
    {
        full = "";
        error = "";

        if (string.IsNullOrWhiteSpace(raw)) { error = "blocked: empty path argument"; return false; }
        if (raw!.IndexOf('\0') >= 0) { error = "blocked: invalid path"; return false; }
        if (raw.StartsWith(@"\\") || raw.StartsWith("//")) { error = "blocked: UNC paths are not allowed"; return false; }

        bool isRelative = !Path.IsPathRooted(raw);
        string cand;
        try
        {
            cand = isRelative ? Path.GetFullPath(raw, _roots[0]) : Path.GetFullPath(raw);
        }
        catch
        {
            error = "blocked: invalid path syntax"; return false;
        }

        string norm = cand.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        bool inside = _roots.Any(r =>
            string.Equals(norm, r, StringComparison.OrdinalIgnoreCase) ||
            norm.StartsWith(r + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) ||
            norm.StartsWith(r + Path.AltDirectorySeparatorChar, StringComparison.OrdinalIgnoreCase));
        if (!inside)
        {
            error = "blocked: path is outside the configured workspace roots";
            return false;
        }

        // Reject explicit traversal segments in the raw argument even though
        // GetFullPath already consumed them.
        foreach (var seg in raw.Split('\\', '/'))
        {
            if (seg == "..")
            {
                error = "blocked: path traversal ('..') is not allowed";
                return false;
            }
        }

        if (HasSymlinkOutsideRoot(cand, out var linkErr))
        {
            error = linkErr;
            return false;
        }

        if (requireExists && !File.Exists(norm) && !Directory.Exists(norm))
        {
            error = "blocked: path does not exist (" + norm + ")";
            return false;
        }

        full = norm;
        return true;
    }

    // Walk every ancestor from the target up to the matching root and reject
    // any junction/symlink whose target escapes the roots.
    private bool HasSymlinkOutsideRoot(string path, out string error)
    {
        error = "";
        string? cur = path;
        while (cur is not null)
        {
            string noSep = cur.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            if (_roots.Any(r => string.Equals(noSep, r, StringComparison.OrdinalIgnoreCase)))
                return false; // reached the root itself, no link escaped

            FileSystemInfo info;
            if (Directory.Exists(noSep)) info = new DirectoryInfo(noSep);
            else info = new FileInfo(noSep);

            try
            {
                var target = info.ResolveLinkTarget(returnFinalTarget: true);
                if (target is not null && !InsideAnyRoot(target.FullName))
                {
                    error = "blocked: symlink target is outside the workspace roots";
                    return true;
                }
            }
            catch
            {
                // best effort: Windows may refuse LinkTarget probing on some fs entries
            }

            cur = Path.GetDirectoryName(noSep);
        }
        error = "blocked: path escaped the workspace roots";
        return true;
    }

    private bool InsideAnyRoot(string path) =>
        _roots.Any(r =>
            string.Equals(path, r, StringComparison.OrdinalIgnoreCase) ||
            path.StartsWith(r + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase));

    private static bool SamePath(string a, string b) =>
        string.Equals(
            a.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar),
            b.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar),
            StringComparison.OrdinalIgnoreCase);
}

// Deny-list for files that must never be read or surfaced by any tool.
public static class SecretGuard
{
    private static readonly string[] Patterns =
    {
        "*.pem", "*.key", "*.pfx", "*.p12", "id_rsa*", "id_ed25519*",
        ".env", "*.env", "credentials*", "*.kdbx", "secrets*",
        "appsettings.Secrets.json"
    };

    public static bool IsDenied(string rawOrFullPath)
    {
        if (string.IsNullOrWhiteSpace(rawOrFullPath)) return false;
        string norm = rawOrFullPath.Replace('\\', '/');
        string lower = norm.ToLowerInvariant();
        if (lower.Contains("token") || lower.Contains("password") || lower.Contains("secret"))
            return true;

        string[] segs = norm.Split('/');
        // .git/config matches via the documented pattern applied to relative tail
        for (int i = 0; i < segs.Length; i++)
        {
            string seg = segs[i];
            if (seg.Length == 0) continue;
            if (seg == ".git" && i + 1 < segs.Length && segs[i + 1].Equals("config", StringComparison.OrdinalIgnoreCase))
                return true;
            foreach (var p in Patterns)
                if (WildcardMatch(p, seg, StringComparison.OrdinalIgnoreCase)) return true;
        }
        return false;
    }

    // minimal glob over '*' and '?' only.
    public static bool WildcardMatch(string pattern, string input, StringComparison cmp)
    {
        int pi = 0, ij = 0, star = -1, mark = 0;
        while (ij < input.Length)
        {
            if (pi < pattern.Length && (pattern[pi] == '?' || string.Equals(pattern[pi].ToString(), input[ij].ToString(), cmp)))
            {
                pi++; ij++;
            }
            else if (pi < pattern.Length && pattern[pi] == '*')
            {
                star = pi++; mark = ij;
            }
            else if (star >= 0)
            {
                pi = star + 1; ij = ++mark;
            }
            else return false;
        }
        while (pi < pattern.Length && pattern[pi] == '*') pi++;
        return pi == pattern.Length;
    }
}
