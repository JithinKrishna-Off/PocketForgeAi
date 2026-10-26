using System.Runtime.CompilerServices;
using System.Text;

namespace PocketForge.McpTools;

// Orchestrates the model <-> tool cycle on the laptop:
// model reply -> parse pf-tool block -> validate/approve -> run via MCP ->
// feed "TOOL RESULT..." as the next user message -> repeat or finish.
// The system prompt carries the tool protocol appended to the caller's own
// system prompt.
public sealed class ToolLoop
{
    private readonly IModelClient _client;
    private readonly IMcpToolClient _tools;
    private readonly IApprovalUi? _approval;
    private readonly int _maxIterations;
    private static ToolRegistry? _registryOverride;

    // The registry used for prompt building and call validation. Defaults to
    // the built-in set (name/schema must always match the MCP server).
    public static ToolRegistry Registry
    {
        get => _registryOverride ?? ToolRegistry.Default;
        set => _registryOverride = value;
    }

    public ToolLoop(IModelClient client, IMcpToolClient tools, IApprovalUi? approval, int maxIterations = 5)
    {
        _client = client ?? throw new ArgumentNullException(nameof(client));
        _tools = tools ?? throw new ArgumentNullException(nameof(tools));
        _approval = approval;
        _maxIterations = Math.Max(1, maxIterations);
    }

    public async IAsyncEnumerable<LoopEvent> RunAsync(
        string userText, string? systemPrompt,
        [EnumeratorCancellation] CancellationToken ct = default)
    {
        var messages = new List<(string Role, string Content)>();
        string sys = (string.IsNullOrWhiteSpace(systemPrompt) ? "" : systemPrompt!.TrimEnd() + "\n\n")
                     + Registry.BuildProtocolSection();
        messages.Add(("system", sys));
        messages.Add(("user", userText ?? ""));

        int iteration = 0;
        while (true)
        {
            var buffer = new StringBuilder();
            var enumerator = _client.StreamAsync(messages, ct).GetAsyncEnumerator(ct);
            bool cancelled = false;
            string? streamError = null;
            try
            {
                while (true)
                {
                    string? delta;
                    bool has;
                    try
                    {
                        has = await enumerator.MoveNextAsync().ConfigureAwait(false);
                        delta = has ? enumerator.Current : null;
                    }
                    catch (OperationCanceledException) { cancelled = true; break; }
                    catch (Exception ex) { streamError = ex.Message; break; }

                    if (!has) break;
                    buffer.Append(delta);
                    yield return new LoopEvent(LoopEventKind.AssistantDelta, Delta: delta);
                }
            }
            finally
            {
                await enumerator.DisposeAsync().ConfigureAwait(false);
            }

            if (cancelled)
            {
                try { await _client.CancelAsync(CancellationToken.None).ConfigureAwait(false); } catch { }
                yield return new LoopEvent(LoopEventKind.Done);
                yield break;
            }
            if (streamError is not null)
            {
                yield return ErrorEvent("model stream failed: " + streamError);
                yield return new LoopEvent(LoopEventKind.Done);
                yield break;
            }

            iteration++;
            string reply = buffer.ToString();

            var call = ToolParser.FindLastToolBlock(reply);
            if (call is null)
            {
                yield return new LoopEvent(LoopEventKind.Done, Delta: reply.Trim());
                yield break;
            }

            string name = call.Value.Name;
            string argsJson = call.Value.ArgsJson;
            yield return new LoopEvent(LoopEventKind.ToolRequested, ToolName: name, ToolArgs: argsJson);

            string rejected = ValidateCall(name, argsJson);
            string nextText;
            if (rejected.Length > 0)
            {
                yield return new LoopEvent(LoopEventKind.Denied, ToolName: name, ToolArgs: argsJson, Error: rejected);
                nextText = "The call was rejected and nothing ran. Reason:\n" + rejected;
            }
            else if (Registry.TryGet(name, out var def) && def.RequiresApproval)
            {
                bool approved = _approval is not null &&
                                await SafeApproveAsync(_approval, name, argsJson, ct).ConfigureAwait(false);
                if (!approved)
                {
                    yield return new LoopEvent(LoopEventKind.Denied,
                        ToolName: name, ToolArgs: argsJson, Error: "the user declined or no approval UI");
                    nextText = "The call was rejected and nothing ran. Reason:\ndenied by user approval";
                }
                else
                {
                    nextText = null;
                }
            }
            else
            {
                nextText = null;
            }

            if (nextText is not null)
            {
                if (iteration >= _maxIterations)
                {
                    yield return ErrorEvent("tool call limit reached (" + _maxIterations + ")");
                    yield return new LoopEvent(LoopEventKind.Done);
                    yield break;
                }
                messages.Add(("user", nextText));
                continue;
            }

            ToolCallResult result;
            bool toolCancelled = false;
            string? toolError = null;
            try
            {
                result = await _tools.CallAsync(name, argsJson, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                toolCancelled = true;
                result = new ToolCallResult(false, "");
            }
            catch (Exception ex)
            {
                toolError = ex.Message;
                result = new ToolCallResult(false, "");
            }

            if (toolCancelled)
            {
                try { await _client.CancelAsync(CancellationToken.None).ConfigureAwait(false); } catch { }
                yield return new LoopEvent(LoopEventKind.Done);
                yield break;
            }
            if (toolError is not null)
            {
                yield return ErrorEvent("tool execution failed: " + toolError);
                yield return new LoopEvent(LoopEventKind.Done);
                yield break;
            }

            yield return new LoopEvent(LoopEventKind.ToolResult,
                ToolName: name, ToolArgs: argsJson, Result: result.Output, Ok: result.Ok);

            if (iteration >= _maxIterations)
            {
                yield return ErrorEvent("tool call limit reached (" + _maxIterations + ")");
                yield return new LoopEvent(LoopEventKind.Done);
                yield break;
            }

            messages.Add(("user", "TOOL RESULT name=" + name + " status=" + (result.Ok ? "ok" : "error") + "\n" + result.Output));
        }
    }

    private async Task<bool> SafeApproveAsync(IApprovalUi ui, string name, string argsJson, CancellationToken ct)
    {
        try
        {
            return await ui.ApproveAsync(name, argsJson).ConfigureAwait(false);
        }
        catch (OperationCanceledException) { throw; }
        catch
        {
            return false;
        }
    }

    // returns empty string when the call is acceptable, else the rejection reason
    private static string ValidateCall(string name, string argsJson)
    {
        if (!Registry.TryGet(name, out var def))
            return "unknown tool '" + name + "'";

        try
        {
            using var doc = System.Text.Json.JsonDocument.Parse(argsJson);
            if (doc.RootElement.ValueKind != System.Text.Json.JsonValueKind.Object)
                return "tool args must be a JSON object";
        }
        catch
        {
            return "tool args are not valid JSON";
        }

        var err = SchemaValidator.Validate(def.ArgsSchema, argsJson);
        return err.Length > 0 ? "schema violation: " + err : "";
    }

    private static LoopEvent ErrorEvent(string message) =>
        new(LoopEventKind.Error, Error: message);
}

// Extracts the last ```pf-tool fenced block and its strict JSON payload.
public static class ToolParser
{
    public static (string Name, string ArgsJson)? FindLastToolBlock(string reply)
    {
        const string open = "```pf-tool";
        int idx = reply.LastIndexOf(open, StringComparison.Ordinal);
        if (idx < 0) return null;

        int after = idx + open.Length;
        if (after < reply.Length && reply[after] == '\r') after++;
        if (after < reply.Length && reply[after] == '\n') after++;

        int close = reply.IndexOf("```", after, StringComparison.Ordinal);
        string body = (close < 0 ? reply[after..] : reply[after..close]).Trim();
        if (body.Length == 0) return null;

        try
        {
            using var doc = System.Text.Json.JsonDocument.Parse(body);
            if (doc.RootElement.ValueKind != System.Text.Json.JsonValueKind.Object) return null;
            if (!doc.RootElement.TryGetProperty("tool", out var toolEl) ||
                toolEl.ValueKind != System.Text.Json.JsonValueKind.String)
                return null;
            string name = toolEl.GetString()!.Trim();
            if (name.Length == 0) return null;

            string argsJson = "{}";
            if (doc.RootElement.TryGetProperty("args", out var argsEl))
            {
                if (argsEl.ValueKind != System.Text.Json.JsonValueKind.Object) return null;
                argsJson = argsEl.GetRawText();
            }
            return (name, argsJson);
        }
        catch
        {
            return null;
        }
    }
}

// Lightweight arg validation from a JSON schema: required keys present and,
// when the property dict has a primitive "type", the matching kind.
internal static class SchemaValidator
{
    public static string Validate(string schemaText, string argsJson)
    {
        try
        {
            using var schema = System.Text.Json.JsonDocument.Parse(schemaText);
            using var args = System.Text.Json.JsonDocument.Parse(argsJson);
            return TypeCheck(schema.RootElement, args.RootElement, "");
        }
        catch
        {
            return "invalid schema or args document";
        }
    }

