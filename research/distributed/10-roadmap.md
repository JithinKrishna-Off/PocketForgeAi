# THEORIST-10: distributed phone<->PC inference - implementation roadmap

Author: THEORIST-10 (systems architect). Input: real code read in both repos (refs are exact).
Read-only pass; no builds were run. MC basis: D9300+ phone, decode 12.5 tok/s held resident.
Targets: (A) ggml RPC backend layer offload, (B) distributed speculative decoding.

---

## 0. Hard code facts this roadmap is built on

### 0.1 llama.cpp ggml RPC (read in `ggml/src/ggml-rpc/`)

- DL-plugin registration: `ggml/src/ggml-rpc/ggml-rpc.cpp:3194` ends with
  `GGML_BACKEND_DL_IMPL(ggml_backend_rpc_reg)`; the reg is registered by the standard
  `ggml_backend_load()` path. The reg is a *zero-device shell* until an endpoint is added:
  `ggml_backend_rpc_reg_get_device_count` returns 0 when `ctx == nullptr`
  (ggml-rpc.cpp:2952-2955), and `ggml_backend_rpc_reg_get_device` ABORTs if enumerated
  without devices (ggml-rpc.cpp:2957-2960).
- The RPC backend is *add-enabled by CMake as any backend*: `ggml_add_backend_library(ggml-rpc ...)`
  (`ggml/src/ggml-rpc/CMakeLists.txt:3-7`), gated only by the usual `GGML_RPC` pool in
  `ggml/src/ggml/CMakeLists.txt`. Windows build links `ws2_32` (ggml-rpc/CMakeLists.txt:9-11).
  RWMA is auto-detected and OFF on Android (CMakeLists.txt:29-31).
- Devices are created only via `ggml_backend_rpc_add_server(endpoint)`
  (ggml-rpc.cpp:3150-3191), exported from the loaded reg through
  `ggml_backend_reg_get_proc_address` (ggml-rpc.cpp:3098-3114). It does
  `RPC_CMD_DEVICE_COUNT` over a live TCP connect; returns nullptr if the server reports 0 devices.
- Connecting: `rpc_dispatcher::start` (`ggml-rpc.cpp:638-658`): `socket_t::connect`
  (blocking `::connect` in transport.cpp:752-770), then HELLO negotiation
  (`negotiate_hello`, ggml-rpc.cpp:420-436; protocol ver asserted at transport.h/server
  and client in hello :2453-2455).
- **Failure semantics (load time):** endpoint unreachable at connect =>
  `GGML_ABORT("Failed to connect to %s")` (ggml-rpc.cpp:649-651). This kills the whole
  process, i.e. on Android the app (and the foreground server) dies. There is no retry
  or null path in stock ggml.
- **Failure semantics (mid-generation):** server crash / malformed =>
  `RPC_STATUS_ASSERT` => `GGML_ABORT("Remote RPC server crashed...")`
  (ggml-rpc.cpp:31, 682-685 in the dispatcher worker). Same: native abort of the
  Android process. Stock ggml has no graceful path on either failure.
- Target device type: `ggml_backend_rpc_device_get_type` hard-returns
  `GGML_BACKEND_DEVICE_TYPE_GPU` (ggml-rpc.cpp:2851-2856, TODO inside).

- Server side: `tools/rpc/rpc-server.cpp:213-267`: `ggml_backend_load_all()`,
  device picks (params `dev `-` :port`), passes `host:port` into
  `ggml_backend_rpc_start_server` (impl ggml-rpc.cpp:2762-2831); the server accepts
  one client connection at a time in a loop (ggml-rpc.cpp:2817-2826). Plaintext,
  **no auth** by design; warning banner when host != 127.0.0.1 (rpc-server.cpp:224-232).
  Per-message TCP: all commands are blocking recv/send in `rpc_serve_client`
  (`RPC_CMD_GRAPH_COMPUTE` serialized graph, set/get tensors in :2600-2695); the
  largest transfer is SET_TENSOR (weights on first pass; then graph serialization).

