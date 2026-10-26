# NOVELTY — H6, H7 (and H10 side-scan)

Agent: NOV · Date of search: 2026-10-23 · Method: ≥2 independent web searches per claim (different wording; arXiv, Google Scholar via search, GitHub, vendor/docs). Weighting: last 12 months preferred. No device, no builds.

---

## CANDIDATE H6 — device-adaptive runtime: probe phone (90s calibration) → select/expose per-device execution plan (llama.cpp CPU variant, threads, ngl split, KV type, cache scheduling) via C ABI for JNI/Kotlin

### Search log
1. "llama.cpp automatic tuning thread count mobile phone profiling on-device LLM inference autotuning 2025" → **armtune, LITTLETai, PocketTune, GraviTune, neonpilot, llama-optimus** (GitHub + project pages).
2. "arXiv device profiling select execution plan LLM inference smartphone CPU NPU backend selection 2025 2026" → **HeteroInfer (2501.14794), PowerBench (2607.05475), "When NPUs Are Not Always Faster" (2605.27435), lm-Meter (2510.06126), Agent.xpu (2506.24045)**.
3. "MLC LLM auto executor runtime selection per device" → **mlc-llm `auto_device.py` / `auto_target.py`** (device detection + build-target presets, e.g. `android:adreno`).
4. "MNN LLM battery power mode NeuroPilot Qualcomm AI Stack device adaptive config" → **MNN-AECS (2506.19884)**, MNN-QNN/NeuroPilot plugin docs, MNN-LLM paper (2506.10443).
5. "thermal-aware online tuning smartphone LLM decode thread adaptation 2026" → **EnerInfer (2606.23001)**, PELM (2609.09662), DVFS-governor study (2507.02135).

### Closest prior art (top 2)

**PA-6.1: PocketTune** — https://ayanbag.github.io/PocketTune/ (accessed 2026-10-23)
> "It finds the fastest local-LLM setup for your exact Arm chip. Then it proves it." … "Tune sweeps thread counts, flash attention and KV-cache layout on the phone itself, scores each result for speed and tokens per joule, and applies the winner." … "It ships six arm64 kernel builds and picks one at startup by reading the chip's features: i8mm silicon gets the i8mm build, a dotprod-only chip gets the dotprod build."

That is: probe chip (instructions + core map) → on-device sweep → **install winner as app live settings** → chat offline. Stack: React Native/TypeScript over **llama.cpp via llama.rn**, MIT, Arm Create AI Challenge 2026. Covers: CPU/build-variant selection, thread count, flash-attn, **KV-cache layout**, energy scoring.

**PA-6.2: MNN-AECS** — https://arxiv.org/abs/2506.19884 (accessed 2026-10-23)
> "Between installation and LLM service, we add a once-and-for-all AECS decode tuning, which searches for the optimal core selection that reduces decode energy with negligible speed loss… After tuning, the searched optimal core selection is input to the thread pool as decode configuration for all future services."

Engine-level, no root, per-device adaptive core-selection plan (Android affinity binding; thread number on iOS), separate prefill/decode selections. 5 Android + 2 iOS devices.

Honorable mentions: **armtune** https://github.com/hiteshchopra11/armtune (llama.cpp PR #26838; adb+python host-side probe/sweep → exact flag set `-t 4 -C f0 --cpu-strict 1`, +22–24%); **LITTLETai** https://github.com/Kamrulhasan12345/littletai (reads phone CPU layout, presets); **HeteroInfer** https://arxiv.org/html/2501.14794v2 (offline per-SoC profiler + tensor-partitioning solver → pre-generated execution plan, per-SoC); **EnerInfer** https://arxiv.org/html/2606.23001v2 (predicts power/throughput tables at model init, chooses NPU/DDR frequency plan with thermal MPC on phones); **mlc-llm auto_device** https://github.com/mlc-ai/mlc-llm/blob/main/python/mlc_llm/support/auto_device.py (device="auto" detection; build presets per GPU family). Upstream llama.cpp itself auto-picks thread count (`common_cpu_get_num_math()`), which LITTLETai documents as a static heuristic with no Arm big.LITTLE logic.

