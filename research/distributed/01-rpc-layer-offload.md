# 01 - RPC layer-offload feasibility (phone client, laptop server)

Author: THEORIST-01 (research only; no code changed in either repo)
Date: 2026-10-27

## 1. What ggml RPC actually does (from source, this tree)

Source: `ggml/src/ggml-rpc/ggml-rpc.cpp` (3194 lines), `ggml/include/ggml-rpc.h` (proto v8.0.1).

- **Whole-graph dispatch, not per-op.** On first compute of a graph uid the client
  serializes the entire split graph (node pointers + `rpc_tensor` structs, 32 srcs etc.)
  and sends `RPC_CMD_GRAPH_COMPUTE` (`serialize_graph`, ggml-rpc.cpp:1212-1240). The
  server rebuilds the graph (`rpc_server::graph_compute`, :2106) and runs it locally on
  its own devices.
- **On later tokens the graph shape repeats**, so the client sends only
  `RPC_CMD_GRAPH_RECOMPUTE` with an 8-byte uid (:1242-1268). Per-op sync work is zero.
- **Weight stash.** Tensors living in buffers marked
  `GGML_BACKEND_BUFFER_USAGE_WEIGHTS` and larger than 10 MiB are uploaded via
  `RPC_CMD_SET_TENSOR_HASH`; the server answers "hit" if it already holds that FNV
  hash (:840-846, `HASH_THRESHOLD = 10 MiB`). Compute-buffer activations are excluded
  by design (:841-843 comment). So weights are streamed once at load (or on model
  server restart if `--cache-dir` was given to `tools/rpc`).
- **Buffer-usage messages: there are none on the wire.** `usage` is a local property of
  the lb buffer on the phone side that RPC reads to decide hash caching. The wire
  protocol is: HELLO (capabilities), ALLOC/GET_ALIGNMENT/GET_MAX_SIZE once, SET_TENSOR
  for every buffer created, GRAPH_COMPUTE/RECOMPUTE, GET/SET/COPY crossing boundaries,
  sync events.
- **Layer placement is automatic on the phone side.** `src/llama.cpp:275-276` puts RPC
  devices at the FRONT of `model->devices` "to minimize network transfers" and layer
  split mode then fills the first contiguous layer block onto the server until its
  reported memory is full. With one RPC server the split is therefore 2 segments
  (laptop block, phone block) -> about 2 boundary activations copies per token.
- **Remote tensors, not remote compute.** The server materializes every buffer it
  hosts in its own RAM (`rpc_server` local lb). Compute happens ONLY where the tensor
  lives. There is no "phone computes, laptop stores" path.

### Message count per transformer layer per token: ZERO round-trips

Per token with N offloaded contiguous layers the wire sees:
- 1 x GRAPH_RECOMPUTE (16 B, async)
- 1 x copy phone -> laptop (qkv input hidden state, ~2560 x 2-4 B = 5-10 KiB) = 1 RTT + transfer
- 1 x copy laptop -> phone (last hidden state) = 1 RTT + transfer
- pipeline flush at each sync point: effectively ~2 RTT more

=> **~4 RTT per token** independent of layer count, plus per-token bytes that are
`< 50 KiB` (negligible at both link speeds). Prefill: same 4 RTT per ubatch, plus the
once-per-uid full graph serialization (for a 36-layer block ~ 300 nodes x 448 B =
~500 KiB, once).

Ops per real transformer layer (decode): matter only server-side (local speed):
qkv mul_mat, rope, cache set/probe, attn, o_proj, in norm, gate/up mul_mats, silu,
down mul_mat, out norm, add - about 12-16 nodes; none of them cross the wire.

## 2. Measurements (mine, on this laptop/phone)

| probe | result |
|---|---|
| 200 x `adb shell true` | mean 48.3 ms (process spawn + adb protocol overhead; NOT the network RTT) |
| TCP-over-USB via `adb forward tcp:19999 tcp:19999` + phone `nc -l -p 19999` (500 connects, laptop side timing) | mean 4.2 ms, p50 2.1 ms, p90 13.6 ms, min 1.1 ms |
| `adb reverse tcp:19997 tcp:19997` + phone `nc 127.0.0.1 19997` echo loop (200 fresh connections, laptop server) | mean 15.8 ms, p50 13.3 ms per fresh exec (dominated by device-side nc spawn; min 1.7 ms shows the wire adds ~2 ms) |
| persistent-connection projection | p50 2.1 ms -> use **2-3 ms RTT USB** in the math |
| Wi-Fi hotspot | given: 4-12 ms ping, use **8 ms RTT** |

Important finding: **`adb reverse` works on this laptop right now** (probe registered
as `UsbFfs tcp:19997 tcp:19997`, echo loop completed). The earlier "silently fails"
report did not reproduce; still flag it as a reliability risk (adb server restarts and
device re-plugs drop reverse ports) and keep a fallback.

## 3. Math: decode latency model

Per token: `T(N) = t_phone(L-N) + t_laptop_x(N) + 4 RTT + boundary copy bytes`.

Per-layer compute times (memory-bandwidth bound, Q4_K_M, single token):

| model | layers | phone per-layer (measured 4B: 80 ms / 36) | laptop per-layer (est.) |
|---|---|---|---|
| 4B 2.7 GB | 36 | **2.2 ms** | ~2.2-2.5 ms CPU (DDR4-3200 UMA ~ LPDDR5X effective), ~1.5 ms if Vulkan iGPU helps |
| 9B ~5 GB | 36 | ~5.0 ms (phone-only ~5.5 tok/s est.) | ~3.3 ms CPU |
| 14B ~8.7 GB | 40 | ~11 ms (phone-only ~2.2 tok/s est.) | ~3.6 ms CPU |

