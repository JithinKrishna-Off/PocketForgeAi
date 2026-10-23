# R4 — Theory: parametric roofline calculator + slack analysis (agent TH)

Branch `research/breakthrough`, 2026-10-23. Code: `research/calc/` (device.py, model.py, decode.py, prefill.py, main.py, test_calc.py). Everything numeric below is **ESTIMATED** except (a) the DRAM theory-bw formula (exact by construction) and (b) the cited phase0-prior measurements. Every parameter has a confidence tag and a recalibration hook (`--bw`, `--thermal`, `--bw-frac`, `--kv-elem-bytes`, per-core GFLOPS via `prefill.DEFAULT_GFLOPS_PER_CORE` or `ClusterSpec.matmul_gflops_per_core_est`).

## 1. Formulas

**DRAM theory bw (exact)**: `bw_theory = channels × (width_bits/8) × MT/s`. Vivo X200 FE: 4ch × 16bit × 8533 MT/s = **68.264 GB/s**. *Caveat (est, med-low conf): some Dimensity 9300+ SKUs run LPDDR5X 9600 MT/s = 76.8 GB/s; verify the part on device and reset `dram_mt_per_s`.*

**Effective bw (both factors EST)**: `bw_eff = bw_theory × achievable_bw_frac × sustained_thermal_frac` = 68.264 × 0.65 × 0.85 = **37.7 GB/s (est)**.
- `achievable_bw_frac` (est 0.5–0.75 band): the fraction of theory bw a big-cluster CPU streaming reader (GEMV/memcpy) sustains. Recalibrate: pinned memcpy/GEMV bench.
- `sustained_thermal_frac` (est 0.85): multiplier for multi-minute throttled loads; set 1.0 for short benchmark bursts (`--no-thermal`). Recalibrate: cpufreq + long tg runs.

**Decode (per token, ctx C)**:
- `bytes/token_min = W + KV(C)`; `W = decode_params × bytes/weight` (all layers + lm_head, streamed every token); `KV(C) = 2 × n_kv × head_dim × n_layers × kv_elem_bytes × C` — **exact linear in C** (Qwen3-4B f16 KV: 147.5 KB/ctx-token slope). Plus ~10 KB activation state (negligible est).
- `flops/token = 2 × decode_params` (dense GEMV) `+ 4 × L × n_heads × head_dim × C` (QKᵀ + A·V overhead, linear in C).
- `tok/s_MEM = bw_eff / bytes/token_min` (kern-bound); `tok/s_COMPUTE = GFLOPS_eff / flops/token` (ideal-GEMV bound); verdict = min; headroom = max/min.

**Prefill (T prompt tokens)**: dense `2×T×decode_params` + attention `2×L×n_heads×head_dim×T²` (causal avg); per-token at T=2048 for 4B: 8.04 GF dense + 0.60 GF attn = 8.65 GFLOP/token. Compute time with **configurable per-core NEON fp16-dotparam GFLOPS: X4 = 35, A720 = 22 (EST, deliberately conservative mid-estimates)**: aggregate param 228 GFLOPS; conservative × thermal (0.85) = 193.8 GFLOPS; optimistic × 1.25 (est: fused fp16-dotprod GEMM + flash-attention) = 285 GFLOPS. Memory side: weights pass once + KV write + **KV read, quadratic: Σ(t·kv_bytes) = kv_per_tok·T(T+1)/2** — at T=2048 ≈ 309 GB (8.2 s, not binding); at T=8k ≈ 4.9 TB; KV-read traffic eventually rivals or exceeds compute time at ctx ≥ ~25–30k (est). Activation traffic is a coarse EST (non-binding at ≤4k).

## 2. Confidence table (vivo-x200-fe / mt6989-9300+)

