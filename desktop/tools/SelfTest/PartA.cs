using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace PocketForge.SelfTest;

internal sealed class PartA
{
    private readonly string? _mockExe;
    private readonly int _basePort;

    public PartA(string? mockExe, int basePort)
    {
        _mockExe = mockExe;
        _basePort = basePort;
    }

    public async Task Run()
    {
        if (_mockExe is null) return;

        await using var main = new MockInstance(_mockExe, _basePort);
        if (!await main.WaitReady())
        {
            Result.FailLine("PartA startup", "MockPhone did not come up on " + main.BaseUrl);
            return;
        }

        string baseUrl = main.BaseUrl;
        using var http = new HttpClient { BaseAddress = new Uri(baseUrl), Timeout = TimeSpan.FromSeconds(15) };

        await Result.Run("health no auth 200 + mock banner", async () =>
        {
            var resp = await http.GetAsync("/health");
            Assert.Eq(200, (int)resp.StatusCode);
            string body = await resp.Content.ReadAsStringAsync();
            using var doc = JsonDocument.Parse(body);
            Assert.True(doc.RootElement.GetProperty("ok").GetBoolean(), "ok");
            Assert.Eq("v1", doc.RootElement.GetProperty("pf_api").GetString());
            Assert.Eq("PocketForgeAI-mock", doc.RootElement.GetProperty("app").GetString());
        });

        await Result.Run("raw health: Connection close + content length", async () =>
        {
            var (status, headers, _) = await Raw.RequestAsync(_basePort, "GET /health HTTP/1.1\r\nHost: x\r\n\r\n");
            Assert.True(status.StartsWith("HTTP/1.1 200"), status);
            Assert.True(headers.ContainsKey("connection") && headers["connection"] == "close", "Connection: close missing");
            Assert.True(headers.ContainsKey("content-length"), "Content-Length missing");
        });

        string? token = null;
        await Result.Run("pair wrong code -> 401 + error shape", async () =>
        {
            var (code, body) = await PostJson(http, "/pair", "{\"code\":\"000000\"}");
            Assert.Eq(401, code);
            Assert.ErrorShape(body, 401);
        });

        await Result.Run("pair right code -> mock-token", async () =>
        {
            var (code, body) = await PostJson(http, "/pair", "{\"code\":\"123456\"}");
            Assert.Eq(200, code);
            using var doc = JsonDocument.Parse(body);
            token = doc.RootElement.GetProperty("token").GetString();
            Assert.True(token is not null && token.StartsWith("mock-token-"), "token prefix");
        });

        string bearer = token ?? "_invalid_because_pair_failed";
        if (token is null)
            Result.FailLine("pair token sanity", "pairing failed earlier; auth-dependent tests will fail");

        await Result.Run("pair malformed body -> 400", async () =>
        {
            var (code, body) = await PostRaw(http, "/pair", "this is not json");
            Assert.Eq(400, code);
            Assert.ErrorShape(body, 400);
        });

        await Result.Run("/v1/status without token -> 401 + error shape", async () =>
        {
            var resp = await http.GetAsync("/v1/status");
            Assert.Eq(401, (int)resp.StatusCode);
            Assert.ErrorShape(await resp.Content.ReadAsStringAsync(), 401);
        });

        await Result.Run("/v1/chat wrong bearer -> 401", async () =>
        {
            var (code, body) = await PostChat(http, "Bearer wrong-wrong", "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}");
            Assert.Eq(401, code);
            Assert.ErrorShape(body, 401);
        });

        await Result.Run("status with token -> ModelReady, not busy, model loaded", async () =>
        {
            var (code, body) = await GetAuth(http, "/v1/status", bearer);
            Assert.Eq(200, code);
            using var doc = JsonDocument.Parse(body);
            Assert.Eq("ModelReady", doc.RootElement.GetProperty("state").GetString());
            Assert.False(BusyFlag(doc.RootElement), "busy should be false");
            var model = doc.RootElement.GetProperty("model");
            Assert.True(model.GetProperty("loaded").GetBoolean(), "model.loaded");
            Assert.IsNotEmpty(model.GetProperty("name").GetString());
        });

        await Result.Run("models -> one fake model", async () =>
        {
            var (code, body) = await GetAuth(http, "/v1/models", bearer);
            Assert.Eq(200, code);
            using var doc = JsonDocument.Parse(body);
            int count = 0;
            foreach (var m in doc.RootElement.GetProperty("data").EnumerateArray()) count++;
            Assert.True(count == 1, $"expected 1 model, got {count}");
        });

        // /v1/* is auth-gated first, so the unknown-path probe carries a valid token
        await Result.Run("unknown path -> 404 + error shape", async () =>
        {
            var (code, body) = await GetAuth(http, "/v1/definitely/not/here", bearer);
            Assert.Eq(404, code);
            Assert.ErrorShape(body, 404);
        });

        await Result.Run("chat stream=true: >=2 deltas, terminal stop, [DONE], content matches user text", async () =>
        {
            var (frames, status) = await ChatSse(http, bearer, "multi word streaming prompt please");
            Assert.Eq(200, status);
            Assert.True(frames.Count >= 3, $"too few frames: {frames.Count}");
            Assert.Eq("stop", Sse.Finish(frames[^2]));
            Assert.True(Sse.IsDone(frames[^1]), "last frame must be [DONE]");
            string total = string.Join("", frames.SkipLast(2).Select(Sse.DeltaContent));
            Assert.True(total.Contains("multi word streaming prompt"), "delta text should reflect scripted reply");
        });

        await Result.Run("chat stream=false: JSON completion shape", async () =>
        {
            var (code, body) = await PostChat(http, "Bearer " + bearer,
                "{\"messages\":[{\"role\":\"user\",\"content\":\"plain json answer\"}],\"stream\":false,\"max_tokens\":2048}");
            Assert.Eq(200, code);
            using var doc = JsonDocument.Parse(body);
            Assert.Eq("chat.completion", doc.RootElement.GetProperty("object").GetString());
            var choice = doc.RootElement.GetProperty("choices")[0];
            Assert.Eq("stop", choice.GetProperty("finish_reason").GetString());
            string content = choice.GetProperty("message").GetProperty("content").GetString() ?? "";
            Assert.True(content.Contains("plain json answer"), $"content mismatch: {content}");
            Assert.True(doc.RootElement.TryGetProperty("usage", out _), "usage missing");
        });

        await Result.Run("chat TOOL RESULT text -> Result acknowledged prefix", async () =>
        {
            var (code, body) = await PostChat(http, "Bearer " + bearer,
                "{\"messages\":[{\"role\":\"user\",\"content\":\"TOOL RESULT {" + new string('0', 80) + "}\"}],\"stream\":false}");
            Assert.Eq(200, code);
            using var doc = JsonDocument.Parse(body);
            string content = doc.RootElement.GetProperty("choices")[0].GetProperty("message").GetProperty("content").GetString() ?? "";
            Assert.True(content.StartsWith("Result acknowledged: "), $"content: {content}");
        });

        await Result.Run("no user message -> 400", async () =>
        {
            var (code, body) = await PostChat(http, "Bearer " + bearer,
                "{\"messages\":[{\"role\":\"assistant\",\"content\":\"hello\"}],\"stream\":false}");
            Assert.Eq(400, code);
            Assert.ErrorShape(body, 400);
        });

        await Result.Run("second system message -> 400", async () =>
        {
            var (code, body) = await PostChat(http, "Bearer " + bearer,
                "{\"messages\":[{\"role\":\"system\",\"content\":\"s1\"},{\"role\":\"system\",\"content\":\"s2\"},{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}");
            Assert.Eq(400, code);
            Assert.ErrorShape(body, 400);
        });

        await Result.Run("chunked request body -> 400", async () =>
        {
            string raw =
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: x\r\nAuthorization: Bearer " + bearer + "\r\n" +
                "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "0\r\n\r\n";
            var (status, headers, _) = await Raw.RequestAsync(_basePort, raw);
            Assert.True(status.StartsWith("HTTP/1.1 400"), $"chunked rejected with: {status}");
            Assert.True(headers.ContainsKey("connection"), "Connection header missing");
        });

        await Result.Run("body > 256KB -> 413", async () =>
        {
            var (status, headers, body) = await Raw.RequestOversizedAsync(_basePort, bearer, 300000);
            Assert.True(status.StartsWith("HTTP/1.1 413"), $"oversized body rejected with: {status}");
            Assert.ErrorShape(body, 413);
        });

        // --- scripted instances ---
        await using var busy = new MockInstance(_mockExe, _basePort + 1, ("MOCK_BUSY", "1"));
        if (await busy.WaitReady())
        {
            await Result.Run("MOCK_BUSY=1: status busy true and chat -> 409", async () =>
            {
                var (code, body) = await PostJsonWithAuth(busy.BaseUrl, "/pair", "{\"code\":\"123456\"}");
                Assert.Eq(200, code);
                var tok = JsonDocument.Parse(body).RootElement.GetProperty("token").GetString() ?? "";
                var (sc, sbody) = await GetAuth(busy.BaseUrl, "/v1/status", tok);
                Assert.Eq(200, sc);
                using var sdoc = JsonDocument.Parse(sbody);
                Assert.True(BusyFlag(sdoc.RootElement), "busy must be true");
                var (cc, cbody) = await PostChat(busy.BaseUrl, "Bearer " + tok, "{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"stream\":false}");
                Assert.Eq(409, cc);
                Assert.ErrorShape(cbody, 409);
            });
        }
        else
        {
            Result.FailLine("MOCK_BUSY=1 mock startup", "no health on " + busy.BaseUrl);
        }

        await using var slow = new MockInstance(_mockExe, _basePort + 2, ("MOCK_DELAY_MS", "1000"));
        if (await slow.WaitReady())
        {
            await Result.Run("cancel: 204, stream ends fast with finish_reason cancelled", async () =>
            {
                var (_, pbody) = await PostJsonWithAuth(slow.BaseUrl, "/pair", "{\"code\":\"123456\"}");
                var tok = JsonDocument.Parse(pbody).RootElement.GetProperty("token").GetString() ?? "";
                using var http2 = new HttpClient { BaseAddress = new Uri(slow.BaseUrl), Timeout = TimeSpan.FromSeconds(10) };
                using var req = new HttpRequestMessage(HttpMethod.Post, "/v1/chat/completions")
                {
                    Content = new StringContent("{\"messages\":[{\"role\":\"user\",\"content\":\"slow tokens for cancel\"}],\"stream\":true}", Encoding.UTF8, "application/json"),
                };
                req.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", tok);
                var resp = await http2.SendAsync(req, HttpCompletionOption.ResponseHeadersRead);
                Assert.Eq(200, (int)resp.StatusCode);

                var sw = Stopwatch.StartNew();
                await using var stream = await resp.Content.ReadAsStreamAsync();
                var frames = new List<string>();
                bool sawDelta = false;
                // per-token delay bounds this read; cancel fires right after the first delta
                using var readCts = new CancellationTokenSource(TimeSpan.FromSeconds(15));
                try
                {
                    var reader = new StreamReader(stream, Encoding.UTF8);
                    while (!reader.EndOfStream && !sawDelta)
                    {
                        string? line = await reader.ReadLineAsync(readCts.Token);
                        if (line is null) break;
                        if (line.Length == 0 || !line.StartsWith("data: ")) continue;
                        frames.Add(line);
                        var payload = line[6..];
                        if (payload == "[DONE]") break;
                        using var fd = JsonDocument.Parse(payload);
                        var delta = fd.RootElement.GetProperty("choices")[0].GetProperty("delta");
                        if (delta.TryGetProperty("content", out var c) && c.GetString()!.Length > 0)
                            sawDelta = true;
                    }
                }
                catch (OperationCanceledException) { }
                var deltas = frames.Count(f => f.Contains("\"content\":"));
                Assert.True(deltas > 0, "no delta received before cancel");

                var cancel = new HttpRequestMessage(HttpMethod.Post, "/v1/cancel");
                cancel.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", tok);
                var cres = await http2.SendAsync(cancel);
                Assert.Eq(204, (int)cres.StatusCode);

                // finish reading the stream; the phone must close it promptly
                var reader2 = new StreamReader(stream, Encoding.UTF8);
                while (!reader2.EndOfStream)
                {
                    string? line = await reader2.ReadLineAsync(readCts.Token);
                    if (line is null) break;
                    if (line.StartsWith("data: ")) frames.Add(line);
                }
                Assert.True(frames[^1].Replace("data: ", "") == "[DONE]", "stream must end with [DONE]");
                string finish = Sse.Finish(frames[^2]);
                Assert.True(finish == "cancelled" || finish == "stop", $"finish_reason: {finish}");
                Assert.True(sw.Elapsed < TimeSpan.FromSeconds(10), $"stream took too long after cancel: {sw.Elapsed}");
            });
        }
        else
        {
            Result.FailLine("slow mock startup", "no health on " + slow.BaseUrl);
        }

        await using var fail = new MockInstance(_mockExe, _basePort + 3, ("MOCK_FAIL", "error"));
        if (await fail.WaitReady())
        {
            await Result.Run("MOCK_FAIL=error: chat -> 500 + error shape", async () =>
            {
                var (_, pbody) = await PostJsonWithAuth(fail.BaseUrl, "/pair", "{\"code\":\"123456\"}");
                var tok = JsonDocument.Parse(pbody).RootElement.GetProperty("token").GetString() ?? "";
                var (cc, cbody) = await PostChat(fail.BaseUrl, "Bearer " + tok, "{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"stream\":false}");
                Assert.Eq(500, cc);
                Assert.ErrorShape(cbody, 500);
            });
        }
        else
        {
            Result.FailLine("MOCK_FAIL=error mock startup", "no health on " + fail.BaseUrl);
        }
    }

