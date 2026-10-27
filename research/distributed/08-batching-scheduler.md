# 08 - Continuous batching / multi-stream scheduling on the phone

THEORIST-08, read-only study. Inputs: `LlamaInferenceEngine.kt` (single-threaded `llamaDispatcher`, one stream), `pf_llm.cpp` (global single-seq decode state, per-token `generateNextToken`), `PhoneHttpServer.kt`/`PhoneServerController.kt` (atomic `generationLock`, bare 409, no queue), `docs/pocketforge-api-v1.md`, `desktop/docs/mcp-tools.md`, `llama.h` API surface, R4 roofline byte model (`research/R4_theory.md`).

## 1. Byte model and batch math

Decode step of batch B at context position C (one `llama_decode` submit):

```
t_step(B) = (W + B * kv(C)) / BW_eff
kv(C) = 147.5 KB * C        (Qwen3-4B: 36L, 8 KV heads, head_dim 128, f16 K+V, exact)
W     = 2.59 GB             (Q4_K_M 4.02B incl fp16 emb/out, R4)
BW_eff = 37.7 GB/s          (R4: 68.264 * 0.65 * 0.85)
```

Weights are read once per step and amortized across all B rows; each stream still reads its own KV linearly (attention reads KV per query row). Cross-check with R4's roofline: B=1 at C=2048 predicts t_step = (2.59 + 0.302)/37.7 = 76.7 ms = 13.0 tok/s vs measured 12.5 tok/s (model 4% optimistic). Anchor below on the measured 80 ms step.

Aggregate throughput = B / t_step(B); per-stream latency = 1 / t_step(B).

**C = 2048** (anchor: measured 12.5 tok/s):

| B | t_step | agg tok/s | speedup | per-stream tok/s |
|---|---|---|---|---|
| 1 | 80 ms | 12.5 | 1.00 | 12.5 |
| 2 | 88.0 ms (t1 + 1*kv/BW, kv/BW = 8.0 ms) | 22.7 | 1.82 | 11.4 (-9%) |
| 4 | 104.0 ms | 38.5 | 3.08 | 9.6 (-23%) |

**C = 8192** (contract example nCtx=8192; model, no anchor at 8k: t1 = 100.7 ms = 9.9 tok/s):

| B | t_step | agg tok/s | speedup | per-stream tok/s |
|---|---|---|---|---|
| 1 | 100.7 ms | 9.9 | 1.00 | 9.9 |
| 2 | 132.7 ms (kv/BW = 32.0 ms) | 15.1 | 1.52 | 7.5 (-24%) |
| 4 | 196.7 ms | 20.3 | 2.05 | 5.1 (-49%) |

Memory side: KV is per-stream. At C=8192, B streams cost B x 1.21 GB KV on top of ~2.4 GB weights. B=4 at 8k = ~7.3 GB process RAM; feasible only on 16 GB devices at nCtx <= ~6k.

UX threshold: tokens must appear faster than reading speed (~7 tok/s). Interleaving two streams is nearly free at C <= 4k (11.4 tok/s B=2 at 2k), hits the floor at C=8192 (7.5), and B=4 degrades visibly everywhere at >= 8k.

## 2. Queueing model

Service time per turn: S = prefill + decode. Prefill ~300 tok at 54.2 tok/s = 5.5 s; decode ~400 tok at 12.5 = 32 s (contract HARD cap 512; typical replies 250-400). S = 25-38 s, CV ~ 0.5 (decode dominates). M/G/1:

- Stated load, lambda = 1 turn/min per source x 3 sources = 0.05/s: **rho = lambda * S = 1.3-1.9 > 1: the phone is saturated.** No scheduler fixes this; capacity must rise (theory ceiling 1/S = 1.6-2.3 turns/min aggregate at B=1). Batching B=2 lifts capacity 1.82x (to ~4 turns/min at 2k), B=4 3.08x (~5 turns/min even at 8k). Honest answer to "lambda=1/min per source": the queueing win is NOT tiny there, it is the difference between diverging and stable - but only batching provides it.
- Realistic light load (each source a few turns/hour, lambda_tot 0.005/s): rho = 0.16-0.19, mean wait Wq = lambda*E[S^2]/(2(1-rho)) with E[S^2] = 1.25 S^2 = ~1250 s^2: **Wq = 3-4 s. Tiny** - turn-level queueing alone suffices; batching adds ~nothing for throughput here (B=2 saves CPU/battery per token pair only if turns overlap).
- Break point where pure queueing (B=1 FIFO) stops working: lambda_tot > 1/S ~ 2 turns/min; waits then grow unboundedly and MCP bursts starve user chat.
- When batching hurts perceived latency: any stream sharing a decode step drops to 1/t_step: -9% at B=2/2k (barely perceptible) to -49% at B=4/8k (clearly broken typing feel). Rule: batch only near-idle streams at short ctx, never degrade an active user-visible stream below ~8 tok/s.

