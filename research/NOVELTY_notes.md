# NOVELTY notes (NOV) — 2026-10-23

Working file for NOVELTY_H6_H7.md. Raw observations, quotes I did not want to lose, and loose ends.

## Search hygiene
- 8 websearch batches + 1 page fetch, all on 2026-10-23. Every candidate got ≥2 differently-worded searches (see "Search log" sections in NOVELTY_H6_H7.md). Permalinks recorded at paper-page level; GitHub refs pinned to PR/commit where possible.
- Quotes copied verbatim from search-result excerpts / fetched pages; arXiv HTML anchors are stable but re-verify quote text before submitting anywhere.
- No device, no builds, per instructions. Timebox respected (~30 min).

## Surprises / strong signals
1. **PocketTune is the H6 killer.** It is not a paper — it is a shipped Android app (Arm Create AI Challenge 2026, MIT) doing probe→sweep→apply for llama.cpp on phone, including six arm64 kernel build variants and KV/flash-attn sweeps. If H6 was written before Aug 2026, the landscape moved. The only surviving H6 delta is the *bounded* 90s budget, NPU-inclusive ngl split, thermal input, and plan-as-C-ABI.
2. **llama.cpp upstream now defaults to draftless n-gram speculation** (b9235 release note: "spec : extend --spec-default with ngram-map-k4v"). The self-spec refactor #18471 + ngram-mod #19164 landed Jan 2026. H7's "n-gram + lookahead plugin" is upstream stock behavior now — a plugin claim is dead on arrival; strategy *planner* claims must be sharply framed.
3. **Closed-but-citable adaptive-bound PR #19744** (EMA acceptance-rate → draft-min/max) — resembles our adaptive drafting idea; it was closed (2026-02), which may leave room, but it is public prior art for the mechanism.
4. **sd.npu is both prior art and a threat to our headline number.** Quote worth keeping: "Improvements of applying SD on CPU vanilla are moderate (0.92–1.13× for SAM, 0.56–1.18× for EAGLE), as LLM inference on mobile CPU is compute-bound due to the limited CPU capacity." If we want ≥1.5× tg on 4B–8B CPU-side, we must differ from their setup: they ran vanilla+SD on mllm CPU at short ctx with EAGLE/SAM; K-quant + FA + longer ctx + ngram (no draft model, ~zero drafter FLOPs) changes the economics — ngram-spec verify batches cost the same as plain decode, so speedup ≈ acceptance length × batch-verify amortization, minus rollout overhead. The disproof must be an experiment: ngram-map-k4v+ngram-mod combo vs plain on 8B Q4_K, midrange SoC.
5. **PowerServe datapoint** (NPU phones): llama-3.1-8B with llama-3.2-1B draft: 12.0 → 21–23 t/s (~1.75×). So ≥1.5× total-tg is achievable on-device *with a draft model on NPU*; our draftless-CPU claim is the contested one.
6. **Ghidorah (ICCD'25)** is the academic "speculative strategy planner via profiling" — call it out explicitly in related work or an ACIS-style reviewer will.
7. **PELM (SenSys'26)** couples spec + variable verification depth + DVFS with "comparable task performance" — closest mobile analogue to a quality-bounded planner; treat as the fence-post for H7's ≤2% claim.
8. **H10 budget arithmetic is uncomfortable:** measured footprints (2505.15030): 7B q2_k ≈ 2.4 GB, q4_0 ≈ 6.9 GB. So "7B in ≤7 GiB" is table stakes in llama.cpp today; the claim only bites for **13B in ≤7 GiB** (q2/q3 + bounded ctx) or on 4–6 GB usable-RAM midrange. ActiveFlow (llama.cpp-based, phones, −40% DRAM for 7B) plus MNN-LLM DRAM-Flash hybrid storage make "first" impossible; "probe-integrated footprint guarantee" is the honest reframe.
9. **Thermal prior art is dense on the frequency side (EnerInfer, PELM) but empty for llama.cpp CPU-flag plans.** If we present thermal-aware plan *re-selection* (not DVFS) for llama.cpp backends, nobody has published that exact knob.
10. **Vendor layer (Qualcomm QAIRT power configs, MTK NeuroPilot, MNN Power_Low mode)** exists but is static/compile-time or frequency-level; MNN-AECS is the one engine-level adaptive selection. Worth one paragraph in related work to preempt "vendor SDK does this."

## Loose ends / follow-ups for coordinator
- Verify whether PocketTune publishes its sweep duration; if it beats 90 s, revise our calibration-budget framing to "bounded, cold-start-first-use" and lead with NPU-split + C ABI instead.
- PR #19744 was closed unmerged — check discussion for maintainer reasoning; an adaptive-draft-bounds implementation that landed differently would matter.
- sd.npu v4 (2025-12-17) is the current version; cite v4.
- Not searched in depth (timebox): ONNX Runtime device selection, ExecuTorch XNNPACK thread auto-config, OpenReview versions of sd.npu/Ghidorah. Flagged as rows for a second NOV pass if H6/H7 go forward.
