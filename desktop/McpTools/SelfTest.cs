using PocketForge.McpTools;
using PocketForge.McpTools.Tools;
using System.Text.Json.Nodes;

namespace PocketForge.McpTools;

// Real-execution self-test: prints one PASS/FAIL line per check and returns
// non-zero if anything failed. Creates and deletes a temp dir inside the
// first workspace root.
public static class SelfTest
{
    private static int _pass;
    private static int _fail;
    private static string? _tempRoot;
    private static string? _fixtureRoot;
    private static readonly List<Action> _cleanup = new();

    private static void Check(string name, bool cond, string detail = "")
    {
        if (cond)
        {
            _pass++;
            Console.WriteLine("PASS " + name);
        }
        else
        {
            _fail++;
            Console.WriteLine("FAIL " + name + (detail.Length > 0 ? "  // " + detail : ""));
        }
    }

    public static int Run()
    {
        Console.WriteLine("McpTools self-test");
        try
        {
            var guard0 = new PathGuard(ToolConfig.Load());
            _tempRoot = Path.Combine(guard0.Roots[0], ".pf-mcp-selftest-" + Guid.NewGuid().ToString("N")[..8]);
            Directory.CreateDirectory(_tempRoot!);
            _cleanup.Add(() => { try { Directory.Delete(_tempRoot!, true); } catch { } });

            // search fixtures live outside any ".pf-*" dir because the tool
            // skips those (they would be invisible otherwise)
            _fixtureRoot = Path.Combine(guard0.Roots[0], "mcp-selftest-fixture-" + Guid.NewGuid().ToString("N")[..8]);
            Directory.CreateDirectory(_fixtureRoot!);
            _cleanup.Add(() => { try { Directory.Delete(_fixtureRoot!, true); } catch { } });

            RunPathGuardTests(guard0);
            RunReadListTests(guard0);
            RunSearchTests(guard0);
            RunCommandTests(guard0).GetAwaiter().GetResult();
            RunMcpRoundTrip(guard0).GetAwaiter().GetResult();
            RunToolLoopTest(guard0).GetAwaiter().GetResult();
        }
        catch (Exception ex)
        {
            Check("selftest harness", false, ex.ToString());
        }
        finally
        {
            foreach (var a in _cleanup) a();
        }

        Console.WriteLine("PASS: " + _pass + "  FAIL: " + _fail);
        return _fail == 0 ? 0 : 1;
    }

    // ---------- PathGuard / SecretGuard ----------

    private static void RunPathGuardTests(PathGuard guard)
    {
        Check("pathguard.traversal", !guard.TryResolve(@"..\..\secret", true, out _, out _));

        Check("pathguard.abs-outside-root", !guard.TryResolve(@"C:\Windows\win.ini", true, out _, out _));

        Check("pathguard.unc", !guard.TryResolve(@"\\server\share\x.txt", false, out _, out _));

        Check("pathguard.nonexistent", !guard.TryResolve(
            Path.Combine(_tempRoot!, "no-such-file.txt"), true, out _, out _));

        string rel = Path.Combine(Path.GetFileName(_tempRoot!), "README.md");
        bool okFile = guard.TryResolve(rel, true, out var okFull, out var err);
        if (!okFile)
        {
            // no README.md at repo root -> fall back to a temp file created now
            var tmp = Path.Combine(_tempRoot!, "selftest-seed.txt");
            File.WriteAllText(tmp, "seed");
            okFile = guard.TryResolve(Path.Combine(Path.GetFileName(_tempRoot!), "selftest-seed.txt"),
                true, out okFull, out err);
        }
        Check("pathguard.inside-root-ok", okFile, err);

        Check("secretguard.env", SecretGuard.IsDenied(@"C:\repo\.env"));
        Check("secretguard.doteEnv", SecretGuard.IsDenied(@"staging.env"));
        Check("secretguard.id_rsa", SecretGuard.IsDenied(@"C:\repo\home\id_rsa"));
        Check("secretguard.pem", SecretGuard.IsDenied(@"server.pem"));
        Check("secretguard.token-in-name", SecretGuard.IsDenied(@"C:\repo\AUTH_TOKEN_CACHE.txt"));
        Check("secretguard.git-config", SecretGuard.IsDenied(@"C:\repo\.git\config"));
        Check("secretguard.clean-name-ok", !SecretGuard.IsDenied(@"notes.txt"));
        Check("secretguard.wildcard", SecretGuard.WildcardMatch("*.kt", "Main.kt", StringComparison.OrdinalIgnoreCase)
            && !SecretGuard.WildcardMatch("*.kt", "Main.java", StringComparison.OrdinalIgnoreCase));
    }