- `ggml.h` decode API actually used by the app: common-batch + `llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch)` (`pf_llm.cpp:653`; declared in include/llama.h:1092-1095), so a batched k-token verify is the existing `decode_tokens_in_batches` family in `pf_llm.cpp:635-658` -- only positions differ.

### 0.2 PocketForge app: backend discovery, jniLibs, plan surface

- Kotlin calls `System.loadLibrary("pocketforge-llm")` then `LlamaBridge.init(nativeLibDir)`
  (`LlamaInferenceEngine.kt:91-104`).
- `pf_load_backends` (`pf_llm.cpp:234-264`) dlopen()s only the 7 CPU variants by
  bare soname / absolute path (`pf_try_backend` :197-223), using raw
  `register_backends` pytest counting (`ggml_backend_reg_count()` deltas). Vulkan is lazily
  loaded at loadModel only if the plan asks for offload (`pf_ensure_vulkan_loaded` :228-232,
  ref in loadModel :427-435). Adding an RPC lib = one extra call into the exact same
  `pf_try_backend` list, gated by plan: that is literally how it plugs in.
- **jniLibs layout** (`app/src/main/jniLibs/arm64-v8a/`): `libggml.so`, `libllama.so`,
  `libllama-common.so`, 7 `libggml-cpu-android_*.so` shards, `libggml-vulkan.so` +
  helpers (`libmtmd.so`, `libpocketforge-llm.so`, per-tool bench libs). Adding
  `libggml-rpc.so` follows `libggml-vulkan.so` exactly (a dlopen-on-demand backend).
- Build script: `pocketforge-android/build-apk-libs.ps1` builds with
  `GGML_BACKEND_DL=ON`, `GGML_CPU_ALL_VARIANTS=ON`, targets `llama llama-common`
  (:121, :130), validates every .so for NEEDED entries (:149-171) and stages via
  `-CopyTo` (:184-188). To get libggml-rpc.so: just add `-DGGML_RPC=ON` to `$Opts`
  (the backend lib is emitted automatically as part of the same Ninja graph); optionally
  extend the validation filter to also blacklist `libggml-rpc.so` NEEDED on e.g.
  ibverbs (RDMA is OFF on Android anyway so the lib will be NEEDED-free).
  **Build changes end-to-end: one CMake flag + one jniLibs copy + one filter line.**
- App-side gradle (`app/build.gradle.kts`): jniLibs is staged from
  `src/main/jniLibs` (`jniLibs.srcDir`, :67-73) and library packaging notes
  `useLegacyPackaging = true` (:45-50) -- exactly what the pf_llm.cpp header comment
  (:172-186) already documents as the dual-mode loader. A new .so requires zero gradle work.
- Plan surface: `pf::PfPlan` (`pf_plan.h:56-79`) + `pfSetPlan` /
  `pfPlansJson` (`LlamaBridge.kt:95-99`). This is where the offload block belongs
  (v2), and where the client layer gets /v1/status data from.
- Decision structure precedent for "devices = explicitly CPU-only when GPU offload off"
  (`pf_llm.cpp:437-448`): the same explicit device-array pattern is the correct insertion
  point for the RPC device: `devices = {rpc_dev}` (if user requests PC offload) else
  `{cpu_dev}` or `{vulkan..., cpu}` per existing behavior.

### 0.3 PocketForge app: server/transport facts

- v1 contract (`docs/pocketforge-api-v1.md`): HTTP/1.1, one conn/req, dB 8751,
  SSE stream, one generation per app (`409` on busy), bearer-token-only routes
  except /health & /pair, `pf_api` versioning, session state lives phone-side,
  connection loss mid-stream == phone cancels the turn (v1.md:104-105).
- Server auth store (`PhoneAuth.kt:58-65`): constant-time bearer comparison,
  6-digit rotating pairing code, tokens app-private.
- Desktop client (`desktop/app/ChatApi.cs:43-76`): health probe with 2 s timeout,
  infinite HTTP timeout for SSE, `NextTurnResetsSession` session-reset flag;
  ToolLoop (`desktop/McpTools/ToolLoop.cs:11-70`) streams turns tool-side.
