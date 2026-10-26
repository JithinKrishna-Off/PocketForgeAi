# MNN-LLM Cross-Build Plan (arm64-v8a, from Windows host)

Target: Vivo X200 FE (Dimensity 9300+ = 4x Cortex-X4 @3.4GHz + 4x Cortex-A720, armv9.2-A, i8mm+SDOT, SME2 optional/runtime-detected).

## Version
- MNN HEAD `97715f8` ("[Core:Bugfix] Refresh module input copies on every forward (#5014)"), version string **3.6.1** (`include/MNN/MNNDefine.h`).
- Clone at `C:\Users\ASUS\AppData\Local\Temp\opencode\mnn-clone` (shallow, today's HEAD).

## Toolchain (host)
- NDK 28.2.13676358 at `C:\Users\ASUS\AppData\Local\Android\Sdk\ndk\28.2.13676358` (clang 19, supports aarch64 SME2 arch flags if ever needed; SME2 asm in MNN is runtime-gated).
- CMake (SDK) 3.31.6 + Ninja.

## Build chain (PowerShell, one copy-paste block)

```powershell
$NDK = "C:\Users\ASUS\AppData\Local\Android\Sdk\ndk\28.2.13676358"
$SRC = "C:\Users\ASUS\AppData\Local\Temp\opencode\mnn-clone"
$BLD = "C:\Users\ASUS\AppData\Local\Temp\opencode\mnn-clone\build"
cmake -G Ninja -S $SRC -B $BLD `
  -DCMAKE_TOOLCHAIN_FILE="$NDK\build\cmake\android.toolchain.cmake" `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 `
  -DCMAKE_BUILD_TYPE=Release `
  -DMNN_BUILD_FOR_ANDROID_COMMAND=ON `
  -DMNN_BUILD_LLM=ON `
  -DMNN_LOW_MEMORY=ON `
  -DMNN_SUPPORT_TRANSFORMER_FUSE=ON `
  -DMNN_ARM82=ON `
  -DMNN_SUPPORT_BF16=ON `
  -DMNN_SME2=ON `
  -DMNN_USE_THREAD_POOL=ON `
  -DMNN_SEP_BUILD=OFF `
  -DMNN_MEMLEAK_CHECK=OFF `
  -DMNN_BUILD_BENCHMARK=OFF -DMNN_BUILD_DEMO=OFF -DMNN_BUILD_DIFFUSION=OFF `
  -DMNN_BUILD_LLM_OMNI=OFF
cmake --build $BLD -j
```

Flag rationale (verified against `CMakeLists.txt` + CI `build_64.sh` invocation):
- `MNN_BUILD_LLM` — builds `MNN::Transformer` + `llm_demo` (target defined in `transformers/llm/engine/CMakeLists.txt:106`).
- `MNN_LOW_MEMORY` — **required**; without it the quantized-linear op (`CPUIndexedLinear`, lines 88–136) fails `mValid` and falls back to FP32 dequant at runtime (i.e. no W4A8/W8A8 kernels at all).
- `MNN_SUPPORT_TRANSFORMER_FUSE` — fuses RMSNorm/attention/etc. at conversion time; the LLM CI target uses it.
- `MNN_ARM82` (default ON) — fp16 kernels + `pack=8` fp16 path.
- `MNN_SUPPORT_BF16` — bf16 embedding / mixed-precision ops.
- `MNN_SME2` (default ON) — adds SME2 kernel set (`sme2_asm/`, incl. W4 Hew128/32 units). Dispatch is runtime-gated by ELF auxval detection, so ON is safe even if the 9300+ lacks SME2.
- `MNN_USE_THREAD_POOL` (default ON) — MNN's own thread pool with static big.LITTLE-aware load balancing (paper §5.2). Keep ON.
- `MNN_BUILD_FOR_ANDROID_COMMAND=ON` — needed when cross-building outside gradle; adds `-pie`, `-s`, gc-sections and keeps outputs in the build dir instead of `$src/libs` (CMakeLists.txt:428-451).
- Do NOT pass `MNN_USE_INT8` — that symbol doesn't exist in this CMake; low-memory int8/int4 GEMMs come with `MNN_LOW_MEMORY`.

Outputs: `build/llm_demo`, `build/needlebench`, `build/mls` (benchmark harness), `libMNN.so`, `libMNN_Express.so`, `libMNN_Tools.so`.

## Expected build time
~10–20 min wall on an 8-core desktop (Release, ~large; `CommonOptFunction.cpp` and `Int8FunctionsOpt.cpp` are multi-thousand-line translation units that dominate). Disk ~2-3 GB for build dir. No Windows-host incompatibilities expected: pure cmake + Ninja + NDK.

## Quant formats MNN-LLM handles (Q4-equivalent for 4B/7B)

Two routes into MNN format (`transformers/llm/export/`):

1. **`gguf2mnn.py`** — reads existing llama.cpp GGUFs directly. Supported tensor types (verified in code): **F32, F16, Q4_0, Q4_1, Q4_K, Q5_0, Q5_1, Q6_K, Q8_0**. Q4_K is decomposed into flat 32-element asymmetric blocks with d/dmin-derived per-subblock scale+bias (grid of 8), interleaved (`shuffle_weight_int4`) into MNN's packed layout. Caveat: Q2_K / Q3_K / IQ-series are NOT accepted (they'd hit the F32 fallback path — avoid).
2. **`llmexport.py`** (own PTQ from HF safetensors) — `--quant_bit {2,3,4,8} --quant_block 64`, asymmetric by default (`--sym`), `--awq`, `--hqq`, plus `--lm_quant_bit` for the head. Runtime kernels natively expect block-64 int4/int8 asymmetric, so llmexport output is the fastest path; GGUF Q4_K conversion is functionally equivalent after dequant/regrouping.

Runtime weight layout on device (`CPUIndexedLinear.cpp`, `MNNReorderWeightInt4Arm82/86`): 4-bit blocks of 64 in-c with per-block per-h-pack-channel scale+bias (f32 or f16), pre-reordered to `[hu, blocknum, lu, hP, lP]` plus a precomputed per-row quantized-weight sum used to fold the asymmetric zero-offset into the bias (kernel e.g. `MNNGemmInt8AddBiasScale_ARMV86_w4_Unit`, output FP16).

For a 4B/7B "Q4 GGUF-equivalent" comparison our existing Q4_K/Q4_0 GGUFs convert 1:1 via `gguf2mnn.py`.

## On-device test plan (run later with device access)
1. `adb push build/llm_demo` to `/data/local/tmp`; `LD_LIBRARY_PATH=. ./llm_demo qwen2-7b-q4.mnn.json "<prompt>"` — llm_demo itself prints prefill tok/s, decode tok/s, and accuracy sanity output. Threads/config via the JSON (`threads: 4`, `precision: low`, `memory: low`).
2. Long-context: `./needlebench config.json` (same CMake target) for prefill scaling vs llama.cpp.
3. Full harness: `mls` binary (`transformers/llm/engine/app/llm_benchmark.cpp`) runs 128-token prefill / 512-token decode loops — the same methodology as the MNN paper.
4. Baseline parity: same model converted from the same weights we use for tuned llama.cpp; report both engines' numbers on the X200 FE for direct comparison.
