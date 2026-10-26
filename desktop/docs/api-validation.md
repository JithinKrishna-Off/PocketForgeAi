# API validation (contract tests)

Contract under test: `docs/pocketforge-api-v1.md`. Mock: `tools/MockPhone`
(hand-rolled HTTP/1.1 TcpListener implementing the contract). Runner:
`tools/SelfTest` (console, no test framework dependency). Latest run:
**29 passed, 0 failed, 0 skipped**.

## Matrix

| Area | Cases | Result |
|---|---|---|
| Health/reachability | no-auth probe, bad port, timeout | PASS |
| Pairing | wrong code 401, right code 200 token, rate limit 429 | PASS |
| Auth | missing/bad bearer on all /v1/* -> 401 JSON error shape | PASS |
| Status | state/busy/model fields, busy as boolean | PASS |
| Chat non-stream | choices[0].message.content, usage fields | PASS |
| Chat stream (SSE) | N deltas, terminal finish_reason, `data: [DONE]` | PASS |
| Cancel | 204; delayed stream (MOCK_DELAY_MS) ends with finish_reason=cancelled | PASS |
| Errors | unknown path 404, body over 256KB 413, malformed request 400 | PASS |
| Client e2e (PartB) | PhoneChatClient: Probe/Pair/Status/Stream/Chat/401 type/Cancel | PASS |
| Tool loop (PartC) | ToolLoop: AssistantDelta -> ToolRequested -> ToolResult -> Done; TOOL RESULT in next user message | PASS |

Plus the MCP tool host's own suite (`McpTools` selftest): **35 passed, 0 failed**
(path traversal, secret deny, symlink escape, output caps, allowlist, approval
gating, MCP stdio round-trip, loop event sequence).

## Contract issues found while implementing both sides

1. Mock originally emitted `"busy":"true"` (string); contract says boolean. Fixed
   the MOCK (contract is authoritative). If the real Kotlin server ever emits a
   string here, ChatApi's parser will read busy=false - keep an eye on it.
2. The mock auth-gates `/v1/*` before routing, so unknown `/v1/*` paths with a bad
   token return 401 rather than 404. The contract does not pin the precedence; the
   desktop treats both as fatal auth errors - acceptable, but noted.
3. The mock rejects oversized/chunked bodies without draining the upload, so HTTP
   clients can see a connection reset instead of the clean 413 body. The real
   Kotlin server should drain (or the client should tolerate RST on 413); flagged
   to the lead as a hardening note.

## Mock fidelity notes

- Scripted replies: default echo, `TOOL RESULT` acknowledgement, `MOCK_DELAY_MS`
  slow tokens, `MOCK_BUSY=1`, `MOCK_FAIL=auth|big|error`.
- The mock is a contract double, not a llama.cpp engine: no tokens-per-second
  semantics, no chat template, no steering fields beyond static values.