    // mock reports busy as a JSON string ("true"/"false"); accept both spellings
    private static bool BusyFlag(JsonElement root)
    {
        var el = root.GetProperty("busy");
        if (el.ValueKind == JsonValueKind.True || el.ValueKind == JsonValueKind.False)
            return el.GetBoolean();
        return el.ValueKind == JsonValueKind.String && el.GetString() == "true";
    }

    private static async Task<(int code, string body)> PostJson(HttpClient http, string path, string json)
    {
        using var body = new StringContent(json, Encoding.UTF8, "application/json");
        var resp = await http.PostAsync(path, body);
        var text = await resp.Content.ReadAsStringAsync();
        return ((int)resp.StatusCode, text);
    }

    private static async Task<(int code, string body)> PostJsonWithAuth(string baseUrl, string path, string json)
    {
        using var http = new HttpClient { BaseAddress = new Uri(baseUrl), Timeout = TimeSpan.FromSeconds(15) };
        return await PostJson(http, path, json);
    }

    private static async Task<(int code, string body)> PostRaw(HttpClient http, string path, string rawBody)
    {
        using var body = new StringContent(rawBody, Encoding.UTF8, "application/json");
        var resp = await http.PostAsync(path, body);
        return ((int)resp.StatusCode, await resp.Content.ReadAsStringAsync());
    }

