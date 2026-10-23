# R3 — Hardware dossier: Vivo X200 FE (Dimensity 9300+ / MT6989)

Compiled 2026-10-23. Access date for all cited URLs: 2026-10-23. All numbers labeled [published] (vendor/review-measured) or [estimated] (inference from published data).
Device probe baseline (owner-reported): uma=1, fp16=1, bf16=0, no int_dot in Vulkan (probe says none; ggml log from G720 elsewhere reports int dot: 1 — discrepancy noted in §4), warp 16, shared 32KB, GPU matrix cores: none. 16 GB RAM variant [owner report]; retail SKUs are 12/16 GB LPDDR5X [phonebench.net, notebookcheck].

## 1. CPU (Dimensity 9300+, TSMC 4nm+ 3rd gen)

| core | arch | max clock | source |
|---|---|---|---|
| 1× prime | Cortex-X4 | 3.4 GHz [published] | mediatek.com product page |
| 3× | Cortex-X4 | 2.85 GHz [published] | mediatek.com |
| 4× | Cortex-A720 | 2.0 GHz [published] | mediatek.com |

Sources: mediatek.com/products/smartphones/mediatek-dimensity-9300-plus; gsmarena.com/news-62743.php; fonearena.com/blog/423210.

**Caches:** "18MB L3 + SLC" [published] (not split by MediaTek). Per-core X4 L2 is 1 MiB/core (4 MiB cluster) + shared L3 [estimated from Arm's Cortex-X4 default confi g — D9300 listings show 3.5 MB L2 / L3 split unclear: cpudb.cc/soc/mediatek-dimensity-9300]. SLC size not disclosed; D9300-gen analyses put SLC at 8 MB-class for a 10 MB X4-L3 + 8MB SLC split total 18MB [estimated; flag as uncertain].

**Sustained clocks under all-core load:** Notebookcheck X200 FE review (2025-11-09, notebookcheck.net/A-compact-Android-smartphone-…-X200-FE-review.1114877.0.html): "severe throttling", >40% throttled in 3DMark stress, surface 47 °C. GB6 MC on the FE: 7198 (avg 7148, n=4) vs GB6 devices using throttled D9300-hybrids; from the ratio to X-Elite-class and 3DMark stability 58.9%, **sustained all-core X4 frequency estimated ~2.0–2.3 GHz, A720 ~1.4–1.6 GHz after ~10 min** [estimated]. Prime X4 under single-thread LLM decode typically holds ~2.6–3.0 GHz briefly then ~2.2–2.5 GHz thermal plateaus [estimated].
DVFS per-core governors: userspace sees standard cpufreq (schedutil-class, per-cluster policy: 2 clusters: 4×X4 policy, 4×A720 policy) [estimated — MTK ships EAS+walt; no vendor console without root]. No public MTK published sustained-clock sheet found.

## 2. DRAM subsystem

SoC supports LPDDR5X **and** LPDDR5T @ 9600 MT/s × 4 ch × 16-bit [published, mediatek.com]. X200 FE ships LPDDR5X (12/16 GB) [published, notebookcheck + phonebench]. At LPDDR5X-8533: 4×16×8533/8 = **68.3 GB/s theoretical** [estimated]; if LPDDR5T-9600 populated: 76.8 GB/s [published ceiling, cpudb.cc].
Measured: AnTuTu mem sub-score 279,435 (phonebench) / 295,511 (nanoreview AnTuTu 11) — consistent with ~60–68 GB/s-class MEM [estimated mapping].
llama.cpp-class real memcpy on UMA flagship SoCs typically achieves ~60–75% of theoretical: ~40–45 GB/s effective [estimated; no direct X200 FE figure found — TODO probe]. Overlapped GPU+CPU traffic shares the same 68 GB/s.

## 4. GPU — Mali Immortalis-G720 MC12 @ ~1.3 GHz

- 12 cores, 1536 shader units, ~4.0 TFLOPS fp32 [published, 91mobiles/phonebench].
- llama.cpp Vulkan state on G720/MTK (ggml-org PR #18493, 2025-12→2026-08): forced FP32 to get peak perf; warptile retuning; suballocation block 256 MB cap; measured **Gemma-3 4B Q4_0: pp512 205 t/s, tg128 12.7 t/s** (author's final tuned: pp ~65, tg ~10 s [published in-thread, Termux]).
- Known defects: (a) MUL_MAT wrong results across all qtys on Mali-G720 (issue #23241, 2026-05, "167/860 test-backend-ops FAIL"); (b) descriptor-set CEIL_DIV 32-bit overflow on UMA Mali (issue #23057) when maxComputeWorkGroupCount=UINT32_MAX; (c) Mali-G925 small-tile narrow-matmul NaN (issue #26921, same tile-selection family) — G720 likely shares the small-tile defect (wkgrps 64-wide misses m≤32 cases); (d) small-shader odd-K stride bugs (#18493 comments). Workarounds: disable small tile for m≤32&&n>32, 64-bit CEIL_DIV, disable coopmat2 paths (G720 has none anyway — matrix cores: none), FP32 acc only. bf16 absent; int-dot path exists in ggml device reports (int dot: 1) but probe on this unit reports none → runboth check [owner probe; conflict unresolved].
- PocketPal/pocketpal-ai #534: MTK Mali falls back to CPU-only in shipped apps [published].

## 5. ISA on X4 + A720 (Arm v9.2-A)

Both are Armv9.2 cores: **DotProd (SDOT/UDOT) mandatory v8.4+; FEAT_I8MM (SMMLA/UMMLA/USMMLA); BF16; FP16; SVE2 @ 128-bit vector length; MTE3** [published, Arm Cortex-X4 TRM + juszkiewicz arm-cpu-cores table: gpages.juszkiewicz.com.pl/arm-socs-table/arm-cpu-cores.html]. No SME in v9.2 consumer cores. So kernels may emit: int8 dotprod + i8mm micro-kernels, FP16 dots, SVE2 128-bit gather/permute. Big.Little asymmetry only in frequency; ISA identical → no per-core codepath split [published].

## 6. NPU (APU 790) reachability — **verdict: effectively NO for llama.cpp/GGUF**

- APU 790 needs **AOT-compiled SoC-specific bytecode** (.dla via Neuron Compiler / .litertlm for MT6989) [published, developers.google.com/edge/litert/next/mediatek and litert_lm_npu docs]. Only pre-built models (e.g. Gemma3-1B MT6989 variant) run; arbitrary GGUF/ONNX cannot be JIT-compiled on device via the public path.
- Unprivileged APK access exists **only** through: (a) NNAPI/NeuroPilot delegate (Android 16: NNAPI deprecated) or (b) LiteRT-LM MediaTek dispatch .so. Crashes on MT6989 with even the official Gemma3-1B .litertlm reported (LiteRT-LM issue #2507, 2026-07) [published].
- llama.cpp has zero APU backend; no MTK ACC/mindspore-lite path merged.∑ Conclusion: APU is vendor-curated, model-baked, non-GGUF, NNAPI-legacy. Not a resource for this project.

## 7. Android 16 memory reality

- No hard per-app native heap cap above physical; limit comes from: ZRAM/compressed swap policy, LMK/low-memory-killer (vivo 12 GB "RAM expansion" swap ≠ usable working set), and VMA/ASHMEM pressure. Owner's observed ~6 GB free via Process.getFreeMemory() reflects **system-free +文件 cache accounting, not a per-app cap** [estimated]; an APP can mmap and dirty most of a 16 GB device transiently, but both vivo's aggressive LMK and PS killers at >60% device will zombify [estimated from fonearena RAM report: only ~5 GB free of 12 GB with defaults, X200 FE].
- `run-as` only sees the app's own sandbox; cannot read /proc of other UIDs, GPU driver stats, or vendor freq tables without shell/root.
- cgroup v2 memory.max on app processes is set by framework per-importance; backgrounded inference gets reclaimed [Android standard, estimated].

## 8. UFS storage — **correction to assumption: X200 FE uses UFS 3.1, not 4.0**

[A published override, notebookcheck X200 FE review: "512 GB **UFS 3.1**"; AndroBench seq read 1867 MB/s, seq write 1756 MB/s, rand-4KB read 301 MB/s, rand-4KB write 357 MB/s.] Some SKUs may be UFS 4.0 (MCQ supported by SoC) [estimated], but EU 512GB unit measured UFS 3.1.
vs UFS 4.0 nominal seq read ~4 GB/s (X200 Pro Mini measured 3804 MB/s); random 4KB ~300 MB/s in both classes (OA).
**Streaming >7 GB models from UFS cannot rival LPDDR**: UFS seq 1.87–4 GB/s vs DRAM 68.3 GB/s ≈ 3.5–5% and ~18×; random-4KB 300 MB/s ≈ 0.44% of DRAM band. Weight access during Q4 decode is scattered per-layer streaming: sustained decode reads ~1–2 GB/s per token for a 4–8B Q4 model → UFS random-read can just about fill cold-first-pass but not warm steady-state; mmap page-in evictions would thrash if RSS ≫ free DRAM. Effective floor: UFS adds ~5–50 s of load time and then must stay out of the decode path.

## IMPLICATIONS (ranked, for 4–8B Q4 decode)

1. **Memory bandwidth binds** — 68.3 GB/s theory, ~40–45 GB/s effective under LLM traffic; 4–8B Q4 token decode is a ~1–3 GB/token stream → hard cap ~15–25 t/s at 100% efficiency; everything else must fit within leftover bandwidth. Any novel technique that *shrinks bytes-read-per-token* (better locality, SLClined weights, int8-dot CPU kernels) is the main lever [estimated from published floors].
2. **Phone-level memory caps / ZRAM + vivo LMK** second — 12–16 GB device minus system ≈ 8–12 GB app-usable; models >5 GB risk kill/swap-thrash mid-generation; handler-level OOM mid-decode is the failure mode, not a speed one [published RAM free figures + estimated cgroup policy].
3. **X4 compute is not binding for Q4 tg** — i8mm/dotprod SVE2 128-bit kernels can saturate ~10 GOPS×8cores×2.85 GHz class peak ≫ bandwidth need at Q4; only higher precision (FP16 prefill) makes CPU compute a second-order binding [published ISA, estimated arithmetic].
4. **SLC (part of 18 MB L3+SLC)** — too small for 4–8B weight streams; only helps KV/activation tiles. Not a novel-technique target [estimated].
5. **GPU memory (Vulkan Mali) last among CPU options** — Mali-G720 gives 10–12.7 t/s tg for 4B (measured), worse than projected CPU path, plus correctness bugs (#23241/#26921 family); treat GPU as prefill accelerator only after upstream fixes land [published llama.cpp bug reports].

## Sources (access date 2026-10-23 all)

- MediaTek D9300+ product page — https://www.mediatek.com/products/smartphones/mediatek-dimensity-9300-plus
- fonearena launch spec — https://www.fonearena.com/blog/423210/mediatek-dimensity-9300-plus-features.html
- GSMArena launch — https://www.gsmarena.com/mediatek_dimensity_9300_brings_increased_clock_speed_and_improved_ai_processing-news-62743.php
- Notebookcheck X200 FE review (UFS 3.1, throttling, AndroBench) — notebookcheck.net/A-compact-Android-smartphone-with-top-hardware-and-an-XXL-battery-Vivo-X200-FE-review.1114877.0.html
- phonebench / nanoreview X200 FE benchmarks — phonebench.net/phone/vivo-x200-fe ; nanoreview.net/en/phone/vivo-x200-fe
- fonearena X200 FE RAM review — fonearena.com/blog/459570/vivo-x200-fe-review.html
- Arm Cortex-X4 TRM (BF16/i8mm/SVE2-128) — support.arm/documentation/102484/0003
- arm-socs-table (X4 0xd82, A720 0xd81 v9.2 SVE2 128-bit) — gpages.juszkiewicz.com.pl/arm-socs-table/arm-cpu-cores.html
- llama.cpp Vulkan G720 PR #18493, issues #23241, #23057, #26921 — github.com/ggml-org/llama.cpp
- LiteRT MediaTek NPU docs + issue #2507 — developers.google.com/edge/litert/next/mediatek ; github.com/google-ai-edge/LiteRT-LM/issues/2507
- MTK NeuroPilot/APU overview — developer.mediatek.com/ai/6423c8b6c612745b3a4baa2c.html
- PocketPal #534 (Mali CPU-only fallback) — github.com/a-ghorbani/pocketpal-ai/issues/534
