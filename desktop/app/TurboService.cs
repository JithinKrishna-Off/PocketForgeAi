using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace PocketForgeDesktop;

public enum TurboFailureKind { Unavailable, Error }

// Thrown on any turbo problem; the UI shows the message and runs the turn in
// normal mode. TurnStarted tells whether the phone already consumed a partial
// turbo turn, so the fallback should restart the phone-side session.
public sealed class TurboFallbackException : Exception
{
    public TurboFailureKind Kind { get; }
    public bool TurnStarted { get; }

    public TurboFallbackException(TurboFailureKind kind, string message, bool turnStarted) : base(message)
    {
        Kind = kind;
        TurnStarted = turnStarted;
    }

    public static TurboFallbackException Unavailable(string reason) =>
        new(TurboFailureKind.Unavailable, reason, false);

    public static TurboFallbackException Error(string message, bool turnStarted) =>
        new(TurboFailureKind.Error, message, turnStarted);
}

public sealed record TurboConfig(
    bool TurboEnabled, string DraftExePath, string DraftModelPath, int DraftPort, int DraftK)
{
    // Paths default to the exe folder; settings may override with absolute or
    // relative paths (relative resolves against the exe folder).
    public static TurboConfig FromSettings(DesktopSettings s)
    {
        string baseDir = AppContext.BaseDirectory;
        string exe = ResolvePath(s.DraftExePath, Path.Combine(baseDir, "tools", "draft", "llama-server.exe"), baseDir);
        string model = ResolvePath(s.DraftModelPath, Path.Combine(baseDir, "models", "draft.gguf"), baseDir);
        int port = s.DraftPort is >= 1 and <= 65535 ? s.DraftPort : 8755;
        int k = s.DraftK <= 0 ? 4 : Math.Clamp(s.DraftK, 2, 8);
        return new TurboConfig(s.TurboEnabled, exe, model, port, k);
    }

    private static string ResolvePath(string? value, string fallback, string baseDir)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return fallback;
        }
        return Path.IsPathRooted(value) ? value : Path.Combine(baseDir, value);
    }
}

public sealed record TurboTurnRequest(
    string User,
    IReadOnlyList<ChatMessage> Conversation,
    string? System,
    int MaxTokens,
    bool ResetSession);

// Desktop-orchestrated speculative decoding: the phone still owns the session
// and verifies tokens, the local llama-server (loopback) drafts K per round.
public sealed class TurboService
{
    // Must stay byte-identical to the phone-side probe (pf_llm.cpp kSpecTokenizerProbe).
    internal const string TokenizerProbe = "The quick brown fox jumps over the lazy dog. PocketForge tokenizer probe 12345.";

    // Pulled automatically when models\draft.gguf is missing; same model the
    // manifest describes (Qwen3.5-0.8B Q8_0, qwen35 tokenizer pre, 248320 vocab).
    internal const string DraftModelUrl =
        "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q8_0.gguf";

    private static readonly HttpClient DownloadHttp = new() { Timeout = Timeout.InfiniteTimeSpan };

    private static TurboService? _instance;
    public static TurboService? Shared => _instance;

    private readonly TurboDraftServer _draft = new();
    private PhoneChatClient? _capabilitiesPhone;
    private int _phoneNvocab = -1; // -1: not checked yet
    private int _manifestNvocab = -1; // -1: no usable manifest

    public TurboService()
    {
        _instance = this;
    }

    public void Shutdown() => _draft.Stop();

