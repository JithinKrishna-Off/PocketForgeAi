using System.Diagnostics;
using System.Text.Json;

namespace PocketForge.McpTools.Tools;

using System.Text;
using System.Text.RegularExpressions;
using PocketForge.McpTools;

public static class SearchFilesTool
{
    public const string Schema =
        """{"type":"object","properties":{"query":{"type":"string","description":"regex to search"},"root":{"type":"integer","description":"workspace root index, default 0"},"glob":{"type":"string","description":"filename filter like '*.kt'"}},"required":["query"]}""";

    private static readonly string[] SkipDirs = { ".git", "bin", "obj", "node_modules", "build", "out", "publish" };
    private const int MaxMatches = 200;

    public static void Register(ToolRegistry reg) =>
        reg.AddTool(new ToolDef
        {
            Name = "search_files",
            Description = "Search file contents under a workspace root with a regex; returns 'path:line: trimmed-line' matches.",
            ArgsSchema = Schema,
            RequiresApproval = false,
            MaxOutputBytes = ToolOutput.GenericCap,
            Execute = Run
        });

    private static ToolCallResult Run(JsonElement args, ToolExecContext ctx)
    {
        if (Args.ReqString(args, "query", out var err) is not { } query)
            return new ToolCallResult(false, err);

        Regex re;
        try
        {
            re = new Regex(query, RegexOptions.None, TimeSpan.FromMilliseconds(250));
        }
        catch (ArgumentException ex)
        {
            return new ToolCallResult(false, "blocked: invalid regex: " + ex.Message);
        }

        int rootIdx = Args.GetInt(args, "root", 0, 0, int.MaxValue, out err);
        if (err.Length > 0) return new ToolCallResult(false, err);
        if (rootIdx >= ctx.Guard.Roots.Count)
            return new ToolCallResult(false, "blocked: root index out of range");

        string? glob = null;
        if (Args.Prop(args, "glob") is { } g)
        {
            if (g.ValueKind != JsonValueKind.String)
                return new ToolCallResult(false, "blocked: arg 'glob' must be a string");
            glob = string.IsNullOrWhiteSpace(g.GetString()) ? null : g.GetString();
        }

        var rootDir = new DirectoryInfo(ctx.Guard.Roots[rootIdx]);
        var matches = new List<string>();
        bool regexTimedOut = false;

        foreach (var file in Walk(rootDir))
        {
            ctx.CancellationToken.ThrowIfCancellationRequested();
            if (matches.Count >= MaxMatches) break;
            if (glob is not null && !SecretGuard.WildcardMatch(glob, file.Name, StringComparison.OrdinalIgnoreCase))
                continue;
            if (SecretGuard.IsDenied(file.FullName)) continue;

            try
            {
                if (LooksBinary(file.FullName)) continue;
                using var reader = new StreamReader(file.FullName);
                int lineNo = 0;
                string? line;
                while ((line = reader.ReadLine()) is not null)
                {
                    lineNo++;
                    if (matches.Count >= MaxMatches) break;
                    try
                    {
                        if (re.IsMatch(line))
                            matches.Add(file.FullName + ":" + lineNo + ": " + line.Trim());
                    }
                    catch (RegexMatchTimeoutException)
                    {
                        regexTimedOut = true;
                        break;
                    }
                }
            }
            catch (UnauthorizedAccessException) { }
            catch (IOException) { }
        }

        if (matches.Count == 0 && regexTimedOut)
            return new ToolCallResult(true, "no matches (regex timed out on large lines)");
        var result = matches.Count == 0 ? "no matches" : string.Join("\n", matches);
        if (matches.Count >= MaxMatches) result += "\n[stopped at " + MaxMatches + " matches]";
        else if (regexTimedOut) result += "\n[regex timed out on some files; results may be partial]";
        return new ToolCallResult(true, result);
    }

