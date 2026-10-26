# Integration report - phone AI server + Windows MCP desktop client

Status: integrated, both builds green, contract tests green. On-device runtime
verification pending (no device attached in the build environment).

## Workstream status

| Workstream | State | Evidence |
|---|---|---|
| Agent 1: Android server | DONE (lead-fixed 1 syntax bug) | `gradlew assembleDebug` PASS; code in `app/src/main/java/com/pocketforge/ai/server/` |
| Agent 2: WPF desktop client | DONE | solution build PASS (0 errors) |
| Agent 3: MCP host + tools | DONE (fix agent) | McpTools selftest 35/35 |
| Agent 4: mock + contract tests | DONE (fix agent) | SelfTest 29/29 vs MockPhone |
| Agent 5: packaging/delivery | DONE (lead) | build.ps1, smoke-test.ps1, releases/**, manifest |

## Cross-cutting notes

- One session, one engine: the phone holds conversation state; desktop sends the
  incremental user message; concurrent generation -> 409. In-app chat and the
  server share the engine's single-threaded dispatcher (no JNI races observed at
  code level; final verdict needs a device).
- Tool calling never touches the wire protocol: laptop-side system-prompt adapter
  (`docs/mcp-tools.md`), strict JSON parse, allowlist + PathGuard/SecretGuard,
  approval UI for run_command. Models that ignore the protocol just chat.
- MCP transport: JSON-RPC 2.0 over stdio between the WPF app and McpTools.exe
  (initialize / tools/list / tools/call). No network MCP.
- Duplicated interface note: `IModelClient` exists both as `PocketForgeDesktop`
  (app) and `PocketForge.McpTools` (tools); AgentLoopHost bridges them. A future
  cleanup can collapse to one definition; kept as-is to avoid touching two
  agents' shipped code under deadline.

## Model-specific limitations

- Tool-call reliability depends on the model following the pf-tool protocol;
  Qwen3.5-style instruct models are expected to comply, but this is prompt-level,
  not template-enforced. Fallback = plain chat (no tools).
- The phone's status includes steering state, but steering is orthogonal to the
  server (spec applies at model load).

## Pending (needs a device)

1. Install v0.3.0 APK, enable Desktop server, pair from desktop.
2. Wi-Fi (phone IP) and USB (`adb reverse tcp:8751 tcp:8751`) connection paths.
3. Streaming latency/tps overhead check vs in-app chat (same model, same sampler).
4. MCP approval flow on the real UI (run_command gating).
5. dataSync FGS behavior on the device's Android version (13/14/15 differences).
