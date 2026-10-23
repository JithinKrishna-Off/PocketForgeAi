# R1 — agent L raw notes (literature)
Device-independent; all URLs accessed 2026-10-23.

## Method
Websearch (8 rounds, ~64 results) + targeted webfetch of arXiv HTML/PDF pages to pull exact figures. 3 inlines of the app's pin (third_party/llama.cpp @ 0a1ef30 dated 2026-10-21; GGML_LLAMAFILE=OFF; GGML_CPU_KLEIDIAI not in build-native.ps1; GGML_CPU_ALL_VARIANTS=ON/GGML_BACKEND_DL=ON). No device access (no adb), no builds.

## Pin facts (from repo, not web)
- llama.cpp pin: `0a1ef30` "mtmd: add state object for mtmd_gen (#30902)" 2026-10-21 (git log third_party/llama.cpp).
- build-native.ps1 opts: -DGGML_NATIVE=OFF -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_LLAMAFILE=OFF -DBUILD_SHARED_LIBS=ON; Vulkan optional via -EnableVulkan (-DGGML_VULKAN=ON).
- GGML_CPU_KLEIDIAI = ggml-cpu/CMakeLists.txt:630, fetch KleidiAI v1.31.0, AArch64-only, SME2 microkernels ("kai matmul Q4_0"); NOT enabled by app.
- docs/speculative.md present at pin → --spec-type machinery incl. ngram-simple/-map-k/-mod, draft-eagle3/dflash/dspark is available in the pinned tree.

## Key excerpts (condensed)
- MNN-LLM arXiv:2506.10443: "decoding speeds that are 2.3x [llama.cpp] and 8.9x [fastllm] … GPU: up to 7.1x decoding than llama.cpp, 1.7x over MLC-LLM"; DRAM-Flash hybrid storage; Xiaomi14.
- MNN-AECS arXiv:2506.19884: table: llama.cpp 10.2 tok/s @ 8.8 W; MNN 21.7 @ 8.7 W; AECS 20.6 @ 6.2 W (Android, 1.5B int4); iPhone12: 15.3/27.6/31.5.
- LLM-in-Pockets arXiv:2410.03613 (v5 2026-02): D9300 fastest non-Apple CPU decode (10-20% behind M1); Mali-G720 (Vivo Pad3 Pro): decode AND prefill slower than CPU; prefill <5 tok/s for >4B models on Mali; LSU >90% partial-width reads; Mali-G720 fp16 peak 1.5x Adreno750 yet ~10x slower prefill.
- HeteroInfer arXiv:2501.14794v2: SD8Gen3 Llama-8B decode 14.01 tok/s; 1.50x vs llama.cpp / 2.53x vs MNN-OpenCL / 1.52x vs MLC as figure; claims first >1000pp + >50 tg.
- sd.npu arXiv:2510.15312v4: CPU SD on-device only 0.92–1.13x (NG+), 0.56–1.18x (SpecInfer); NPU 1.42–1.85x on 3B; implemented atop mllm; OnePlus13/Redmi K70Pro/K60Pro.
- ExecuTorch (docs/blog): OnePlus 12 4-bit g256: Llama3.1-8B 11.02; Llama2-7B 11.6; Llama3.2-3B 22.98; 1B 53.41 (SpinQuant). ExecuTorch paper arXiv:2605.08195: S25 Ultra — llama.cpp wins dense decode (attention faster for single-token), ET Vulkan wins tg sometimes / loses prefill.
- LLM in a Flash arXiv:2312.11514v3: 32KiB+ multi-threaded reads; 4-5x CPU speedup vs naive; models up to 2x DRAM.
- PowerInfer-2 arXiv:2406.06282: I/O-aware orchestration; 47B on phone 11.68 tok/s; decoded-Mistral-7B 12.4 tok/s @ 41.1 GB/s; Mixtral-47B offloaded 2.4 tok/s vs llama.cpp 0.2–0.5.
- Lever arXiv:2605.16786: DRAM-resident EAGLE3 draft + flash-streamed target; target flash bw ~5 GB/s; beats Chain-SD/EdgeLLM/OPT-Tree; 48 configs.
- PowerServe (GitHub powerserve-project/PowerServe): QNN-only (SoC flags 8650/8750, i.e., Snapdragon); "40 tok/s Smallthinker (MoE)" via NPU speculation; 8G3 table: 8B(+1B draft) 468.35 pp / 12.03 / 21.02 tok/s; qwen2.5-3b 21.01; not usable on MTK.
- Sereno OSDI'26 (usenix.org/system/files/osdi26-xin.pdf): bandwidth contention between LLM and foreground apps; built atop PowerServe+QNN; decode-interference scheduling.
- Vulkan perf threads: discussions/10879 (D9400 llama7B Q4_0 ngl100: pp512 38.36±15.15, tg128 8.92±0.06; "pp512 faster with coopmat off"; Mali-G57 uma/fp16/warp16/no-matrix-cores); issue #26663 (mmvq n=1 200–375x slower than n=128 … shape-dependence); #24066 (b9460 Vulkan regression); #25908 (draft-simple p_min 0.00 default → acceptance 0.07, spec slower than plain even at 0.9 acceptance on RDNA4).
- KleidiAI: learn.arm.com SME2 llama.cpp path (Llama-3.2-3B-Q4_0, SME2 device: prompt 12.3 / gen 9.1 t/s); Arm I8MM blog (Neoverse-N2, Q6_K/Q4_K, charts); ExecuTorch+KleidiAI +20% decode on Armv9 i8mm; release b11413+ ship Snapdragon-only Android builds w/ Ada… no MTK.
- DISCO arXiv:2405.04304: dynamic lookahead +10% over best static (server).
- "When NPUs are not always faster" arXiv:2605.27435: CPU 1.6x NPU in prefill; NPU decode 1.05–1.2x; +51% energy with NPU offload (SD8Gen3).
- DVFS governors arXiv:2507.02135 (cited within 2603.23640); LLM-at-Edge sustained-load arXiv:2603.23640 (two thermal regimes; MLC OpenCL 25 s prefill anomaly).
- BitNet: microsoft/BitNet README (1.37–5.07x ARM vs FP; 2.4B-4T; i2_s 1.13 GB; Jan-2026 1.15–2.1x kernel rework); termux-bitnet PyPI: Galaxy S25 (8 Elite) 5.12 tok/s i2_s 2B; Exynos 1380 1.57 tok/s [anecdote].
- KiF IEEE CAL 2026: in-flash KV, 4.1x vs SSD-offload (hardware). "LLM Inference in a Flash!" arXiv:2609.16161 (compute-in-flash, Sep 2026). FLINT (HBF, IEEE CAL 2026-08) — desktop-class, not phone.
- Old-systems lanes: posix_fadvise.2 / madvise.2 man pages (WILLNEED/DONTNEED/SEQUENTIAL semantics); maps: (a) weight-page residency = readahead/residency hints on GGUF mmap; (b) DMA streaming = LLM-in-a-Flash multi-threaded chunk reads / PowerInfer-2 I/O orchestration; (c) KV→flash = KiF/Lever/FlexGen; (d) SMEM/DMU = no recognizable match found in LLM context (flagged in main doc).

