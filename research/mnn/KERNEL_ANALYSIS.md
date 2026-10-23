# MNN CPU Matmul vs llama.cpp armv8.6 — Kernel Analysis (MNN 3.6.1 @ 97715f8)

Target device family: armv9.2-A (Dimensity 9300+: 4x Cortex-X4 + 4x Cortex-A720; SDOT + i8mm guaranteed, SME2 optional and runtime-detected by MNN via auxval).

## 1. How MNN's Q4-class weight GEMM differs from llama.cpp

All points verified in the cloned source:

### Weight format, packing, zero-point handling
- LLM linears (`CPUIndexedLinear.cpp`) consume **blockwise (default 64-in-feature) INT4/INT8 asymmetric** weights with per-block, per-h-pack-channel scale+bias; scales stored FP32 or FP16 (`alphaIsFp16` fast path, CPUIndexedLinear.cpp:170-177 — kernels read fp16 scale/bias metadata directly, no conversion at load).
- At model load, weights are **reordered exactly once** (`ConvInt8TiledExecutor::reorderWeight`, then `MNNReorderWeightInt4Arm82/Arm86` + `MNNPermuteSumWeightInt4Arm82/Arm86`, CommonOptFunction.cpp:712-878) into a tile-major layout `[hu, blocknum, lu, hP, lP]` — contiguous K-chunks of `lP` x `hP` output channels, matching the GEMM register tile. The two nibbles of each packed byte are physically split into halves so the inner loop does **no nibble shuffle** (llama.cpp's Q4_0 path deinterleaves nibbles in-register every dot; its Q4_K path keeps the llama.cpp interleaved layout and unpacks per block).
- A **per-row quantized-weight sum is precomputed at load** (`MNNSumWeightInt8Arm86`), so the asymmetric zero-point correction of a whole block is a single FMA on a broadcast constant in the epilogue ("The kernel reconstructs the W4 offset in FP32", CPUIndexedLinear.cpp:265). llama.cpp instead folds asymmetry through the "(-8) per element" trick inside the dot product and Q4_K's per-32 `d/dmin` mini-scales.

### Activation path — the core architectural difference
- Activations are **dynamically requantized to INT8 per token** (`MNNAbsMax` + `MNNAsyQuantFunc` asymmetric quant / `MNNDynamicQuant` symmetric; fp16 models use `DynamicQuanInput_ARM82` with fp16 fmlal-based abs-max). MNN therefore runs **W4A8 / W8A8 integer GEMMs**; llama.cpp keeps activations in Q8_K and dequantizes weights into fp16 lanes each step (dequant-then-dot, fp32/f16 accumulate).
- Hard constraint: quant block must divide cleanly into the activation packer's `lPack` (CPUIndexedLinear.cpp:108), so activation and weight tiling share an exact K boundary — needed for the SMMLA kernel that multiplies an `lP`-deep activation tile against the matching weight tile.

### GEMM shape and instructions
- Dispatch is runtime per-feature (`gCoreFunction` pointer table in CommonOptFunction.cpp:~5100-5170): fp16-arith, SDOT, **i8mm**, **SME2** (extra kernel set incl. 16x32 and Hp128 tiles in `sme2_asm/`), RVV on RISC-V.
- INT4 kernel: `Int8GemmKernel_W4` → `MNNGemmInt8AddBiasScale_ARMV86_w4_Unit` on i8mm CPUs — **INT8xINT8 SMMLA (i8mm), int32 accumulate**, epilogue applies per-block scale+bias in fp32/fp16, output written FP16 for fp16 models (`MNNGemmInt8AddBiasScale_w4_Unit_FP16`, CPUIndexedLinear.cpp:113-119) or int8; SDOT fallback variant `..._16x4_w4_Unit` for no-i8mm cores.
- FP16 (non-quant) models: pack=8, fp16 accumulate in `MNNPackedMatMul` fp16 variant (fmlal), Softmax deliberately kept FP32 as mixed precision (paper §5.3).
- FP32 default path: pack4, tile eP/hP/lP = **12/8/4** for SDOT per the paper's register-accumulator minimization formula (`eP + hP + hP·eP ≤ #regs`, `lP = instruction width`) — i8mm raises `lP` to 8. Tile sizes are *derived per core*, not fixed per model.
- Prefill (e > 1 tokens) uses the same packed GEMM with activation packing `MNNPackC4Int8ForMatMulA_ARM86`; decode (e=1) uses `MNNComputeMatMulForH_1` GEMV-optimized paths where profitable.

### llama.cpp tuned armv8.6 baseline (Q4_K)
- `vec_dot_q4_K_q8_K`: per 256 super-block, 8x32 sub-blocks with fp16 mini-scales unpacked in registers, half2 vectorized dot, fp32 accumulate; `mul_mat` batches 8 tokens through a GEMM of Q8_K activations. Decode is essentially a bandwidth-bound GEMV. SMMLA/i8mm kernels exist upstream for Q4_0-style (llamafile sgemm covers i8mm) but **Q4_K decode still never gets i8mm int32-MAC density**, and its per-8-subblock fp16 scale unpacking costs registers in the inner loop.
- Net effect: same DRAM bytes/token (~0.5-0.56 B/weight class), but MNN spends its inner loop on SMMLA MACs while llama.cpp's inner loop mixes nibble unpack + scale multiply + dot on every 32 elements.

## 2. Top-3 portable-to-llama.cpp optimizations (est. effect sizes)

1. **W4A8 prefill via dynamic per-token int8 activation quantization + i8mm SMMLA GEMM.** llama.cpp's Q4_K GEMM is dequant-to-fp16 + SDOT/f16 dot; SMMLA has ~2x MAC density on X4/A720 and halves scale-handling overhead. **Est. +60–100% prefill tok/s** on 9300+ for Q4-class weights. Reference designs: `MNNGemmInt8AddBiasScale_ARMV86_w4_Unit.S` + `MNNPermuteSumWeightInt4Arm86.S` + the `MNNAsyQuantFunc` batch quantizer. (llama.cpp's own i8mm SGEMM experiments show the same class of gain; upstream Q4_K simply doesn't use them yet.)
2. **Precompute per-row weight sums at load; fold zero-point into one FMA in the epilogue.** llama.cpp re-derives asymmetry corrections inside the dot loop (`d8 = scale, sum -= 8`). Moving that to load-time (`MNNSumWeightInt8Arm86` analog) removes several register ops per 32-element block in the hot loop. **Est. +5–10% prefill**, roughly neutral in memory-bound decode. Cheap port: one pass over the tensor at quantize/model-load time.
3. **Static, feature-aware workload split between the e/h dimensions using per-core capability data instead of OpenMP round-robin chunks.** MNN reads `perfCoreNumber` / core types at init and assigns tile counts per thread rather than letting OpenMP chunk a heterogeneous cluster. On big.LITTLE this measurably reduces straggler stalls (paper Fig. 4). **Est. +5–15% with >4 threads on heterogeneous clusters; ~0–5% on the uniform 4+4 large-core 9300+.** Honorable mention: KV cache stored already in GEMM tile layout so decode attention never repacks K/V per step (est. +5–15% long-context attention, harder to port).

## 3. Verdict on the 2.3x claim (arXiv:2506.10443: "52 vs 26 tok/s" 8Gen3, 7B Q4)

**Compared against an untuned, out-of-the-box llama.cpp.** Evidence from the paper:
- Evaluation section (§6) only says they ran "inference engines such as llama.cpp, MLC-LLM, fastllm" on Xiaomi 14 with 4 CPU threads, Qwen2 1.5B/7B + Llama3 8B. It reports **no llama.cpp version/commit, build flags, quantization format choice on the llama.cpp side, thread-pinning, or any tuning step**.
- Decode was capped at **16 tokens** — a short, unsteady-state measurement that also lowers llama.cpp's apparent throughput (warmup/cache effects included but peak buffering excluded).
- The asymmetry of the claimed numbers is diagnostic: **8.6x prefill** vs **2.3x decode** is the signature of a baseline without int8-quantized prefill compute (fp32/fp16 dot path) rather than a tuned opponent; a tuned llama.cpp with an i8mm-backed prefill (e.g. llamafile-style SGEMM) is within ~1.3–2x of MNN's int4 prefill on the same core class.

**Conclusion:** treat "2.3x decode" as MNN-LLM vs default llama.cpp on a 16-token pulse, not vs a tuned llama.cpp. A tuned llama.cpp (i8mm W4A8 prefill + its existing vectorized Q4_K decode) closes most of the decode gap; the MNN decode advantage shrinks to roughly the 4-bit-block layout + packing overhead delta (~1.1–1.4x expected on the same hardware class). The prefill gap is the one that should survive tuning, and that is exactly what our on-device W4A8-MNN experiment should quantify.