    private static IEnumerable<FileInfo> Walk(DirectoryInfo dir)
    {
        var stack = new Stack<DirectoryInfo>();
        stack.Push(dir);
        while (stack.Count > 0)
        {
            var d = stack.Pop();
            try
            {
                foreach (var sub in d.EnumerateDirectories())
                {
                    if (SkipDirs.Contains(sub.Name, StringComparer.OrdinalIgnoreCase)) continue;
                    if (sub.Name.StartsWith(".pf-", StringComparison.OrdinalIgnoreCase)) continue; // temp dirs
                    if (sub.Name.EndsWith("-junk", StringComparison.OrdinalIgnoreCase)) continue;
                    if (sub.Attributes.HasFlag(FileAttributes.ReparsePoint)) continue;
                    stack.Push(sub);
                }
            }
            catch (UnauthorizedAccessException) { }
            catch (IOException) { }
            List<FileInfo> files;
            try { files = d.EnumerateFiles().ToList(); }
            catch { files = new List<FileInfo>(); }
            foreach (var f in files) yield return f;
        }
    }

    // heuristic: any NUL byte in the first 4 KB marks a binary file
    internal static bool LooksBinary(string path)
    {
        var b = new byte[4096];
        using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        int n = fs.Read(b, 0, b.Length);
        for (int i = 0; i < n; i++)
            if (b[i] == 0) return true;
        return false;
    }
}

public static class ReadFileTool
{
    public const string Schema =
        """{"type":"object","properties":{"path":{"type":"string","description":"file path inside a workspace root"},"offsetLines":{"type":"integer","description":"skip this many leading lines first"},"maxBytes":{"type":"integer","description":"max bytes to return, default 65536"}},"required":["path"]}""";

    public const int DefaultMaxBytes = 65536;
    public const int MaxAllowedBytes = 262144;

    public static void Register(ToolRegistry reg) =>
        reg.AddTool(new ToolDef
        {
            Name = "read_file",
            Description = "Read a UTF-8 text file inside a workspace root.",
            ArgsSchema = Schema,
            RequiresApproval = false,
            MaxOutputBytes = 65536,
            Execute = Run
        });

    private static ToolCallResult Run(JsonElement args, ToolExecContext ctx)
    {
        if (Args.ReqString(args, "path", out var err) is not { } raw)
            return new ToolCallResult(false, err);
        if (!ctx.Guard.TryResolve(raw, requireExists: true, out var full, out var gerr))
            return new ToolCallResult(false, gerr);
        if (SecretGuard.IsDenied(full))
            return new ToolCallResult(false, "blocked: potential secret file");

        int offsetLines = Args.GetInt(args, "offsetLines", 0, 0, int.MaxValue, out err);
        if (err.Length > 0) return new ToolCallResult(false, err);
        int maxBytes = Args.GetInt(args, "maxBytes", DefaultMaxBytes, 1, MaxAllowedBytes, out err);
        if (err.Length > 0) return new ToolCallResult(false, err);

        if (Directory.Exists(full))
            return new ToolCallResult(false, "blocked: path is a directory, not a file");

        try
        {
            using var reader = new StreamReader(full);
            int skipped = 0;
            while (skipped < offsetLines && reader.ReadLine() is not null) skipped++;

            var sb = new StringBuilder();
            int budget = maxBytes;
            bool eof = true;
            string? line;
            while ((line = reader.ReadLine()) is not null)
            {
                ctx.CancellationToken.ThrowIfCancellationRequested();
                int cost = Encoding.UTF8.GetByteCount(line) + 1;
                if (cost > budget) { eof = false; break; }
                budget -= cost;
                sb.Append(line).Append('\n');
            }

            var text = sb.ToString();
            if (!eof)
                text += "[note: reached byte budget of " + maxBytes + "; use offsetLines or bigger maxBytes to page]";
            if (skipped >= offsetLines && offsetLines > 0 && sb.Length == 0)
                text += "[note: offsetLines past end of file]";
            return new ToolCallResult(true, text);
        }
        catch (Exception ex)
        {
            return new ToolCallResult(false, "blocked: could not read file: " + ex.Message);
        }
    }
}

public static class ListDirTool
{
    public const string Schema =
        """{"type":"object","properties":{"path":{"type":"string","description":"directory path inside a workspace root"},"depth":{"type":"integer","description":"1-3, default 1"}},"required":["path"]}""";

    public static void Register(ToolRegistry reg) =>
        reg.AddTool(new ToolDef
        {
            Name = "list_dir",
            Description = "List a directory subtree: kind, name, size, last-write time per entry.",
            ArgsSchema = Schema,
            RequiresApproval = false,
            MaxOutputBytes = ToolOutput.GenericCap,
            Execute = Run
        });

