using System.Diagnostics;
using PocketForgeDesktop;

namespace PocketForge.SelfTest;

// PartB: app/ChatApi.cs (PhoneChatClient) driven end-to-end against a real
// MockPhone process over HTTP, exercising the paths the desktop app uses.
internal static class PartB
{
    internal static async Task Run(string mockExe, int port)
    {
        try
        {
            await RunCore(mockExe, port);
        }
        catch (Exception e)
        {
            Result.FailLine("PartB", "unexpected: " + e.Message);
        }
    }

    private static async Task RunCore(string mockExe, int port)
    {
        await using var mock = new MockInstance(mockExe, port);
        if (!await mock.WaitReady())
        {
            Result.FailLine("PartB startup", "MockPhone did not come up on " + mock.BaseUrl);
            return;
        }

        var addr = new Uri(mock.BaseUrl);
        using var api = new PhoneChatClient(addr.Host, addr.Port);

        await RunT("PartB ProbeAsync: ok=true pf_api=v1", async ct =>
        {
            HealthInfo h = await api.ProbeAsync(addr.Host, addr.Port, ct);
            Assert.True(h.Ok, "health ok must be true");
            Assert.Eq("v1", h.Api ?? "");
        });

        await RunT("PartB PairAsync wrong code -> UnauthorizedPhoneException 401", async ct =>
        {
            try
            {
                await api.PairAsync("000000", ct);
                throw new Exception("expected 401 for wrong pairing code");
            }
            catch (UnauthorizedPhoneException ex)
            {
                Assert.Eq(401, ex.StatusCode);
            }
        });

        string token = "_invalid_because_pair_failed";
        await RunT("PartB PairAsync right code -> mock token", async ct =>
        {
            string t = await api.PairAsync("123456", ct);
            Assert.True(t.StartsWith("mock-token-"), "token prefix: " + t);
            token = t;
        });
        api.SetToken(token);

        await RunT("PartB GetStatusAsync: ModelReady, not busy, model loaded", async ct =>
        {
            StatusInfo s = await api.GetStatusAsync(ct);
            Assert.True(s.Server, "server true");
            Assert.Eq("ModelReady", s.State);
            Assert.False(s.Busy, "busy should be false");
            Assert.True(s.Model is { Loaded: true }, "model.loaded true");
            Assert.IsNotEmpty(s.Model!.Name);
        });

        await RunT("PartB StreamChatAsync: deltas echo, terminal finish_reason stop, clean end", async ct =>
        {
            var deltas = new List<ChatDelta>();
            await foreach (ChatDelta d in api.StreamChatAsync(
                new[] { new ChatMessage("user", "hello streaming turn") }, new ChatOptions(Stream: true), ct))
                deltas.Add(d);

            string text = string.Concat(deltas.Where(x => x.Content != null).Select(x => x.Content));
            Assert.True(deltas.Count >= 3, "want >=3 deltas, got " + deltas.Count);
            Assert.True(text.Contains("hello streaming turn"), "content mismatch: " + text);
            Assert.Eq("stop", deltas.Last(x => x.FinishReason != null).FinishReason ?? "(none)");
        });

        await RunT("PartB ChatAsync non-stream: content echo, finish stop, usage counted", async ct =>
        {
            ChatResult r = await api.ChatAsync(
                new[] { new ChatMessage("user", "plain json answer") }, new ChatOptions(Stream: false), ct);
            Assert.True(r.Content.Contains("plain json answer"), "content mismatch: " + r.Content);
            Assert.Eq("stop", r.FinishReason);
            Assert.True(r.PromptTokens > 0 && r.CompletionTokens > 0, $"usage {r.PromptTokens}/{r.CompletionTokens}");
        });

        await RunT("PartB no token -> UnauthorizedPhoneException 401", async ct =>
        {
            using var anon = new PhoneChatClient(addr.Host, addr.Port);
            try
            {
                await anon.ChatAsync(new[] { new ChatMessage("user", "hi") }, new ChatOptions(Stream: false), ct);
                throw new Exception("expected 401 without a token");
            }
            catch (UnauthorizedPhoneException ex)
            {
                Assert.Eq(401, ex.StatusCode);
            }
        });

        // --- MOCK_DELAY_MS slow stream for the cancel path ---
        await using var slow = new MockInstance(mockExe, port + 1, ("MOCK_DELAY_MS", "300"));
        if (await slow.WaitReady())
        {
            var slowAddr = new Uri(slow.BaseUrl);
            await RunT("PartB CancelAsync mid-stream: finished with cancelled, ends fast", async ct =>
            {
                using var slowApi = new PhoneChatClient(slowAddr.Host, slowAddr.Port);
                slowApi.SetToken(await slowApi.PairAsync("123456", ct));

                var deltas = new List<ChatDelta>();
                var sw = Stopwatch.StartNew();
                bool cancelSent = false;

                // keep consuming so the connection stays open for the terminal frames
                await foreach (ChatDelta d in slowApi.StreamChatAsync(
                    new[] { new ChatMessage("user", "slow tokens for cancel") },
                    new ChatOptions(Stream: true), ct))
                {
                    if (d.Content != null && !cancelSent)
                    {
                        cancelSent = true;
                        await slowApi.CancelAsync(ct);
                    }
                    deltas.Add(d);
                }

                Assert.True(cancelSent, "never saw a content delta to cancel after");
                Assert.True(deltas.Any(x => x.Content != null), "no content delta");
                Assert.True(deltas.Any(x => x.FinishReason == "cancelled"), "no finish_reason cancelled");
                Assert.True(sw.Elapsed < TimeSpan.FromSeconds(9), "stream did not end fast after cancel: " + sw.Elapsed);
            });
        }
        else
        {
            Result.FailLine("PartB MOCK_DELAY_MS mock startup", "no health on " + slow.BaseUrl);
        }
    }

    private static async Task RunT(string name, Func<CancellationToken, Task> body)
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(12));
        await Result.Run(name, () => body(cts.Token));
    }
}