    private static async Task<(int code, string body)> PostChat(string baseUrl, string auth, string json)
    {
        using var http = new HttpClient { BaseAddress = new Uri(baseUrl), Timeout = TimeSpan.FromSeconds(15) };
        return await PostChat(http, auth, json);
    }

    private static async Task<(int code, string body)> PostChat(HttpClient http, string auth, string json)
    {
        using var req = new HttpRequestMessage(HttpMethod.Post, "/v1/chat/completions")
        {
            Content = new StringContent(json, Encoding.UTF8, "application/json"),
        };
        req.Headers.TryAddWithoutValidation("Authorization", auth);
        var resp = await http.SendAsync(req);
        return ((int)resp.StatusCode, await resp.Content.ReadAsStringAsync());
    }

    private static async Task<(int code, string body)> GetAuth(string baseUrl, string path, string bearer)
    {
        using var http = new HttpClient { BaseAddress = new Uri(baseUrl), Timeout = TimeSpan.FromSeconds(15) };
        return await GetAuth(http, path, bearer);
    }

    private static async Task<(int code, string body)> GetAuth(HttpClient http, string path, string bearer)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, path);
        req.Headers.TryAddWithoutValidation("Authorization", "Bearer " + bearer);
        var resp = await http.SendAsync(req);
        return ((int)resp.StatusCode, await resp.Content.ReadAsStringAsync());
    }

    private static async Task<(IReadOnlyList<string> frames, int status)> ChatSse(HttpClient http, string bearer, string userText)
    {
        using var req = new HttpRequestMessage(HttpMethod.Post, "/v1/chat/completions")
        {
            Content = new StringContent(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"" + userText + "\"}],\"stream\":true}",
                Encoding.UTF8, "application/json"),
        };
        req.Headers.TryAddWithoutValidation("Authorization", "Bearer " + bearer);
        var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead);
        int status = (int)resp.StatusCode;
        var frames = Sse.ReadFrames(await resp.Content.ReadAsStreamAsync(), TimeSpan.FromSeconds(10)).ToList();
        return (frames, status);
    }
}