- adb note (`local.properties|null` => no runtime roots are asserted in repo), machine
  facts: `adb reverse` silently BROKEN on this laptop (direction we'd need for
  phone -> PC RPC on USB), `adb forward` OK, USB ~125 MB/s, hotspot RTT 4-12 ms.

---

## 1. Backend architecture: decision tree (A) vs (B)

### Pre-algebra (own byte/RTT budget, phone numbers)

Let M = resident target model on phone, weights W bytes. Measured phone decode is
12.5 tok/s; we assume that is BW-bound: B_phone_eff = W*12.5 [B/s]. For typical
4B Q4_K_M, W ~ 2.4 GB -> B_eff ~ 30 GB/s.

- **Per-verify byte budget (B):** one batched verify of k positions is
  *compute-parallel* for the near-attention but still reads W once:
  cost_verify(k) >= W/B_phone_eff + k * KV_scan_cost(seq) + logits pass.
  B_phone_eff=30 GB/s => 80 ms for W=2.4 GB single param sweep.
- **Per-token RPC budget (A) at load time:** weights stream over LAN/USB once,
  then SET tensors cached. Per-token compute round trips:
  graph payload: a packed `rpc_tensor` is 8+4+8+16+16+4+64+4+80+16+8+64+4 = ~276 B
  marshalled per node; full graph about 2000 bytes x N small
  tensors. For a 4-tier layer split model, graph cmd is ~50-200 KB, cached on both
  sides (GRAPH_CACHE, ggml-rpc.cpp:120-122) so per-token steady-state transport
  is only the changed-tensor region: boundary vecs (input & output matmul rows)
  + KV. ~4-40 KB/token.
- **RTT cost (A):** 2 round trips per decode step is realistic:
  (1) graph_compute submit on layer-boundary w-side, (2) sync/wait
  (dispatcher `send()` = sync `future.wait()`). On hotspot RTT 4-12 ms -> 8-24 ms of
  pure latency *on top of the Fastest-Device compute in the serialized pipeline*.
  On USB RNDIS (phone->PC reachable network via USB tether interface) the RTT
  bound is <2 ms -> 2-4 ms overhead.

### Option A verdict (PC as additional compute device via ggml RPC)

**Model-load changes needed on phone:**

1. build: `-DGGML_RPC=ON` in `pocketforge-android/build-apk-libs.ps1` -> +1 .so.
   (S)
2. Add `GgmlRpc` load option in `pf_llm.cpp`'s deferred section — same code shape as
   `pf_ensure_vulkan_loaded`, but for `libggml-rpc.so`, **must be done lazily and only
   after a Kotlin/Java-side socket-availability pre-probe**, because at stock ggml the
   *first* TCP-connect failure inside `ggml_backend_rpc_add_server -> get_dispatcher`
   kills the process (per above facts).
3. `pf::PfPlan` + Kotlin call path: append `offload: { mode:"rpc"|none, endpoint:"host:port", nRpcLayers }`.
4. Dekan sizes: extend loadModel device-array path (`pf_llm.cpp:437-448`) to include keep
   local CPU device + the RPC device, use `n_gpu_layers` as the *split* control.
5. wc the runtime `pf_backend_diag` lines into the /v1/status payload for the UI.

**Runtime model:** with RPC devices registered, the scheduler's split-pushes-block
per-decode-tensor. Because RPC devices always report `GPU` (ggml-rpc.cpp:2851), an
`n_gpu_layers` > 0 will drop parts of the graph onto PC. Data movement per token is
weighed above. With W remote-split (a "whole latter N layers live on PC") from a
4 GB, 40-layer model with 24 layers on phone (10 GB/s marginal spot) and 16 layers on
PC (PC CPU 1.5-2.5x phone for the same layers):

t_step ~= max_latency( remote-RTT, ~2ms if USB ) + compute patterns:
  phone part: 2.4 GB * (24/40) / 30 GB/s = ~48 ms
  pc part: 2.4 GB * (16/40) / (45-75 GB/s) = ~13-21 ms
  RTT overhead: 8-24 ms (hotspot) or 2-6 ms (USB RNDIS)
