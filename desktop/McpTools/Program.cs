using PocketForge.McpTools;
using PocketForge.McpTools.Tools;
using System.Text.Json.Nodes;

namespace PocketForge.McpTools;

// stdio JSON-RPC 2.0 server (newline-delimited) exposing the local tools.
public static class Program
{
    public static int Main(string[] args)
    {
        var mode = args.Length > 0 ? args[0] : "selftest";
        try
        {
            if (mode == "--server") return RunServer();
            if (mode == "--list-tools") { ListToolsToOut(); return 0; }
            if (mode == "selftest") return SelfTest.Run();
            Console.Error.WriteLine("Usage: McpTools [--server | --list-tools] | selftest");
            return 2;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("fatal: " + ex.Message);
            return 2;
        }
    }

    private static void ListToolsToOut()
    {
        foreach (var t in ToolRegistry.Default.All)
            Console.WriteLine(t.Name + "  |  " + t.Description + "  |  args: " + t.ArgsSchema);
    }


    private static int RunServer()
    {
        var guard = new PathGuard(ToolConfig.Load());
        var registry = ToolRegistry.Default;

        var input = new StreamReader(Console.OpenStandardInput(),
            new System.Text.UTF8Encoding(false), false, 1 << 16);
        var writer = new StreamWriter(Console.OpenStandardOutput(),
            new System.Text.UTF8Encoding(false)) { AutoFlush = true };

        string? line;
        while ((line = input.ReadLine()) is not null)
            HandleLine(line, writer, registry, guard);

        return 0;
    }

    private static void HandleLine(string line, StreamWriter writer, ToolRegistry registry, PathGuard guard)
    {
        if (string.IsNullOrWhiteSpace(line)) return;
        JsonNode? node;
        try { node = JsonNode.Parse(line); }
        catch { return; } // malformed line: no id, so no response possible
        if (node is not JsonObject obj) return;

        string? method = obj["method"]?.GetValue<string>();
        if (string.IsNullOrEmpty(method)) return; // malformed or notification
        var id = obj["id"];                       // absent => notification
        var pr = obj["params"];

        if (method == "initialize")
        {
            Respond(writer, id, new JsonObject
            {
                ["protocolVersion"] = "2024-11-05",
                ["capabilities"] = new JsonObject { ["tools"] = new JsonObject() },
                ["serverInfo"] = new JsonObject { ["name"] = "pocketforge-mcp-tools", ["version"] = "0.3.0" }
            });
            return;
        }
        if (method == "notifications/initialized")
            return; // notification, no response

        if (method == "tools/list")
        {
            var arr = new JsonArray();
            foreach (var t in registry.All)
                arr.Add(new JsonObject
                {
                    ["name"] = t.Name,
                    ["description"] = t.Description,
                    ["inputSchema"] = JsonNode.Parse(t.ArgsSchema)
                });
            Respond(writer, id, new JsonObject { ["tools"] = arr });
            return;
        }

        if (method == "tools/call")
        {
            string? name = pr?["name"]?.GetValue<string>();
            if (string.IsNullOrWhiteSpace(name))
            {
                RespondError(writer, id, -32602, "invalid params: missing tool name");
                return;
            }
            var argsNode = pr["arguments"];
            string argsJson = argsNode is null ? "{}" : argsNode.ToJsonString();
            var res = registry.Execute(name, argsJson, new ToolExecContext
            {
                Guard = guard,
            });
            Respond(writer, id, new JsonObject
            {
                ["content"] = new JsonArray
                {
                    new JsonObject { ["type"] = "text", ["text"] = res.Output }
                },
                ["isError"] = !res.Ok
            });
            return;
        }

        RespondError(writer, id, -32601, "unknown method '" + method + "'");
    }

    private static void Respond(StreamWriter writer, JsonNode? id, JsonObject result)
    {
        if (id is null) return;
        writer.WriteLine(new JsonObject
        {
            ["jsonrpc"] = "2.0",
            ["id"] = id.DeepClone(),
            ["result"] = result
        }.ToJsonString());
    }

    private static void RespondError(StreamWriter writer, JsonNode? id, int code, string message)
    {
        if (id is null) return;
        writer.WriteLine(new JsonObject
        {
            ["jsonrpc"] = "2.0",
            ["id"] = id.DeepClone(),
            ["error"] = new JsonObject { ["code"] = code, ["message"] = message }
        }.ToJsonString());
    }
}