## Search dead-ends (to prevent re-searching)
- No engine named "FlashStreaming" (names verified absent in arXiv search results 2026-10-23).
- No public head-to-head tg numbers llama.cpp armv9.2_2 vs armv8.0_1 on any phone (only the variant list exists).
- No published llama.cpp Vulkan-vs-CPU on Dimensity 9300/9300+ specifically (D9400 yes, via #10879).
- ertas.ai blog results table omits D9300 entirely; treated unreliable.
- cpu-monkey / topcpu / cputronic — SoC spec aggregators only, no LLM t/s of value.
- H2/H4/H8-related (thermal/thread collapse): no published D9300-specific decode-therm曲线; closest is arXiv:2603.23640 sustained-load + MNN-AECS power experiment + DVFS paper.
- Cross-app shared weight memory on Android: zero hits (GAP #8 → implicit novelty if ever built).

## Cross-references
- Supports H1 (variant tuning): KLEIDIAI + armv9.2_2 are the two untested-on-phone candidate kernel ladders; both outside or disabled in current pin → easy experiment.
- Supports H3 (vulkan loses): 2410.03613 + #10879 (both independent).
- Threatens H7 (SD on CPU): sd.npu CPU 0.92–1.13x + issue #25908.
- Supports H4 (noise): Sereno (bandwidth contention co-running).
- Supports H5 (roofline): PowerInfer-2's 41.1 GB/s measured CPU-family bandwidth on OnePlus 12 shows aggregate CPU-accessible bw is the binding constraint; nothing published on LPDDR5X-D9300+ measured bw for LLM decode.
- Supports H2 (thread optimum): pattern "decode peak = big-core count" (Kirin 9000E/SD8+G1 4-big-core devices) — indirect only.