=> 1 / t_step: hotspot -> 12-14 tok/s, USB -> 13-15 tok/s.
All layers on PC (device off side splits convention we disallow: phone must own at least the
embeddings/sampler) leads to:
  phone part: residual ~small (embeddings + sampler stand local anyway)
=> hotspot 20-30 tok/s, USB 40-60 tok/s; but at this tier the PC equivalent stack
   competes against simply running the whole model on PC and streaming tokens
   (`/tmp/...`= *not* the product goal: PocketForge's goal is phone-resident, PC-assisted).
- **Honest range:** 12.5 -> 15-25 tok/s hotspot, 15-30 tok/s over USB RNDIS, AND only
  if the PC is >=1.5x phone per-layer. On a weak PC (thermal laptop doing another job),
  Option A degrades below local-only. **Effort: S (initial) / M (production hardening).**
- **Failure semantics to design around:** PC disappearance mid-turn kills the phone
  app (see ggml-rpc fatal semantics). Plan: hard-detects via a dedicated,
  **pre-loaded-decision:** user explicitly files a `pc-offload` toggle; failures
  =>  either process restart with fallback, so the design contract is:
  "PC-offload turns are fire-and-forget-optimistic; any PC-side drop during a turn
  degrades the app to restart + local-only; a server-visible `offload` status field
  carries the last-known-good state". `offload=0` retry happens after the phone reloads
  the model without RPC devices in the device array (a re-load, not a re-attach — once a
  model is assigned its buffer set, CPU-side reclaim must go through `llama_model_free`
  closest to stock).

### Option B verdict (PC drafts k tokens; phone batch-verifies)

**Needed primitives (all across one pinned revision of llama.cpp + one C++ JNI):**

- `pf_llm.cpp`: keep a second *`llama_context` on PC is unnecessary*; phone-side is
  built on llama decode pairs: one JNI pair:
  - `pfSpecPushDraft(draftTokens long[], k int)` — Kotlin receives from PC over a new
    contract v2 endpoint, and passes to native.
  - `pfSpecVerify(k)` — takes the k drafts, feeds `pf_spec_batch(...)` (=
    `llama_process` with `LLAMA_PROCESS_TYPE_DECODE`, positions `pos0..pos0+k`, seq 0,
    via the existing `decode_tokens_in_batches`-style batch builder), then runs accept
    comparison logic (greedy; or sampler with draft sampling chain matching
    `common/speculative.cpp` semantics), and trims non-accepted KV:
    `llama_memory_seq_rm(seq0, pos0+accepted+1, pos0+k)` (LLAMA_NEISH!!! here exact name —
    NV: confirm current API, but the destroy-range primitive exists and v7.6+ it is
    `llama_memory_seq_rm`). Returns accepted token stream to Kotlin as JSON with
    accepted count + resulting next piece.
- All this lives inside the single-turn-loop of one engine, ensuring One-Session.
- Desktop side: a `PfDraftDae` daemon = local llama.cpp work with small draft model
  (e.g., Qwen3 0.6B Q4_K_M, `.gguf`), a long-lived raw TCP or HTTP chunked conn
  to the phone (plain `llama common == device-local; no ggml wire needed`).
  synt entirely owned: an *MCP-friendly* tool chain is already the v1 contract, so the
  ToolLoop (`ToolLoop.cs:11-70`) in agent mode calls `StreamAsync` with `spec` extension
  which, per step, asks phone: `/v2/spec/step` with draft token list + k.

**Per-verify and per-draft algebra (own numbers):**

- Draft model 0.6B Q4: W_d ~0.35 GB. On PC with an iGPU/RADEON/graphic-something
  (unknown here, laptop ASUS) assumption mid-band: > 45 GB/s; with CPU only,
  4-6 threads: 12-20 GB/s effective => draft tok/s 30-55 (CPU)/
  100 - 300 (iGPU). k = 6:
  elapsed_draft = k/ (tok/s _draft) -> 110-160 ms CPU, 20-40 ms iGPU.
- phone verify: W-read by layer = 80 ms (BW-bound; batch amortized across k)
  + KV attention at seq L small; ~ 85-100 ms, plus RTT for draft fetch (k*4B, ~0
  ms on USB; 4-12 ms on hotspot). If draft fetch overlaps with previous turn's verify
  (pipeline); draft runs *concurrently*, i.e., transparently on the daemon, the *new
  draft* can be produced while the phone verifies the previous one => period ≈
  verify + RTT ≈ 90-110ms.
- Acceptance rate: standard EAGLE LEAN: 2.3-3.1 tokens/step for 0.6B -> \
4B Qwen3 family w/ temp ~ 0 => LLAMA_SPEC accepts ~2.5-3.
- **throughput = 2.5-3.0 / 0.10 s = 25-30 tok/s** (hotspot), up to ~30-35 tok/s (USB).
- **Throughput vs. its energy budget** — the strategically interesting number for
  PocketForge: per accepted ~3 tokens you put ~2.4 GB through LPDDR X window once
  vs once per single decode token before. Effective bytes/token: 0.8 GB vs 2.4 GB
  before = **3x less copper-time per output token**, i.e. 3x less thermal/battery.
- **Honest range:** 12.5 -> 22-30 tok/s hotspot (2.0-2.4x), 25-35 USB. **Effort: L.**

### A vs B verdict (mine, independent)

- B beats A for the realistic pair here (phone BW is the bottleneck; PC intrusion is
  small-diffusion and comes with 1 conn burst per step rather than 2 full split
  round trips per token). B is also ~3x kinder to battery (see SPF parsings).
- A's only clean wins: (i) memory-limited phone user wanting a model too large to
  load, (ii) a *tiny* pre-implementation cost if you only need it as A/B
  experiment (build flag + 150 lines). For a 12.5 tok/s phone, A can at best be
  +2x with an extremely fast PC, but requires RTT <4 ms to matter on USB, and
  carries crash-semantics risk that is anti-PocketForge.
- **Recommended: P0 = A smoke test (cheap experiment + real numbers), production
  direction = B.** Do not ship A broadly. Detail see section 2.

---

## 2. Phased plan P0..P3

### P0 — RPC smoke-and-sweep experiment (S, ~2-4 sessions)

Scope: build `libggml-rpc.so` into the arm64 lib set (build flag + `CopyTo` stage),
add the lazy loader branch in `pf_llm.cpp`, add *a debug-only* `pfSetPlan` extension
`offloadRpc: "<host:port>"` `nRpcLayers`. PC side: build the standard
`llama.cpp/tools/rpc` rpc-server.exe (already exists in the tree; just build on Windows
host) with the same model.

Acceptance criteria:
1. `pc-rpc-server.exe` reachable: `Test-NetConnection` + phone TCP probe success (2s
   timeout), then a phone-loaded model with `nRpcLayers = {0, 8, 16, 24, all}` runs
   one full 64-token decode turn without crash.
2. Printed measurements: per-turn decode wall ms, prompt wall ms, `/v1/status`
   backend list must show `RPC[...]` section. Compare against local-only baseline
   (12.5 tok/s).
3. Measured tok/s delta published (even if negative!) into
   `research/distributed/RESULTS-P0.md`. This is the A/B decision input.
4. *Negative path acceptance:* kill PC process mid-turn; phone app crash (expected,
   per ggml-rpc fatal facts) — this is documented, not fought, in P0; observed and
   measured.

Why this is the right P0: it is the *only single-change* that yields a measured tok/s
delta on the real pair in a day (KV quantization is already inside `pf_plan` and not
distributed; PC-draft-prefill-only would need but new contract endpoints before
measure). The RPC lib exists pinned; the delta will be *measured* instead of
estimated and settles the A-vs-B investment call with data.

### P1 — production hardening of whichever A/B wins the P0 data (M)

For B (expected winner):
1. `pf_llm.cpp` spec-verify JNI pair + `llama_memory_seq_rm` KV trim.
2. `PhoneServerController.kt`/`PhoneServerContract.kt` throne: new `/v2/spec/*`
   endpoints (see section 3), strict bearer (reuse `PhoneAuth.kt:58-65`).
3. Desktop `PfDraftDae` daemon + `ChatApi.cs`/ToolLoop hook.
4. Status surfacing: `/v1/status` gains `spec:{active, k, acceptedAvg, tokPerS}`.

Acceptance criteria:
1. 30-min hourlong conversation without the phone's out-of-order KV divergence:
   verify output tokens == local-only output tokens for temp=0 across a 100-token
   transcript N>=5 times (deterministic replay check).
2. tok/s measured >= 18 on the real pair; acceptedAvg >= 2.2 over 2000 tokens.
3. Network drop mid-turn: phone falls back to local-only decode *inside the same
   turn* (no restart, no 409). Session intact.
4. 2 engines never appear: confirmed via single-model residency check (`pf_llm.cpp`
   native rejects second model; this constraint is in *LlamaInferenceEngine.kt:26-35*
   and explicitly maintained under P1).

### P2 — power/thermal + plan integration (M)

- Move plan offload decision into `pf::PfPlan`; auto-toggling by measured RTT &
  battery (& SOC temperature) from the auto-plan layer; UI: PerformanceScreen toggle.
- `StopDetector`/session semantics as-is; ensure spec-verify shares sampler state.

### P3 — transport + security polish (S-M)

- USB-RNDIS tunnel for phone->PC (no adb reverse needed; PC becomes USB gateway).
- scoped rpc-server bind / firewall rules + PC daemon LAN policies.
- opc windows' sleep/hibernate bitting (see risk #4).

---

## 3. Contract v2 extensions (additive fields only; no v1 breaks)

### Plan JSON (`pf_plan.h` shape, `pfPlansJson()/pfSetPlan`)

```json
"offload": {                       // distributed offload config (absent = disabled)
  "mode": "rpc" | "draft" | "none" | "auto",
  "endpoint": "192.168.1.5:8012",  // where PC-side component listens
  "transport": "tcp-lan" | "usb-rndis" | "tunnel",
  "nRpcLayers": -1                 // for mode rpc: -1 = all layers eligible
}
"spec": {                          // for mode draft
  "draftK": 6,
  "sessionToken": "<short-lived>", // separate from bearer; set at /v2/spec/handshake
  "fallbackLocal": true
}
```

### v2 endpoints (new trunk, bearer-gated, server-side JSON)

| Method | Path | Purpose |
|---|---|---|
| POST | /v2/spec/handshake | PC daemon -> phone: exchange draftK + one-time session nonce; returns negot parameters |
| POST | /v2/spec/step | PC daemon -> phone: `{"draft":[tokens], "k":n}` ; response `{"accepted":n, "tokens":[...], "state":"ok"|"overheat"|"local_only"}` |
| POST | /v2/draft/next | Phone -> PC (if PC pushes on demand rather than active-push) |
| GET  | /v2/offload/status | extended offload telemetry (RTT ms, tok/s, mode, failures) |

### /v1/status additional top-level key

```json
"offload": {"mode":"draft","endpoint":"...","state":"active|degraded|off",
 "rttMs":8.4,"acceptAvg":2.7,"tokPerS":24.3,"lastFail":"pc-disconnected@1769..."}
```

---

## 4. Risk register

| # | Risk | Basis (file:line) | Mitigation |
|---|---|---|---|
| 1 | PC unreachable at connect => native `GGML_ABORT`, Android app dies | ggml-rpc.cpp:649-651 | Kotlin pre-probe (2 s connect-timeout) on the same host:port before any pf RPC lib loads; on failure skip RPC load entirely and fall back local-only |
| 2 | PC dies mid-turn => dispatcher worker `GGML_ABORT` kills phone app | ggml-rpc.cpp:31, 682-685 | scope = A only: accept crash as data point; if A ever ships (non-default), first patch the pin: convert both ABORT ~ 6 lines to error return + a polled liveness flag consumed as `state:"degraded"` |
| 3 | RPC device always reports GPU type -> it gets selected when user toggles GPU offload ON (Vulkan // RPC in one split), and the `ngl==0` CPU-only path strips it | ggml-rpc.cpp:2851-2856; pf_llm.cpp:437-448 | single-source-of-truth: plan's offload block wins; explicit device array always built inside loadModel; forbid RPC & Vulkan devices in same device array simultaneously |
| 4 | Windows laptop sleeps/hibernates mid-generation => phone dispatcher waits forever on `future.wait()` (queue.pop blocks) => phone stuck in Generating forever | rpc_dispatcher list: `send()` blocks on completion. Needs: stop flags | phone-side: watchdog that (a) cancels the turn via engine.stopFlag, (b) notes `offload.lastFail`. Note the local-only session is *safe* because browse meets the stop detector handling |
| 5 | JNI/ABI skew: libggml-rpc.so built from a different llama.cpp rev or with a different GGML_BACKEND_API_VERSION will break inside `pf_load_backends dlopen` | pf_llm.cpp:197-223; build-apk-libs.ps1:12-14 pinned rev | keep RPC build in the same build file with the *same pinned tree input* the app's JNI lib comes from; pin it in the ps1 param block; extend the NEEDED validation (ps1:149-171) to enforce build |
| 6 | One-engine-one-session constraint: two running engines on the phone breaks session/KV/memory margins | LlamaInferenceEngine.kt:26-35 (single engine, serialized dispatcher, refuse-second-model) is a design invariant | A/B must both live inside the singular engine's existing dispatcher; a second `llama_context` not spawned; the PC *only* hosts the small draft model (its context lives on PC, invisible to the phone's one-engine invariant) |
| 7 | Security: rpc-server has **no auth** and warns about exposure when not 127.0.0.1 | rpc-server.cpp:224-232 (banner), min: proto has no identity handshake | PC firewall rule binding scoped-subnet and high random port; treat as "PC-is-exposing" only for users' hotspot; *v2-consistent story*: `spec` mode routes only through existing pcap bearer-token auth (`PhoneAuth.kt:58-65`); draft model weights *never* cross the wire |
| 8 | `adb reverse` Broken on this laptop (machine fact) | USB-tunnel path for phone->PC would have been adb reverse | use USB RNDIS (USB-tethered NIC interface) or hotspot path; test USB RNDIS RTT as one of the P0 measurements |

---

## 5. Open questions for the other theorists (what I need from you)

1. **T1 (hardware):** PC-side effective decode BW for a 4B Q4_K_M and 0.6B Q4_K_M
   draft, CPU-only AND with the laptop's iGPU; PC prefill tok/s for both. Without
   these, my tok/s tables hold only as ranks.
2. **T2 (transport):** measure RTT over USB-RNDIS (USB-tether, phone-pc reachable)
   + hotspot + (`Test-NetConnection`) and if available `adb forward`-tunnel
   *in the reverse direction via a python/socat relay running on the phone*
   (idea only; is `adb forward` fitting any reverse-tunnel proxy pattern on this box?).
   Need ms numbers; 4-12 ms hotspot figure controls the A-tail.
3. **T3 (llama.cpp RPC split behavior):** empirical SMTP-window size & mechanics:
   per-token transport bytes when a 40-layer 4B model is split (24/16, 8/32, 0/40)
   with an already-cached graph -- uses `GGML_RPC_DEBUG=3` (log.h:11). It keeps my
   per-byte budget honest against actual `rpc_log_format_size` trace.
4. **T4 (spec acceptance):** empirical accept-length for Qwen3.5-4B target + Qwen3
   0.6B Q4 draft at temp 0.2 greedy over 100-chat peer; the 2.3-3.1 number I use for
   tok/s must be confirmed (or replaced) by `common/speculative.cpp` numbers on
   a real run.
5. **T5 (API):** /v2 spec endpoint shape review (looks fine?; conformance vs. server
   conventions in `PhoneServerContract.kt`), and whether the *auto-fallback local mid-turn*
   is acceptable contract behavior for v2.
6. **T6 (batteries/thermals):** is the 3x fewer LPDDR weight-read claim right at the
   bank level? (my derivation: verify reads W once per accepted ~3 tokens; effect:
   bytes/token 0.8 GB vs 2.4). Confirm measured mA/token at these rates on D9300.
