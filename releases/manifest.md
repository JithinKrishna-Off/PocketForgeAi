# PocketForgeAI release manifest - v0.3.0 (phone server + Windows MCP desktop client)

Build date (UTC): 2026-10-26. Branch: research/breakthrough.

## Artifacts

| Artifact | Variant | ABI | Size (bytes) | SHA-256 |
|---|---|---|---|---|
| `android/PocketForgeAI-v0.3.0-server-arm64-v8a.apk` (versionName 0.3.0, versionCode 3) | debug (installable; project has no release signing config) | arm64-v8a | 28,020,490 | `52e509e701539329015b8fa780a493683432e3e8936da58a5e156dfa1b9d5a38` |
| `windows/PocketForgeDesktop-win-x64.zip` (app + MCP tool server, self-contained) | Release publish, win-x64 | x64 | 90,889,113 | `b44f71884aed37e101748d42b734d29c5f4867b3ad11854d7793afa1a6d2cffb` |

Sidecar `.sha256` files sit next to each artifact. Git note: the Android repo's
`.gitignore` ignores `*.apk` except under `artifacts/`, so the git-tracked copy of
the APK also lives at `artifacts/PocketForgeAI-v0.3.0-server-arm64-v8a.apk`; this
`releases/` folder is the delivery bundle.

## Verify

```powershell
Get-FileHash releases\android\PocketForgeAI-v0.3.0-server-arm64-v8a.apk -Algorithm SHA256
Get-FileHash releases\windows\PocketForgeDesktop-win-x64.zip -Algorithm SHA256
```

## What is in this release

- Android: additive desktop-server mode (Settings -> Desktop server): opt-in
  foreground service, pairing code + bearer tokens, `/health /pair /v1/status
  /v1/models /v1/chat/completions (SSE) /v1/cancel` per `docs/pocketforge-api-v1.md`.
  Loopback-only by default; LAN binding is an explicit toggle. All prior v0.2.0
  features (steering, hub, encrypted chats, panic wipe) unchanged.
- Windows: `PocketForgeDesktop.exe` (WPF chat client) + `McpTools.exe` (MCP stdio
  tool server: search_files, read_file, list_dir, git_status, approval-gated
  run_command) + `tools.config.json` (workspace roots).

## Reproduce

Android: `gradlew assembleDebug` (repo root; arm64-v8a only; jniLibs prebuilt).
Windows: `desktop/build.ps1 -Configuration Release` (dotnet 10 SDK; single-file
self-contained publish + zip + hash).

## Device steps still required (no device was attached during this build)

1. Install APK, enable Desktop server in Settings, note the pairing code.
2. Desktop: run PocketForgeDesktop.exe, enter phone IP (Wi-Fi) or use
   `adb reverse tcp:8751 tcp:8751` and connect to 127.0.0.1:8751.
3. Pair, load a model on the phone, chat from the desktop; test Stop and
   disconnect; then enable Agent mode with Agent mode toggle + tools approval.

## Known limitations

- No hardware verification this cycle (no device attached): pairing/stream/
  cancel/tool approval are verified only against the contract mock (29/29) and
  unit-level selftests (35/35).
- Server mode shares the model session with in-app chat (one conversation state);
  concurrent generation returns 409.
- dataSync foreground service on Android 14+ is subject to system timeout policy.
- Tool calling is a laptop-side protocol (system-prompt adapter); models that do
  not follow it just answer normally - no tool calls are forced.