MCP loop (ToolLoop, maxIterations 5, back-to-back turns, short tool-emitting replies): with a turn-level queue the burst serializes; burst wall time = sum of S over <= 5 iterations ~ 2-3 min. A user chat arriving mid-burst waits at most the residual service (mean residual = 1.25 S / 2 ~ 15-25 s) if MCP turns are queued ahead of it without priority - with app-UI priority it jumps ahead at the next turn boundary. The current "409 while user chats" failure mode disappears entirely (the failure becomes a bounded wait).

## 3. Scheduler design for THIS engine

One decode stream today = interleaving two turns means B=1 time-sliced at turn boundaries = plain FIFO queue (a turn cannot yield mid-decode without native work). That is still the 90% win.

**Minimal scheduler (Kotlin only, zero native changes):**
- Bounded priority queue in front of `engine.send`, capacity 3: priority app-UI (0) > server chat (1) > MCP auto-turn (2); FIFO within class.
- Server path: instead of `generationLock.compareAndSet -> 409`, enqueue and await admission. Overflow: HTTP 429 with `{queue_depth, retry_after_ms}` fields; add `queue_depth` + `state` mirrors to `/v1/status`. Contract change: v1 additive fields + "409 -> queue/429 with retry-after" note; desktop already retries on retry-after semantics.
- Backpressure: reject at queue-full, never mid-turn; `/v1/cancel` also cancels a queued turn's entry.

**What mid-turn (true B=2) switching needs in llama.cpp** - primitives already exist in this tree's `llama.h`:
- Parallel sequences on ONE context: `llama_batch_ext_add_token(batch, seq_id, id)` (+ `set_pos` / auto pos), KV per-seq ops `llama_memory_seq_rm/add/cp/keep` on `llama_get_memory(ctx)` (the JNI already uses seq 0 for context shift) - this is the enabling primitive.
- Per-seq sampling: `llama_set_sampler(ctx, seq_id, smpl)` + one logit row per seq per step.
- No context change needed for B<=4; `n_batch` already 512.

JNI changes for B=2 (ranked):
1. `pf_llm.cpp`: hoist global single-seq state (`g_current_position`, `g_system_position`, `g_stop_position`, `g_sampler`, `g_cached_token_chars`, `chat_msgs()`) into a per-seq `Decoder` struct (seq_id 0 app, 1 server chat, 2 MCP). ~40% of the work.
2. `generateNextToken` -> `step(active_seqs[])`: one `common_batch` per tick with one token per active seq, single `llama_process`, then sample per seq. Worst case one JNI call per tick for all streams.
3. Per-seq cancel/stop flags, per-seq context shift (`seq_rm/seq_add` keyed by seq, yes it already does the arithmetic), per-seq perf JSON.
4. Kotlin engine interface: `send` becomes turn-admission returning a stream handle; the single-owner `EngineState` machine (ProcessingUserPrompt/Generating shared) becomes per-stream.
5. Server queue + priority from above (unchanged - already needed).

Native change is ~300-500 LOC C++ plus an engine API rework; it changes the app's core contract (one generation at a time across the app is currently documented kernel-of-truth).

## 4. Verdict

- For 2-3 streams at realistic load, batching buys capacity you do not need (rho < 0.2) and -9% single-stream latency at B=2/2k. Marginal: **number = 1.82x aggregate, per-stream -9%** - not worth the JNI rework now.
- At the stated 1 turn/min/source, the system is capacity-bound (rho 1.3-1.9): only B>=4 saves UX, and that contradicts per-stream latency. Capacity must come from elsewhere (shorter turns, earlier EOG, streaming prefill).
- Recommended: build the priority queue + queue_depth/retry-after first (the 90% win: kills the 409 failure mode, ~1 Kb of Kotlin); revisit B=2 native interleaving only if telemetry shows lambda_tot sustained > ~0.02/s with rho > 0.5.