    // Runs the prechecks (once per phone connection) and then one turn:
    // spec turn on the phone, then verify rounds driven by draft candidates.
    public async Task RunTurnAsync(
        PhoneChatClient phone, TurboConfig cfg, TurboTurnRequest req, Action<string> onText,
        CancellationToken ct, Action<string>? onStatus = null)
    {
        await WarmUpAsync(phone, cfg, ct, onStatus);

        bool turnStarted = false;
        try
        {
            SpecTurn start = await phone.SpecTurnAsync(req.User, req.System, req.MaxTokens, req.ResetSession, ct);
            turnStarted = true;

            // the phone reports how many tokens it still intends to generate
            int remaining = start.MaxNew > 0 ? Math.Min(start.MaxNew, req.MaxTokens) : req.MaxTokens;
            var generated = new StringBuilder();
            while (remaining > 0)
            {
                ct.ThrowIfCancellationRequested();
                if (!_draft.IsRunning)
                {
                    throw TurboFallbackException.Error(
                        "draft server exited unexpectedly (exit code " + _draft.LastExitCode + ")", turnStarted);
                }

                int k = Math.Min(cfg.DraftK, remaining);
                var draftMessages = new List<ChatMessage>(req.Conversation)
                {
                    new("assistant", generated.ToString()),
                };
                var (ids, _) = await _draft.DraftCandidatesAsync(draftMessages, k, ct);

                SpecVerify v = await phone.SpecVerifyAsync(ids, ct);
                if (v.Text.Length > 0)
                {
                    generated.Append(v.Text);
                    onText(v.Text);
                }
                remaining -= v.Committed;
                if (v.Done)
                {
                    break;
                }
                if (v.Committed <= 0)
                {
                    // no forward progress: fall back instead of spinning
                    throw TurboFallbackException.Error(
                        "phone committed no tokens for the draft candidates", turnStarted);
                }
            }
        }
        catch (TurboFallbackException)
        {
            throw;
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (PhoneApiException pex)
        {
            if (turnStarted)
            {
                try { await phone.SpecFinishAsync("", CancellationToken.None); } catch { }
            }
            throw TurboFallbackException.Error(
                "phone spec/" + (turnStarted ? "verify" : "turn") + " error " + pex.StatusCode + ": " + pex.Message,
                turnStarted);
        }
        catch (HttpRequestException hex)
        {
            if (turnStarted)
            {
                try { await phone.SpecFinishAsync("", CancellationToken.None); } catch { }
            }
            throw TurboFallbackException.Error("connection problem: " + hex.Message, turnStarted);
        }
        catch (Exception ex)
        {
            if (turnStarted)
            {
                try { await phone.SpecFinishAsync("", CancellationToken.None); } catch { }
            }
            throw TurboFallbackException.Error("turbo turn failed: " + ex.Message, turnStarted);
        }
    }

    // Validates the draft exe/model paths, starts or reuses the draft server,
    // checks the phone's spec support, and compares the draft manifest vocab.
    // Called when the toggle turns on and at the start of every turbo turn.
    public async Task WarmUpAsync(
        PhoneChatClient? phone, TurboConfig cfg, CancellationToken ct, Action<string>? onStatus = null)
    {
        if (!File.Exists(cfg.DraftExePath))
        {
            throw TurboFallbackException.Unavailable(
                "draft runner not found at " + cfg.DraftExePath +
                ". Expected tools\\draft\\llama-server.exe next to the app folder, or set DraftExePath in %APPDATA%\\PocketForgeDesktop\\settings.json");
        }
        if (!File.Exists(cfg.DraftModelPath))
        {
            onStatus?.Invoke("draft model missing; downloading...");
            await DownloadDraftModelAsync(cfg.DraftModelPath, onStatus, ct);
        }

        if (!_draft.IsRunning || _draft.Port != cfg.DraftPort)
        {
            try
            {
                await _draft.StartAsync(cfg.DraftExePath, cfg.DraftModelPath, cfg.DraftPort, ct);
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex)
            {
                throw TurboFallbackException.Error("draft server failed to start: " + ex.Message, turnStarted: false);
            }
        }

        if (phone != null && !ReferenceEquals(phone, _capabilitiesPhone))
        {
            SpecCapabilities caps = await CheckPhoneCapabilitiesAsync(phone, ct);
            await CheckTokenizerProbeAsync(caps, ct);
        }
        CheckManifest(cfg);
    }

    // Streams DraftModelUrl to targetPath. Progress is surfaced through
    // onStatus (throttled); a partial file is cleaned up on failure.
    private async Task DownloadDraftModelAsync(string targetPath, Action<string>? onStatus, CancellationToken ct)
    {
        string partial = targetPath + ".download";
        try
        {
            using var resp = await DownloadHttp.GetAsync(
                DraftModelUrl, HttpCompletionOption.ResponseHeadersRead, ct);
            resp.EnsureSuccessStatusCode();
            long total = resp.Content.Headers.ContentLength ?? -1;
            var dir = Path.GetDirectoryName(targetPath);
            if (!string.IsNullOrEmpty(dir))
            {
                Directory.CreateDirectory(dir);
            }

            long done = 0;
            int lastPct = -1;
            await using (var src = await resp.Content.ReadAsStreamAsync(ct))
            await using (var dst = new FileStream(partial, FileMode.Create, FileAccess.Write, FileShare.None, 256 * 1024))
            {
                var buf = new byte[256 * 1024];
                while (true)
                {
                    int n = await src.ReadAsync(buf, ct);
                    if (n <= 0)
                    {
                        break;
                    }
                    await dst.WriteAsync(buf.AsMemory(0, n), ct);
                    done += n;
                    if (total > 0)
                    {
                        int pct = (int)(done * 100 / total);
                        if (pct >= lastPct + 5)
                        {
                            lastPct = pct;
                            onStatus?.Invoke("downloading draft model " + pct + "% (" + done / (1024 * 1024) + " MB)");
                        }
                    }
                }
            }

            File.Move(partial, targetPath, overwrite: true);
            WriteManifestIfMissing(targetPath, new FileInfo(targetPath).Length);
            onStatus?.Invoke("draft model ready (" + new FileInfo(targetPath).Length / (1024 * 1024) + " MB)");
        }
        catch (OperationCanceledException)
        {
            TryDelete(partial);
            throw;
        }
        catch (Exception ex)
        {
            TryDelete(partial);
            throw TurboFallbackException.Unavailable("draft model download failed: " + ex.Message);
        }
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) { File.Delete(path); } }
        catch { /* best effort; the next warmup retries the download */ }
    }

    // Identity is known from the shipped manifest; write one only when absent
    // so a downloaded model still passes the n_vocab compat check.
    private static void WriteManifestIfMissing(string modelPath, long bytes)
    {
        string manifestPath = modelPath + ".manifest.json";
        if (File.Exists(manifestPath))
        {
            return;
        }
        try
        {
            File.WriteAllText(manifestPath,
                "{\"file\":\"" + Path.GetFileName(modelPath) + "\",\"n_vocab\":248320,\"arch\":\"qwen35\"," +
                "\"name\":\"Qwen3.5-0.8B\",\"size_label\":\"773M\",\"quant\":\"Q8_0\"," +
                "\"tokenizer_pre\":\"qwen35\",\"context_length\":262144,\"eos_token_id\":248046," +
                "\"downloaded_from\":\"" + DraftModelUrl + "\",\"bytes\":" + bytes + "}");
        }
        catch (IOException)
        {
            // manifest is optional; the vocab is still checked at runtime
        }
    }

    private async Task<SpecCapabilities> CheckPhoneCapabilitiesAsync(PhoneChatClient phone, CancellationToken ct)
    {
        SpecCapabilities caps;
        try
        {
            caps = await phone.GetSpecCapabilitiesAsync(ct);
        }
        catch (PhoneApiException pex) when (pex.StatusCode == 404)
        {
            throw TurboFallbackException.Unavailable("phone build lacks turbo verification");
        }
        catch (PhoneApiException pex)
        {
            throw TurboFallbackException.Error("spec capabilities check failed: " + pex.Message, turnStarted: false);
        }
        if (!caps.SpecVerify)
        {
            throw TurboFallbackException.Unavailable("phone build lacks turbo verification");
        }
        _capabilitiesPhone = phone;
        _phoneNvocab = caps.NVocab;
        if (_manifestNvocab >= 0 && _phoneNvocab >= 0 && _manifestNvocab != _phoneNvocab)
        {
            throw TokenizerMismatch();
        }
        return caps;
    }

    // Tokenizes the fixed probe string with the draft model and compares the ids
    // against the ones the phone reported for its (target model) tokenizer.
    private async Task CheckTokenizerProbeAsync(SpecCapabilities caps, CancellationToken ct)
    {
        // Old phone builds report no probe; skip the compare (n_vocab still guards).
        if (caps.Probe.Length == 0)
        {
            return;
        }
        int[] draftIds = await _draft.TokenizeIdsAsync(TokenizerProbe, ct);
        if (draftIds.Length == 0)
        {
            ct.ThrowIfCancellationRequested(); // empty may be a cancel, not a tokenize failure
            throw TurboFallbackException.Unavailable("draft tokenizer probe failed");
        }
        var phoneIds = new List<int>(draftIds.Length);
        foreach (string part in caps.Probe.Split(','))
        {
            string s = part.Trim();
            if (s.Length == 0)
            {
                continue; // tolerate stray commas
            }
            if (!int.TryParse(s, out int id))
            {
                throw ProbeMismatch(draftIds.Length, phoneIds.Count);
            }
            phoneIds.Add(id);
        }
        if (phoneIds.Count != draftIds.Length || !phoneIds.SequenceEqual(draftIds))
        {
            throw ProbeMismatch(draftIds.Length, phoneIds.Count);
        }
    }

    private TurboFallbackException ProbeMismatch(int draftCount, int phoneCount) =>
        TurboFallbackException.Unavailable(
            "tokenizer probe mismatch (draft " + draftCount + " ids, phone " + phoneCount + " ids)");

    private void CheckManifest(TurboConfig cfg)
    {
        // read fresh each warmup; cheap and always correct
        _manifestNvocab = -1;
        string path = cfg.DraftModelPath + ".manifest.json";
        if (!File.Exists(path))
        {
            return; // manifest file is optional
        }
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(path));
            if (doc.RootElement.TryGetProperty("n_vocab", out var nv) && nv.TryGetInt32(out int n))
            {
                _manifestNvocab = n;
            }
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            return; // unreadable manifest: treat as absent
        }
        if (_manifestNvocab >= 0 && _phoneNvocab >= 0 && _manifestNvocab != _phoneNvocab)
        {
            throw TokenizerMismatch();
        }
    }

    private TurboFallbackException TokenizerMismatch() =>
        TurboFallbackException.Unavailable(
            "tokenizer mismatch (draft manifest n_vocab " + _manifestNvocab +
            ", phone n_vocab " + _phoneNvocab + ")");
}
