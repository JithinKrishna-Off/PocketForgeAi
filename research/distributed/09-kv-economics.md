# THEORIST-09: KV-cache economics for the PocketForge chat use-case

Read-only findings. Sources: `app/src/main/cpp/pf_llm.cpp`, `pf_plan.h`, Kotlin engine/server code, `docs/pocketforge-api-v1.md`, the pinned llama.cpp tree, and GGUF metadata pulled from the phone via `adb pull` of a header slice. No builds, no commits.

## 1. Model geometry (verified, not assumed)

From the 9B GGUF header itself (`Qwen3.5-9B-Uncensored-HauhauCS-Aggressive-Q4_K_M.gguf`, arch `qwen35`):

```
qwen35.attention.head_count     = 16
qwen35.attention.head_count_kv  = 4          (GQA 4:1)
qwen35.attention.key_length     = 256
qwen35.attention.value_length   = 256
qwen35.block_count              = 32
qwen35.full_attention_interval  = 4
qwen35.context_length           = 262144
qwen35.ssm.state_size = 128, inner_size = 4096, group_count = 16, conv_kernel = 4
```

Qwen3.5 is a HYBRID model: 24 gated-DeltaNet linear-attention layers + 8 full-attention layers. llama.cpp builds a `llama_memory_hybrid` for qwen35 (src/llama-model.cpp:3139-3215): KV cache only on non-recurrent layers, F32 recurrent state on the rest. The 4B (`abl-qwen35-4b-q4km`, 2707513696 B) has identical attention geometry per the published Qwen3.5-4B config (32 layers, 16/4 heads, head_dim 256, interval 4; hidden 2560); only weights differ.

Files currently on the device (`/sdcard/Android/data/com.pocketforge.ai/files/models`): Qwen2.5-7B 4.68 GB, Qwen3.5-9B 5.63 GB, Qwen2.5-Coder-14B 9.0 GB.

## 2. KV bytes/token

Per full-attention layer per token: 2 x (4 kv_heads x 256) = 2048 elements (K+V). 8 such layers in qwen35.

| KV type | per layer | per token (8 full layers) |
|---|---|---|
| f16  (2 B/el)      | 4096 B  | 32 KiB |
| q8_0 (1.0625 B/el) | 2176 B  | 17.0 KiB |
| q4_0 (0.5625 B/el) | 1152 B  | 9.0 KiB |

Constant per-session extras (context-independent): recurrent DeltaNet state is F32, 24 layers x (128 x 4096 elements) = ~50 MiB, plus conv states ~2 MiB. A dense 32-layer KV would cost 128 KiB/token f16; the hybrid is 4x cheaper automatically.

Notes: quantized V requires flash attention (enforced at llama-context.cpp:4079-4085; FA auto-enables for quantized V, and K quant needs head_dim divisible by block size - 256/32 OK). The pf_plan.h:38 rule calling "q4_0 + flashAttn=1" INVALID is stale for this pinned tree: FA + q4_0 K/V is the standard supported pairing here (256 % 32 == 0).

Concrete sizes; per-352-token-turn growth in parentheses:

| ctx | f16 | q8_0 | q4_0 |
|---|---|---|---|
| 4096 | 128 MiB (11.0 MiB/turn) | 68 MiB (5.9) | 36 MiB (3.1) |
| 8192 | 256 MiB | 136 MiB | 72 MiB |
| 16384 | 512 MiB | 272 MiB | 144 MiB |
| 32768 | 1024 MiB | 545 MiB | 288 MiB |

## 3. Reuse vs re-prefill (the session-owner win)

Code path verified: server turn = `POST /v1/chat/completions` -> PhoneServerController extracts only the LAST user message -> `ensureSystemPrompt()` applied once per model load (`systemPromptApplied` flag, LlamaInferenceEngine.kt:155-169) -> `engine.send()` -> `processUserPrompt` (pf_llm.cpp:691), which tokenizes ONLY the new message and decodes at `g_current_position`. No re-prefill of history. `resetSession` fires only on `reset_session:true` (PhoneServerController.kt:309). The server IGNORES earlier user turns in `messages` rather than rejecting them - a stateless-style client re-sending full history would silently diverge; the contract documents the rule and the shipped desktop client honours it.

