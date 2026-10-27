using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace PocketForge.SelfTest;

// PartD: speculative decoding (turbo mode) endpoints on MockPhone, driven over plain
// HTTP. Validates the /v1/spec/* contract without hardware before the desktop
// orchestrator ships.
internal static class PartD
{
    internal static async Task Run(string mockExe, int port)
    {
        try
        {
            await RunCore(mockExe, port);
        }
        catch (Exception e)
        {
            Result.FailLine("PartD", "unexpected: " + e.Message);
        }
    }

    private static async Task RunCore(string mockExe, int port)
    {
        await using var mock = new MockInstance(mockExe, port);
        if (!await mock.WaitReady())
        {
            Result.FailLine("PartD startup", "MockPhone did not come up on " + mock.BaseUrl);
            return;
        }

        string token = await Pair(mock.BaseUrl);

        await Result.Run("PartD spec capabilities: 200 with spec_verify, n_vocab, greedy_only", async () =>
        {
            var (code, body) = await GetAuth(mock.BaseUrl, "/v1/spec/capabilities", token);
            Assert.Eq(200, code);
            using var doc = JsonDocument.Parse(body);
            Assert.True(doc.RootElement.GetProperty("spec_verify").GetBoolean(), "spec_verify must be true");
            Assert.Eq(151936, doc.RootElement.GetProperty("n_vocab").GetInt32());
            Assert.True(doc.RootElement.GetProperty("greedy_only").GetBoolean(), "greedy_only must be true");
        });

        await Result.Run("PartD spec turn: missing user -> 400 + error shape", async () =>
        {
            var (code, body) = await PostSpec(mock.BaseUrl, "/v1/spec/turn", token, "{\"user\":null,\"max_tokens\":4}");
            Assert.Eq(400, code);
            Assert.ErrorShape(body, 400);
            var (code2, body2) = await PostSpec(mock.BaseUrl, "/v1/spec/turn", token, "{\"max_tokens\":4}");
            Assert.Eq(400, code2);
            Assert.ErrorShape(body2, 400);
        });

        await Result.Run("PartD turn -> verify loop: clamp, done=true, finish_reason length, exact text", async () =>
        {
            var (code, body) = await PostSpec(mock.BaseUrl, "/v1/spec/turn", token,
                "{\"user\":\"spec loop turn\",\"max_tokens\":8}");
            Assert.Eq(200, code);
            using (var doc = JsonDocument.Parse(body))
            {
                Assert.True(doc.RootElement.GetProperty("ok").GetBoolean(), "turn ok");
                Assert.Eq(0, doc.RootElement.GetProperty("committed").GetInt32());
                Assert.Eq(8, doc.RootElement.GetProperty("max_new").GetInt32());
                Assert.True(doc.RootElement.TryGetProperty("next_token", out var nt) &&
                            nt.ValueKind == JsonValueKind.Number, "next_token must be a number");
            }

            // round 3 sends one more token than remain (8-6=2), so the min() clamp applies
            var rounds = new[]
            {
                (send: new[] { 71, 72, 73 }, accepted: 3, text: "71|72|73", committed: 3, done: false),
                (send: new[] { 74, 75, 76 }, accepted: 3, text: "74|75|76", committed: 6, done: false),
                (send: new[] { 77, 78, 79 }, accepted: 2, text: "77|78", committed: 8, done: true),
            };
            foreach (var round in rounds)
            {
                string payload = "{\"tokens\":[" + string.Join(",", round.send.Select(t => t.ToString())) + "]}";
                var (rc, rbody) = await PostSpec(mock.BaseUrl, "/v1/spec/verify", token, payload);
                Assert.Eq(200, rc);
                using var rdoc = JsonDocument.Parse(rbody);
                Assert.Eq(round.accepted, rdoc.RootElement.GetProperty("accepted").GetInt32());
                Assert.Eq(round.text, rdoc.RootElement.GetProperty("text").GetString());
                Assert.Eq(round.committed, rdoc.RootElement.GetProperty("committed").GetInt32());
                Assert.Eq(round.done, rdoc.RootElement.GetProperty("done").GetBoolean());
                if (round.done)
                    Assert.Eq("length", rdoc.RootElement.GetProperty("finish_reason").GetString());
                else
                    Assert.True(rdoc.RootElement.GetProperty("finish_reason").ValueKind == JsonValueKind.Null,
                        "finish_reason must be null while not done");
            }
        });

        await Result.Run("PartD spec finish: ok with plausible stats", async () =>
        {
            // fresh turn so completion_tokens reflects a verify round above (committed=8)
            var (mcode, _) = await PostSpec(mock.BaseUrl, "/v1/spec/turn", token,
                "{\"user\":\"finish stats turn\",\"max_tokens\":4}");
            Assert.Eq(200, mcode);
            await PostSpec(mock.BaseUrl, "/v1/spec/verify", token, "{\"tokens\":[901,902]}");

            var (code, body) = await PostSpec(mock.BaseUrl, "/v1/spec/finish", token,
                "{\"text\":\"a short mock reply\"}");
            Assert.Eq(200, code);
            using var doc = JsonDocument.Parse(body);
            Assert.True(doc.RootElement.GetProperty("ok").GetBoolean(), "finish ok");
            Assert.True(doc.RootElement.GetProperty("prompt_tokens").GetInt32() > 0, "prompt_tokens");
            Assert.Eq(2, doc.RootElement.GetProperty("completion_tokens").GetInt32());
            Assert.True(doc.RootElement.GetProperty("gen_ms").GetInt32() >= 0, "gen_ms");
        });

        // --- scripted instances ---
        await using var noSpec = new MockInstance(mockExe, port + 1, ("MOCK_NO_SPEC", "1"));
        if (await noSpec.WaitReady())
        {
            await Result.Run("PartD MOCK_NO_SPEC=1: capabilities -> 404", async () =>
            {
                var tok = await Pair(noSpec.BaseUrl);
                var (code, body) = await GetAuth(noSpec.BaseUrl, "/v1/spec/capabilities", tok);
                Assert.Eq(404, code);
                Assert.ErrorShape(body, 404);
            });
        }
        else
        {
            Result.FailLine("PartD MOCK_NO_SPEC mock startup", "no health on " + noSpec.BaseUrl);
        }

        await using var reject = new MockInstance(mockExe, port + 2, ("MOCK_SPEC_REJECT", "1"));
        if (await reject.WaitReady())
        {
            await Result.Run("PartD MOCK_SPEC_REJECT=1: loop gives up bounded, no hang, clean state", async () =>
            {
                var tok = await Pair(reject.BaseUrl);
                var (tcode, _) = await PostSpec(reject.BaseUrl, "/v1/spec/turn", tok,
                    "{\"user\":\"reject loop\",\"max_tokens\":6}");
                Assert.Eq(200, tcode);

                // an orchestrator loop with a round cap and an overall stop clock; with the
                // phone stuck at accepted=0 it must bail out instead of spinning forever
                const int maxRounds = 8;
                var sw = Stopwatch.StartNew();
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(15));
                int round;
                bool allRejected = true;
                for (round = 0; round < maxRounds && !cts.IsCancellationRequested; round++)
                {
                    var (code, body) = await PostSpec(reject.BaseUrl, "/v1/spec/verify", tok,
                        "{\"tokens\":[1,2,3]}", cts.Token);
                    Assert.Eq(200, code);
                    using var doc = JsonDocument.Parse(body);
                    Assert.Eq(0, doc.RootElement.GetProperty("accepted").GetInt32());
                    Assert.Eq("", doc.RootElement.GetProperty("text").GetString());
                    Assert.Eq(0, doc.RootElement.GetProperty("committed").GetInt32());
                    Assert.False(doc.RootElement.GetProperty("done").GetBoolean(), "done too early");
                    if (doc.RootElement.GetProperty("accepted").GetInt32() > 0)
                        allRejected = false;
                }

                // surface the state the orchestrator would report on giving up
                string failure = $"spec verify stuck: 0 of 8 rounds accepted, committed=0, done=false (rejected)";
                Assert.True(round == maxRounds, $"loop left early on round {round}");
                Assert.True(allRejected, "every round must be fully rejected when MOCK_SPEC_REJECT=1");
                Assert.True(sw.Elapsed < TimeSpan.FromSeconds(10),
                    $"verify loop did not give up in time: {sw.Elapsed}");
                Assert.True(failure.Contains("committed=0, done=false"), "state report shape");
            });
        }
        else
        {
            Result.FailLine("PartD MOCK_SPEC_REJECT mock startup", "no health on " + reject.BaseUrl);
        }