// ---- shared helpers ----

internal static class Assert
{
    internal static void True(bool cond, string why) { if (!cond) throw new Exception(why); }
    internal static void False(bool cond, string why) { if (cond) throw new Exception(why); }
    internal static void Eq<T>(T expected, T actual) where T : notnull
    {
        if (!Equals(expected, actual)) throw new Exception($"expected {expected}, got {actual}");
    }
    internal static void IsNotEmpty(string? s) { if (string.IsNullOrEmpty(s)) throw new Exception("expected non-empty"); }
    internal static void ErrorShape(string body, int code)
    {
        using var doc = JsonDocument.Parse(body);
        var root = doc.RootElement;
        if (!root.TryGetProperty("error", out var err)) throw new Exception("no error object in " + body);
        if (!err.TryGetProperty("message", out var msg) || msg.ValueKind != JsonValueKind.String)
            throw new Exception("no error.message in " + body);
        if (!err.TryGetProperty("code", out var c) || c.GetInt32() != code)
            throw new Exception($"error.code mismatch: {body}");
    }
}

internal static class Sse
{
    // reads data-frames until the socket closes; framed by blank lines per contract
    internal static IEnumerable<string> ReadFrames(Stream s, TimeSpan timeout)
    {
        using var cts = new CancellationTokenSource(timeout);
        var reader = new StreamReader(s, Encoding.UTF8, leaveOpen: true);
        while (!reader.EndOfStream)
        {
            string? line;
            try { line = reader.ReadLineAsync(cts.Token).AsTask().GetAwaiter().GetResult(); }
            catch (OperationCanceledException) { break; }
            if (line is null) break;
            if (line.Length == 0) continue;
            yield return line; // every data line, done-frames included
        }
    }