Cost model: 20-turn conversation, 200-token user turns, 150-token replies, ~300-token system prompt (MCP tool protocol). Turn t prompt length = 300 + 352t.

| design | prefill tokens (20 turns) | prefill @ 54.2 tok/s | decode | total wall |
|---|---|---|---|---|
| phone session-owner (only new user msg prefilling) | 300 + 20x~200 = ~4.3k | ~80 s | 3.0k tok @ 12.5 = 240 s | ~5.3 min |
| stateless OpenAI-style (re-prefill full prefix every turn) | 20x300 + 352x210 = ~80k | ~1475 s (24.6 min) | 240 s | ~28.6 min |

18.6x prefill reduction, 5.3x end-to-end. The single worst stateless turn prefills 7.3k tokens (135 s wasted). KV RAM is the same single coherent copy in both cases, but a stateless server must either hold it between connections or discard/redo it; the phone holds it once, incrementally (11.0 MiB/turn f16, 5.9 q8_0, 3.1 q4_0). This is the core economic argument for the phone-side session and it is real in code, not aspirational. Caveat: it only holds while the model stays loaded; a model reload forces `restoreHistory`-style rebuild (full prefix re-prefill, ChatViewModel.kt:252).

## 4. Context headroom (Dimensity 9300, 15.7 GB device)

`/proc/meminfo` (READ-ONLY adb): MemTotal 15693644 kB (14.97 GiB), MemAvailable 7837124 kB (7.48 GiB at observation; other apps fluctuate this by a few hundred MB). Budget model: MemAvailable - weights - reserve. Reserve ~1.6 GiB covers the compute buffer (hybrid graph, n_batch 512) + mmap working set + Android overhead; conservative.

| model | weights | KV+state budget left | max ctx f16 | q8_0 | q4_0 |
|---|---|---|---|---|---|
| Qwen3.5-4B Q4_K_M | 2583 MiB | ~3.4 GiB | ~100k tok | ~200k | ~350k |
| Qwen3.5-9B Q4_K_M | 5365 MiB | ~0.7 GiB (minus 52 MiB state) | ~21k tok | ~41k | ~74k |

Full-16k load sums (weights + KV + state + reserve): 4B ~5.0 GiB (trivially fits); 9B f16 ~7.0 GiB (fits a typical MemAvailable), 9B q8_0 at 32k ~7.9 GiB (needs MemAvailable > ~8.5 GiB - rare), 9B q4_0 at 32k ~7.7 GiB (borderline).

Verdict: "9B on the phone comfortably" = yes with q8_0 KV at 16k (and most of the time at 32k with q4_0). f16 caps the 9B at roughly 16k practical. Decode cost scales with bytes-touched-per-token (weights 2583->5365 MiB): expect ~5.5 tok/s decode and ~24 tok/s prefill for the 9B - usable for desktop turn-taking, and KV quant is the single lever that makes 16k+ desktop chats boring instead of impossible.

## 5. In-tree reuse mechanisms inventory (exact names)

