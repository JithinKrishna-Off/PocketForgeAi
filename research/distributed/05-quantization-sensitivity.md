# 05 - Quantization Sensitivity: How Much Precision Can We Shave?

THEORIST-05 (statistical physicist, quantization theory). Read-only theory doc. No builds, no data collection performed here; every number is either (a) exact block math from llama.cpp source, (b) derived from the 4B Q4_K_M on-device measurement (12.5 tok/s, KV = 288 MiB @ ctx 2048, `bench/raw/s4_loadlog_full.txt:966`), or (c) flagged PRIOR/EST.

Anchors:
- [measured] D9300, 4B Q4_K_M, t=6, armv8.6_1: tg128 = 12.62, pp512 = 54.2 (research/FINDINGS.md:28; coordinator brief says 12.5/54.2).
- [measured] KV CPU buffer 288.00 MiB at n_ctx=2048 = exactly 144 KB/token -> 36 layers x 2(K,V) x 8 kv_heads x 128 dim x 2 B (f16). Confirms the GQA geometry below against the real phone run.
- Memory-bound decode premise held throughout (agrees with THEORIST-03's roofline).

## 1. Model geometry + exact block sizes

Geometry used: 36 layers, hidden 2560, 32 Q heads / 8 KV heads (GQA 4:1), head_dim 128, ffn 9728, vocab 151936, tied embeddings. SOURCE: the 4B "Qwen3.5-4B" config has no verified public table in corpus; I use the Qwen3-4B config.json numbers, CROSS-CHECKED against the measured 288.00 MiB KV buffer at ctx 2048 (all four derived quantities - layers, kv_heads, head_dim, f16 KV - must combine to 288 MiB; they do exactly). Layer count 36 also matches the llama_kv_cache "layer 0..35" log lines (s4_loadlog_full.txt:921-965). Residual uncertainty in hidden/ffn dims does not change any conclusion below by >2%.

Derived:
- per-layer attn params: q,o (2 x 2560x4096) + k,v (2 x 2560x1024) = 26.214M
- per-layer MLP: 3 x 2560x9728 = 74.650M
- decode_params = 36 x 100.864M + lm_head 151936x2560 (tied) = **4.020 B params streamed every token**
- embed tensor is NOT streamed at decode (it is at prefill once).

Exact block sizes (ggml/src/ggml-common.h static_asserts; QK_K=256, K_SCALE_SIZE=12):

| quant | block bytes | per weight | bpw | source |
|---|---|---|---|---|
| Q8_0 | 34/32 | 1.0625 | 8.50 | exact (ggml-common.h:256) |
| Q6_K | 210/256 | 0.8203125 | 6.5625 | exact (ggml-common.h:368: 2 + 16 + 3x64) |
| Q5_K | 176/256 | 0.6875 | 5.50 | exact (128 nib + 32 hi-bits + 12 + 2) |
| Q4_K | 144/256 | 0.5625 | 4.50 | exact (128 nib + 12 + 2) |
| Q3_K | 110/256 | 0.4297 | 3.4375 | exact (64 q3 + 16 d2 + 4 m + 2) |
| IQ4_XS | 136/256 | 0.53125 | 4.25 | exact (ggml-common.h:460: 2 + 2 + 4 + 128) |
| Q2_K | 84/256 | 0.328125 | 2.5625 | exact (16 q2 + 64 q4 scales + 2 (+2 sb)) |
| Q4_K_M (file) | ~0.575 | ~4.6 | 2.31 GB/token | EST: Q4_K block + Q6_K mix on output/attn parts; anchored so 12.5 tok/s x bytes = BW_eff |
| Q4_K_S (file) | ~0.565 | ~4.5 | 2.27 | EST: fewer Q6_K-mixed tensors than M |

Q4_K_S vs Q4_K_M: same Q4_K super-block; the _M/_S suffix only changes WHICH tensors get upgraded to Q6_K (mixture), so the delta is small by design (~1-2% of payload).

## 2. Bytes/token ladder and implied decode tok/s

Effective bandwidth derivation: Q4_K_M payload = 4.020e9 x 0.575 = 2.31 GB/token (per-token streamed bytes: weights + lm_head, embeddings excluded). At 12.5 tok/s:

**BW_eff = 2.31 GB x 12.5 = 28.8 GB/s** (call it 29). Sanity: D9300 LPDDR5X burst is ~60 GB/s class -> 43% of peak burst, inside the 40-70% efficiency band already observed for this CPU stack (R4_theory.md cross-check). Throttled-sustained DRAM rate is lower still; treat 29 as the decode-operating bandwidth.

Decoding is memory-bound => tok/s ~= 29.0 / (bytes/token) + small KV/ctx term (section 3). Ladder (weights only, ctx ~= 0 direction):

| quant | GB/token | tok/s (pred) | vs Q4_K_M |
|---|---|---|---|
| Q8_0 | 4.271 | 6.8 | -46% |
| Q6_K | 3.298 | 8.8 | -30% |
| Q5_K / Q5_K_M | 2.79 | 10.5 | -16% |
| Q4_K_M (measured) | 2.30 | **12.5** | anchor |
| Q4_K_S | 2.27 | 12.9 | +3% |
| IQ4_XS | 2.14 | 13.6 | +9% |
| Q3_K (plain) | 1.73 | 16.8 | +34% |
| Q3_K_M (file) | ~1.85 | 15.9 | +27% |
| Q2_K | 1.32 | 22.0 | +76% |

Notes:
- Q8_0/Q5/Q6 gains in speed go the WRONG way - precision ADDS bytes on a memory-bound phone. Q8_0 weights only pay for themselves where compute is the bottleneck (prefill, KleidiAI/i8mm GEMMs; B2 audit: KleidiAI on MT6989 only fires for Q8_0 weights) or where you want near-lossless.
- The curve is concave in bpw: each shaved bpw is worth ~same tok/s regardless of where on the ladder, but quality risk is NOT symmetric (section 4).
- Prefill (54.2 tok/s) is compute-bound; lower bpw barely moves it, and Q8_0 + KleidiAI may RAISE prefill even while lowering decode. Choose per use case.

## 3. KV quant math (36L, 8 KV, hd 128)

Elements/token (all layers): 2 x 8 x 128 x 36 = 73,728.

| KV type | B/elem | bytes/token | write/read per token | total @ ctx C |
|---|---|---|---|---|
| f16 | 2.0 | 147,456 (144 KB) | baseline | 0.144 MB x C |
| q8_0 | 1.0625 | 78,336 (76.5 KB) | -46% | 0.0765 MB x C |
| q4_0 | 0.5625 | 41,472 (40.5 KB) | -72% | 0.0405 MB x C |

Decode is memory-bound => the KV cache READ is streamed every token: total stream/token = W + KV_bytes/token x C.

| ctx | f16 KV tok/s | q8_0 KV | q4_0 KV |
|---|---|---|---|
| 2k | 11.2 | 11.8 (+5.7%) | 12.0 (+7.3%) |
| 4k | 10.2 | 11.0 (+8%) | 11.3 (+11%) |
| 8k | 8.4 | 10.0 (+18%) | 10.7 (+27%) |
| 16k | 6.2 | 8.4 (+35%) | 9.8 (+57%) |
| 32k | 4.6 | 6.9 (+50%) | 9.2 (+100%) |

(All at Q4_K_M weights, 2.30 GB/token stream.)

Context CAPACITY for a fixed RAM budget (say 4 GB for KV): f16 = 27.3k tokens, q8_0 = 52k, q4_0 = 97k. This is the "very high overhead models" angle: at ctx >= ~16k the f16 KV cache (>= 2.4 GB) exceeds the entire free-RAM headroom of most 12 GB phones mid-session; q8_0 pushes the cliff out 2x, q4_0 out 3.5x. KV quant is a CAPACITY lever first, SPEED lever second on the phone, and (on the PC side) a BANDWIDTH lever for THEORIST-01 offload (every offloaded token's KV read/write over PCIe/eth scales with B/elem).

FA compatibility note from pf-plugins/pf_plan.cpp:256: with flashAttn=1, K may be q8_0 but V must stay f16 (llama.cpp FA constraint; the plan layer already warns). Without FA, K=V=q8_0 both fine. Scheme support table in llama-kv-cache: f16/f32/q8_0/q4_0 (+bf16 for K in newer builds).

## 4. Sensitivity theory: prior + empirical calibration

### Scale-invariant proxies (PRIOR, not gospel)
For each weight matrix W (per layer), compute offline from the dequantized GGUF:
- P1 weight kurtosis / per-channel RMS variance: quantization error grows with outlier-channel mass; proxy = kurtosis of argmax_i |W_i:| over rows and the coefficient of variation of per-row RMS.
- P2 d-scale dispersion: for K-quants, the fp16 block scales d (and 6-bit mins) already encode layer difficulty; layers with heavy-tailed d distributions are the ones iq-quants were invented for.
- P3 logit piping: error in a layer i perturbs all downstream logits with gain ~ ||L_jerk||; error in tok_emb/lm_head is unbuffered.

The llama.cpp quantizER's own built-in folklore encodes the same prior empirically (quantize.cpp tensor rules): output.weight and output always get Q6_K (or Q8_0) because lm_head errors are unbuffered and visible per token; attention k/q/v and first FFN/down projections historically the most sensitive 4B-8B dense; MoE experts and interior MLP gate/up are the least. Typical measured pattern for dense 4-8B chat models: Q4_K_M -> Q3_K_M costs ~1-3% MMLU but a deep Q2_K/IQ2 drop costs 5-15 ppl-equivalents and usually destroys instruction following, while IQ4_XS vs Q4_K_M is usually inside noise (<0.5%).

Ranking, prior expectation for the 4B (fragile -> robust): tok_emb/lm_head >> layer-0 attn K > first ~2 and last ~2 layers > interleaved attn > MoE experts / interior MLP > (nothing else is fragile).

### Cheap EMPIRICAL calibration probe (design)
The app already computes logits for every generated token; a prompt-only pass gives mean NLL of a fixed text with no generation at all. Probe:
1. Fixed calibration text: 8-16 KB of on-domain prose (ship it as an asset; 2 KB headline figure is fine, use 8 KB for stability: NLL delta of 0.01 has ~sqrt(16)=4x std reduction vs 2 KB).
2. Baseline pass: ingest the text, no sampling, accumulate mean NLL of the reference text under (Q4_K_M weights, q8_0 KV). Cost = prefill of 4-8k tokens ~ 80-150 s on 54 tok/s... too slow: use 1-2 KB text = 20-40 s, accept +-0.9x std on NLL; average 2 texts.
3. Variant passes: candidate quant variants are PRE-BAKED GGUFs (llama.cpp cannot re-quantize at runtime cheaply; per-tensor re-quant at load IS technically available via tensor overrides in the Vulkan path only). So the menu = N alternative GGUFs (q4km, iq4xs, q3km, q2k + kv variants), user downloads delta files ~2 GB each... cheaper: ship ONE probe GGUF at the aggressive level; if NLL ratio > 1.03 vs the safe variant's advertised NLL, fall back automatically.
4. Decision rule: ship variant X if mean NLL(X)/NLL(anchor) <= 1.02 and no token of a 64-token fixed continuation flips (cheap top-1 agreement check on the same pass: fraction of greedy next-token agreements; directly interpretable).

Total on-device cost: ~1-2 min once per downloaded model. This converts the folklore prior into a per-model measurement for the price of two prefill passes.

## 5. Compounding: 9B on the phone

Take the 8B-class geometry the ladder hits first (36L, hidden 4096, 32Q/8KV, hd128, ffn 12288, vocab 151936, untied; ~8.19B decode params; generic 9B variants within +-10%):

| config | weights RAM | KV @ 8k ctx | total (incl ~1 GB ctx/workspace) | decode tok/s @8k |
|---|---|---|---|---|
| Q4_K_M, KV f16 | 4.8 GB | 1.17 GB | ~7.0 GB | 4.9 |
| Q4_K_M, KV q8_0 | 4.8 GB | 0.62 GB | ~6.4 GB | 5.9 (+20%) |
| IQ4_XS, KV q8_0 | 4.3 GB | 0.62 GB | ~5.9 GB | 6.4 (+31%) |

Q4_K_M 9B: 4.7-5.0 GB of weights, KV q8_0 8k ctx 0.62 GB, workspace ~0.8-1 GB (batch 512 + graph), total ~6.5 GB -> fits a 12 GB phone with ~5 GB of headroom for the OS/app; a 16 GB phone is comfortable. Fitted-comfort line: up to ~10B at IQ4_XS + KV q8_0 within 12 GB; ~14B will not fit phone-side (weights 7.4 GB + KV > 8 GB phone budget); that is the regime where PC offload (THEORIST-01) is mandatory, and there the freed bytes translate 1:1 into LOWER offload data per token (each streamed tensor's size shrinks with bpw; KV BW per token over the link drops 46%/72% at q8_0/q4_0), improving the offloaded decode ceiling linearly (compute on PC is not memory-bound on cache, see 01/03).

The best defensible ladder (Q4_K_M -> IQ4_XS, KV f16 -> q8_0) compounds: 4B gains ~+9% weights-ladder +5.7% KV @ 2k => ~15% decode speedup and 0.16 GB weights + 0.31 GB @2k / 0.55 GB @8k KV freed (WB 2.30->2.14, KV 0.288->0.153 GB/token @2k).

## 6. Verdict: shippable moves

| tier | move | expected gain | risk |
|---|---|---|---|
| SHIP NOW (near-zero risk) | KV K=q8_0 (+V q8_0 only when FA off; K q8_0 with FA on) | RAM: KV /1.88; tok/s +6% @2k, +18% @8k; and it is already a pf_plan flag (kvTypeK/v in pf_plan.cpp:196) | minor: some models' V-cache quant path with FA off is best-tested only for q8_0 K/f16 V; guard in plan validation |
| SAFE | weight GGUF at Q4_K_S / IQ4_XS | decode +3% / +9%; RAM -1% / -5%; quality ~inside noise for Q4 family | needs one extra GGUF download; IQ4_XS does NOT use the KleidiAI path (skipped on this stack) but that path doesn't fire for Q4_K either; repack/NNEON path identical class |
| SAFE-ish | per-token: keep output.weight at Q6_K in all conversions (llama.cpp default) | free | none |
| AGGRESSIVE (probe-gated) | Q3_K_M / IQ3_XXS weights or q4_0 KV | decode +27%; KV -72% (ctx capacity 3.5x) | quality loss is model-specific, 1-5+ NLL points; MUST gate on section 4 probe, else silent bad UX |
| NOT worth it | Q5/Q6/Q8_0 weights on phone decode (traded AWAY tok/s for memory-bound work); Q2_K as default | negative tok/s vs anchor | only as a compute-side choice for prefill-heavy offload scenarios |

Ordering with other levers: code (armv8.6_1 / kernel variant) is free quality; KV q8_0 is one JSON flag; IQ4_XS is one extra artifact + 4-bit selection logic in the hosting/distribution layer; probe is an app feature. Compounding with PC offload: the same 2 bits/token shaved from KV cuts the offload link load per token by ~2x (THEORIST-01) and cuts phone-side RAM pressure so a bigger share of a 9B stays resident.

## Open items (testable, cheap)
1. Measure IQ4_XS 4B converted from the same checkpoint: predicted 13.6 tok/s; verify +-1 tok/s.
2. Verify KV q8_0 V (FA off) correctness on-device with the pf_plan kvTypeV knob; log tok/s at ctx 8k.
3. Run the NLL probe across {Q4_K_S, IQ4_XS, Q3_K_M, Q2_K} for 4B to calibrate the probe's own sensitivity (how many NLL points of resolution does 8 KB of text give).
