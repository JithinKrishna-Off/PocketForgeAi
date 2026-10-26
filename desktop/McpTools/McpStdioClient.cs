using System.Diagnostics;
using System.Text.Json.Nodes;

namespace PocketForge.McpTools;

// Client for the MCP stdio host: spawns McpTools.exe --server as a child
// process and speaks newline-delimited JSON-RPC 2.0 over its stdin/stdout.
// Responses are matched by request id. Kills the child on Dispose.
public sealed class McpStdioClient : IMcpToolClient, IDisposable
{
    private readonly object _sync = new();
    private readonly string? _exePathOverride;
    private Process? _proc;
    private StreamWriter? _writer;
    private int _nextId = 1;
    private bool _starting;
    private readonly Dictionary<int, TaskCompletionSource<JsonNode?>> _pending = new();

    private static readonly TimeSpan DefaultTimeout = TimeSpan.FromSeconds(10);
    // run_command itself has a 30s budget; give the RPC matching room
    private static readonly TimeSpan ToolCallTimeout = TimeSpan.FromSeconds(30);

    public McpStdioClient(string? exePath = null)
    {
        _exePathOverride = exePath;
    }

    // Variant that also runs the initialize handshake synchronously.
    public static McpStdioClient StartNew(string? exePath = null)
    {
        var c = new McpStdioClient(exePath);
        c.Initialize();
        return c;
    }