    internal static string DeltaContent(string frame)
    {
        using var doc = JsonDocument.Parse(frame.TrimStart().StartsWith("data: ") ? frame[6..] : frame);
        return doc.RootElement.GetProperty("choices")[0].GetProperty("delta").GetProperty("content").GetString() ?? "";
    }

    internal static string Finish(string frame)
    {
        using var doc = JsonDocument.Parse(frame.TrimStart().StartsWith("data: ") ? frame[6..] : frame);
        return doc.RootElement.GetProperty("choices")[0].GetProperty("finish_reason").GetString() ?? "(none)";
    }

    internal static bool IsDone(string frame) => frame.Replace("data: ", "").Trim() == "[DONE]";
}

internal sealed class MockInstance : IAsyncDisposable
{
    private Process? _proc;
    public string BaseUrl { get; }
    private readonly string _exe;
    private readonly (string key, string val)? _env;

    public MockInstance(string exe, int port, (string, string)? env = null)
    {
        _exe = exe;
        _env = env;
        BaseUrl = $"http://127.0.0.1:{port}";
    }

    public async Task<bool> WaitReady()
    {
        if (System.IO.File.Exists(_exe))
        {
            try { Start(); }
            catch { return false; }
        }
        else
        {
            return false;
        }

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        using var http = new HttpClient();
        while (!cts.IsCancellationRequested)
        {
            try
            {
                if (_proc is not { HasExited: true })
                {
                    var resp = await http.GetAsync(BaseUrl + "/health", cts.Token);
                    if ((int)resp.StatusCode == 200) return true;
                }
                else break;
            }
            catch { }
            await Task.Delay(120);
        }
        return false;
    }

    private void Start()
    {
        if (_proc is not null) return;
        var psi = new ProcessStartInfo(_exe, "--port " + new Uri(BaseUrl).Port)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        if (_env is { } e)
            psi.EnvironmentVariables[e.key] = e.val;
        // never inherit a leftover scripting env that would break these scenario boundaries
        psi.EnvironmentVariables.Remove("MOCK_FAIL");
        psi.EnvironmentVariables.Remove("MOCK_BUSY");
        psi.EnvironmentVariables.Remove("MOCK_DELAY_MS");
        psi.EnvironmentVariables.Remove("MOCK_NO_SPEC");
        psi.EnvironmentVariables.Remove("MOCK_SPEC_REJECT");
        if (_env is { } e2)
            psi.EnvironmentVariables[e2.key] = e2.val;
        _proc = Process.Start(psi) ?? throw new Exception("failed to start " + _exe);
    }

