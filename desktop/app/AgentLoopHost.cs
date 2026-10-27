using PocketForge.McpTools;
using System.Runtime.CompilerServices;

namespace PocketForgeDesktop;

public record UiLoopEvent(string Kind, string Text, string? Detail);

// Adapter exposing the desktop client under the tool loop's IModelClient
// contract. Only this file references McpTools types; app/ChatApi.cs and
// app/Models.cs stay standalone-compilable for the self-test project.
public sealed class PhoneMcpModelClient : PocketForge.McpTools.IModelClient
{
    private readonly PocketForgeDesktop.IModelClient _inner;

    public PhoneMcpModelClient(PocketForgeDesktop.IModelClient inner)
    {
        _inner = inner;
    }

    public IAsyncEnumerable<string> StreamAsync(IReadOnlyList<(string Role, string Content)> messages, CancellationToken ct)
        => _inner.StreamAsync(messages, ct);

    public Task CancelAsync(CancellationToken ct) => _inner.CancelAsync(ct);
}

// Runs one agent-mode turn through the McpTools tool loop and maps its events
// to UI-friendly ones. One MCP stdio server child process is spawned lazily and
// reused across turns. There is no approval UI yet, so tools that require
// approval are denied safely.
public static class AgentLoopHost
{
    private static McpStdioClient? _mcp;

    public static async IAsyncEnumerable<UiLoopEvent> RunAsync(
        string userMessage, PocketForgeDesktop.IModelClient model, [EnumeratorCancellation] CancellationToken ct)
    {
        // each agent task gets a fresh phone session (bounds KV growth) and its
        // turns jump the phone turn queue
        if (model is PhoneChatClient pcc)
        {
            pcc.AgentMode = true;
            pcc.NextTurnResetsSession = true;
        }
        try
        {
            var loop = new ToolLoop(new PhoneMcpModelClient(model), GetMcpTools(), approval: null, maxIterations: 5);
            await foreach (var ev in loop.RunAsync(userMessage, systemPrompt: null, ct).ConfigureAwait(false))
            {
                switch (ev.Kind)
                {
                    case LoopEventKind.AssistantDelta:
                        yield return new UiLoopEvent("delta", ev.Delta ?? "", null);
                        break;
                    case LoopEventKind.ToolRequested:
                        yield return new UiLoopEvent("tool", ev.ToolName ?? "?", ev.ToolArgs);
                        break;
                    case LoopEventKind.ToolResult:
                        yield return new UiLoopEvent("result", ev.ToolName ?? "?", ev.Ok ? ev.Result : "[failed] " + (ev.Result ?? ""));
                        break;
                    case LoopEventKind.Denied:
                        yield return new UiLoopEvent("denied", ev.ToolName ?? "", ev.Error);
                        break;
                    case LoopEventKind.Error:
                        yield return new UiLoopEvent("error", ev.Error ?? "tool loop error", null);
                        break;
                    case LoopEventKind.Done:
                        break;
                }
            }
        }
        finally
        {
            if (model is PhoneChatClient doneClient)
            {
                doneClient.AgentMode = false;
            }
        }
    }

    private static McpStdioClient GetMcpTools()
    {
        _mcp ??= McpStdioClient.StartNew();
        return _mcp;
    }
}