    private static string TypeCheck(System.Text.Json.JsonElement schema, System.Text.Json.JsonElement value, string path)
    {
        if (schema.ValueKind == System.Text.Json.JsonValueKind.Object)
        {
            if (schema.TryGetProperty("properties", out var props) &&
                props.ValueKind == System.Text.Json.JsonValueKind.Object)
            {
                // required keys
                if (schema.TryGetProperty("required", out var req) &&
                    req.ValueKind == System.Text.Json.JsonValueKind.Array)
                {
                    foreach (var r in req.EnumerateArray())
                    {
                        if (r.ValueKind != System.Text.Json.JsonValueKind.String) continue;
                        string key = r.GetString()!;
                        if (!value.ValueKind.ToString().Equals("Object") ||
                            !value.TryGetProperty(key, out _))
                            return "missing required arg '" + key + "'";
                    }
                }
                // types of provided keys
                if (value.ValueKind == System.Text.Json.JsonValueKind.Object)
                {
                    foreach (var p in props.EnumerateObject())
                    {
                        if (!value.TryGetProperty(p.Name, out var v)) continue; // unknown keys ignored by design
                        if (p.Value.ValueKind == System.Text.Json.JsonValueKind.Object &&
                            p.Value.TryGetProperty("type", out var t) &&
                            t.ValueKind == System.Text.Json.JsonValueKind.String)
                        {
                            var kind = t.GetString();
                            bool ok = kind switch
                            {
                                "string" => v.ValueKind == System.Text.Json.JsonValueKind.String,
                                "integer" => v.ValueKind == System.Text.Json.JsonValueKind.Number && v.TryGetInt32(out _),
                                "number" => v.ValueKind == System.Text.Json.JsonValueKind.Number,
                                "boolean" => v.ValueKind == System.Text.Json.JsonValueKind.True || v.ValueKind == System.Text.Json.JsonValueKind.False,
                                "array" => v.ValueKind == System.Text.Json.JsonValueKind.Array,
                                "object" => v.ValueKind == System.Text.Json.JsonValueKind.Object,
                                _ => true
                            };
                            if (!ok)
                                return "arg '" + p.Name + "' must be of type " + kind;
                        }
                    }
                }
            }
        }
        return "";
    }
}