| param | value | conf | recalibrate |
|---|---|---|---|
| cluster topology | 4×X4@3.4 + 4×A720@2.5 (task spec; real 9300+ likely 1×3.4+3×2.85+4×2.0, est) | med | cpufreq scan on device (phase0 saw X4 mask 0x80 only) |
| sustained GHz | 2.9 / 2.1 (est) | low | on-device sustained-load profiling |
| instret rate | 20 / 12 GIPS (est, informational) | low | perf counters |
| LPDDR5X | 4×16bit@8533 → 68.264 GB/s (exact geometry; 8533 assumed) | med | device dump; may be 9600 |
| achievable frac | 0.65 (est band 0.5–0.75) | med | memcpy/GEMV bench |
| thermal frac | 0.85 (est) | low-med | long-run throttle logs (phase0 temps 35–38 °C, cool) |
| matmul GFLOPS/core | X4 35 / A720 22 (est) | low | solve from measured pp (phase0 armv8.0_1 pp512 = 96.7 tok/s ⇒ ≥ ~300 GFLOPS aggregate with *scalar fp32* kernels ⇒ fp16-dotprod band likely ≥ 35) |
| L2 4 MB / L3 12 MB | est | low | unused in roofline |

Model geometry (HF config.json knowledge, med–high conf): qwen2.5-1.5b (28L, h1536, 12Q/2KV, hd128, ffn8960, vocab151936, tied, 1.54 B) ✓ card; qwen3-4b (36L, h2560, 32Q/8KV, hd128, ffn9728, vocab151936, tied, 4.02 B) ✓ card; qwen2.5-7b (28L, h3584, 28Q/4KV, hd128, ffn18944, vocab152064, untied, 7.62 B) ✓; llama-3.1-8b (32L, h4096, 32Q/8KV, hd128, ffn14336, vocab128256, untied, 8.03 B) ✓; phi-3-mini (32L, h3072, 32Q/32KV MHA, **hd96**, ffn8192, vocab32064, untied, 3.82 B, med conf). Verify each against actual GGUF metadata when the device is attached.

Quant bytes/weight: Q4_0 18/32 = 0.5625 (exact), Q8_0 34/32 = 1.0625 (exact), Q6_K 210/256 = 0.8203 (exact), **Q4_K_M ≈ 0.570 (est, well-known ballpark; measured GGUFs 0.58–0.62 incl. f16 emb/out — recalibrate `file_GB/params`)**, Q5_K_M ≈ 0.700 (est), fp16 = 2.0 (exact). KV default fp16 (2 B/elem, exact by layout; llama.cpp may ship q8_0/q4 KV).

## 3. Expected ceilings — vivo-x200-fe, Q4_K_M (param 0.57 B/w), ctx 2048 / pp 2048 (all est)

| model | params B | bytes/token | KV% | dec tok/s (thermal) | dec tok/s (burst) | pp cons | pp opt |
|---|---|---|---|---|---|---|---|
| Qwen2.5-1.5B | 1.54 | 0.94 GB | 6% | 40.2 | 47.3 | 59 | 87 |
| **Qwen3-4B** | 4.02 | **2.59 GB** | 12% | **14.5** | **17.1** | **22** | **33** |
| Qwen2.5-7B | 7.62 | 4.15 GB | 3% | 9.1 | 10.7 | 13 | 20 |
| Llama-3.1-8B | 8.03 | 4.55 GB | 6% | 8.3 | 9.8 | 12 | 18 |
| Phi-3-mini-3.8B | 3.82 | 2.93 GB | 28% | 12.9 | 15.2 | 25 | 36 |

Context dependence (4B, MEM-bound at all ctx shown): 512→15.9, 1024→15.4, 2048→14.5, 4096→13.0, 8192→10.8, 16384→8.0, 32768→5.3 tok/s; KV read passes weights in size at ctx ≈ 16k → ultra-low-bit KV becomes mandatory there (est). Quant ladder for 4B @2k: q4km 14.5 / q8_0 8.2 / fp16 4.5 tok/s — quant moves the roofline itself; kernel work can only approach it.

