# PocketForge AI Research — FINDINGS (living document)
Branch: research/breakthrough | Device: Vivo X200 FE (V2503i), MT6989 (Dimensity 9300+), Android 16, 16 GB, Mali-G720-Immortalis MC12 | Coordinator file — others write own files.

## 1. Baseline (owner's prior phase0, 2026-10-22, llama-bench build 0a1ef30; pulled to research/data/phase0_prior/)
Raw llama-bench CSV (columns: "/" = 2 halves per test, second row = replica):
- 1.5B Q4_0 **pure CPU, backend lib = armv8.0_1 (Armv8.0 — NO dotprod, NO fp16 = the weakest shipped kernel)**:
  - pp512: t=4: 96.7/93.6 | t=6: 94.5/94.6 | t=8: 66.3/70.1  tok/s
  - tg128: t=4: 18.1/30.2 (!!) | t=6: 10.8/18.1 (!!) | t=8: 6.1/7.7
  - => 8 threads regresses prefill **and** decode; tg128 variance is enormous (6–30 across threads and repeat-instances) → suspect bg/system noise + thermal, not true kernel limits. This is a STRAWMAN baseline; tuned-baseline work is mandatory.
- Vulkan full offload (ngl 99): 1.5B: pp 13.5, tg 22.2 (all-slower than CPU). 4B Q4_K_M: **Killed exit=137** (could not complete).
- phase0 LAYOUT: all spare libs present in /data/local/tmp/pfbench/spare: armv8.2_1, armv8.2_2, armv8.6_1, armv9.0_1, armv9.2_1, armv9.2_2, vulkan. Phase0 script stopped at 12:31 mid-matrix (after armv8.0_1 1.5B) — no 4B, no 9.2, no mask, no auto-pick data.
- Mali-G720-Immortalis MC12 Vulkan probe: uma=1, fp16=1, bf16=0, fp4=0, warp=16, shared=32768, int_dot=0, matrix cores=none.
- X4 big-core mask per phase0's own cpufreq scan = 0x80 → only CPU7 returned max freq. NEEDS on-device verification.
- battery temp during runs: 35.4–38.0 C (cool-down target 33.0 not fully reached).

## 2. Device state at our session start (2026-10-23 10:50)
- battery level 94%, temp 33.3 C | storage /data: 155 GB free
- MemTotal 15.68 GB (proc value 15693708 kB), MemFree 0.30 GB, MemAvailable → **~7.66 GB physically available to a fresh process**, Cached 5.96 GB, zram swap in use 4.68 GB → Android bg processes squeeze capacity
- **loadavg pinned ~16.0 while CPU shows 715% idle / 800% total and 2 running tasks**; kernel vpsy_thd (Vivo privacy?) in D state with 9:59 CPU. Indicates blocking IO / kernel contention unrelated to our app. Treat as confound; quantify impact before every claimed number.
- models on device: qwen25-1.5b-q40.gguf (1.0 GB), qwen3-4b-q40.gguf (2.4 GB), qwen3-4b-q4km.gguf (2.5 GB). No 7B yet.
- app files: `/storage/emulated/0/Android/data/com.pocketforge.ai/files/{downloads,models}` present; app-internal data readable (`run-as` works).

## 3. Hypotheses & decisions
- see research\HYPOTHESES.jsonl (coordinator-owned; agents append outcomes via findings only)

## 3b. phase0b (our tuned sweep, 2026-10-23, llama-bench 0a1ef30, all 4B Q4_K_M pure CPU unless noted)
- **armv9.0_1: `llama_bench: error: CPU backend is not loaded`** — score-rejected at load on V2503i. Investigate load-gate logic offline (HWCAP vs NDK-built march buses). Skipped.
- **armv8.6_1 (dotprod+fp16+i8mm, no SVE), t=6, 4B Q4_K_M: pp512=54.2±1.3, tg128=12.62±0.06 tok/s** (first tuned row; ~= 82% of TH burst roofline 15.3 for 2.46GB/token short-ctx).
- Remaining variants + thread sweep + vulkan check finishing; see research\data\phase0b after.

## 4. Decisions / gate outcomes (log; newest bottom)
- [10:52] Device confirmed attached as `device`; lock protocol in research/data/device.lock (coordinator first).
- [10:53] Prior phase0-prior data adjudged strawman-baseline; tuned baseline re-run required. Agent fleet (HARNESS, B2, L, HW, TH) launched.
