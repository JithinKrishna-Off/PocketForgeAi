using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace PocketForgeDesktop;

// Owns the llama-server child process that drafts tokens for turbo mode.
// Loopback only; one process for the whole app; killed before a restart.
public sealed class TurboDraftServer : IDisposable
{
    private Process? _proc;
    private HttpClient? _http;
    private int _port;
    private int _lastExitCode = -1;

    public bool IsRunning => _proc is { HasExited: false };
    public int Port => _port;
    public int LastExitCode => _lastExitCode;

    // Health poll waits up to 60 s: draft models can be large to load.
    public async Task StartAsync(string exePath, string modelPath, int port, CancellationToken ct)
    {
        if (IsRunning)
        {
            Stop(); // never spawn duplicates
        }

        var psi = new ProcessStartInfo
        {
            FileName = exePath,
            Arguments = "-m \"" + modelPath.Replace("\"", "\\\"") + "\" --port " + port +
                        " --host 127.0.0.1 -c 4096 --no-webui",
            WorkingDirectory = Path.GetDirectoryName(exePath) ?? ".",
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };

        var p = new Process { StartInfo = psi, EnableRaisingEvents = true };
        p.Exited += OnExited;
        try
        {
            if (!p.Start())
            {
                throw new InvalidOperationException("the process could not be started");
            }
        }
        catch (Exception ex)
        {
            p.Dispose();
            throw new InvalidOperationException("failed to launch " + exePath + ": " + ex.Message);
        }

        _proc = p;
        _port = port;

        _http?.Dispose();
        _http = new HttpClient
        {
            BaseAddress = new Uri("http://127.0.0.1:" + port),
            Timeout = Timeout.InfiniteTimeSpan,
        };

        var sw = Stopwatch.StartNew();
        while (true)
        {
            ct.ThrowIfCancellationRequested();
            if (p.HasExited)
            {
                throw new InvalidOperationException(
                    "draft server exited during startup (exit code " + TryExitCode(p) + ")");
            }
            if (await ProbeHealthyAsync(ct))
            {
                return;
            }
            if (sw.Elapsed >= TimeSpan.FromSeconds(60))
            {
                throw new InvalidOperationException(
                    "draft server not healthy within 60 s (check the draft model path)");
            }
            await Task.Delay(500, ct);
        }
    }