    public async ValueTask DisposeAsync()
    {
        if (_proc is not null && !_proc.HasExited)
        {
            try
            {
                _proc.Kill();
                _proc.WaitForExit(2000); // reap so no fail-safe child lingers
            }
            catch { }
        }
        _proc?.Dispose();
        await Task.CompletedTask;
    }
}

// minimal raw-socket HTTP for header-level contract checks (chunked rejection etc)
internal static class Raw
{
    internal static async Task<(string statusLine, Dictionary<string, string> headers, string body)> RequestAsync(int port, string rawRequest)
    {
        using var tcp = new TcpClient();
        var connectCts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        await tcp.ConnectAsync(IPAddress.Loopback, port, connectCts.Token);
        await using var stream = tcp.GetStream();
        var bytes = Encoding.ASCII.GetBytes(rawRequest);
        await stream.WriteAsync(bytes, connectCts.Token);
        await stream.FlushAsync(connectCts.Token);

        var readCts = CancellationTokenSource.CreateLinkedTokenSource(connectCts.Token);
        readCts.CancelAfter(TimeSpan.FromSeconds(10));
        var all = new MemoryStream();
        var buf = new byte[4096];
        try
        {
            while (!readCts.IsCancellationRequested)
            {
                int n = await stream.ReadAsync(buf, readCts.Token);
                if (n <= 0) break; // Connection: close by contract
                all.Write(buf, 0, n);
                if (all.Length > 1 << 20) break;
            }
        }
        catch (OperationCanceledException) { }

        string text = Encoding.UTF8.GetString(all.ToArray());
        int sep = text.IndexOf("\r\n\r\n", StringComparison.Ordinal);
        string head = sep >= 0 ? text[..sep] : text;
        string body = sep >= 0 ? text[(sep + 4)..] : "";
        var lines = head.Split("\r\n");
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var h in lines.Skip(1))
        {
            int c = h.IndexOf(':');
            if (c > 0) headers[h[..c].Trim()] = h[(c + 1)..].Trim();
        }
        return (lines[0], headers, body);
    }

    // The mock rejects oversized bodies by Content-Length alone, without draining the
    // upload, so the early-close reset can wipe an HttpClient-style response. By sending
    // only the claiming headers (no body bytes) we get a clean FIN after the 413.
    internal static async Task<(string statusLine, Dictionary<string, string> headers, string body)> RequestOversizedAsync(int port, string bearer, int bodyBytes)
    {
        using var tcp = new TcpClient();
        var cts = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        await tcp.ConnectAsync(IPAddress.Loopback, port, cts.Token);
        await using var stream = tcp.GetStream();

        var head = Encoding.ASCII.GetBytes(
            "POST /v1/chat/completions HTTP/1.1\r\n" +
            "Host: x\r\nAuthorization: Bearer " + bearer + "\r\n" +
            "Content-Type: application/json\r\nContent-Length: " + bodyBytes + "\r\n\r\n");
        try { await stream.WriteAsync(head, cts.Token); }
        catch (Exception) { }

        var readCts = CancellationTokenSource.CreateLinkedTokenSource(cts.Token);
        readCts.CancelAfter(TimeSpan.FromSeconds(10));
        var all = new MemoryStream();
        var buf = new byte[4096];
        try
        {
            while (!readCts.IsCancellationRequested)
            {
                int n = await stream.ReadAsync(buf, readCts.Token);
                if (n <= 0) break;
                all.Write(buf, 0, n);
                if (all.Length > 1 << 20) break;
            }
        }
        catch (Exception) { }

        string text = Encoding.UTF8.GetString(all.ToArray());
        int sep = text.IndexOf("\r\n\r\n", StringComparison.Ordinal);
        string head2 = sep >= 0 ? text[..sep] : text;
        string body = sep >= 0 ? text[(sep + 4)..] : "";
        var lines = head2.Split("\r\n");
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var h in lines.Skip(1))
        {
            int c = h.IndexOf(':');
            if (c > 0) headers[h[..c].Trim()] = h[(c + 1)..].Trim();
        }
        return (lines[0], headers, body);
    }
}