    private static ToolCallResult Run(JsonElement args, ToolExecContext ctx)
    {
        if (Args.ReqString(args, "path", out var err) is not { } raw)
            return new ToolCallResult(false, err);
        if (!ctx.Guard.TryResolve(raw, requireExists: true, out var full, out var gerr))
            return new ToolCallResult(false, gerr);
        if (SecretGuard.IsDenied(full))
            return new ToolCallResult(false, "blocked: potential secret file");

        int depth = Args.GetInt(args, "depth", 1, 1, 3, out err);
        if (err.Length > 0) return new ToolCallResult(false, err);

        var di = new DirectoryInfo(full);
        if (!di.Exists || (di.Attributes & FileAttributes.Directory) == 0)
            return new ToolCallResult(false, "blocked: not a directory: " + full);

        var sb = new StringBuilder();
        List(di, depth, sb, ctx.CancellationToken);
        return new ToolCallResult(true, sb.Length == 0 ? "directory is empty" : sb.ToString());
    }

    private static void List(DirectoryInfo dir, int depth, StringBuilder sb, CancellationToken ct)
    {
        ct.ThrowIfCancellationRequested();
        IEnumerable<FileSystemInfo> entries;
        try { entries = dir.EnumerateFileSystemInfos(); }
        catch { return; }

        foreach (var e in entries)
        {
            string kind = e is DirectoryInfo ? "d" : "f";
            string size = e is FileInfo fi ? fi.Length.ToString()
                        : e.Attributes.HasFlag(FileAttributes.ReparsePoint) ? "<link>" : "<dir>";
            sb.Append(kind).Append(' ').Append(e.Name).Append('\t').Append(size)
              .Append('\t').Append(e.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss")).Append('\n');
            if (e is DirectoryInfo d && depth > 1 && !e.Attributes.HasFlag(FileAttributes.ReparsePoint))
                List(d, depth - 1, sb, ct);
        }
    }
}

public static class GitStatusTool
{
    public const string Schema =
        """{"type":"object","properties":{"root":{"type":"integer","description":"workspace root index, default 0"}}}""";

    public static void Register(ToolRegistry reg) =>
        reg.AddTool(new ToolDef
        {
            Name = "git_status",
            Description = "Show git status --porcelain and git diff --stat for a workspace root.",
            ArgsSchema = Schema,
            RequiresApproval = false,
            MaxOutputBytes = ToolOutput.GenericCap,
            Execute = Run
        });

    private static ToolCallResult Run(JsonElement args, ToolExecContext ctx)
    {
        int rootIdx = Args.GetInt(args, "root", 0, 0, int.MaxValue, out var err);
        if (err.Length > 0) return new ToolCallResult(false, err);
        if (rootIdx >= ctx.Guard.Roots.Count)
            return new ToolCallResult(false, "blocked: root index out of range");
        string cwd = ctx.Guard.Roots[rootIdx];

        var sb = new StringBuilder();
        var st = ProcRunner.Run("git", new[] { "status", "--porcelain" }, cwd, 20, ctx.CancellationToken);
        sb.Append("status:\n").Append(st.Output).Append('\n');
        var diff = ProcRunner.Run("git", new[] { "diff", "--stat" }, cwd, 20, ctx.CancellationToken);
        sb.Append("diff-stat:\n").Append(diff.Output).Append('\n');

        bool allOk = st.Ok && diff.Ok;
        return new ToolCallResult(allOk, sb.ToString());
    }
}

public static class RunCommandTool
{
    public const string Schema =
        """{"type":"object","properties":{"command":{"type":"string","description":"one of: git, dotnet, gradlew.bat"},"args":{"type":"array","items":{"type":"string"},"description":"arguments passed verbatim"},"root":{"type":"integer","description":"workspace root index for the working dir, default 0"}},"required":["command","args"]}""";

    public static readonly string[] Allowlist = { "git", "dotnet", "gradlew.bat" };

    public static void Register(ToolRegistry reg) =>
        reg.AddTool(new ToolDef
        {
            Name = "run_command",
            Description = "Run an allowlisted command (git/dotnet/gradlew.bat) inside a workspace root. Always requires explicit user approval.",
            ArgsSchema = Schema,
            RequiresApproval = true,
            MaxOutputBytes = ToolOutput.GenericCap,
            Execute = Run
        });

