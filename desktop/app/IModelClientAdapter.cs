using PocketForge.McpTools;
namespace PocketForgeDesktop;

public interface IModelClient
{
    IAsyncEnumerable<string> StreamAsync(IReadOnlyList<(string Role, string Content)> messages, CancellationToken ct);
    Task CancelAsync(CancellationToken ct);
}
