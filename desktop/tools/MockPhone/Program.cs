// MockPhone - contract mock of the Android phone server (PocketForge Phone API v1).
// Hand-rolled HTTP/1.1 over TcpListener, in-box types only. Runs until killed.
// Scripting knobs:
//   --port N         listen port (default 17851)
//   MOCK_BUSY=1      /v1/status reports busy:true and chat returns 409
//   MOCK_FAIL=auth   POST /v1/chat/completions -> 401 even with valid token
//   MOCK_FAIL=big    POST /v1/chat/completions -> 413
//   MOCK_FAIL=error  POST /v1/chat/completions -> 500 error JSON
//   MOCK_DELAY_MS=N  delay between SSE tokens (slow-token mode for cancel tests)
//   MOCK_NO_SPEC=1   /v1/spec/* -> 404 (simulates an older phone build without spec support)
//   MOCK_SPEC_REJECT=1  /v1/spec/verify accepts 0 tokens every round (rejection path)

using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PocketForge.MockPhone;

internal static class Program
{
    private static async Task<int> Main(string[] args)
    {
        int port = 17851;
        for (int i = 0; i < args.Length - 1; i++)
        {
            if (args[i] == "--port" && int.TryParse(args[i + 1], out int p))
                port = p;
        }

        Console.WriteLine($"MockPhone listening on 127.0.0.1:{port} (pf_api v1, app PocketForgeAI-mock)");
        using var exitCts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; exitCts.Cancel(); };
        AppDomain.CurrentDomain.ProcessExit += (_, _) => exitCts.Cancel();

        var server = new MockServer(port);
        try
        {
            await server.RunAsync(exitCts.Token);
        }
        catch (OperationCanceledException) { }
        return 0;
    }
}

internal sealed class MockServer
{
    private const int MaxHeaderBytes = 16 * 1024;
    private const int MaxBodyBytes = 256 * 1024;
    private const int MaxFailedPairsPerMinute = 5;

    private readonly int _port;
    private readonly ConcurrentQueue<DateTime> _failedPairAttempts = new();
    private string? _issuedToken;

    // one generation at a time across the whole mock, as per contract
    private CancellationTokenSource? _genCts;

    // speculative decoding session, reset by every /v1/spec/turn
    private int _specMaxNew;
    private int _specCommitted;
    private int _specNextToken;

    private static bool FailAuth => Environment.GetEnvironmentVariable("MOCK_FAIL") == "auth";
    private static bool FailBig => Environment.GetEnvironmentVariable("MOCK_FAIL") == "big";
    private static bool FailError => Environment.GetEnvironmentVariable("MOCK_FAIL") == "error";
    private static bool ForcedBusy => Environment.GetEnvironmentVariable("MOCK_BUSY") == "1";
    private static bool NoSpec => Environment.GetEnvironmentVariable("MOCK_NO_SPEC") == "1";
    private static bool SpecReject => Environment.GetEnvironmentVariable("MOCK_SPEC_REJECT") == "1";
    private static int TokenDelayMs =>
        int.TryParse(Environment.GetEnvironmentVariable("MOCK_DELAY_MS"), out int d) ? d : 0;

    private string ModelName => "mock-mini-1b";

    public MockServer(int port) => _port = port;

    public async Task RunAsync(CancellationToken ct)
    {
        var listener = new TcpListener(IPAddress.Loopback, _port);
        listener.Start();
        try
        {
            while (!ct.IsCancellationRequested)
            {
                TcpClient client;
                try
                {
                    client = await listener.AcceptTcpClientAsync(ct);
                }
                catch (OperationCanceledException) { break; }
                catch (SocketException) when (ct.IsCancellationRequested) { break; }
                _ = HandleConnectionAsync(client, ct);
            }
        }
        finally
        {
            listener.Stop();
        }
    }