    private async Task<bool> ProbeHealthyAsync(CancellationToken ct)
    {
        HttpClient? http = _http;
        if (http == null)
        {
            return false;
        }
        try
        {
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct);
            linked.CancelAfter(TimeSpan.FromSeconds(2));
            using var resp = await http.GetAsync("/health", linked.Token);
            if (resp.StatusCode != HttpStatusCode.OK)
            {
                return false;
            }
            string body = await resp.Content.ReadAsStringAsync(linked.Token);
            using var doc = JsonDocument.Parse(body);
            return doc.RootElement.TryGetProperty("status", out var s) &&
                   s.ValueKind == JsonValueKind.String &&
                   s.GetString() == "ok";
        }
        catch (Exception ex)
            when (ex is HttpRequestException or JsonException or OperationCanceledException or ObjectDisposedException)
        {
            return false; // outer ct cancels are rethrown at the poll loop head
        }
    }

    // One draft round: render the conversation at temperature 0 and pull K
    // draft token ids. Prefers an explicit token-id array in the response;
    // falls back to /v1/tokenize on the returned text.
    public async Task<(int[] Ids, string Content)> DraftCandidatesAsync(
        IReadOnlyList<ChatMessage> messages, int maxTokens, CancellationToken ct)
    {
        var http = _http ?? throw new InvalidOperationException("draft server is not running");

        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartObject();
            w.WriteStartArray("messages");
            foreach (var m in messages)
            {
                w.WriteStartObject();
                w.WriteString("role", m.Role);
                w.WriteString("content", m.Content);
                w.WriteEndObject();
            }
            w.WriteEndArray();
            w.WriteNumber("max_tokens", maxTokens);
            w.WriteNumber("temperature", 0);
            w.WriteBoolean("stream", false);
            w.WriteBoolean("cache_prompt", true);
            w.WriteEndObject();
        }

        using var req = new HttpRequestMessage(HttpMethod.Post, "/v1/chat/completions")
        {
            Content = new StringContent(Encoding.UTF8.GetString(ms.ToArray()), Encoding.UTF8, "application/json"),
        };
        using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseContentRead, ct);
        string json = await resp.Content.ReadAsStringAsync(ct);
        if (resp.StatusCode != HttpStatusCode.OK)
        {
            throw new InvalidOperationException("draft server HTTP " + (int)resp.StatusCode + ": " + Summarize(json));
        }
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;

        int[] ids = ExtractTokenIds(root);
        string content = ExtractContent(root);
        if (ids.Length == 0 && content.Length > 0)
        {
            ids = await TokenizeAsync(http, content, ct);
        }
        return (ids, content);
    }

    // Single place that knows how to pull draft token ids out of a response.
    private static int[] ExtractTokenIds(JsonElement root)
    {
        if (root.TryGetProperty("tokens", out var top) && top.ValueKind == JsonValueKind.Array)
        {
            return ParseIds(top);
        }
        if (root.TryGetProperty("choices", out var choices) && choices.ValueKind == JsonValueKind.Array &&
            choices.GetArrayLength() > 0)
        {
            var choice = choices[0];
            if (choice.TryGetProperty("tokens", out var inner) && inner.ValueKind == JsonValueKind.Array)
            {
                return ParseIds(inner);
            }
        }
        return Array.Empty<int>();
    }

    private static int[] ParseIds(JsonElement arr)
    {
        var ids = new List<int>();
        foreach (var el in arr.EnumerateArray())
        {
            if (el.ValueKind == JsonValueKind.Number && el.TryGetInt32(out int v))
            {
                ids.Add(v);
            }
        }
        return ids.ToArray();
    }

    private static string ExtractContent(JsonElement root)
    {
        if (root.TryGetProperty("choices", out var choices) && choices.ValueKind == JsonValueKind.Array &&
            choices.GetArrayLength() > 0)
        {
            var choice = choices[0];
            if (choice.TryGetProperty("message", out var msg) &&
                msg.TryGetProperty("content", out var c) && c.ValueKind == JsonValueKind.String)
            {
                return c.GetString() ?? "";
            }
            if (choice.TryGetProperty("text", out var t) && t.ValueKind == JsonValueKind.String)
            {
                return t.GetString() ?? "";
            }
        }
        return "";
    }

    // Tokenize arbitrary content with the draft model's tokenizer; the single
    // implementation is TokenizeAsync below.
    public Task<int[]> TokenizeIdsAsync(string content, CancellationToken ct)
    {
        HttpClient? http = _http;
        if (http == null)
        {
            return Task.FromResult(Array.Empty<int>());
        }
        return TokenizeAsync(http, content, ct);
    }

    private static async Task<int[]> TokenizeAsync(HttpClient http, string content, CancellationToken ct)
    {
        try
        {
            string body = JsonSerializer.Serialize(new { content, add_special = false });
            using var req = new HttpRequestMessage(HttpMethod.Post, "/v1/tokenize")
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json"),
            };
            using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseContentRead, ct);
            if (resp.StatusCode != HttpStatusCode.OK)
            {
                return Array.Empty<int>();
            }
            string json = await resp.Content.ReadAsStringAsync(ct);
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            if (root.ValueKind == JsonValueKind.Array)
            {
                return ParseIds(root);
            }
            if (root.TryGetProperty("tokens", out var t) && t.ValueKind == JsonValueKind.Array)
            {
                return ParseIds(t);
            }
        }
        catch (Exception ex)
            when (ex is HttpRequestException or JsonException or OperationCanceledException or ObjectDisposedException)
        {
            // no ids from the fallback either; the caller decides what to do
        }
        return Array.Empty<int>();
    }

    private static string Summarize(string body) => body.Length <= 300 ? body : body.Substring(0, 300) + "...";

    private void OnExited(object? sender, EventArgs e)
    {
        try
        {
            var p = _proc;
            _lastExitCode = p is { HasExited: true } ? p.ExitCode : -1;
        }
        catch (InvalidOperationException)
        {
        }
    }

    private static int TryExitCode(Process p)
    {
        try
        {
            return p.HasExited ? p.ExitCode : -1;
        }
        catch (InvalidOperationException)
        {
            return -1;
        }
    }

    public void Stop()
    {
        var p = _proc;
        _proc = null;
        if (p != null)
        {
            p.Exited -= OnExited;
            try
            {
                if (!p.HasExited)
                {
                    p.Kill(entireProcessTree: true);
                }
            }
            catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception)
            {
            }
            try { p.Dispose(); } catch { }
        }
        _http?.Dispose();
        _http = null;
    }

    public void Dispose() => Stop();
}