Break-even N where offload stops losing: `N > 4 RTT / (t_phone - t_laptop)`.

| link | RTT | 4B break-even | 9B | 14B |
|---|---|---|---|---|
| USB | 2.5 ms (10 ms/token) | ~5..? layers (savings ~0-0.3 ms) -> effectively **N = 0** | ~7 layers | ~2 layers |
| Wi-Fi | 8 ms (32 ms/token) | never (needs ~100 layers) | ~22 layers | ~5 layers |

Why 4B loses: the Dimensity 9300 already streams weights at ~34 GB/s effective
(12.5 tok/s on 2.7 GB); the i5-1235U CPU shares similar-or-slower DDR4 bandwidth with
the Iris Xe iGPU, so offloaded layers run no faster on the laptop - the 10-32 ms
RTT tax per token buys nothing. The only class of win on THIS laptop is
**models that are RAM- or throughput-starved on the phone**.

Expected tok/s at the best affordable N (USB, one contiguous block, one-time weight
stream 125 MB/s: 4B 21 s, 9B ~40 s, 14B ~70 s just to upload):

| model | phone alone | offloaded (best N) | offloaded tok/s | net |
|---|---|---|---|---|
| 4B | 12.5 | N = 0 (optimal) | 12.5 | 0 (any N > 0 loses; N = 16 -> ~11.9) |
| 9B | ~5.5 | ~16 IL (USB) | ~6.0-6.6 | +10..20 % |
| 9B over Wi-Fi | ~5.5 | ~24 IL | ~5.4 | ~0 |
| 14B | ~2.2 | ~32 IL (USB/Wi-Fi both) | ~4.6-5.0 | +110-130 % |

## 4. Flash-offload variant (weights on laptop disk, compute on phone)

**Not supported, and no cheap path to it exists.** RPC moves compute to where the
tensor's buffer lives; it never streams weights to the caller. `tools/rpc --cache-dir`
only lets the server answer *later SET_TENSOR misses from its own disk* (hash reuse for
the very same hosted buffer); the data still lands in server RAM before compute. A
phone-side streaming-from-PC-disk backend would have to be written from scratch
(display fetch-per-op-descriptor path does not exist).

## 5. Implementation sketch (what the app would need)

1. Build: add `-DGGML_RPC=ON` to `native/build-native.ps1`. Because the app builds
   with `GGML_BACKEND_DL=ON` and `BUILD_SHARED_LIBS=ON`, ggml-rpc is already wired in
   ggml to produce `libggml-rpc.so` (see its CMakeLists `ggml_add_backend_library`).
   Ship it next to the other dynamic backends; `pf_llm.cpp:177-220` already has the
   "extract + `ggml_backend_load` bare soname" loader used for Vulkan - same pattern.
2. Endpoint: before `llama_model_load`, call
   `ggml_backend_rpc_add_server("127.0.0.1:<port>")` (public in ggml-rpc.h:31).
   Upstream `src/llama.cpp:275-276` then inserts RPC devices first and layer-split
   loading happens without further patches; expose the endpoint as one new string in
   the pf_llm JNI + a `rpc_endpoint` field in the server API model-load call
   (docs/pocketforge-api-v1.md).
3. Transport into the phone: two real options:
   - `adb reverse tcp:<port> tcp:<port>` (phone connects to 127.0.0.1:<port> over USB,
     laptop runs `tools/rpc-server -p <port>` bound to 127.0.0.1). Verified working in
     this whole machine today, but restartar the adb server silently drops it - add a
     startup check + re-arm in the Kotlin transport layer. **Flag: must not be silent.**
   - plain Wi-Fi hotspot socket from the phone to the laptop IP (4-12 ms, no adb).
   The idea "laptop-side python TCP relay over `adb forward`" does NOT work for this
   direction: `adb forward` opens a socket *on the laptop* pointing INTO the phone, i.e.
   it serves a laptop client -> phone server; a relay behind it cannot reverse the RPC
   role (phone client -> laptop compute). Only `adb reverse` (direction demonstrated) or
   an independent IP link gives phone->laptop.
4. Network requirements on the phone side: connectivity
   `android.permission.INTERNET` only - no new permission implied by using 127.0.0.1.

## 6. Open risks

- KV cache placement for cross-device splits: must confirm during a spike that the
  KV blocks of remote layers end up in the remote buffer (upstream layer split is
  supposed to handle this; not verified here without a build/test).
- Graph uid stability across context shift / prompt changes: expected fine (same graph
  -> RECOMPUTE), worth a log check.
- BLAS thread contention on the laptop during weight upload (21-70 s one-time, 125 MB/s
  USB or Wi-Fi much slower: 9B over Wi-Fi ~ 5 GB at maybe 25 MB/s = 3+ minutes).

## 7. Verdict

- Worth building ONLY as a model-size enabler for >9B Q4 models (14B: ~2.2 -> ~4.7-5
  tok/s, USB and Wi-Fi both viable), and marginally for 8-9B (plus 10-20 %).
- NOT worth building to speed up the current 4B path: expected tok/s is 12.5 -> 12.5
  (USB) or worse; N = 0 is the true optimum for 4B today.
- The one lever on this hardware pair that would flip the 4B/9B verdict is a laptop
  device with distinctly higher per-layer decode speed than the D9300 - a discrete
  NVIDIA GPU. Without one, both ends are bandwidth-bound at similar GB/s and RPC can
  only pay off on latency-insensitive, memory-poor cases.
- If built, use `adb reverse` (USB primary) + hotspot (fallback), one RPC server,
  contiguous layer block, phone holding embed/tail layers, and keep the GGUF on the
  phone (RPC pushes weights from the client; the laptop never reads the model file).