    private static ToolCallResult Run(JsonElement args, ToolExecContext ctx)
    {
        // tool-side abort switch: deny by default unless the client process
        // (which owns the approval UI) opted in for this server instance
        if (Environment.GetEnvironmentVariable("PF_MCP_RUN_APPROVED") != "1")
            return new ToolCallResult(false, "blocked: run_command denied (approval not enabled for this session)");
        return RunCore.Run(args, ctx);
    }
}

public static class RunCore
{
    public static ToolCallResult Run(JsonElement args, ToolExecContext ctx)
    {
        if (Args.ReqString(args, "command", out var err) is not { } command)
            return new ToolCallResult(false, err);

        // strict allowlist on the exe NAME only (never a path from the model)
        var cmd = command.Trim().ToLowerInvariant();
        if (!RunCommandTool.Allowlist.Contains(cmd))
            return new ToolCallResult(false,
                "blocked: command '" + command + "' is not in the allowlist (git, dotnet, gradlew.bat)");

        if (Args.Prop(args, "args") is not { } arrEl || arrEl.ValueKind != JsonValueKind.Array)
            return new ToolCallResult(false, "blocked: arg 'args' must be an array of strings");
        var argList = new List<string>();
        foreach (var e in arrEl.EnumerateArray())
        {
            if (e.ValueKind != JsonValueKind.String)
                return new ToolCallResult(false, "blocked: arg 'args' must be an array of strings");
            argList.Add(e.GetString()!);
        }

        int rootIdx = Args.GetInt(args, "root", 0, 0, int.MaxValue, out err);
        if (err.Length > 0) return new ToolCallResult(false, err);
        if (rootIdx >= ctx.Guard.Roots.Count)
            return new ToolCallResult(false, "blocked: root index out of range");
        string cwd = ctx.Guard.Roots[rootIdx];

        string exe;
        try
        {
            exe = cmd == "gradlew.bat"
                ? Path.Combine(cwd, "gradlew.bat")   // must exist inside the root
                : ProcRunner.WhereExe(cmd) ?? throw new FileNotFoundException(cmd + " not found on PATH");
        }
        catch (Exception ex)
        {
            return new ToolCallResult(false, "blocked: could not resolve executable: " + ex.Message);
        }

        var res = ProcRunner.Run(exe, argList, cwd, 30, ctx.CancellationToken);
        return new ToolCallResult(res.Ok, res.Output);
    }
}

public static class ProcRunner
{
    public static (bool Ok, string Output) Run(string exe, IReadOnlyList<string> args, string cwd, int timeoutSeconds, CancellationToken ct)
    {
        try
        {
            using var p = new Process();
            p.StartInfo.FileName = exe;
            p.StartInfo.WorkingDirectory = cwd;
            p.StartInfo.RedirectStandardOutput = true;
            p.StartInfo.RedirectStandardError = true;
            p.StartInfo.UseShellExecute = false;
            p.StartInfo.CreateNoWindow = true;
            foreach (var a in args) p.StartInfo.ArgumentList.Add(a);
            p.StartInfo.StandardOutputEncoding = System.Text.Encoding.UTF8;
            p.StartInfo.StandardErrorEncoding = System.Text.Encoding.UTF8;

            if (!p.Start())
                return (false, "blocked: could not start " + exe);

            var outTask = p.StandardOutput.ReadToEndAsync(ct);
            var errTask = p.StandardError.ReadToEndAsync(ct);
            try
            {
                if (!p.WaitForExit(timeoutSeconds * 1000))
                {
                    try { p.Kill(entireProcessTree: true); } catch { }
                    return (false, "[timed out after " + timeoutSeconds + "s, process killed]\n" + outTask.Result + errTask.Result);
                }
            }
            catch (OperationCanceledException)
            {
                try { p.Kill(entireProcessTree: true); } catch { }
                throw;
            }

            var text = outTask.Result;
            if (errTask.Result.Length > 0) text += errTask.Result;
            int code = p.ExitCode;
            return (code == 0, "exit=" + code + "\n" + text);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            return (false, "blocked: process failed: " + ex.Message);
        }
    }

    // resolve <name>.exe via PATH (no shell involved)
    public static string? WhereExe(string name)
    {
        var ext = Path.GetExtension(name).Length > 0 ? name : name + ".exe";
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';', StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                var cand = Environment.ExpandEnvironmentVariables(dir.Trim());
                var full = Path.Combine(cand, ext);
                if (File.Exists(full)) return full;
            }
            catch { }
        }
        return null;
    }
}