### Exact difference vs what exists
- PocketTune already does **probe→sweep→apply winner** on phones for llama.cpp, including CPU variant (6 kernel builds) and KV/flash-attn.
- Δ-remaining: (a) bounded *90-s* calibration budget with early-stop (PocketTune/llama-optimus sweeps are unbounded); (b) plan space extends to **ngl/split for NPU+CPU hybrid** and **kv-cache scheduling** (PocketTune is CPU-only, no NPU); (c) thermal-state input (EnerInfer has thermal for NPU/DDR freq, not for llama.cpp CPU flags); (d) artifact exposed as **C ABI consumed by JNI/Kotlin planner** (PocketTune is llama.rn/TS; armtune is host-side; MNN-AECS is opaque to callers); (e) unified single "plan object" rather than scattered knobs.

### NOVELTY-CLEAR?
**No (≈components ≥80% covered).** The core move "probe the phone, pick per-device llama.cpp config automatically" is published and shipped (PocketTune 2026; armtune/LITTLETai; MNN-AECS; EnerInfer for thermal/frequency plans). Remaining delta is integration breadth + bounded budget + NPU-split plan space + C ABI — engineering, in a busy space.

**REINTERPRETATION (must-present framing):** Do NOT claim "first device-adaptive select." Claim: **"budget-bounded (90 s) calibration producing a single declarative per-device plan artifact (variant, threads+affinity, ngl split incl. NPU-vs-CPU partitioning, KV type, cache/slot scheduling) whose space is strictly larger than prior app-level sweeps; delivered as C ABI so the plan is an interface, not a feature."** Cite PocketTune/armtune/MNN-AECS/EnerInfer as known; novelty rests on the plan-schema generality + measured wall-clock calibration guarantee on commodity Android.

---

## CANDIDATE H7 — on-device speculative decoding plugin, *general planner* (no draft model), 4B–8B llama.cpp on phones; adaptive context reuse, n-gram + lookahead tuned for mobile chat; ≥1.5× tg at ≤2% quality change

