using System.Runtime.CompilerServices;
using System.Text.Json;
using PocketForge.McpTools;

namespace PocketForge.SelfTest;

// PartC: ToolLoop with a scripted fake model client and a fake MCP tool client;
// exercises the desktop-side model<->tool cycle without any network.
internal static class PartC
{
    internal static async Task Run()
    {
        try
        {
            await RunCore();
        }
        catch (Exception e)
        {
            Result.FailLine("PartC", "unexpected: " + e.Message);
        }
    }

    private static async Task RunCore()
    {
        const string firstReply =
            "Let me look.\n```pf-tool\n{\"tool\":\"search_files\",\"args\":{\"query\":\"selftest\"}}\n```";
        const string finalReply = "Finished: no matches, the workspace is clean.";

        var calls = new List<IReadOnlyList<(string Role, string Content)>>();
        var model = new FakeModelClient(new[] { firstReply, finalReply }, calls);
        var tools = new FakeToolClient();
        var loop = new ToolLoop(model, tools, approval: null, maxIterations: 5);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));

        await Result.Run("PartC ToolLoop happy path: AssistantDelta -> ToolRequested -> ToolResult -> Done",
            async () =>
            {
                var events = new List<LoopEvent>();
                await foreach (LoopEvent ev in
                    loop.RunAsync("find the selftest marker", "You are the desktop agent.", cts.Token))
                    events.Add(ev);

                // scripted model served exactly two turns: original text, then the result feed
                Assert.Eq(2, calls.Count);
                Assert.True(calls[0].Count >= 2 && calls[0][0].Role == "system",
                    "first turn must start with the system message");
                Assert.True(calls[0][1] == ("user", "find the selftest marker"), "first user text went missing");
                var secondUser = calls[1].Last(m => m.Role == "user");
                Assert.True(secondUser.Content?.StartsWith("TOOL RESULT") == true,
                    "second request user message: " + secondUser.Content);
                Assert.True(secondUser.Content!.Contains("fake result line"),
                    "tool output not carried back: " + secondUser.Content);

                var deltas = events.Where(e => e.Kind == LoopEventKind.AssistantDelta).ToList();
                var kindsAfter = events.Where(e => e.Kind != LoopEventKind.AssistantDelta)
                    .Select(e => e.Kind).ToList();
                Assert.True(kindsAfter.SequenceEqual(
                        new[] { LoopEventKind.ToolRequested, LoopEventKind.ToolResult, LoopEventKind.Done }),
                    "event sequence: " + string.Join(",", kindsAfter));
                Assert.True(deltas.Count >= 2, "want streamed deltas, got " + deltas.Count);

                string firstTurnText = string.Concat(deltas.Select(e => e.Delta));
                Assert.True(firstTurnText.Contains("```pf-tool") && firstTurnText.Contains("search_files"),
                    "first reply must carry the pf-tool fence: " + firstTurnText);

                var req = events.First(e => e.Kind == LoopEventKind.ToolRequested);
                Assert.Eq("search_files", req.ToolName);
                using (var argsDoc = JsonDocument.Parse(req.ToolArgs ?? "not json"))
                {
                    Assert.True(argsDoc.RootElement.ValueKind == JsonValueKind.Object,
                        "tool args must be a JSON object");
                }

                var res = events.First(e => e.Kind == LoopEventKind.ToolResult);
                Assert.Eq(true, res.Ok);
                Assert.Eq("fake result line", res.Result);

                var done = events.First(e => e.Kind == LoopEventKind.Done);
                Assert.Eq(finalReply, done.Delta);

                Assert.Eq(1, tools.Calls.Count);
                Assert.Eq(req.ToolArgs, tools.Calls[0].ArgsJson);
                Assert.True(!events.Any(e => e.Kind is LoopEventKind.Denied or LoopEventKind.Error),
                    "no denied/error events expected");
            });
    }

    private sealed class FakeModelClient : IModelClient
    {
        private readonly string[] _replies;
        private readonly List<IReadOnlyList<(string Role, string Content)>> _calls;
        private int _turn;

        public FakeModelClient(string[] replies, List<IReadOnlyList<(string Role, string Content)>> calls)
        {
            _replies = replies;
            _calls = calls;
        }

        // snapshot per call: the loop itself mutates one shared message list across turns
        public async IAsyncEnumerable<string> StreamAsync(
            IReadOnlyList<(string Role, string Content)> messages,
            [EnumeratorCancellation] CancellationToken ct = default)
        {
            _calls.Add(messages.Select(m => m).ToArray());
            if (_turn >= _replies.Length)
                throw new Exception("extra model turn " + (_turn + 1));
            string reply = _replies[_turn++];
            await Task.Yield();
            for (int i = 0; i < reply.Length; i += 7)
            {
                ct.ThrowIfCancellationRequested();
                yield return reply.Substring(i, Math.Min(7, reply.Length - i));
            }
        }

        public Task CancelAsync(CancellationToken ct) => Task.CompletedTask;
    }

    private sealed record FakeToolCall(string Name, string ArgsJson);

    private sealed class FakeToolClient : IMcpToolClient
    {
        public List<FakeToolCall> Calls { get; } = new();

        public Task<IReadOnlyList<ToolInfo>> ListToolsAsync(CancellationToken ct) =>
            Task.FromResult<IReadOnlyList<ToolInfo>>(new[] { new ToolInfo("search_files", "fake search tool") });

        public Task<ToolCallResult> CallAsync(string name, string argsJson, CancellationToken ct)
        {
            Calls.Add(new FakeToolCall(name, argsJson));
            return Task.FromResult(new ToolCallResult(true, "fake result line"));
        }
    }
}
