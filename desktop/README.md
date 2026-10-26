# PocketForgeDesktop

Windows desktop client (WPF, .NET 10) that chats with the PocketForgeAI phone
server. The phone runs llama.cpp locally and exposes the HTTP API described in
`PocketForgeAI/docs/pocketforge-api-v1.md`.

## Build

```
dotnet build app/PocketForgeDesktop.csproj
```

Requires the .NET 10 SDK (`10.0.2xx` was used for development). Windows only
(WPF).

## Run

```
dotnet run --project app/PocketForgeDesktop.csproj
```

## Connect to the phone

Every response of the phone server answers on the same port as the phone app
configures (default `8751`).

- Wi-Fi: connect both devices to the same network, enter the phone's LAN IP in
  the `Host` field (example: `192.168.1.34`, port `8751`).
- USB: plug in the phone, enable USB debugging, and reverse the port once (or
  each time the connection changes):

  ```
  adb reverse tcp:8751 tcp:8751
  ```

  Then connect with `Host = 127.0.0.1`, `Port = 8751`.

The diagnostics row shows whether a PocketForge server answered `/health`.

## Pairing

The first successful connect (or any later attempt that gets rejected with
`401`) opens a pairing dialog. Type the 6-digit code shown in the phone app
(server settings; the code rotates on every server start) and press `Pair`.
The resulting token:

- is stored DPAPI-protected (Windows current-user scope) at
  `%APPDATA%\PocketForgeDesktop\settings.json` together with host, port, agent
  mode, and last connected time.
- is never logged or displayed by this app.
- lives until the user taps "Revoke desktop access" in the phone app. To
  auto-obtain a new token, delete `settings.json` (or revoke on the phone) and
  connect again; the app will ask for a fresh pairing code.

## Session semantics (important)

Per the v1 contract the phone holds the conversation state. This client only
ever sends the newest user message of each turn on the wire; a system prompt is
sent only on the first generation of a session. `New session` clears the local
chat view and makes the next message carry `reset_session: true`.

## Agent mode

The `Agent mode` toggle (bottom panel) routes chat turns through
`PocketForge.McpTools.ToolLoop` instead of plain chat. Tool calls, results, and
denials are listed in the collapsible "Agent activity" panel. See `desktop/docs/mcp-tools.md` for the tool protocol.

## Troubleshooting

- `Connecting...` stays forever: the phone server probably answers, but a stale
  network path or USB device is hanging; check `adb devices` and retry.
- `401` errors: pair again (Connect -> pairing dialog).
- `409` errors: the phone is running another generation; press Connect's
  `Refresh status` or wait for "busy" to clear before retrying.
- App data folder: `%APPDATA%\PocketForgeDesktop` (contains `settings.json`
  plus DPAPI-protected token; delete the file to forget the phone).
