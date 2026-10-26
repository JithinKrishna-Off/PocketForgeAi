# PocketForgeDesktop app

WPF front end for PocketForgeAI (phone-side llama.cpp server). Sources only;
the build output is not checked in.

Build and run from the repo root (see the top-level README.md):

```
dotnet build app/PocketForgeDesktop.csproj
dotnet run --project app/PocketForgeDesktop.csproj
```

Files of interest:

- `Models.cs` - shared client/broker data contract for the phone chat API.
- `ChatApi.cs` - `PhoneChatClient` (SSE streaming client, pairing, status,
  cancel) plus typed phone errors. WPF-free; compiles standalone in tests.
- `TokenStore.cs` - `%APPDATA%\PocketForgeDesktop\settings.json` persistence
  and DPAPI protection of the pairing token.
- `App.xaml(.cs)` - entry point and dark-aware theme brushes.
- `MainWindow.xaml(.cs)` - connection bar, chat, agent activity panel.
- `PairDialog.xaml(.cs)` - 6-digit pairing code dialog.
- `AgentLoopHost.cs` - only file referencing `McpTools.ToolLoop` (agent mode).