- `llama_kv_cache` (src/llama-kv-cache.*): seq_rm/seq_add/seq_cp/seq_keep, `get_can_shift()`, in-tree K-shift (`llama_kv_cache::update(lctx, do_shift, ...)` -> build_graph_shift) repositions after rm/add WITHOUT re-prefill; `llama_memory_can_shift()` (llama.h:821).
- State persistence: `llama_state_get/set_data` (whole ctx), `llama_state_save_file/load_file` (session at a path), per-sequence `llama_state_seq_save_file/load_file` (llama.h:830-960, magic GGSQ v4. For qwen35 the hybrid serializes BOTH attn KV and DeltaNet state (llama-memory-hybrid.cpp:190-192), so these APIs are hybrid-correct.
- No `common/llama-cache` exists. Exact in-tree neighbors: `common/hf-cache.*` (HF download cache, not KV), `common/ngram-cache.*` (speculative-decoding lookup), `common/common.h:520 path_prompt_cache` + `tools/completion/completion.cpp:206` (the classic session-file prompt cache = `llama_state_load/save_file`), `server_prompt_cache` in tools/server (RAM prompt snapshots, `--cache-ram` knob, default 8192 MiB), `kv_unified` (llama.h:415) for shared-KV parallel sequences. The app currently uses none of these.
- Recurrent rollback: `llama_context_params.n_rs_seq` (llama.h:371, EXPERIMENTAL, default 0). Nonzero = per-token DeltaNet snapshots bounded by n_rs_seq tokens back: this is what allows non-tail (mid-history) seq_rm on recurrent layers, which the snapshot cost scales with.

Bug flag (mid-history discard on hybrid, default params): the generation-time context shift (pf_llm.cpp:740-749) calls `llama_memory_seq_rm(mem, 0, sys_pos, sys_pos + n_discard)`, mid-history. With default params (n_rs_seq = 0, set at llama-context.cpp:3998) `llama_memory_recurrent::seq_rm` refuses non-tail partial erasure (returns false unless rollback fits the snapshot budget, llama-memory-recurrent.cpp:201-219), so `llama_memory_hybrid::seq_rm` returns false early (llama-memory-hybrid.cpp:143-149) and pf_llm.cpp ignores the rc: the DeltaNet state does NOT rewind while KV positions shift. On qwen35 4B/9B that is state-inconsistent (KV positions shifted, recurrent chain still believes the old tokens). This is exactly the "KV management is correctness-sensitive" risk surface.

## 6. Ranked recommendations for the app

1. Make q8_0 K+V (+FA) the plan default for qwen35 models. Saving: halved KV (16k: 512 -> 272 MiB; 32k: 1024 -> 545 MiB); turns "9B, 16k chat" from marginal to routine. Risk: small attention-quality loss; requires FA (already enforced); fix pf_plan's stale q4_0+FA rule while touching it. Effort: plan defaults only, engine already accepts kvTypeK/kvTypeV.
2. Explicit reset boundaries per MCP loop: desktop sends `reset_session:true` at a new loop/task instead of unbounded growth. Saving: caps KV at ~36-72 MiB per session (4k ctx q8_0/q4_0) instead of 300+ MiB sprawl; also bounds context shift invocations. Risk: none - API supported today.
3. Pick and fix the shift policy: today the hybrid mid-history discard is state-broken (Section 5 flag) and shifts only happen at generation time (processUserPrompt overflow with rc=2 kills the turn instead of discarding-then-retrying). Options: (a) set `n_rs_seq` >= max shift span at context build (pf_llm.cpp builds ctx_params itself, so it is one flag + snapshot RAM cost ~4 MiB x n_rs_seq... per layer x 8192 el) or (b) shift only at turn boundaries via bounded system+delta re-prefill (cheap: <= 2 s at 54 tok/s for ~100 tokens). Risk: needs a design decision and tests; (b) is simpler and robust.
4. Session persistence via `llama_state_seq_save_file` on unload/process death, restore after reload. Saving: avoids the full-history rebuild (system + prefix, 3-7k tokens = 1-2 min prefill at ~54 tok/s) per restart or model swap. Cost: file size == KV bytes (0.5-1.0 GiB at 16-32k on 4B/9B) written to app-private storage; only worth persisting for long sessions (short ones re-prefill faster than they serialize at these sizes).

Numbers assumption note: 4B geometry from the published Qwen3.5-4B config (same hybrid pattern); 9B geometry from the on-device GGUF header. MemAvailable numbers are a point-in-time reading; the budget table's qualitative tiers (relative type columns) are robust to the fluctuation.
