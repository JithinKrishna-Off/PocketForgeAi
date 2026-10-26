# Task board - phone server + Windows MCP desktop client

| # | Workstream | Owner | Scope | Key files (ownership) | Status |
|---|---|---|---|---|---|
| 1 | Android inference server | Agent 1 (lead-fixed) | HTTP/1.1 server, SSE, pairing/auth, FGS, settings card | app/src/main/java/com/pocketforge/ai/server/** (new), SettingsStore.kt, SettingsViewModel.kt, SettingsScreen.kt, AndroidManifest.xml | DONE (builds; fixes: PhoneAuth.kt nested comment) |
| 2 | Windows WPF client | Agent 2 (lead-fixed) | Chat UI, connection, streaming, pairing dialog, agent-mode wiring | desktop/app/**, desktop/README.md | DONE (builds clean) |
| 3 | MCP host + tools | Agent 3 (fix agent) | Tool loop, 5 tools, PathGuard/SecretGuard, MCP stdio server+client, selftest | desktop/McpTools/**, desktop/docs/mcp-tools.md | DONE (35/35 selftest; PathGuard symlink-check bug fixed) |
| 4 | Mock server + contract tests | Agent 4 (fix agent) | MockPhone, SelfTest PartA/B/C | desktop/tools/MockPhone/**, desktop/tools/SelfTest/** | DONE (29/29; mock busy-flag fidelity fixed) |
| 5 | Integration + delivery | Lead | build/smoke scripts, board, baseline, release folders, manifest, consolidation | desktop/build.ps1, desktop/smoke-test.ps1, desktop/docs/baseline.md, releases/** | DONE |

## Ownership rules in force
- Only the lead runs gradle. Agents may dotnet-build only their own csproj.
- Shared type contracts: app/Models.cs (IPhoneChatClient), app/IModelClientAdapter.cs
  (IModelClient, PocketForgeDesktop) bridged to PocketForge.McpTools by AgentLoopHost.cs;
  McpTools/Contracts.cs is the tool-side source of truth.
- Contract doc: docs/pocketforge-api-v1.md (canonical, lead-owned).

## Integration steps (all completed)
1. Lead fixed csproj wiring (SelfTest TFM + references) and Kotlin syntax bug.
2. Android assembleDebug PASS; desktop solution build PASS.
3. SelfTest 29/29 PASS against MockPhone; McpTools selftest 35/35 PASS.
4. Desktop moved into PocketForgeAI/desktop/; scripts re-pointed; both builds re-verified.
5. Release artifacts produced (see releases/manifest.md).