    // ---------- read_file / list_dir ----------

    private static void RunReadListTests(PathGuard guard)
    {
        var registry = ToolRegistry.Build(ToolConfig.Load());
        var ctx = new ToolExecContext
        {
            Guard = guard,
            CancellationToken = CancellationToken.None
        };

        var a = Path.Combine(_tempRoot!, "page-a.txt");
        File.WriteAllText(a, "l1\nl2\nl3\nl4\nl5\n");
        var rf = registry.Execute("read_file", "{\"path\":" + Js(a) + ",\"offsetLines\":2}", ctx);
        Check("read_file.offset", rf.Ok && rf.Output.Contains("l3") && !rf.Output.Contains("l1"), rf.Output);

        File.WriteAllText(Path.Combine(_tempRoot!, "big.txt"),
            string.Concat(Enumerable.Repeat("x", 200)) + "\n");
        var rb = registry.Execute("read_file", "{\"path\":" + Js(a) + ",\"maxBytes\":10}", ctx);
        Check("read_file.bytenote", rb.Ok && rb.Output.Contains("byte budget"), rb.Output);

        var rd = registry.Execute("read_file", "{\"path\":" + Js(@"C:\Windows\win.ini") + "}", ctx);
        Check("read_file.outside-root-denied", !rd.Ok, rd.Output);

        var secretPath = Path.Combine(_tempRoot!, ".env");
        File.WriteAllText(secretPath, "DATA_URL=example\n");
        var secret = registry.Execute("read_file", "{\"path\":" + Js(secretPath) + "}", ctx);
        Check("read_file.secret-denied", !secret.Ok && secret.Output.Contains("secret"), secret.Output);

        var ld = registry.Execute("list_dir", "{\"path\":" + Js(_tempRoot!) + ",\"depth\":2}", ctx);
        Check("list_dir.entries", ld.Ok && ld.Output.Contains("page-a.txt") && ld.Output.Contains("big.txt"), ld.Output);

        var ldNo = registry.Execute("list_dir", "{\"path\":" + Js(@"C:\Windows\") + "}", ctx);
        Check("list_dir.outside-root-denied", !ldNo.Ok, ldNo.Output);
    }

    // ---------- search_files ----------

    private static void RunSearchTests(PathGuard guard)
    {
        var registry = ToolRegistry.Build(ToolConfig.Load());
        var ctx = new ToolExecContext { Guard = guard, CancellationToken = CancellationToken.None };

        // tokens chosen to be unique: they appear nowhere else in the repo.
        // composed at runtime so this source file itself never matches the query
        var tok = "ZFQ" + "LF";
        File.WriteAllText(Path.Combine(_fixtureRoot!, "s1.kt"), "val " + tok + " = 1\nplain line\nval " + tok + " = 2\n");
        var binName = tok.ToLowerInvariant() + "binary.bin";
        File.WriteAllText(Path.Combine(_fixtureRoot!, binName), "\0\0\0" + tok + " inside binary\0");
        var junkDir = tok.ToLowerInvariant() + "-junk";
        Directory.CreateDirectory(Path.Combine(_fixtureRoot!, junkDir));
        File.WriteAllText(Path.Combine(_fixtureRoot!, junkDir, "s3.js"), tok + " in third-party\n");

        var res = registry.Execute("search_files",
            "{\"query\":\"" + tok + "\",\"glob\":\"*.kt\"}", ctx);
        Check("search.found-glob", res.Ok && RegexCount(res.Output, tok) == 2, res.Output);

        var res2 = registry.Execute("search_files", "{\"query\":\"" + tok + "\"}", ctx);
        Check("search.skips-binary-and-junk-dirs",
            res2.Ok && !res2.Output.Contains(binName) && !res2.Output.Contains(junkDir), res2.Output);

        var res3 = registry.Execute("search_files", "{\"query\":\"(\"}", ctx);
        Check("search.bad-regex", !res3.Ok, res3.Output);

        var res4 = registry.Execute("search_files", "{\"query\":\"NEEDLE\",\"root\":99}", ctx);
        Check("search.root-out-of-range", !res4.Ok, res4.Output);
    }

    private static int RegexCount(string haystack, string needle) =>
        System.Text.RegularExpressions.Regex.Matches(haystack, System.Text.RegularExpressions.Regex.Escape(needle)).Count;

    // ---------- run_command ----------

    private static async Task RunCommandTests(PathGuard guard)
    {
        var registry = ToolRegistry.Build(ToolConfig.Load());

        // without an approval UI: ToolLoop must deny
        var fakeModel = new ScriptedModelClient(new[]
        {
            "hmm let me run it\n```pf-tool\n{\"tool\":\"run_command\",\"args\":{\"command\":\"git\",\"args\":[\"--version\"]}}\n```"
        });
        var deniedEvents = new List<LoopEvent>();
        var deniedClient = new RecordingToolClient(null); // never actually called: approval gates first
        var loop = new ToolLoop(fakeModel, deniedClient, null);
        await foreach (var ev in loop.RunAsync("please run git --version", null, CancellationToken.None))
            deniedEvents.Add(ev);
        Check("runcmd.denied-without-approval",
            deniedEvents.Any(e => e.Kind == LoopEventKind.Denied && e.ToolName == "run_command") &&
            deniedEvents.Last().Kind == LoopEventKind.Done &&
            deniedEvents.Any(e => e.Kind == LoopEventKind.AssistantDelta),
            string.Join("|", deniedEvents.Select(e => e.Kind)));

        // with always-approve UI + real MCP client over the real server
        using var server = new McpStdioClient(ResolveSelfExe());
        server.Initialize();
        var fakeApprover = new AlwaysApproveUi();
        var fakeModel2 = new ScriptedModelClient(new[]
        {
            "running version\n```pf-tool\n{\"tool\":\"run_command\",\"args\":{\"command\":\"git\",\"args\":[\"--version\"]}}\n```"
        });
        var loop2 = new ToolLoop(fakeModel2, server, fakeApprover);
        var events2 = new List<LoopEvent>();
        await foreach (var ev in loop2.RunAsync("please run git --version", null, CancellationToken.None))
            events2.Add(ev);
        var toolRes2 = events2.FirstOrDefault(e => e.Kind == LoopEventKind.ToolResult);
        Check("runcmd.approved-executes",
            toolRes2 is not null && toolRes2.Ok && (toolRes2.Result ?? "").Contains("git version"),
            toolRes2?.Result ?? "(no tool result)");

        // non-allowlisted command denied by the server-side tool itself
        var reg2 = ToolRegistry.Build(ToolConfig.Load());
        var cmdRes = reg2.Execute("run_command",
            "{\"command\":\"powershell.exe\",\"args\":[\"-c\",\"dir\"]}",
            new ToolExecContext { Guard = guard, CancellationToken = CancellationToken.None });
        Check("runcmd.allowlist-no-shell", !cmdRes.Ok, cmdRes.Output);
    }

    // ---------- MCP stdio round trip ----------

    private static async Task RunMcpRoundTrip(PathGuard guard)
    {
        using var server = new McpStdioClient(ResolveSelfExe());
        server.Initialize();

        var tools = await server.ListToolsAsync(CancellationToken.None);
        Check("mcp.tools-list", tools.Any(t => t.Name == "search_files") && tools.Any(t => t.Name == "read_file"),
            "[" + string.Join(",", tools.Select(t => t.Name)) + "]");

        var tempFile = Path.Combine(_tempRoot!, "mcp-roundtrip.txt");
        File.WriteAllText(tempFile, "hello roundtrip");
        var call = await server.CallAsync("read_file",
            "{\"path\":" + System.Text.Json.JsonSerializer.Serialize(tempFile) + "}",
            CancellationToken.None);
        Check("mcp.tools-call-read", call.Ok && call.Output.Contains("hello roundtrip"), call.Output);

        var bad = await server.CallAsync("no_such_tool", "{}", CancellationToken.None);
        Check("mcp.unknown-tool", !bad.Ok, bad.Output);
    }

    private static string Js(string s) => System.Text.Json.JsonSerializer.Serialize(s);

    // ---------- ToolLoop end-to-end (fake model + real MCP server) ----------

    private static async Task RunToolLoopTest(PathGuard guard)
    {
        var needleFile = Path.Combine(_fixtureRoot!, "needle.txt");
        File.WriteAllText(needleFile, "alpha\nQZKX_MARK beta\nalpha\n");

        var fakeModel = new ScriptedModelClient(new[]
        {
            "I will search for it.\n```pf-tool\n{\"tool\":\"search_files\",\"args\":{\"query\":\"QZKX_MARK\"}}\n```",
            "DONE with the search, found QZKX_MARK in " + Path.GetFileName(needleFile) + "."
        });
        var recorder = new RecordingApprovalUi();

        using var server = new McpStdioClient(ResolveSelfExe());
        server.Initialize();
        var loop = new ToolLoop(fakeModel, server, recorder);
        var events = new List<LoopEvent>();
        await foreach (var ev in loop.RunAsync("find QZKX_MARK", null, CancellationToken.None))
            events.Add(ev);

        Check("loop.event-sequence",
            SequenceMatches(events,
                LoopEventKind.AssistantDelta, LoopEventKind.ToolRequested, LoopEventKind.ToolResult),
            string.Join("|", events.Select(e => e.Kind)));
        Check("loop.tool-result-ok",
            events.Any(e => e.Kind == LoopEventKind.ToolResult && e.Ok && (e.Result ?? "").Contains(needleFile)),
            "(events above)");
        Check("loop.done-last", events.Last().Kind == LoopEventKind.Done && events.Last().Delta!.Contains("QZKX_MARK"),
            events.Last().Delta);
        Check("loop.final-user-has-tool-result",
            fakeModel.Requests.Count >= 2 &&
            fakeModel.Requests.Last()[^1].Role == "user" &&
            fakeModel.Requests.Last()[^1].Content.StartsWith("TOOL RESULT name=search_files status=ok"),
            string.Join("\\n", fakeModel.Requests.Last().Select(m => m.Role + ":" + m.Content[..Math.Min(40, m.Content.Length)])));
        Check("loop.system-prompt-has-protocol",
            fakeModel.Requests[0][0].Role == "system" &&
            fakeModel.Requests[0][0].Content.Contains("```pf-tool"),
            "(see request dump)");
        Check("loop.approval-not-asked-for-read-only", recorder.Calls.Count == 0);
    }

    private static bool SequenceMatches(List<LoopEvent> events, params LoopEventKind[] wanted)
    {
        int wi = 0;
        foreach (var e in events)
            if (wi < wanted.Length && e.Kind == wanted[wi]) wi++;
        return wi == wanted.Length;
    }

    private static string ResolveSelfExe()
    {
        // selftest may be launched by the exe apphost or by dotnet + dll
        var main = Environment.ProcessPath;
        if (main is not null && main.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
            return main;
        if (main is not null && main.EndsWith(".dll", StringComparison.OrdinalIgnoreCase))
        {
            var dotnet = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "dotnet", "dotnet.exe");
            if (File.Exists(dotnet)) return dotnet.Length > 0 ? dotnet + "\n" + main : main!;
            // fall back to dotnet from PATH
            var exe = Path.Combine(AppContext.BaseDirectory, "McpTools.exe");
            if (File.Exists(exe)) return exe;
        }
        throw new InvalidOperationException("cannot resolve self exe for MCP spawn");
    }

    // ---------- fakes ----------

    private sealed class ScriptedModelClient : IModelClient
    {
        private readonly string[] _replies;
        public List<List<(string Role, string Content)>> Requests { get; } = new();

        public ScriptedModelClient(string[] replies) => _replies = replies;

        public async IAsyncEnumerable<string> StreamAsync(
            IReadOnlyList<(string Role, string Content)> messages,
            [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken ct = default)
        {
            Requests.Add(messages.ToList());
            var idx = Math.Min(Requests.Count - 1, _replies.Length - 1);
            foreach (var part in _replies[idx].Chunk(_replies[idx].Length / 3 + 1))
            {
                await Task.Yield();
                yield return new string(part);
            }
        }

        public Task CancelAsync(CancellationToken ct) => Task.CompletedTask;
    }

    // records everything; returns failure unless approve is set
    private sealed class RecordingToolClient : IMcpToolClient
    {
        public List<(string Name, string Args)> Calls { get; } = new();
        private readonly ToolCallResult _result;

        public RecordingToolClient(ToolCallResult? result)
        {
            _result = result ?? new ToolCallResult(false, "unused");
        }

        public Task<IReadOnlyList<ToolInfo>> ListToolsAsync(CancellationToken ct) =>
            Task.FromResult<IReadOnlyList<ToolInfo>>(Array.Empty<ToolInfo>());

        public Task<ToolCallResult> CallAsync(string name, string argsJson, CancellationToken ct)
        {
            Calls.Add((name, argsJson));
            return Task.FromResult(_result);
        }
    }

    private sealed class AlwaysApproveUi : IApprovalUi
    {
        public Task<bool> ApproveAsync(string tool, string argsJson) => Task.FromResult(true);
    }

    private sealed class RecordingApprovalUi : IApprovalUi
    {
        public List<(string Tool, string Args)> Calls { get; } = new();
        public Task<bool> ApproveAsync(string tool, string argsJson)
        {
            Calls.Add((tool, argsJson));
            return Task.FromResult(true);
        }
    }
}