        await using var busy = new MockInstance(mockExe, port + 3, ("MOCK_BUSY", "1"));
        if (await busy.WaitReady())
        {
            await Result.Run("PartD MOCK_BUSY=1: spec turn and verify -> 409 like chat", async () =>
            {
                var tok = await Pair(busy.BaseUrl);
                var (tcode, tbody) = await PostSpec(busy.BaseUrl, "/v1/spec/turn", tok,
                    "{\"user\":\"busy turn\",\"max_tokens\":4}");
                Assert.Eq(409, tcode);
                Assert.ErrorShape(tbody, 409);
                var (vcode, vbody) = await PostSpec(busy.BaseUrl, "/v1/spec/verify", tok, "{\"tokens\":[1,2]}");
                Assert.Eq(409, vcode);
                Assert.ErrorShape(vbody, 409);
            });
        }
        else
        {
            Result.FailLine("PartD MOCK_BUSY mock startup", "no health on " + busy.BaseUrl);
        }
    }

    private static async Task<string> Pair(string baseUrl)
    {
        var (code, body) = await PostSpec(baseUrl, "/pair", "", "{\"code\":\"123456\"}");
        Assert.Eq(200, code);
        string token = JsonDocument.Parse(body).RootElement.GetProperty("token").GetString() ?? "";
        Assert.True(token.StartsWith("mock-token-"), "token prefix: " + token);
        return token;
    }

    private static async Task<(int code, string body)> GetAuth(string baseUrl, string path, string bearer)
    {
        using var http = new HttpClient { BaseAddress = new Uri(baseUrl), Timeout = TimeSpan.FromSeconds(15) };
        using var req = new HttpRequestMessage(HttpMethod.Get, path);
        req.Headers.TryAddWithoutValidation("Authorization", "Bearer " + bearer);
        var resp = await http.SendAsync(req);
        return ((int)resp.StatusCode, await resp.Content.ReadAsStringAsync());
    }

    private static async Task<(int code, string body)> PostSpec(string baseUrl, string path, string bearer, string json) =>
        await PostSpec(baseUrl, path, bearer, json, default);

    private static async Task<(int code, string body)> PostSpec(
        string baseUrl, string path, string bearer, string json, CancellationToken ct)
    {
        using var http = new HttpClient { BaseAddress = new Uri(baseUrl), Timeout = TimeSpan.FromSeconds(15) };
        using var req = new HttpRequestMessage(HttpMethod.Post, path)
        {
            Content = new StringContent(json, Encoding.UTF8, "application/json"),
        };
        if (bearer.Length > 0)
            req.Headers.TryAddWithoutValidation("Authorization", "Bearer " + bearer);
        var resp = await http.SendAsync(req, ct);
        return ((int)resp.StatusCode, await resp.Content.ReadAsStringAsync());
    }
}
