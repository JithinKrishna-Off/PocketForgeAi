# PocketForge Phone API v1 (contract)

Canonical interface between the Android phone server, the Windows desktop
client, and the MCP tool host. Version: 1. Status: implemented by the phone
server (Kotlin) and consumed by the desktop client (C#).

## Transport

- HTTP/1.1 over TCP. Default port 8751. Bind: 127.0.0.1 by default; 0.0.0.0
  only when the user enables "Allow LAN access" in the app.
- Every response uses `Connection: close`. Requests must carry
  `Content-Length` (chunked request bodies are rejected with 400).
- Max request body: 256 KB. Max `Content-Length` header block: 16 KB.
- One connection per request (no keep-alive). SSE responses are long-lived on
  their own connection.

## Authentication

- All endpoints except `GET /health` require `Authorization: Bearer <token>`.
- Pairing: `POST /pair` with body `{"code":"<6 digits>"}`. The code is shown in
  the app's server settings while the server runs and rotates on every server
  start. Response: `{"token":"<128-bit hex>"}`. Max 5 failed pair attempts per
  minute (429 otherwise).
- The token lives for the life of the pairing (until the user taps
  "Revoke desktop access" in the app, which clears all tokens). Phone stores it
  in app-private storage; desktop stores it DPAPI-protected. The token is never
  logged or shown in UI.
- Wrong/missing token: 401 `{"error":{"message":"unauthorized","code":401}}`.

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | /health | no | Liveness probe (desktop reachability check) |
| POST | /pair | no | Exchange pairing code for bearer token |
| GET | /v1/status | yes | Server + model state, metadata |
| GET | /v1/models | yes | OpenAI-style model list (loaded model only) |
| POST | /v1/chat/completions | yes | Chat generation (JSON or SSE stream) |
| POST | /v1/cancel | yes | Cancel the current generation (204) |

### GET /health

`200 {"ok":true,"pf_api":"v1","app":"PocketForgeAI"}` - no model info, no auth.
404/timeout means no server.

### GET /v1/status

```json
{"api":"v1","server":true,"port":8751,"lan":false,
 "state":"ModelReady","busy":false,
 "model":{"loaded":true,"name":"qwen3.5-4b","desc":"...","sizeBytes":2700000000,
          "nCtx":8192,"nThreads":6,"backend":"cpu",
          "steering":{"active":true,"spec":"14:8949:+1,13:7956:-1"}}}
```

`state` mirrors the engine state machine: `Uninitialized`, `Initialized`,
`LoadingModel`, `ModelReady`, `ProcessingUserPrompt`, `Generating`, `Error`.
`busy` = a generation is in progress (including one from the app's own chat
UI). Model may be `"loaded":false` while the server is on; requests then fail
with 503.

### POST /v1/chat/completions

Request:

```json
{"messages":[{"role":"system","content":"..."},
             {"role":"user","content":"..."}],
 "stream":true,"max_tokens":2048,"stop":[],"reset_session":false,"seed":0}
```

Session semantics (important): the phone holds the conversation state (KV
cache + chat template history). The server processes only the LAST `user`
message in `messages` as the turn text. Prior `assistant`/`user` turns must
NOT be re-sent on follow-up requests (they are already in the phone session).
`reset_session:true` clears the phone-side session first (fresh conversation).
A `system` message in the first request becomes/updates the system prompt
(applied once per model load); later system messages are rejected with 400.

Response, non-streaming (`stream:false`):

```json
{"id":"pf-...","object":"chat.completion","created":1769000000,
 "model":"<name>","pf_state":"ModelReady",
 "choices":[{"index":0,"message":{"role":"assistant","content":"..."},
             "finish_reason":"stop|length|cancelled"}],
 "usage":{"prompt_tokens":N,"completion_tokens":M,"prompt_ms":T1,"gen_ms":T2}}
```

Response, streaming (`stream:true`): `Content-Type: text/event-stream`. Frames:

```
data: {"id":"pf-...","choices":[{"index":0,"delta":{"content":"tok"},"finish_reason":null}]}

data: {"id":"pf-...","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

- Deltas arrive per generated piece (word/wordpiece level).
- Terminal `finish_reason`: `stop` (EOG), `length` (max_tokens),
  `cancelled` (client or phone user cancelled), `error` + trailing
  `event: error` frame `data: {"error":{"message":"..."}}` before close.
- If the stream breaks mid-generation (network drop), the phone cancels the
  turn when the connection write fails; state returns to ModelReady.

### Errors

`{"error":{"message":"...","code":N}}` with status codes: 400 malformed,
401 auth, 404 unknown path, 409 busy (a generation is already running - retry
later; the phone does NOT queue concurrent generations), 413 body too large,
429 pairing rate limit, 503 model not loaded / server shutting down, 500
internal. Errors are JSON even mid-stream (before first delta; after deltas
the stream errors as an `error` event then closes).

### Concurrency

- One generation at a time across the whole app (in-app chat and server share
  the engine). `409` when busy.
- Request bodies are read fully before generation starts; generation runs on
  the engine's own dispatcher; network I/O never runs on the UI thread.

## Lifecycle

- Server mode is explicitly enabled by the user (Settings -> "Desktop server").
  It runs as a foreground service (`dataSync` type) with a persistent
  notification while enabled. Disabling the toggle stops the service and closes
  the listener. The model is NOT unloaded when a desktop client disconnects.
- Android process death kills the server; the toggle comes back off (opt-in
  every boot). The listener rebinds on service start.
- USB/ADB use: `adb reverse tcp:8751 tcp:8751` then desktop connects to
  127.0.0.1:8751. Wi-Fi use: desktop connects to the phone's LAN IP.

## Tool calling (laptop-side, not on the wire)

The phone API carries plain chat only. Tool calling is an orchestrator concern
on the desktop: the orchestrator injects a tool protocol into the system
prompt, parses model output for tool-call JSON (strict validation), executes
approved tools on the laptop, and feeds results back as the next user message.
See `PocketForgeDesktop/docs/mcp-tools.md`. Models that cannot reliably emit
the protocol simply produce normal chat.

## Versioning

- `pf_api` is "v1". The desktop refuses servers with a different major and
  says so in diagnostics. Additive minor changes keep "v1".
