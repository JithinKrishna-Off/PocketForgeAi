# McpTools: laptop-side tool host

## Overview

The model runs on the phone and the phone API carries plain chat only. Tool
calling is an orchestrator concern that lives on the Windows laptop. This
project (PocketForge.McpTools) provides that concern:

- an orchestrator loop (ToolLoop) that injects a tool protocol into the system
  prompt, parses tool-call blocks out of model output, runs the tools locally,
  and feeds results back as the next user message;
- a small set of workspace tools (read_file, list_dir, search_files,
  git_status, run_command) guarded by path and secret rules;
- a stdio JSON-RPC/MCP host (run `McpTools --server`) so the loop talks to the
  tools through the same McpStdioClient an external MCP client would use.

## Tool protocol

The system prompt gets a protocol section appended (built by
ToolRegistry.BuildProtocolSection). It lists every tool with its schema and
then tells the model, verbatim in structure:

    You can use tools on the user's Windows laptop. Available tools and their
    JSON arguments:
    <name>: <description> | args: <schema>   (one line per tool)

    To call a tool, END your reply with one fenced block exactly:

    ```pf-tool
    {"tool":"<name>","args":{...}}
    ```

    Use at most one tool call per reply. After a message beginning with
    "TOOL RESULT", continue the task. When done, reply normally with no
    pf-tool block.

Parse rules (ToolParser.FindLastToolBlock):

