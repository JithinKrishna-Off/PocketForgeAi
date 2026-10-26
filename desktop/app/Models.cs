namespace PocketForgeDesktop;

public record ChatMessage(string Role, string Content);
public sealed record ChatOptions(bool Stream = true, int MaxTokens = 2048, string[]? Stop = null, bool ResetSession = false);
public sealed record ChatDelta(string? Content, string? FinishReason, string? Error);
public sealed record ChatResult(string Content, string FinishReason, int PromptTokens, int CompletionTokens);
public sealed record HealthInfo(bool Ok, string Api);
public sealed record StatusInfo(bool Server, string State, bool Busy, ModelInfo? Model);
public sealed record ModelInfo(bool Loaded, string? Name, string? Desc, long SizeBytes, long NCtx, int NThreads, string? Backend, bool SteerActive, string? SteerSpec);
public record PairResult(string Token);

public interface IPhoneChatClient : IDisposable
{
    Task<HealthInfo> ProbeAsync(string host, int port, CancellationToken ct);
    Task<StatusInfo> GetStatusAsync(CancellationToken ct);
    Task<string> PairAsync(string pairingCode, CancellationToken ct);
    IAsyncEnumerable<ChatDelta> StreamChatAsync(IReadOnlyList<ChatMessage> messages, ChatOptions options, CancellationToken ct);
    Task<ChatResult> ChatAsync(IReadOnlyList<ChatMessage> messages, ChatOptions options, CancellationToken ct);
    Task CancelAsync(CancellationToken ct);
}
