using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace PocketForgeDesktop;

public class PhoneApiException : Exception
{
    public int StatusCode { get; }
    public int? ErrorCode { get; }

    public PhoneApiException(string message, int statusCode, int? errorCode = null) : base(message)
    {
        StatusCode = statusCode;
        ErrorCode = errorCode;
    }
}

public sealed class UnauthorizedPhoneException : PhoneApiException
{
    public UnauthorizedPhoneException(string message, int? errorCode = null) : base(message, 401, errorCode) { }
}

public sealed class BusyPhoneException : PhoneApiException
{
    public BusyPhoneException(string message, int? errorCode = null) : base(message, 409, errorCode) { }
}

public sealed class ModelNotReadyException : PhoneApiException
{
    public ModelNotReadyException(string message, int? errorCode = null) : base(message, 503, errorCode) { }
}

public sealed class PhoneChatClient : IPhoneChatClient, IModelClient
{
    private readonly HttpClient _http;
    private readonly string _host;
    private readonly int _port;
    private string? _token;

    // The phone applies a system prompt from the first request of a session and
    // rejects later system messages, so a system message is sent at most once
    // per phone-side session (cleared again by reset_session).
    private bool _systemSent;

    // Set by the UI (agent mode) so the next IModelClient turn starts a fresh
    // phone-side session; consumed and cleared by the first chat request.
    public bool NextTurnResetsSession { get; set; }

    // IModelClient turns advertise agent priority so the phone turn queue
    // schedules them ahead of plain chat turns.
    public bool AgentMode { get; set; }

    private static readonly JsonSerializerOptions JsonOpts = new() { PropertyNameCaseInsensitive = true };

    public PhoneChatClient(string host, int port, string? token = null)
    {
        _host = host;
        _port = port;
        _token = token;
        _http = new HttpClient
        {
            // No global timeout: SSE streams are long-lived; every operation
            // carries its own CancellationToken instead.
            Timeout = Timeout.InfiniteTimeSpan
        };
    }

    public string BaseUrl => $"http://{_host}:{_port}";

    public void SetToken(string token) => _token = token;

    public void Dispose()
    {
        _http.Dispose();
    }