- the LAST ```pf-tool fenced block in a reply is the call; no block means the
  reply is final.
- the block body must be strict JSON: an object with a string "tool" and an
  optional object "args"; anything else re-parses to nothing and ends the turn.
- before running, the args are validated against the tool schema (required
  keys present, primitive types match). Unknown keys are ignored.
- the result is fed back as a user message: "TOOL RESULT name=<name>
  status=<ok|error>" followed by the tool output.
- more than one tool call in a turn is not supported; the loop actually takes
  the last block if the model emitted several.
- iteration cap: ToolLoop defaults to maxIterations 5 (at most 5 tool calls in
  one conversation run). Hitting the cap ends the run with an Error event.

## Tools

| name | args | defaults | caps | approval |
|---|---|---|---|---|
| read_file | path (string, required), offsetLines (int), maxBytes (int) | offsetLines 0, maxBytes 65536 | maxBytes cap 262144; output capped at 65536 bytes | no |
| list_dir | path (string, required), depth (int 1-3) | depth 1 | output capped at 16384 bytes | no |
| search_files | query (string regex, required), root (int), glob (string) | root 0, no glob | 200 matches; output capped at 16384 bytes | no |
| git_status | root (int) | root 0 | output capped at 16384 bytes | no |
| run_command | command (string, required), args (string[], required), root (int) | root 0 | 30s timeout; output capped at 16384 bytes | always |

Notes:
- search_files runs a case-sensitive regex per line with a 250 ms regex
  timeout budget per file and returns "path:line: trimmed-line" matches.
- list_dir output is "kind, name, size, last-write time" per entry
  (kind: f file, d directory; size: bytes, <dir> or <link>).
- read_file pages with byte budgets and appends
  "[note: reached byte budget of N; ...]" when truncated; when an explicit
  maxBytes is given and the budget is reached, the note is part of the output
  ("byte budget" is the marker string).

## Security rules

Workspace roots: loaded from tools.config.json ("workspaceRoots" array of
absolute dirs). Defaults if the file is missing or malformed:
C:\Users\ASUS\Desktop\PocketForgeAI\desktop and
C:\Users\ASUS\Desktop\PocketForgeAI. All tool paths are canonicalized with
Path.GetFullPath and must fall inside one root:

- relative paths resolve against root 0; absolute paths are used as-is after
  canonicalization.
- any ".." segment in the raw argument is rejected even where GetFullPath
  would land inside a root.
- UNC paths (\\server\share, //server/share) are rejected.
- symlink/junction defense: every ancestor of the target is checked with
  ResolveLinkTarget; a reparse point whose final target escapes the roots
  rejects the whole path.
- nonexistent paths are refused for read-style calls (requireExists).

Secret deny list (SecretGuard.IsDenied, checked by every tool and by the
search walker): filename patterns *.pem, *.key, *.pfx, *.p12, id_rsa*,
id_ed25519*, .env, *.env, credentials*, *.kdbx, secrets*,
appsettings.Secrets.json; a .git/config relative-tail entry; plus any path
whose name contains "token", "password" or "secret" (case-insensitive). A
denied read returns "blocked: potential secret file".

Output caps: every tool result is truncated to the tool's MaxOutputBytes at a
UTF-8 boundary with a "[truncated: ...]" marker (ToolOutput.Cap).

run_command: allowlist on the bare command name only (git, dotnet,
gradlew.bat; gradlew.bat must exist inside the target root). Executables are
resolved per current directory/PATH, no shell is ever involved (args go to
ArgumentList verbatim), timeout 30s with process-tree kill. The server also
requires the environment variable PF_MCP_RUN_APPROVED=1, which the desktop
process sets only for the server instance it spawned itself after user
approval.

## Approval flow

ToolLoop takes an optional IApprovalUi. A tool marked RequiresApproval
(only run_command in v1) is executed only if an approval UI is present and
returns true from ApproveAsync(tool, argsJson); otherwise the loop emits a
Denied event and feeds "The call was rejected and nothing ran" back to the
model. Read-only tools never prompt, so a UI-less host gets results directly.
The MCP server never prompts; approval belongs to the loop's host process,
which is why the spawned server gets PF_MCP_RUN_APPROVED=1.

## Adding a tool

1. Implement: add a public static class in Tools/BuiltinTools.cs (or a new
   file in the same namespace) with an Execute handler that takes a
   JsonElement plus ToolExecContext and returns a ToolCallResult, and validate
   args with the Args helpers. Keep it throw-safe: ToolRegistry.Execute
   already converts exceptions into blocked results.
2. Register: call <Tool>.Register(reg) in ToolRegistry.Build (ToolInfra.cs).
3. Schema string: set ArgsSchema to a JSON Schema object literal
   ({"type":"object","properties":{...},"required":[...]}) in the tool class;
   the same string drives the protocol prompt, MCP tools/list, and loop-side
   validation, so all three stay in sync.

## MCP wire subset

McpStdioClient spawns "McpTools --server" (or the configured dotnet + dll) and
speaks newline-delimited JSON-RPC 2.0 over stdio. protocolVersion used:
"2024-11-05". Implemented methods:

- initialize (request/response with serverInfo and tools capability) plus the
  notifications/initialized notification.
- tools/list returning { name, description, inputSchema } per tool.
- tools/call with { name, arguments } returning content: [{type:"text",
  text:"..."}] and isError; JSON-RPC errors come back as code -32602
  (invalid params) / -32601 (unknown method).
- notifications and unknown methods are ignored/answered -32601; responses
  are matched by numeric request id.

## Config file format

tools.config.json (copied next to the exe; loaded from AppContext.BaseDirectory):

    {
      "workspaceRoots": [
        "C:/Users/ASUS/Desktop/PocketForgeAI/desktop",
        "C:/Users/ASUS/Desktop/PocketForgeAI"
      ]
    }

Missing, malformed, or empty-array configs fall back to the defaults listed
under Security rules.

## Limitations (v1)

- read-only workspace tools; the only write-capable tool is run_command,
  which is approval-gated and allowlisted.
- no remote/network MCP servers: the only transport is the local stdio
  child process.
- single-process tool server: one spawned McpTools instance per
  McpStdioClient; no multiplexing, no named-pipe or TCP transports.
- at most one tool call per model reply and a fixed iteration cap; no
  parallel tool execution.