    private static string LocateExe()
    {
        // 1) explicit env override
        var env = Environment.GetEnvironmentVariable("PF_MCP_TOOLS_EXE");
        if (!string.IsNullOrEmpty(env) && File.Exists(env)) return env;

        // 2) same directory as the host process (app output)
        var exe = Path.Combine(AppContext.BaseDirectory, "McpTools.exe");
        if (File.Exists(exe)) return exe;

        // 3) dotnet + McpTools.dll next to the host
        var dll = Path.Combine(AppContext.BaseDirectory, "McpTools.dll");
        if (File.Exists(dll))
        {
            var dotnet = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                "dotnet", "dotnet.exe");
            if (!File.Exists(dotnet))
                dotnet = "dotnet"; // let Process resolve from PATH
            return dotnet + "\n" + dll; // internal marker, handled in Spawn
        }
        throw new FileNotFoundException(
            "McpTools server not found; set environment variable PF_MCP_TOOLS_EXE");
    }

    private void Spawn()
    {
        var loc = _exePathOverride ?? LocateExe();
        var psi = new ProcessStartInfo
        {
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true
        };
        if (loc.Contains('\n'))
        {
            var parts = loc.Split('\n', 2);
            psi.FileName = parts[0];
            psi.ArgumentList.Add(parts[1]);
        }
        else
        {
            psi.FileName = loc;
        }
        psi.ArgumentList.Add("--server");
        // tool-side abort switch for run_command; the approval decision itself
        // still lives in ToolLoop + IApprovalUi
        psi.EnvironmentVariables["PF_MCP_RUN_APPROVED"] = "1";

        var proc = Process.Start(psi) ?? throw new InvalidOperationException("could not spawn MCP server");

        var reader = proc.StandardOutput;
        proc.StandardError.ReadLineAsync(); // drain stderr to keep the pipe moving

        _proc = proc;
        _writer = new StreamWriter(proc.StandardInput.BaseStream, new System.Text.UTF8Encoding(false), 4096) { AutoFlush = true };
        _ = Task.Run(() => ReadLoop(reader));
    }

    private async void ReadLoop(StreamReader reader)
    {
        try
        {
            while (await reader.ReadLineAsync() is { } line)
            {
                JsonNode? node;
                try { node = JsonNode.Parse(line); }
                catch { continue; }

                if (node is not JsonObject obj) continue;
                if (obj["id"] is JsonValue vid)
                {
                    int rid;
                    if (vid.TryGetValue<int>(out rid) || int.TryParse(vid.ToString(), out rid))
                    {
                        if (obj["result"] is JsonObject ro && ro["__rpcError"] is not null)
                        {
                            // error-shaped response embedded by RequestSync
                            Fail(rid, ro["message"]?.GetValue<string>() ?? "MCP server error");
                            continue;
                        }
                        Succeed(rid, obj["result"]);
                    }
                }
                // notifications: ignored client-side
            }
        }
        catch
        {
            FailAll("MCP server process died");
        }
    }

    private void Succeed(int rid, JsonNode? result)
    {
        TaskCompletionSource<JsonNode?> tcs;
        lock (_sync)
        {
            if (!_pending.Remove(rid, out tcs!)) return;
        }
        tcs.TrySetResult(result);
    }

    private void Fail(int rid, string message)
    {
        TaskCompletionSource<JsonNode?> tcs;
        lock (_sync)
        {
            if (!_pending.Remove(rid, out tcs!)) return;
        }
        tcs.TrySetResult(new JsonObject { ["__rpcError"] = 1, ["message"] = message });
    }

    private void FailAll(string message)
    {
        lock (_sync)
        {
            foreach (var tcs in _pending.Values)
                tcs.TrySetResult(new JsonObject { ["__rpcError"] = 1, ["message"] = message });
            _pending.Clear();
        }
    }

    // Runs the MCP initialize handshake. Safe to call once; subsequent
    // calls are no-ops.
    public void Initialize()
    {
        lock (_sync)
        {
            if (_proc is not null) return;
            if (!_starting) { _starting = true; Spawn(); }
        }
        var init = new JsonObject
        {
            ["protocolVersion"] = "2024-11-05",
            ["capabilities"] = new JsonObject { ["tools"] = new JsonObject() },
            ["clientInfo"] = new JsonObject { ["name"] = "pocketforge-desktop", ["version"] = "0.3.0" }
        };
        var res = Request("initialize", init, DefaultTimeout);
        if (res is null)
            throw new InvalidOperationException("MCP initialize: no response from server");
        Notify("notifications/initialized");
    }

    // Both request-and-wait (used by Initialize) and fire-and-forget.
    private JsonNode? Request(string method, JsonObject? parameters, TimeSpan timeout)
    {
        EnsureSpawned();
        int id;
        var tcs = new TaskCompletionSource<JsonNode?>(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (_sync)
        {
            id = _nextId++;
            if (_proc is null || _proc.HasExited)
            {
                FailAll("MCP server is not running");
                _pending.Clear();
                throw new IOException("MCP server is not running");
            }
            _pending[id] = tcs;
        }

        var req = new JsonObject { ["jsonrpc"] = "2.0", ["id"] = id, ["method"] = method };
        if (parameters is not null) req["params"] = parameters;

        try
        {
            lock (_sync) _writer!.WriteLine(req.ToJsonString());
        }
        catch
        {
            lock (_sync) _pending.Remove(id);
            throw new IOException("writing to the MCP server pipe failed");
        }

        try
        {
            return tcs.Task.WaitAsync(timeout).GetAwaiter().GetResult();
        }
        finally
        {
            lock (_sync) _pending.Remove(id);
        }
    }

    private void EnsureSpawned()
    {
        lock (_sync)
        {
            if (_proc is null && !_starting) { _starting = true; Spawn(); }
        }
    }

    private void Notify(string method)
    {
        lock (_sync) _writer!.WriteLine(
            new JsonObject { ["jsonrpc"] = "2.0", ["method"] = method }.ToJsonString());
    }

    public async Task<IReadOnlyList<ToolInfo>> ListToolsAsync(CancellationToken ct)
    {
        var res = await Task.Run(() => Request("tools/list", null, DefaultTimeout), ct).ConfigureAwait(false);
        var list = new List<ToolInfo>();
        if (res?["tools"] is JsonArray arr)
        {
            foreach (var t in arr)
            {
                var n = t?["name"]?.GetValue<string>();
                if (string.IsNullOrEmpty(n)) continue;
                list.Add(new ToolInfo(n, t?["description"]?.GetValue<string>() ?? ""));
            }
        }
        return list;
    }

    public async Task<ToolCallResult> CallAsync(string name, string argsJson, CancellationToken ct)
    {
        JsonNode? argsNode;
        try
        {
            argsNode = string.IsNullOrWhiteSpace(argsJson) ? new JsonObject() : JsonNode.Parse(argsJson);
        }
        catch
        {
            return new ToolCallResult(false, "blocked: tool args must be valid JSON");
        }

        var p = new JsonObject { ["name"] = name, ["arguments"] = argsNode ?? new JsonObject() };
        JsonNode? res;
        try
        {
            res = await Task.Run(() => Request("tools/call", p, ToolCallTimeout), ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            return new ToolCallResult(false, "blocked: tool call failed: " + ex.Message);
        }
        if (res is null)
            return new ToolCallResult(false, "blocked: MCP server closed the pipe");
        if (res["__rpcError"] is not null)
            return new ToolCallResult(false, "blocked: " + res["message"]?.GetValue<string>());

        string text = "";
        if (res["content"] is JsonArray content && content.Count > 0)
            text = content[0]?["text"]?.GetValue<string>() ?? "";
        bool isError = res["isError"]?.GetValue<bool>() ?? false;
        return new ToolCallResult(!isError, text);
    }

    public void Dispose()
    {
        try
        {
            lock (_sync)
            {
                _starting = false;
                try { _proc?.Kill(entireProcessTree: true); } catch { }
                _writer?.Dispose();
                _proc?.Dispose();
                _proc = null;
            }
            FailAll("MCP client disposed");
        }
        catch { }
    }
}