    public async Task<HealthInfo> ProbeAsync(string host, int port, CancellationToken ct)
    {
        try
        {
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct);
            linked.CancelAfter(TimeSpan.FromSeconds(2));
            using var req = new HttpRequestMessage(HttpMethod.Get, $"http://{host}:{port}/health");
            using var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseContentRead, linked.Token).ConfigureAwait(false);
            if (resp.StatusCode != HttpStatusCode.OK)
            {
                return new HealthInfo(false, "");
            }
            var json = await resp.Content.ReadAsStringAsync(linked.Token).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            bool ok = root.TryGetProperty("ok", out var okEl) && okEl.ValueKind == JsonValueKind.True;
            string api = root.TryGetProperty("pf_api", out var apiEl) && apiEl.ValueKind == JsonValueKind.String
                ? apiEl.GetString() ?? ""
                : "";
            return new HealthInfo(ok, api);
        }
        catch (Exception ex)
            when (ex is HttpRequestException or IOException or JsonException or OperationCanceledException or NotSupportedException or ObjectDisposedException)
        {
            // A caller cancellation should propagate; everything else (and the
            // internal 2s timeout) means "no reachable PocketForge server".
            ct.ThrowIfCancellationRequested();
            return new HealthInfo(false, "");
        }
    }

    public async Task<StatusInfo> GetStatusAsync(CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, BaseUrl + "/v1/status");
        AddAuth(req);
        using var resp = await _http.SendAsync(req, ct).ConfigureAwait(false);
        if (resp.StatusCode != HttpStatusCode.OK)
        {
            throw await BuildApiErrorAsync(resp, ct).ConfigureAwait(false);
        }
        var json = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(json);
        return ParseStatus(doc.RootElement);
    }

    public async Task<string> PairAsync(string pairingCode, CancellationToken ct)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct);
        linked.CancelAfter(TimeSpan.FromSeconds(8));
        string body = JsonSerializer.Serialize(new { code = pairingCode });
        using var req = new HttpRequestMessage(HttpMethod.Post, BaseUrl + "/pair")
        {
            Content = new StringContent(body, Encoding.UTF8, "application/json")
        };
        using var resp = await _http.SendAsync(req, linked.Token).ConfigureAwait(false);
        if (resp.StatusCode != HttpStatusCode.OK)
        {
            throw await BuildApiErrorAsync(resp, linked.Token).ConfigureAwait(false);
        }
        var json = await resp.Content.ReadAsStringAsync(linked.Token).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(json);
        string? token = doc.RootElement.TryGetProperty("token", out var t) && t.ValueKind == JsonValueKind.String
            ? t.GetString()
            : null;
        return token ?? throw new PhoneApiException("pairing response missing token", (int)resp.StatusCode);
    }

    public async Task CancelAsync(CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Post, BaseUrl + "/v1/cancel");
        AddAuth(req);
        using var resp = await _http.SendAsync(req, ct).ConfigureAwait(false);
        if ((int)resp.StatusCode >= 300)
        {
            throw await BuildApiErrorAsync(resp, ct).ConfigureAwait(false);
        }
    }

    // 429 from the phone turn queue: wait retry_after (bounded to 30 s) and
    // retry once before surfacing the error to the caller.
    private async Task<HttpResponseMessage> SendQueueAwareAsync(
        Func<HttpRequestMessage> factory, HttpCompletionOption opt, CancellationToken ct)
    {
        var resp = await _http.SendAsync(factory(), opt, ct).ConfigureAwait(false);
        if (resp.StatusCode != (HttpStatusCode)429)
        {
            return resp;
        }
        string body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        int retryAfter = ParseQueueRetryAfter(body);
        if (retryAfter <= 0)
        {
            return resp; // caller turns the response into an exception
        }
        resp.Dispose();
        await Task.Delay(TimeSpan.FromSeconds(retryAfter), ct).ConfigureAwait(false);
        return await _http.SendAsync(factory(), opt, ct).ConfigureAwait(false);
    }

    private static int ParseQueueRetryAfter(string body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.TryGetProperty("retry_after", out var el) && el.TryGetInt32(out int s))
            {
                return Math.Clamp(s, 1, 30);
            }
        }
        catch (JsonException)
        {
        }
        return 2;
    }

    public async IAsyncEnumerable<ChatDelta> StreamChatAsync(
        IReadOnlyList<ChatMessage> messages, ChatOptions options, [EnumeratorCancellation] CancellationToken ct)
    {
        bool reset = options.ResetSession || NextTurnResetsSession;
        var turn = BuildTurn(messages, reset);
        string body = BuildRequestBody(turn, stream: true, options, reset);

        // ResponseHeadersRead: hand the SSE stream back to the caller while it
        // is still open; the per-request lifetime is owned by this request.
        using var resp = await SendQueueAwareAsync(() =>
        {
            var req = new HttpRequestMessage(HttpMethod.Post, BaseUrl + "/v1/chat/completions")
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json")
            };
            AddAuth(req);
            return req;
        }, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        if (resp.StatusCode != HttpStatusCode.OK)
        {
            throw await BuildApiErrorAsync(resp, ct).ConfigureAwait(false);
        }

        using var stream = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var reader = new StreamReader(stream, Encoding.UTF8);

        while (true)
        {
            string? line = await reader.ReadLineAsync(ct).ConfigureAwait(false);
            if (line == null)
            {
                // Server closed the stream (contract: it cancels the turn on
                // connection close; nothing more to deliver).
                yield break;
            }
            line = line.TrimEnd('\r').Trim();
            if (line.Length == 0 || line.StartsWith(':'))
            {
                continue; // keep-alive/comment line
            }
            if (line.StartsWith("event:", StringComparison.OrdinalIgnoreCase))
            {
                continue; // the payload that follows carries the details
            }
            if (!line.StartsWith("data:", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            string payload = line.Substring(5).Trim();
            if (payload == "[DONE]")
            {
                yield break;
            }

            string? content = null;
            string? finish = null;
            string? error = null;
            try
            {
                using var doc = JsonDocument.Parse(payload);
                var root = doc.RootElement;
                if (root.TryGetProperty("error", out var errEl) && errEl.ValueKind == JsonValueKind.Object)
                {
                    error = errEl.TryGetProperty("message", out var m) && m.ValueKind == JsonValueKind.String
                        ? m.GetString()
                        : errEl.ToString();
                }
                else if (root.TryGetProperty("choices", out var choices) && choices.ValueKind == JsonValueKind.Array && choices.GetArrayLength() > 0)
                {
                    var choice = choices[0];
                    if (choice.TryGetProperty("delta", out var delta) && delta.ValueKind == JsonValueKind.Object
                        && delta.TryGetProperty("content", out var c) && c.ValueKind == JsonValueKind.String)
                    {
                        content = c.GetString();
                    }
                    if (choice.TryGetProperty("finish_reason", out var f) && f.ValueKind == JsonValueKind.String)
                    {
                        finish = f.GetString();
                    }
                }
            }
            catch (JsonException)
            {
                continue; // tolerate malformed frames rather than killing the stream
            }

            bool wentError = error != null;
            if (wentError || (content ?? finish) != null)
            {
                yield return new ChatDelta(content, wentError ? null : finish, error);
            }
            if (wentError)
            {
                yield break; // contract: an error event ends the stream
            }
        }
    }

    public async Task<ChatResult> ChatAsync(IReadOnlyList<ChatMessage> messages, ChatOptions options, CancellationToken ct)
    {
        bool reset = options.ResetSession || NextTurnResetsSession;
        var turn = BuildTurn(messages, reset);
        string body = BuildRequestBody(turn, stream: false, options, reset);

        using var resp = await SendQueueAwareAsync(() =>
        {
            var req = new HttpRequestMessage(HttpMethod.Post, BaseUrl + "/v1/chat/completions")
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json")
            };
            AddAuth(req);
            return req;
        }, HttpCompletionOption.ResponseContentRead, ct).ConfigureAwait(false);
        if (resp.StatusCode != HttpStatusCode.OK)
        {
            throw await BuildApiErrorAsync(resp, ct).ConfigureAwait(false);
        }
        var json = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;

        string content = "";
        string finish = "stop";
        int prompt = 0;
        int completion = 0;
        if (root.TryGetProperty("choices", out var choices) && choices.ValueKind == JsonValueKind.Array && choices.GetArrayLength() > 0)
        {
            var choice = choices[0];
            if (choice.TryGetProperty("message", out var msg) && msg.TryGetProperty("content", out var c) && c.ValueKind == JsonValueKind.String)
            {
                content = c.GetString() ?? "";
            }
            if (choice.TryGetProperty("finish_reason", out var f) && f.ValueKind == JsonValueKind.String)
            {
                finish = f.GetString() ?? finish;
            }
        }
        if (root.TryGetProperty("usage", out var usage))
        {
            if (usage.TryGetProperty("prompt_tokens", out var p) && p.TryGetInt32(out var pv)) prompt = pv;
            if (usage.TryGetProperty("completion_tokens", out var cc) && cc.TryGetInt32(out var cv)) completion = cv;
        }
        return new ChatResult(content, finish, prompt, completion);
    }

    /// <summary>
    /// Adapter for the McpTools tool-loop. The phone holds the session, so
    /// only the newest system prompt (first turn of a session) and the newest
    /// user text are ever put on the wire.
    /// </summary>
    public async IAsyncEnumerable<string> StreamAsync(
        IReadOnlyList<(string Role, string Content)> messages, [EnumeratorCancellation] CancellationToken ct)
    {
        var turn = new List<ChatMessage>();
        var sysTuple = messages.LastOrDefault(m => m.Role == "system");
        if (sysTuple != default)
        {
            turn.Add(new ChatMessage(sysTuple.Role, sysTuple.Content));
        }
        var userTuple = messages.LastOrDefault(m => m.Role == "user");
        string text = userTuple != default
            ? userTuple.Content
            : messages.Count > 0 ? messages[^1].Content : "";
        turn.Add(new ChatMessage("user", text));

        await foreach (var d in StreamChatAsync(turn, new ChatOptions(Agent: AgentMode), ct).ConfigureAwait(false))
        {
            if (d.Content != null)
            {
                yield return d.Content;
            }
        }
    }

    private List<ChatMessage> BuildTurn(IReadOnlyList<ChatMessage> messages, bool reset)
    {
        if (reset)
        {
            _systemSent = false;
            NextTurnResetsSession = false;
        }
        var turn = new List<ChatMessage>();
        string? system = messages.LastOrDefault(m => m.Role == "system")?.Content;
        bool sendSystem = system != null && !_systemSent;
        if (system != null && sendSystem)
        {
            turn.Add(new ChatMessage("system", system));
        }
        var user = messages.LastOrDefault(m => m.Role == "user");
        if (user != null)
        {
            turn.Add(user); // only the new user text; prior turns live in the phone session
        }
        if (sendSystem)
        {
            _systemSent = true;
        }
        return turn;
    }

    private static string BuildRequestBody(List<ChatMessage> turn, bool stream, ChatOptions options, bool reset)
    {
        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartObject();
            w.WriteStartArray("messages");
            foreach (var m in turn)
            {
                w.WriteStartObject();
                w.WriteString("role", m.Role);
                w.WriteString("content", m.Content);
                w.WriteEndObject();
            }
            w.WriteEndArray();
            w.WriteBoolean("stream", stream);
            w.WriteNumber("max_tokens", options.MaxTokens);
            w.WriteStartArray("stop");
            if (options.Stop != null)
            {
                foreach (var s in options.Stop)
                {
                    w.WriteStringValue(s);
                }
            }
            w.WriteEndArray();
            w.WriteBoolean("reset_session", reset);
            w.WriteNumber("seed", 0);
            w.WriteEndObject();
        }
        return Encoding.UTF8.GetString(ms.ToArray());
    }

    private void AddAuth(HttpRequestMessage req)
    {
        if (_token != null)
        {
            req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _token);
        }
    }

    private static StatusInfo ParseStatus(JsonElement root)
    {
        bool server = root.TryGetProperty("server", out var s) && s.ValueKind == JsonValueKind.True;
        string state = root.TryGetProperty("state", out var st) && st.ValueKind == JsonValueKind.String
            ? st.GetString() ?? ""
            : "";
        bool busy = root.TryGetProperty("busy", out var b) && b.ValueKind == JsonValueKind.True;

        ModelInfo? model = null;
        if (root.TryGetProperty("model", out var m) && m.ValueKind == JsonValueKind.Object)
        {
            bool loaded = m.TryGetProperty("loaded", out var l) && l.ValueKind == JsonValueKind.True;
            string? name = GetString(m, "name");
            string? desc = GetString(m, "desc");
            long size = m.TryGetProperty("sizeBytes", out var sz) && sz.TryGetInt64(out var sv) ? sv : 0;
            long nCtx = m.TryGetProperty("nCtx", out var cx) && cx.TryGetInt64(out var cv) ? cv : 0;
            int nThreads = m.TryGetProperty("nThreads", out var th) && th.TryGetInt32(out var tv) ? tv : 0;
            string? backend = GetString(m, "backend");
            model = new ModelInfo(loaded, name, desc, size, nCtx, nThreads, backend);
        }
        return new StatusInfo(server, state, busy, model);
    }

    private static string? GetString(JsonElement parent, string name)
    {
        return parent.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.String ? el.GetString() : null;
    }

    // ---- SpecApi: distributed speculative decoding (turbo mode) ------------
    // Additive v1 extension; auth and session ownership are unchanged.

    public async Task<SpecCapabilities> GetSpecCapabilitiesAsync(CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, BaseUrl + "/v1/spec/capabilities");
        AddAuth(req);
        using var resp = await _http.SendAsync(req, ct).ConfigureAwait(false);
        if (resp.StatusCode != HttpStatusCode.OK)
        {
            throw await BuildApiErrorAsync(resp, ct).ConfigureAwait(false);
        }
        var json = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        return new SpecCapabilities(
            SpecVerify: GetBool(root, "spec_verify"),
            NVocab: GetInt(root, "n_vocab"),
            GreedyOnly: GetBool(root, "greedy_only"),
            Probe: GetString(root, "probe") ?? "");
    }

    public async Task<SpecTurn> SpecTurnAsync(
        string user, string? system, int maxTokens, bool resetSession, CancellationToken ct)
    {
        string body = BuildSpecTurnBody(user, system, maxTokens, resetSession);
        using var resp = await SendQueueAwareAsync(() =>
        {
            var req = new HttpRequestMessage(HttpMethod.Post, BaseUrl + "/v1/spec/turn")
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json")
            };
            AddAuth(req);
            return req;
        }, HttpCompletionOption.ResponseContentRead, ct).ConfigureAwait(false);
        if (resp.StatusCode != HttpStatusCode.OK)
        {
            throw await BuildApiErrorAsync(resp, ct).ConfigureAwait(false);
        }
        var json = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        var turn = new SpecTurn(
            Ok: GetBool(root, "ok"),
            NextToken: GetInt(root, "next_token"),
            Committed: GetInt(root, "committed"),
            MaxNew: GetInt(root, "max_new"));
        if (!turn.Ok)
        {
            throw new PhoneApiException("spec turn reported ok=false", (int)resp.StatusCode);
        }
        return turn;
    }

    public async Task<SpecVerify> SpecVerifyAsync(int[] tokens, CancellationToken ct)
    {
        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartObject();
            w.WriteStartArray("tokens");
            foreach (int t in tokens)
            {
                w.WriteNumberValue(t);
            }
            w.WriteEndArray();
            w.WriteEndObject();
        }
        string body = Encoding.UTF8.GetString(ms.ToArray());
        using var req = new HttpRequestMessage(HttpMethod.Post, BaseUrl + "/v1/spec/verify")
        {
            Content = new StringContent(body, Encoding.UTF8, "application/json")
        };
        AddAuth(req);
        using var resp = await _http.SendAsync(req, ct).ConfigureAwait(false);
        if (resp.StatusCode != HttpStatusCode.OK)
        {
            throw await BuildApiErrorAsync(resp, ct).ConfigureAwait(false);
        }
        var json = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        return new SpecVerify(
            Accepted: GetInt(root, "accepted"),
            Text: GetString(root, "text") ?? "",
            NextToken: GetInt(root, "next_token"),
            Committed: GetInt(root, "committed"),
            Done: GetBool(root, "done"),
            FinishReason: GetString(root, "finish_reason") ?? "");
    }

    // End-of-turn bookkeeping: the phone records usage stats; the response body
    // carries nothing the caller needs.
    public async Task SpecFinishAsync(string text, CancellationToken ct)
    {
        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartObject();
            w.WriteString("text", text);
            w.WriteEndObject();
        }
        using var req = new HttpRequestMessage(HttpMethod.Post, BaseUrl + "/v1/spec/finish")
        {
            Content = new StringContent(Encoding.UTF8.GetString(ms.ToArray()), Encoding.UTF8, "application/json")
        };
        AddAuth(req);
        using var resp = await _http.SendAsync(req, ct).ConfigureAwait(false);
        if ((int)resp.StatusCode >= 300)
        {
            throw await BuildApiErrorAsync(resp, ct).ConfigureAwait(false);
        }
    }

    private static string BuildSpecTurnBody(string user, string? system, int maxTokens, bool resetSession)
    {
        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartObject();
            w.WriteString("user", user);
            if (system != null)
            {
                w.WriteString("system", system);
            }
            else
            {
                w.WriteNull("system");
            }
            w.WriteNumber("max_tokens", maxTokens);
            if (resetSession)
            {
                w.WriteBoolean("reset_session", true);
            }
            w.WriteEndObject();
        }
        return Encoding.UTF8.GetString(ms.ToArray());
    }

    private static bool GetBool(JsonElement parent, string name)
    {
        return parent.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.True;
    }

    private static int GetInt(JsonElement parent, string name)
    {
        return parent.TryGetProperty(name, out var el) && el.TryGetInt32(out int v) ? v : 0;
    }

    private static async Task<PhoneApiException> BuildApiErrorAsync(HttpResponseMessage resp, CancellationToken ct)
    {
        string message = $"HTTP {(int)resp.StatusCode}";
        int? errorCode = null;
        try
        {
            var json = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            if (root.TryGetProperty("error", out var err) && err.ValueKind == JsonValueKind.Object)
            {
                message = err.TryGetProperty("message", out var m) && m.ValueKind == JsonValueKind.String
                    ? m.GetString() ?? message
                    : err.ToString();
                if (err.TryGetProperty("code", out var c) && c.TryGetInt32(out var cv))
                {
                    errorCode = cv;
                }
            }
        }
        catch (JsonException)
        {
            // non-JSON error body: keep the generic message
        }
        return (int)resp.StatusCode switch
        {
            401 => new UnauthorizedPhoneException(message, errorCode),
            409 => new BusyPhoneException(message, errorCode),
            503 => new ModelNotReadyException(message, errorCode),
            _ => new PhoneApiException(message, (int)resp.StatusCode, errorCode),
        };
    }
}