### Search log
1. "speculative decoding on-device smartphone LLM NPU draft model 2025 arXiv" → **sd.npu (2510.15312), LP-Spec (2508.07227), Ghidorah (2505.23219), PowerServe (GitHub), SpecExec (2406.02532)**.
2. "lookahead decoding n-gram draft-free llama.cpp plugin pull request" → **llama.cpp lookahead PR #4207, prompt-lookup PR #4484, spec refactor #18471, ngram-mod #19164, docs/speculative.md**.
3. "GitHub Android LLM speculative decoding adaptive draft length llama.cpp 2026" → **llama.cpp PR #19744 (adaptive draft bounds, closed), MVP Factory Android guide (2026-07)**.
4. "thermal/online tuning + spec" sweep → **PELM (2609.09662, SenSys'26)**.

### Closest prior art (top 2)

**PA-7.1: llama.cpp upstream draftless spec suite (2026)** — https://github.com/ggml-org/llama.cpp/blob/master/docs/speculative.md (accessed 2026-10-23); PRs #18471 (self-speculative refactor, merged 2026-01-30), #19164 `spec : add ngram-mod` (merged 2026-01-30), release note b9235: *"spec : extend `--spec-default` with ngram-map-k4v"* (2026-05-20).
> "### n-gram Map (`ngram-simple`, `ngram-map-*`) These implementations search the token history for patterns and use matching sequences as draft candidates. They require no additional model…" … "`--spec-type none|draft-…|ngram-cache|ngram-simple|ngram-map-k|ngram-map-k4v|ngram-mod`" — and upstream now defaults server speculation to ngram-map-k4v.

I.e., **draft-free n-gram speculation, multiple variants, combined+layered, is shipped upstream and is the default in llama-server.** A closed PR #19744 (2026-02) even proposed `--spec-ngram-adaptive`: *"Dynamically adjusts draft-min and draft-max based on acceptance rate using EMA tracking… High acceptance rate (>0.7) → more aggressive drafting."*

**PA-7.2: sd.npu** — https://arxiv.org/abs/2510.15312 (accessed 2026-10-23)
> "context-aligned drafting, which improves speculative efficiency through lightweight online calibration to current tasks; and hardware-efficient draft extension, which reuses and expands intermediate sequences… sd.npu achieves 1.06–3.81× end-to-end speedup over the vanilla framework."

Mobile (mllm+NPU, Snapdragon Gen2–Elite phones), retrieval-based SD, **online distribution calibration + draft reuse**, 0.5B–3B. Also in it: *"Improvements of applying SD on CPU vanilla are moderate (0.92–1.13× for SAM, 0.56–1.18× for EAGLE), as LLM inference on mobile CPU is compute-bound due to the limited CPU capacity."* — this is the exact evidence contradicting H7's ≥1.5× tg target on CPU-side 4B–8B.

Also: **Ghidorah** https://arxiv.org/abs/2505.23219 (ICCD'25): *"ARCA… determine the optimal speculative strategy and partitioning strategy, balancing acceptance rate with parallel capability"* — a profiling-driven **spec-strategy planner**, but Medusa-based (extra heads), Jetson NX (CPU+GPU), up to 7.6×; not phones, not draftless. **PELM** https://arxiv.org/abs/2609.09662 (SenSys'26): spec + variable verification depth + DVFS on mobile, ≤ task-performance degradation, 23.1% speedup. **PowerServe** https://github.com/fengerhu1/PowerServe: draft-model SD on phone NPU, llama-3.1-8B 12.0→21–23 t/s (~1.75×) — but uses llama-3.2-1B as draft. **MVP Factory** https://mvpfactory.io/blog/speculative-decoding-on-android-wiring-a-draft-model-to-llama-cpp-for-2-3x/ (2026-07): draft+target on Pixel 9 via llama.cpp, 2–3× — draft-model recipe.

### Exact difference vs what exists
- Draftless n-gram/lookahead SD on llama.cpp: **exists upstream** (by default). Adaptive draft bounds: **proposed upstream** (closed PR). Mobile SD with online calibration + context reuse: **exists (sd.npu)** but on mllm/NPU, RAG-heavy, ≤3B. Quality-bounded (≤2%) verification on-device: **PELM** targets comparable quality via depth control.
- Δ-remaining: (a) turn-level **context-reuse across chat turns** as first-class planner input (llama.cpp ngram cache persists; sd.npu reuses in-task context); (b) **strategy-switching planner** (ngram vs lookahead vs none per device/thermal/workload) integrated with H6's plan layer; (c) a measured **≥1.5× tg on mobile CPU** for 4B–8B — contradicted by sd.npu's CPU measurements; needs new evidence on K-quant/FA/memory-bandwidth grounds, or the claim should be re-aimed (NPU-assisted verify, or quality ≤2% envelope with mixed strategies).

### NOVELTY-CLEAR?
**Edge → No.** "Draftless n-gram plugin for llama.cpp on Android" ≈ 80% covered (upstream defaults + sd.npu + PELM + Ghidorah's planner). The planner-with-guarantee + cross-turn reuse is a real, narrow delta; the headline-number claim (≥1.5× CPU tg) is contraindicated by prior measurements and would have to be earned.

**REINTERPRETATION:** Present as **"quality-bounded speculative plan layer": a runtime that per-turn selects/enables upstream draftless spec types (ngram-*/lookahead) under a measured ≤2% quality-drift envelope and a thermal/time budget, with cross-turn context reuse on mobile chat; report tg speedups as a function of device bandwidth (not a flat ≥1.5× claim)."** Then sd.npu's CPU-bound result becomes a jointly-published constraint we characterize on K-quant/FA enabled phones rather than disprove.

---

## CANDIDATE H10 (side-scan, as instructed) — constrained-memory mode: KV types + memory sizing + per-layer pinned pages; 7B–13B in ≤7 GiB at comparable speed

### Search log
1. "run 7B LLM on smartphone limited RAM KV cache quantization per-layer offload llama.cpp 2025 2026" → **ActiveFlow (2504.08378), llm-optimizer/AHMP, H1B-KV, systematic on-device eval (2505.15030), TurboQuant-integration repo/blog, DirectKV (OSDI'26)**; MNN-LLM DRAM-Flash storage surfaced via MNN search in H6 log.
2. (Combined with H6 vendor sweep) → MNN-LLM 2506.10443, ActiveFlow built on llama.cpp.

### Closest prior art (top 2)

**PA-10.1: ActiveFlow** — https://arxiv.org/abs/2504.08378 (accessed 2026-10-23)
> "the first LLM inference framework that can achieve adaptive DRAM usage for modern LLMs… active weight DRAM-flash swapping… under the same model quality and speed, ActiveFlow reduces the DRAM usage by up to 40% for LLaMA 7B compared to llama.cpp" — built **on llama.cpp**, evaluated on OnePlus 12 / Pixel 6 / Infinix Zero; deployed Mixtral-8x7B 4-bit on mid-range Pixel 6 at 2.9 GB.

**PA-10.2: AHMP (llm-optimizer)** — https://github.com/Fai305/llm-optimizer (accessed 2026-10-23)
> "A planner for running large language models locally under a fixed memory budget… AHMP's contribution is to treat them as one constrained allocation problem and solve the hard part exactly… given a per-block VRAM budget, which precision should each transformer block use to maximise quality? … precisely a Multiple-Choice Knapsack Problem" — per-block mixed precision + KV steps + offload + context shrink; desktop (VRAM/RAM/disk), no phones, no runtime integration.

Also: **MNN-LLM** https://arxiv.org/html/2506.10443: *"model quantization and DRAM-Flash hybrid storage, effectively reducing memory usage"* on phones; **H1B-KV** (1-bit K-sketch + 4-bit V: 7B @ 8k ctx <60 MB cache); **"Don't Waste Bits!"** https://arxiv.org/abs/2604.04722 adaptive KV precision policy; raw llama.cpp facts: 7B q2_k ≈ 2.4 GB, q4_0 ≈ 6.9 GB footprint (2505.15030 measurements) — so "7B in ≤7 GiB" is **already routine in llama.cpp q4_K_M/q3_K today**; the interesting bound is 13B in ≤7 GiB (q3/q2 + small ctx) or midrange with thL-Promise.

### NOVELTY-CLEAR?
**Edge.** Memory-planning under a budget = AHMP (desktop), DRAM-Flash swapping = ActiveFlow/MNN-LLM, KV types = upstream + quantization papers. Δ: **on-device, planner-integrated with the H6 probe (footprint-guarantee mode derived from measured free RAM + bandwidth, per-layer pinned/mmap pages to avoid swap-death), evaluated on midrange phones where "can't run at all" is real (4–6 GB usable)**. Frame as re-interpretation: *"probe-driven footprint-guarantee mode"* rather than "first memory planner."

---

## Cross-candidate engagement summary
- H6: verbatim-claim already shipped (PocketTune). Our defensible slice: bounded calibration + plan-as-ABI + NPU-split plan space. NOVELTY-CLEAR? **No.**
- H7: upstream n-gram spec defaults + sd.npu/PELM/Ghidorah. Our defensible slice: quality-bounded strategy planner + cross-turn reuse; ≥1.5× CPU-tg claim must be re-scoped. NOVELTY-CLEAR? **Edge/No.**
- H10: ActiveFlow+AHMP+MNN-LLM. Our defensible slice: probe-integrated footprint guarantee on midrange. NOVELTY-CLEAR? **Edge.**