    private async Task HandleConnectionAsync(TcpClient client, CancellationToken serverCt)
    {
        using var _ = client;
        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(serverCt);
            using var stream = client.GetStream();
            var req = await ReadRequestAsync(stream, cts.Token);
            if (req is null) return; // closed before headers or unreadable
            if (req.Error is not null) { await WriteJsonErrorAsync(stream, req.Error.Value.Code, req.Error.Value.Message, cts.Token); return; }
            await RouteAsync(stream, req, cts.Token);
        }
        catch (Exception e) when (e is IOException or SocketException or ObjectDisposedException or OperationCanceledException)
        {
            // dropped mid-write, or the server is being killed
        }
    }

    private async Task RouteAsync(Stream stream, Request req, CancellationToken ct)
    {
        string path = req.Path;
        string method = req.Method;

        if (path == "/health" && method == "GET")
        {
            await WriteJsonAsync(stream, 200, "{\"ok\":true,\"pf_api\":\"v1\",\"app\":\"PocketForgeAI-mock\"}", ct);
            return;
        }

        if (path == "/pair" && method == "POST")
        {
            await HandlePairAsync(stream, req.BodyText(), ct);
            return;
        }

        if (path.StartsWith("/v1/"))
        {
            if (!CheckAuth(req.Headers))
            {
                await WriteJsonErrorAsync(stream, 401, "unauthorized", ct);
                return;
            }

            switch (method + " " + path)
            {
                case "GET /v1/status": await HandleStatusAsync(stream, ct); return;
                case "GET /v1/models": await WriteJsonAsync(stream, 200, BuildModelsJson(), ct); return;
                case "POST /v1/chat/completions": await HandleChatAsync(stream, req.BodyText(), ct); return;
                case "POST /v1/cancel":
                    CancelGeneration();
                    await WriteJsonAsync(stream, 204, null, ct);
                    return;
            }

            if (path.StartsWith("/v1/spec/"))
            {
                // older phone build: the endpoints do not exist at all, not 405 nor a busy error
                if (NoSpec) { await WriteJsonErrorAsync(stream, 404, "not found", ct); return; }

                switch (method + " " + path)
                {
                    case "GET /v1/spec/capabilities": await HandleSpecCapabilitiesAsync(stream, ct); return;
                    case "POST /v1/spec/turn": await HandleSpecTurnAsync(stream, req.BodyText(), ct); return;
                    case "POST /v1/spec/verify": await HandleSpecVerifyAsync(stream, req.BodyText(), ct); return;
                    case "POST /v1/spec/finish": await HandleSpecFinishAsync(stream, req.BodyText(), ct); return;
                }
            }
        }

        await WriteJsonErrorAsync(stream, 404, "not found", ct);
    }

    private string BuildModelsJson() =>
        "{\"object\":\"list\",\"data\":[" +
        $"{{\"id\":\"{ModelName}\",\"object\":\"model\",\"owned_by\":\"PocketForgeAI\"}}]}}";

    private async Task HandlePairAsync(Stream stream, string bodyText, CancellationToken ct)
    {
        string? code = null;
        bool malformed = false;
        try
        {
            using var doc = JsonDocument.Parse(bodyText);
            if (doc.RootElement.ValueKind == JsonValueKind.Object &&
                doc.RootElement.TryGetProperty("code", out var c) && c.ValueKind == JsonValueKind.String)
                code = c.GetString();
            else
                malformed = true;
        }
        catch (JsonException) { malformed = true; }

        if (malformed)
        {
            await WriteJsonErrorAsync(stream, 400, "malformed request", ct);
            return;
        }

        if (code == "123456")
        {
            string token = "mock-token-" + Guid.NewGuid().ToString("N");
            _issuedToken = token;
            await WriteJsonAsync(stream, 200, $"{{\"token\":\"{token}\"}}", ct);
            return;
        }

        // sliding 60s window; the 6th failed attempt inside the window gets 429
        var now = DateTime.UtcNow;
        while (_failedPairAttempts.TryPeek(out var oldest) && (now - oldest).TotalSeconds > 60)
            _failedPairAttempts.TryDequeue(out _);

        if (_failedPairAttempts.Count >= MaxFailedPairsPerMinute)
        {
            await WriteJsonErrorAsync(stream, 429, "too many failed pair attempts", ct);
            return;
        }

        _failedPairAttempts.Enqueue(now);
        await WriteJsonErrorAsync(stream, 401, "invalid pairing code", ct);
    }

    private bool CheckAuth(IReadOnlyDictionary<string, string> headers)
    {
        if (_issuedToken is null) return false;
        return headers.TryGetValue("Authorization", out var v) && v == "Bearer " + _issuedToken;
    }

    private async Task HandleStatusAsync(Stream stream, CancellationToken ct)
    {
        bool generating = _genCts is not null;
        bool busy = generating || ForcedBusy;
        string json =
            $"{{\"api\":\"v1\",\"server\":true,\"port\":{_port},\"lan\":false," +
            $"\"state\":\"{(generating ? "Generating" : "ModelReady")}\",\"busy\":{(busy ? "true" : "false")}," +
            $"\"model\":{{\"loaded\":true,\"name\":\"{ModelName}\",\"desc\":\"mock model\",\"sizeBytes\":2700000000," +
            $"\"nCtx\":8192,\"nThreads\":6,\"backend\":\"cpu\"}}}}";
        await WriteJsonAsync(stream, 200, json, ct);
    }

    private static string JsonBool(bool b) => b ? "true" : "false";

    private async Task<bool> SpecBusyAsync(Stream stream, CancellationToken ct)
    {
        // same busy contract as chat: one generation at a time across the whole mock
        if (ForcedBusy || _genCts is not null)
        {
            await WriteJsonErrorAsync(stream, 409, "busy", ct);
            return true;
        }
        return false;
    }

    private async Task HandleSpecCapabilitiesAsync(Stream stream, CancellationToken ct)
    {
        await WriteJsonAsync(stream, 200, "{\"spec_verify\":true,\"n_vocab\":151936,\"greedy_only\":true}", ct);
    }

    private async Task HandleSpecTurnAsync(Stream stream, string bodyText, CancellationToken ct)
    {
        if (await SpecBusyAsync(stream, ct)) return;

        string user;
        int maxTokens;
        {
            JsonDocument doc;
            try { doc = JsonDocument.Parse(bodyText); }
            catch (JsonException) { await WriteJsonErrorAsync(stream, 400, "malformed request", ct); return; }
            using (doc)
            {
                var root = doc.RootElement;
                if (root.ValueKind != JsonValueKind.Object ||
                    !root.TryGetProperty("user", out var u) || u.ValueKind != JsonValueKind.String ||
                    string.IsNullOrWhiteSpace(u.GetString()))
                {
                    await WriteJsonErrorAsync(stream, 400, "malformed request", ct);
                    return;
                }
                user = u.GetString() ?? "";

                // tolerant on max_tokens like the chat handler is on stream; missing means no limit
                maxTokens = root.TryGetProperty("max_tokens", out var mt) && mt.ValueKind == JsonValueKind.Number
                    ? mt.GetInt32()
                    : 0;
            }
        }

        _specMaxNew = maxTokens;
        _specCommitted = 0;
        _specNextToken = 1;
        await WriteJsonAsync(stream, 200,
            $"{{\"ok\":true,\"next_token\":{_specNextToken},\"committed\":0,\"max_new\":{maxTokens}}}", ct);
    }

    private async Task HandleSpecVerifyAsync(Stream stream, string bodyText, CancellationToken ct)
    {
        if (await SpecBusyAsync(stream, ct)) return;

        List<int> tokens;
        {
            JsonDocument doc;
            try { doc = JsonDocument.Parse(bodyText); }
            catch (JsonException) { await WriteJsonErrorAsync(stream, 400, "malformed request", ct); return; }
            using (doc)
            {
                var root = doc.RootElement;
                if (root.ValueKind != JsonValueKind.Object ||
                    !root.TryGetProperty("tokens", out var ts) || ts.ValueKind != JsonValueKind.Array)
                {
                    await WriteJsonErrorAsync(stream, 400, "malformed request", ct);
                    return;
                }
                tokens = new List<int>();
                foreach (var t in ts.EnumerateArray())
                    if (t.ValueKind == JsonValueKind.Number && t.TryGetInt32(out int v))
                        tokens.Add(v);
            }
        }

        int remaining = Math.Max(0, _specMaxNew - _specCommitted);
        int accepted = SpecReject ? 0 : Math.Min(tokens.Count, remaining);
        string text = string.Join("|", tokens.Take(accepted));
        int committed = _specCommitted + accepted;
        bool done = committed >= _specMaxNew;

        _specCommitted = committed;
        int nextToken = _specNextToken++;

        await WriteJsonAsync(stream, 200,
            $"{{\"accepted\":{accepted},\"text\":{JsonString(text)},\"next_token\":{nextToken}," +
            $"\"committed\":{committed},\"done\":{JsonBool(done)}," +
            $"\"finish_reason\":{(done ? "\"length\"" : "null")}}}", ct);
    }

    private async Task HandleSpecFinishAsync(Stream stream, string bodyText, CancellationToken ct)
    {
        if (await SpecBusyAsync(stream, ct)) return;

        string text;
        {
            JsonDocument doc;
            try { doc = JsonDocument.Parse(bodyText); }
            catch (JsonException) { await WriteJsonErrorAsync(stream, 400, "malformed request", ct); return; }
            using (doc)
            {
                text = doc.RootElement.ValueKind == JsonValueKind.Object &&
                       doc.RootElement.TryGetProperty("text", out var t) && t.ValueKind == JsonValueKind.String
                    ? t.GetString() ?? ""
                    : "";
            }
        }

        int promptTokens = Tokenize(text).Count();
        int completionTokens = _specCommitted;
        int genMs = TokenDelayMs * completionTokens;
        await WriteJsonAsync(stream, 200,
            $"{{\"ok\":true,\"prompt_tokens\":{promptTokens},\"completion_tokens\":{completionTokens},\"gen_ms\":{genMs}}}", ct);
    }

    private void CancelGeneration()
    {
        var cts = Interlocked.Exchange(ref _genCts, null);
        cts?.Cancel();
    }

    private async Task HandleChatAsync(Stream stream, string bodyText, CancellationToken ct)
    {
        if (FailAuth) { await WriteJsonErrorAsync(stream, 401, "unauthorized", ct); return; }
        if (FailBig) { await WriteJsonErrorAsync(stream, 413, "request body too large", ct); return; }
        if (FailError) { await WriteJsonErrorAsync(stream, 500, "forced error", ct); return; }
        if (ForcedBusy || _genCts is not null)
        {
            await WriteJsonErrorAsync(stream, 409, "busy", ct);
            return;
        }

        string lastUser;
        string streamMode;
        int systemCount;
        bool systemNotFirst = false;
        {
            JsonDocument doc;
            try { doc = JsonDocument.Parse(bodyText); }
            catch (JsonException) { await WriteJsonErrorAsync(stream, 400, "malformed request", ct); return; }
            using (doc)
            {
                if (doc.RootElement.ValueKind != JsonValueKind.Object ||
                    !doc.RootElement.TryGetProperty("messages", out var msgs) ||
                    msgs.ValueKind != JsonValueKind.Array)
                {
                    await WriteJsonErrorAsync(stream, 400, "malformed request", ct);
                    return;
                }

                lastUser = "";
                systemCount = 0;
                int idx = -1; int lastUserIdx = -1;
                foreach (var m in msgs.EnumerateArray())
                {
                    idx++;
                    string role = m.TryGetProperty("role", out var r) && r.ValueKind == JsonValueKind.String ? r.GetString() ?? "" : "";
                    string content = m.TryGetProperty("content", out var c) && c.ValueKind == JsonValueKind.String ? c.GetString() ?? "" : "";
                    if (role == "system")
                    {
                        systemCount++;
                        if (idx > 0) systemNotFirst = true;
                    }
                    if (role == "user") { lastUser = content; lastUserIdx = idx; }
                }
                if (lastUserIdx < 0 || string.IsNullOrWhiteSpace(lastUser))
                {
                    await WriteJsonErrorAsync(stream, 400, "no user message", ct);
                    return;
                }
                streamMode = doc.RootElement.TryGetProperty("stream", out var s) && s.ValueKind == JsonValueKind.True ? "sse" : "json";
            }
        }

        if (systemCount > 1 || systemNotFirst)
        {
            await WriteJsonErrorAsync(stream, 400, "system message not allowed here", ct);
            return;
        }

        string reply = BuildReply(lastUser);
        var tokens = Tokenize(reply).ToArray();
        if (tokens.Length == 0) tokens = new[] { "Mock reply" };

        using var genCts = new CancellationTokenSource();
        Interlocked.Exchange(ref _genCts, genCts);
        try
        {
            if (streamMode == "sse")
                await StreamChatAsync(stream, tokens, genCts.Token, ct);
            else
            {
                int delay = TokenDelayMs;
                if (delay > 0) await Task.Delay(delay, ct);
                await WriteChatResultAsync(stream, tokens, ct);
            }
        }
        finally
        {
            Interlocked.CompareExchange(ref _genCts, null, genCts);
        }
    }

    private async Task StreamChatAsync(Stream stream, string[] tokens, CancellationToken genCt, CancellationToken connCt)
    {
        string id = "pf-" + Guid.NewGuid().ToString("N");
        await WriteSseHeadAsync(stream, connCt);

        bool cancelled = false;
        int delay = TokenDelayMs;
        for (int i = 0; i < tokens.Length && !cancelled; i++)
        {
            if (delay > 0)
            {
                try { await Task.Delay(delay, genCt); }
                catch (OperationCanceledException) { cancelled = true; break; }
            }
            if (genCt.IsCancellationRequested) { cancelled = true; break; }

            var frame = Encoding.UTF8.GetBytes(
                $"data: {{\"id\":\"{id}\",\"choices\":[{{\"index\":0,\"delta\":{{\"content\":{JsonString(tokens[i])}}},\"finish_reason\":null}}]}}\n\n");
            await stream.WriteAsync(frame, connCt);
            await stream.FlushAsync(connCt);
        }

        // the terminal frame still goes out after a cancel so the client can see finish_reason,
        // which is why these writes use the connection token, not the (cancelled) generation token
        string finish = cancelled ? "cancelled" : "stop";
        var term = Encoding.UTF8.GetBytes(
            $"data: {{\"id\":\"{id}\",\"choices\":[{{\"index\":0,\"delta\":{{}},\"finish_reason\":\"{finish}\"}}]}}\n\n");
        await stream.WriteAsync(term, connCt);
        await stream.WriteAsync(Encoding.UTF8.GetBytes("data: [DONE]\n\n"), connCt);
        await stream.FlushAsync(connCt);
    }

    private async Task WriteChatResultAsync(Stream stream, string[] tokens, CancellationToken ct)
    {
        string content = JsonString(string.Concat(tokens));
        string json =
            $"{{\"id\":\"pf-{Guid.NewGuid():N}\"," +
            "\"object\":\"chat.completion\"," +
            $"\"created\":{DateTimeOffset.UtcNow.ToUnixTimeSeconds()}," +
            $"\"model\":\"{ModelName}\"," +
            "\"pf_state\":\"ModelReady\"," +
            "\"choices\":[" +
            $"{{\"index\":0,\"message\":{{\"role\":\"assistant\",\"content\":{content}}},\"finish_reason\":\"stop\"}}]," +
            $"\"usage\":{{\"prompt_tokens\":8,\"completion_tokens\":{tokens.Length},\"prompt_ms\":1,\"gen_ms\":{TokenDelayMs * tokens.Length}}}}}";
        await WriteJsonAsync(stream, 200, json, ct);
    }

    private static string BuildReply(string lastUserMessage)
    {
        string text = lastUserMessage;
        if (text.Contains("TOOL RESULT", StringComparison.OrdinalIgnoreCase))
            return "Result acknowledged: " + text.Substring(0, Math.Min(60, text.Length));
        return "Mock reply: " + text.Substring(Math.Max(0, text.Length - 40));
    }

    // word-piece tokens with the trailing space kept, like a real tokenizer
    private static IEnumerable<string> Tokenize(string reply)
    {
        foreach (Match m in Regex.Matches(reply, @"\S+\s*"))
            if (m.Value.Length > 0)
                yield return m.Value;
    }

    private static string JsonString(string s) => JsonSerializer.Serialize(s);

    // ---- HTTP plumbing ----

    private static async Task<Request?> ReadRequestAsync(Stream stream, CancellationToken ct)
    {
        var headerBuf = new MemoryStream();
        var read = new byte[8192];
        int sepIndex = -1;

        while (true)
        {
            int sep = FindServerDoubleCrlf(headerBuf.GetBuffer(), (int)headerBuf.Length);
            if (sep >= 0) { sepIndex = sep; break; }
            if (headerBuf.Length > MaxHeaderBytes)
                return Request.CreateError(400, "header block too large");
            int n = await stream.ReadAsync(read, ct);
            if (n <= 0) return null;
            headerBuf.Write(read, 0, n);
        }

        byte[] all = headerBuf.ToArray();
        string headerText = Encoding.ASCII.GetString(all, 0, sepIndex);
        string rest = Encoding.ASCII.GetString(all, sepIndex + 4, all.Length - (sepIndex + 4));
        int bodyPrefixBytes = all.Length - (sepIndex + 4);

        var lines = headerText.Split("\r\n");
        var first = lines[0].Split(' ');
        if (first.Length < 2) return Request.CreateError(400, "malformed request line");
        string method = first[0];
        string target = first[1];
        string path = target.Length == 0 ? "" : target.Split('?', 2)[0];

        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var line in lines.Skip(1))
        {
            int c = line.IndexOf(':');
            if (c > 0) headers[line[..c].Trim()] = line[(c + 1)..].Trim();
        }

        if (headers.TryGetValue("Transfer-Encoding", out var te) && te.Contains("chunked", StringComparison.OrdinalIgnoreCase))
            return Request.CreateError(400, "chunked request bodies not supported");

        if (!headers.TryGetValue("Content-Length", out var clStr))
            return new Request(method, path, headers, Array.Empty<byte>());

        if (!int.TryParse(clStr, out int contentLength) || contentLength < 0)
            return Request.CreateError(400, "malformed Content-Length");

        if (contentLength > MaxBodyBytes)
            return Request.CreateError(413, "request body too large");

        var body = new byte[contentLength];
        int prefix = Math.Min(bodyPrefixBytes, contentLength);
        all.AsSpan(all.Length - bodyPrefixBytes).Slice(0, prefix).CopyTo(body);
        int total = prefix;
        while (total < contentLength)
        {
            int n = await stream.ReadAsync(body.AsMemory(total), ct);
            if (n <= 0) return Request.CreateError(400, "request body incomplete");
            total += n;
        }

        return new Request(method, path, headers, body);
    }

    // finds the "\r\n\r\n" separator inside the buffered bytes, returns index of its start, or -1
    private static int FindServerDoubleCrlf(byte[] buf, int length)
    {
        for (int i = 0; i + 3 < length; i++)
        {
            if (buf[i] == 13 && buf[i + 1] == 10 && buf[i + 2] == 13 && buf[i + 3] == 10)
                return i;
        }
        return -1;
    }

    private static async Task WriteJsonAsync(Stream s, int status, string? json, CancellationToken ct)
    {
        if (status == 204 || json is null)
        {
            // 204 has no body; Content-Length would be wrong here
            await s.WriteAsync(Encoding.UTF8.GetBytes("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"), ct);
            await s.FlushAsync(ct);
            return;
        }
        var body = Encoding.UTF8.GetBytes(json);
        var head = Encoding.UTF8.GetBytes(
            $"HTTP/1.1 {status} {Reason(status)}\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: {body.Length}\r\n\r\n");
        await s.WriteAsync(head, ct);
        await s.WriteAsync(body, ct);
        await s.FlushAsync(ct);
    }

    private static Task WriteJsonErrorAsync(Stream s, int code, string message, CancellationToken ct) =>
        WriteJsonAsync(s, code, $"{{\"error\":{{\"message\":\"{message}\",\"code\":{code}}}}}", ct);

    private static async Task WriteSseHeadAsync(Stream s, CancellationToken ct)
    {
        // no Content-Length: SSE is delimited by Connection: close
        await s.WriteAsync(Encoding.UTF8.GetBytes(
            "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: text/event-stream\r\n\r\n"), ct);
        await s.FlushAsync(ct);
    }

    private static string Reason(int code) => code switch
    {
        200 => "OK",
        400 => "Bad Request",
        401 => "Unauthorized",
        404 => "Not Found",
        405 => "Method Not Allowed",
        409 => "Conflict",
        411 => "Length Required",
        413 => "Payload Too Large",
        429 => "Too Many Requests",
        500 => "Internal Server Error",
        503 => "Service Unavailable",
        _ => "Unknown",
    };

    private sealed record Request(string Method, string Path, IReadOnlyDictionary<string, string> Headers, byte[] Body)
    {
        public (int Code, string Message)? Error { get; init; }
        public string BodyText() => Encoding.UTF8.GetString(Body);

        public static Request CreateError(int code, string message) => new("", "", new Dictionary<string, string>(), Array.Empty<byte>()) { Error = (code, message) };
    }
}