## 4. Slack analysis — 4B Q4_K_M on llama.cpp

**Ceiling stack (est):** theoretical 68.3 GB/s → achievable-bw ceiling `0.65×theory` = 44.4 GB/s → burst roofline @2.59 GB/token = **17.1 tok/s** → thermal-adjusted sustained roofline = 14.5 tok/s.

**Kernel reality:** general knowledge (low-med confidence, no on-device 4B CPU data yet — phase0 matrix died at armv8.0_1/1.5B; Mali vulkan 4B OOM-killed): llama.cpp AArch64 GEMV kernels reach **~40–70% of that "achievable" bandwidth** ⇒ expected llama.cpp decode for 4B Q4_K_M @2k ctx ≈ **0.40–0.70 × 17.1 = 6.8–12.0 tok/s**, i.e. **~40–70% of the burst roofline, ~47–83% of thermal roofline (~9–12 t/s nominal, ±30% est)**. The gap = kernel inefficiency: fp32 GEMV paths where fp16-dotprod isn't picked, big.LITTLE thread imbalance (4 fast + 4 slow cores), non-contiguous dequant reads, KV interleave, per-layer launch/sync overhead, mmap page faults cold.

**Cross-check with phase0 data (real):** 1.5B Q4_0 armv8.0_1 (weakest, no dotprod) tg128 measured 18.1–30.2 tok/s (t=4; noisy) vs burst roofline 50.9 tok/s (0.872 GB/token, ctx 128) ⇒ **36–59% of roofline** — consistent with the 40–70% premise, even with weakest kernels + `--no-thermal`. Same lib pp512 measured 96.7 tok/s vs conservative param 61.9 ⇒ the 35 GFLOPS/core parameter is, if anything, BELOW measured scalar-fp32 pp throughput (est ≥ ~300 GFLOPS aggregate across threads); so predicted prefill conservative numbers are floors, not walls.

**Conclusion:** for the tuned-baseline sweep, treat ~17 tok/s (burst) / ~14.5 (sustained) as the physics wall for 4B Q4_K_M @2k. Any measured tg result above ~12 tok/s implies >70% kernel efficiency (good); below ~7 implies ~40% (large recoverable slack, matching the owner's-app-at-1-tok/s situation which must first attribute noise: phase0 tg variance 6–30 t/s, loadavg ~16 confound).

## 5. Pressure points that move the roofline itself (rare, est)

1. **KV cache bits** (q8_0: KV÷1.88; q4: ÷3.56): biggest lever once ctx ≳ 4k; shifts bytes/token materially at 16k+ (`--kv-elem-bytes`).
2. **Tokens per weights pass** (speculative/multi-token/batch): amortizes the 2.29 GB weights read over several tokens — breaks the "1 token = 1 weights pass" assumption; only technique that multiplies the ceiling for *small* ctx.
3. **NPU/GPU offload** (Mali G720-MC12 or 9300+ APU): different engine, different bw/compute mix — new roofline needed (vulkan probe: uma=1, fp16=1).
4. Thread-topology alignment (pure-X4 GEMV vs hetero-8) to actually pull the achievable-bw fraction upward — raises kernel efficiency toward the frac band, not the frac itself.

## 6. Recalibration playbook

1. Measure real tg/pp: `llama-bench` matrix (t∈{4,6}, armv9.2_2, 1.5B+4B, ctx 128/2048, repeats ≥3).
2. `--bw-frac` := measured_stream_GBs / 68.264; `--thermal` from cpufreq at 5 min under load.
3. `prefill.DEFAULT_GFLOPS_PER_CORE` := pp_tok/s ÷ flops/token ÷(#big cores × thermal).
4. Quant check: file size (LLM GGUF: q4km 4B ≈ 2.3–2.5 GB est) ÷ params vs 0.57; KV element bytes from `llama-bench -ctk/-ctv`.
