using System.Text.Json;

namespace PocketForge.McpTools;

public sealed class ToolExecContext
{
    public required PathGuard Guard { get; init; }
    public CancellationToken CancellationToken { get; init; }
}

public delegate ToolCallResult ToolHandler(JsonElement args, ToolExecContext ctx);

public sealed class ToolDef
{
    public required string Name { get; init; }
    public required string Description { get; init; }
    public required string ArgsSchema { get; init; }   // JSON Schema object as text
    public required bool RequiresApproval { get; init; }
    public required int MaxOutputBytes { get; init; }
    public required Func<JsonElement, ToolExecContext, ToolCallResult> Execute { get; init; }
}

public sealed class ToolRegistry
{
    private readonly List<ToolDef> _order = new();
    private readonly Dictionary<string, ToolDef> _map = new(StringComparer.OrdinalIgnoreCase);

    public static ToolRegistry Default { get; } = Build(ToolConfig.Load());

    public void AddTool(ToolDef def)
    {
        if (string.IsNullOrWhiteSpace(def.Name)) throw new ArgumentException("tool name empty");
        if (_map.ContainsKey(def.Name)) throw new ArgumentException("duplicate tool name: " + def.Name);
        _map[def.Name] = def;
        _order.Add(def);
    }

    public bool TryGet(string name, out ToolDef def) => _map.TryGetValue(name ?? "", out def!);
    public IReadOnlyList<ToolDef> All => _order;

    // Executes a tool by name with raw args JSON. Never throws; always a ToolCallResult.
    public ToolCallResult Execute(string name, string? argsJson, ToolExecContext ctx)
    {
        try
        {
            if (!TryGet(name, out var def))
                return new ToolCallResult(false, "blocked: unknown tool '" + name + "'");

            JsonElement args;
            try
            {
                if (string.IsNullOrWhiteSpace(argsJson)) argsJson = "{}";
                using var doc = JsonDocument.Parse(argsJson);
                args = doc.RootElement.Clone();
            }
            catch
            {
                return new ToolCallResult(false, "blocked: tool args must be valid JSON");
            }

            var res = def.Execute(args, ctx);
            return new ToolCallResult(res.Ok, ToolOutput.Cap(res.Output, def.MaxOutputBytes));
        }
        catch (OperationCanceledException)
        {
            return new ToolCallResult(false, "blocked: operation cancelled");
        }
        catch (Exception ex)
        {
            return new ToolCallResult(false, "blocked: tool failure: " + ex.Message);
        }
    }

    public static ToolRegistry Build(IReadOnlyList<string> roots)
    {
        var reg = new ToolRegistry();
        Tools.SearchFilesTool.Register(reg);
        Tools.ReadFileTool.Register(reg);
        Tools.ListDirTool.Register(reg);
        Tools.GitStatusTool.Register(reg);
        Tools.RunCommandTool.Register(reg);
        return reg;
    }

    public string BuildProtocolSection()
    {
        var sb = new System.Text.StringBuilder();
        sb.Append("You can use tools on the user's Windows laptop. Available tools and their JSON arguments:\n");
        foreach (var t in _order)
        {
            sb.Append(t.Name).Append(": ").Append(t.Description)
              .Append(" | args: ").Append(t.ArgsSchema).Append('\n');
        }
        sb.Append("To call a tool, END your reply with one fenced block exactly:\n");
        sb.Append("```pf-tool\n{\"tool\":\"<name>\",\"args\":{...}}\n```\n");
        sb.Append("Use at most one tool call per reply. After a message beginning with TOOL RESULT, continue the task. When done, reply normally with no pf-tool block.");
        return sb.ToString();
    }
}

public static class ToolConfig
{
    public static string ConfigPath => Path.Combine(AppContext.BaseDirectory, "tools.config.json");

    // Defaults resolve the PocketForgeAI repo by walking up from the exe, so the
    // layout survives the desktop folder moving inside the Android repo.
    public static IReadOnlyList<string> Defaults
    {
        get
        {
            var list = new List<string>();
            try
            {
                var dir = new DirectoryInfo(AppContext.BaseDirectory);
                for (int i = 0; i < 10 && dir != null; i++, dir = dir.Parent)
                {
                    if (File.Exists(Path.Combine(dir.FullName, "settings.gradle.kts")))
                    {
                        list.Add(Path.Combine(dir.FullName, "desktop"));
                        list.Add(dir.FullName);
                        break;
                    }
                }
            }
            catch
            {
                // fall through to the hardcoded fallback
            }
            if (list.Count == 0)
            {
                list.Add(@"C:\Users\ASUS\Desktop\PocketForgeAI\desktop");
                list.Add(@"C:\Users\ASUS\Desktop\PocketForgeAI");
            }
            return list;
        }
    }

    public static IReadOnlyList<string> Load()
    {
        try
        {
            if (File.Exists(ConfigPath))
            {
                using var doc = JsonDocument.Parse(File.ReadAllText(ConfigPath));
                if (doc.RootElement.ValueKind == JsonValueKind.Object &&
                    doc.RootElement.TryGetProperty("workspaceRoots", out var arr) &&
                    arr.ValueKind == JsonValueKind.Array && arr.GetArrayLength() > 0)
                {
                    var list = new List<string>();
                    foreach (var e in arr.EnumerateArray())
                        if (e.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(e.GetString()))
                            list.Add(e.GetString()!);
                    if (list.Count > 0) return list;
                }
            }
        }
        catch
        {
            // malformed config falls back to defaults
        }
        return Defaults;
    }
}

public static class ToolOutput
{
    public const int GenericCap = 16 * 1024;

    public static string Cap(string s, int cap)
    {
        if (string.IsNullOrEmpty(s)) return "";
        var bytes = System.Text.Encoding.UTF8.GetBytes(s);
        if (bytes.Length <= cap) return s;
        while (cap > 0 && (bytes[cap] & 0xC0) == 0x80) cap--;
        return System.Text.Encoding.UTF8.GetString(bytes, 0, cap) +
               "\n[truncated: output exceeded " + cap + " bytes]";
    }
}

internal static class Args
{
    public static JsonElement? Prop(JsonElement obj, string name)
    {
        if (obj.ValueKind != JsonValueKind.Object) return null;
        foreach (var p in obj.EnumerateObject())
            if (p.Name.Equals(name, StringComparison.OrdinalIgnoreCase))
                return p.Value;
        return null;
    }

    public static string? ReqString(JsonElement obj, string name, out string err)
    {
        err = "";
        if (Prop(obj, name) is not { } el)
        {
            err = "blocked: missing required arg '" + name + "'";
            return null;
        }
        if (el.ValueKind != JsonValueKind.String)
        {
            err = "blocked: arg '" + name + "' must be a string";
            return null;
        }
        var s = el.GetString();
        if (string.IsNullOrWhiteSpace(s))
        {
            err = "blocked: arg '" + name + "' must not be empty";
            return null;
        }
        return s;
    }

    public static int GetInt(JsonElement obj, string name, int def, int min, int max, out string err)
    {
        err = "";
        if (Prop(obj, name) is not { } el) return def;
        if (el.ValueKind == JsonValueKind.Number && el.TryGetInt32(out var v))
        {
            if (v < min || v > max)
            {
                err = "blocked: arg '" + name + "' must be between " + min + " and " + max;
                return def;
            }
            return v;
        }
        err = "blocked: arg '" + name + "' must be an integer";
        return def;
    }
}
