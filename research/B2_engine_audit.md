# B2 Engine Audit — vendored llama.cpp + pf_llm.cpp (static analysis, no device touch)
Owner: agent B2 | Date: 2026-10-23 | All refs relative to `third_party/llama.cpp` unless prefixed `app/`.

## 1. Version fingerprint
- Vendored checkout is a **1-commit clone** (`git rev-list --count HEAD` = 1):
  - commit `0a1ef309cdbb9f2024f58a38fb4c42352c5db569` "mtmd: add state object for mtmd_gen (#30902)", dated 2026-10-21. No tags present, so `git describe` falls back to `0a1ef30`.
- Versions are compile-time generated (`ggml/src/ggml-version.h.in`, `src/llama-version.h.in`):
  - ggml: **0.27.0** (`ggml/CMakeLists.txt:1-3` area: MAJOR 0 / MINOR 27 / PATCH 0)
  - llama.cpp: **0.7.0** (`CMakeLists.txt` top: LLAMA_VERSION_MAJOR 0 / MINOR 7 / PATCH 0), built as dev `0.7.0`.
- Identical engine at `0a1ef30` was used for phase0 llama-bench runs (FINDINGS.md §1) — bench numbers and this audit version are directly comparable.

## 2. arm64 Android CPU variants actually produced (GGML_CPU_ALL_VARIANTS)
Defined in `ggml/src/CMakeLists.txt:525-533` (`elseif (GGML_SYSTEM_ARCH STREQUAL "ARM") / CMAKE_SYSTEM_NAME MATCHES "Android"` block). Flag synthesis in `ggml/src/ggml-cpu/CMakeLists.txt:212-262`: features bump `-march=` version, tags appended in declaration order; `ggml_add_cpu_backend_features()` adds `GGML_USE_*` defs per feature. All 7 match the 7 CPU `.so`s shipped in `app/src/main/jniLibs/arm64-v8a/`:

| lib name (`libggml-cpu-android_*.so`) | feature list (src/CMakeLists.txt:525-533) | final ARCH_FLAGS | defines | score* |
|---|---|---|---|---|
| armv8.0_1 | (none) | `-march=armv8-a` | — | 1 |
| armv8.2_1 | DOTPROD | `-march=armv8.2-a+dotprod` | GGML_USE_DOTPROD | 3 |
| armv8.2_2 | DOTPROD FP16_VECTOR_ARITHMETIC | `-march=armv8.2-a+dotprod+fp16` | +GGML_USE_FP16_VECTOR_ARITHMETIC | 7 |
| armv8.6_1 | DOTPROD FP16 MATMUL_INT8 | `-march=armv8.6-a+dotprod+fp16+i8mm` | +GGML_USE_MATMUL_INT8 | 23 |
| armv9.0_1 | DOTPROD FP16 MATMUL_INT8 **SVE2** | `-march=armv8.6-a+dotprod+fp16+i8mm+sve2` | +GGML_USE_SVE2 | 55 |
| armv9.2_1 | DOTPROD FP16 MATMUL_INT8 SVE **SME** | `-march=armv9.2-a+dotprod+fp16+i8mm+sve+sme` | +GGML_USE_SVE GGML_USE_SME | 127 (needs hwcap2 SME) |
| armv9.2_2 | prior + SVE2 | `-march=armv9.2-a+...+sve+sve2+sme` | +GGML_USE_SVE2 | 159 |

\*score from `ggml_backend_cpu_aarch64_score()` (below); score formula is 1 + (1<<1)+...+ (1<<6) per compiled feature.

Key quirks:
- **Android list has no SVE-only armv8.2_3 / armv8.6_2** (those exist only for the Linux block, src/CMakeLists.txt:512-518). So a device with SVE but no SME gets nothing compiled-in for SVE GEMM at the codegen level except the armv9.0_1 `sve2` march tag (and SVE2 kernels inside quants/repack guarded by GGML_USE_SVE2).
- Every variant additionally built with (app/src/main/cpp/CMakeLists.txt:12-18): `GGML_SYSTEM_ARCH=ARM`, `GGML_CPU_KLEIDIAI=ON`, `GGML_OPENMP=ON`; ggml defaults give `GGML_CPU_REPACK=ON` too (ggml/src/ggml-cpu/CMakeLists.txt:625-628 → `GGML_USE_CPU_REPACK`), plus `GGML_BACKEND_DL=ON` (each variant = standalone .so exporting `ggml_backend_init` + `ggml_backend_score`).
- `LLAMAFILE=OFF` in the app build; the vendored `ggml-cpu/llamafile/` sgemm path is therefore compiled out.

## 3. Runtime variant selection (the exact code path)
Two layers exist; **pf_llm.cpp does not use ggml's loader loop — it hand-rolls its own**:

Layer 1 — ggml generic (unused by app but used by llama-bench/CLIs):
- `ggml/src/ggml-backend-reg.cpp:480 clamp` `ggml_backend_load_best(name, silent, user_search_path)`: enumerates `libggml-cpu-*.so` files in executable dir / cwd / GGML_BACKEND_DIR, `dlopen`s each, reads exported `ggml_backend_score()` (lines 522-537), keeps the **highest score > 0**, then `load_backend()` (lines 224-250) re-verifies (score==0 → "not supported on this system", logged/rejected) and calls `ggml_backend_init`; API-version check at lines 238-240. If nothing scores >0 it falls back to the base `libggml-cpu.so`.

Layer 2 — app (what actually runs):
- `app/src/main/cpp/pf_llm.cpp:170-178` `PF_CPU_VARIANTS[]` fixed order, most-capable-first (armv9.2_2 → armv8.0_1).
- `pf_llm.cpp:180-206` `pf_try_backend()`: tries absolute `lib_dir/name` (only works when libs are extracted to nativeLibraryDir) then **bare soname** (`ggml_backend_load(name)`) because the app ships `extractNativeLibs=false`; the Android linker namespace resolves the mapping inside the APK.
- `pf_llm.cpp:208-235` `pf_load_backends()`: llamalogs each attempt into `pf_backend_diag` (returned to #Kotlin on failed model load, line 276-288). Vulkan is loaded FIRST (line 220) so GPU devices exist before model load (also enables GPU-at-load-time ngl split).

Why the hand-rolled loop is equivalent to load_best here: every pf attempt goes through `load_backend()`'s `ggml_backend_score()` gate; a variant whose compiled features the device lacks returns 0, registration count doesn't grow, and the loop walks down. First feature-compatible variant wins — and because the list is ordered most-capable-first with this build, the winner on X200 FE is identical to load-best. Score fn: `ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp:6-39` `ggml_backend_cpu_aarch64_score()` (GGML_BACKEND_DL_SCORE_IMPL at line 39).

Feature probe (cheap, no kernel kait file): `ggml/src/ggml-feats.h:95-115` `ggml_feats_get_arch64_runtime()`:
- Linux path = **`getauxval(AT_HWCAP)` / `getauxval(AT_HWCAP2)`** — NOT /proc/cpuinfo, NOT NDK cpufeatures. Bits: dotprod HWCAP(1<<20), fp16 = FPHP(1<<9)|ASIMDHP(1<<10), sve(1<<22), sve2/i8mm/sme/sme2 via hwcap2 (1<<1 / 1<<13 / 1<<23 / 1<<37); SVE vlen via `prctl(PR_SVE_GET_VL)` (lines 110-115).
- Implication for X200 FE: selection depends entirely on kernel-exported hwcap bits. Cortex-X4 (MT6989) exposes dotprod/fp16/i8mm/sve/sve2; SME absent → armv9.2_* score 0 → expected winner **armv9.0_1 = `-march=armv8.6-a+dotprod+fp16+i8mm+sve2`**, unless Xiaomi kernel hides SVE2 → then armv8.6_1 (no sve2, score 23). Verify on-device (logs: `pf_load_backends` prints registered names; `listBackendsJson` exposes CPU device description).

### KLEIDIAI: build-time vs runtime
- **Build-time**: `ggml/src/ggml-cpu/CMakeLists.txt:630-713` gate. `GGML_CPU_KLEIDIAI` requires Linux/Android AArch64 (checks at 636-648), FetchContent downloads kleidiai **v1.31.0** release tarball (URL pinned + MD5 930495f3aeb16b25dc18a2bde45924f4), builds static lib and links into EVERY variant; adds `GGML_USE_CPU_KLEIDIAI` + kleidiai.cpp/kernels.cpp.
- **Runtime**: kernel instance selected per-mul_mat in `ggml/src/ggml-cpu/kleidiai/kleidiai.cpp` + `kernels.cpp`. Tables:
  - `gemm_gemv_kernels[]` (Q4_0 RHS, kernels.cpp:392-733): SME2/SME entries unconditional; the DOTPROD (534), I8MM+DOTPROD (582), SVE+I8MM+DOTPROD (631) NEON kernels are **inside `#if defined(__APPLE__)` (490-732) — NOT compiled for Android**.
  - `gemm_gemv_kernels_q4_k[]` (Q4_K RHS, kernels.cpp:736-788): SME2 (781) / SME-pref (subsequent) only.
  - `gemm_gemv_kernels_q8[]` (Q8_0 RHS, kernels.cpp:789-1028): **SME (881), I8MM+DOTPROD GEMM+GEMV (929-976), DOTPROD (977-1028) — no platform guard**; these DO apply on Android.
  - `ggml_kleidiai_kernels_f32[]` (1029+): SME2/SME only.
- Feature capture at runtime: `kleidiai.cpp:100-170` reads per-core `MIDR_EL1` by parsing `/sys/devices/system/cpu/cpu*/` and CPU feature de-detection (SME via smidr etc).
- **Net effect on MT6989 (no SME/SME2): KleidiAI contributes kernels ONLY when weights are Q8_0 (fg20: NO KleidiAI for Q4_0 or Q4_K; those use ggml's own REPACK path, `arch/arm/repack.cpp`, dotprod/i8mm/SVE2 flavors).** This materially shapes the bench matrix: Q8_0-augmented runs (e.g., Q4_0 model with `--cpu-moe`... note `-ncmoe` quantizes experts to Q8_0) can unlock KleidiAI i8mm GEMMs that Q4_0/Q4_K simply don't have.

## 4. Threading
- Threading backend: OpenMP is **ON** in the shipped builds (app CMake forces GGML_OPENMP=ON; ggml-cpu/CMakeLists.txt:79-81 defines `GGML_USE_OPENMP` and links the OpenMP target). In `ggml/src/ggml-cpu/ggml-cpu.c`:
  - OpenMP path uses `ggml_barrier` based on `#ifdef GGML_USE_OPENMP` (line 582-613) — i.e., threadpool barrier via OpenMP impl; and `ggml_threadpool_new` / secondary-thread machinery (`#ifndef GGML_USE_OPENMP` at lines 2746+, 3101-3385) is compiled OUT when GGML_USE_OPENMP. So with this build the **hybrid poll/spin wait loop (ggml_graph_compute_poll_for_work at 3211-3217, n_rounds = 1024*128*poll)** does NOT apply to the CPU KV paths — OR-parallel words: OpenMP threads are spawned/joined per graph compute; `poll` knob matters only for the non-OpenMP path (and for llama-bench's default build, which also links GGML_OPENMP per ggml CMake when minted the same way — llama-bench built from same CMake has the same OpenMP path, so llama-bench default 0...100 poll curve collapses to a no-op axis; test it once and drop).
  - `ggml_threadpool` DOES exist (struct at ggml-cpu.c:481-507; `ggml_threadpool_params` in `ggml/include/ggml.h:3009-3022`: n_threads, cpumask[64], prio, poll(0-100), strict_cpu, paused). Default params at `ggml/src/ggml.c:8170-8186`: poll=50, strict_cpu=false, prio=0, cpumask all-zero → inherited/OS placement, no pinning by default.
  - Affinity application: `ggml_thread_apply_affinity()` (ggml-cpu.c:2609-2628) — on Linux/Android uses `sched_setaffinity(0, ...)` (Android branch 2619-2623). Bionic DOES support sched_setaffinity; the kunstd comment about affinity not setting on Android exists at line 2175-2176 re-numa-something. `llama_attach_threadpool()` exists in `include/llama.h:499-504`; with GGML_USE_OPENMP the app never creates a custom pool, so today: **threadpool not pinned, no per-thread mask**, all threads free to migrate across the 8 cores.
  - Priority: `ggml_thread_apply_priority()` ggml-cpu.c:2632-2651 (SCHED_BATCH for LOW, SCHED_FIFO 40-90 above NORMAL).
  - NUMA: `ggml_get_numa_affinity()` reads current thread affinity into `g_state.numa.cpuset` (663-654); per-thread NUMA affinity helpers (2175-2246). The MT6989 is single-cluster all-big and NUMA-irrelevant → 'none' NUMA is the only sane option; ignore llama.cpp `--numa` axis.

## 5. pf_llm.cpp — JNI surface + KV cache knobs (read fully, 827 lines)
### What it exposes
- `init(nativeLibDir)` (line 239): llama log hook → ringbuf of 400 lines (`g_recent_logs`, `getRecentLogs`); manual backend load.
- `loadModel(modelPath, nCtx, nThreads, nGpuLayers, batchSize)` (253): model→context; on GPU absence rejects ngl (263-266); n_batch == n_ubatch == batch_size (294-295); threads fallback = clamp(4, hw-2) when Kotlin passes <=0 or >online-count (296-298). No flash_attn_type, type_k/v, op_offload, swa_full, offload_kqv, rope, no_perf, analog; no per-thread-batch split (n_threads_batch = n_threads, line 300); no threadpool injection; no abort_callback (uses its own `g_cancel_flag` checked only between tokens, sets no llama-level abort, so a llama_decode cannot be broken mid-launch; cancel only saves sampling).
- `processSystemPrompt` (413): formats via faction temple `common_chat_format_single` (has explicit template check), `common_tokenize`, `decode_tokens_in_batches` (384-409).
- `processUserPrompt(prompt, n_predict)` (440).
- `generateNextToken` (486): per-token epoch: KV shift-context handling: at ctx wrap meant via `llama_memory_seq_rm` + `seq_add` (489-497); sampler (common_sampler) → token; decode 1 token via `llama_process` SYNC batch; UTF-8 safe UTF emit charset.
- `setStopFlag`/`clearStopFlag`/`resetSession`/`finishTurn`/`addHistoryMessage`; `setSampler(temp, top_k, top_p, seed)`; `modelInfoJson` / `perfJson` (uses llama_perf_context); `contextStateSize` (llama_state_get_size — [EXPIRED? no, correctness free); `listBackendsJson` (575-607) → enumerate devices with free/total bytes; `probeBackendJson(device)` (734-803): GPU mul_mat probe with f32/f16/Q4_K against CPU reference, tolerance per dtype; `systemInfo`, `shutdown`.
### Error codes
`loadModel`: NULL-or std-cold strings; `processSystemPrompt`: 0 ok / 1 prompt too long / 2 ctx overflow / 3 no model; others: NULL jstring or JSON.
### Buffer strategy
- Single global `common_batch` (`g_batch`), cleared+filled per chunk and per token; context overflow guard uses OVERFLOW_HEADROOM = 4 tokens (line 36); KV shift mechanism is token halves mid-generation. GGML-memory pressure: relies on llama.cpp default buffer allocs (model loads at load time; KV allocated at context init).
### Suboptimal already (relevant to tuned-baseline phase):
- KV defaulted: types f16 (no `type_k/type_v`), flash_attn_type AUTO (default), `n_batch=n_ubatch=512`. No per-generation tunables.
- Threads: same n_threads for both; no attach_threadpool; no cpumask/poll; the 4-thread default gives SOME parity with llama-bench but the app can't sweep.
- No speculative decoding, no per-layer backend split (ngl is the only lever; bfamed uniform split fixed 'n layers offloaded').

## 6. Vulkan backend available (vendored `ggml/src/ggml-vulkan/`)
- Files: ggml-vulkan.cpp + shaders repo. Confirmed present in this tree: **FA coopmat1 + coopmat2 paths** (`FA_COOPMAT2` at ggml-vulkan.cpp:1335-1371, tuning at 1306-1331), **MMQ (mul_mmq) pipelines incl. non-coopmat warptiles** (mmq warptile table 1703-1716+), **MMVQ (mul_mat_vec, decode-only) with Q4_0/Q5_0/Q6_0?/Q8_0/Q2_K/Q3_K/Q4_K/Q5_K/Q6_K f32/f16 accum** (2932-2967), **LUT-based CM1/CM2 kernels for MXFP4/NVFP4** (2310-2370 incl. OCP fp4 `ocp_fp4` branch).
- Gate for sturdier prefill: `device->coopmat` = `VK_KHR_cooperative_matrix` (line 96-108), plus `coopmat_support_16x16x16_f32acc/f16acc` shape checks (1351-1352). FA path picked by `device->coopmat2 ? FA_COOPMAT2 : (coopmat1_fa_support ? FA_COOPMAT1 : FA_SCALAR)` (1336).
- Measured Mali-G720-Immortalis MC12 capability (FINDINGS.md §1 probe): uma=1, fp16=1, bf16=0, fp4=0, warp=16, shared=32768, **int_dot=0, coopmat=none**.
- What that means on our device:
  - **No coopmat** → no coopmat1/2: prefill for quantized weights can only use the scalar MMQ warptile path (non-matmul in, no matrix engine; slow on Mali due to 16 wide wide lanes with no int8 dot) or dequant-to-f16 fallback; fp16 `ggml_mul_mat` on f16/f32 prefill runs through generic fp16 mm warptile.
  - **No coopmat FA** → flash attention takes the FA_SCALAR path.
  - Decode (tg) still can use MMVQ which is quite fast on Mali (each token does mul_mat_vec with dequant-in-shader), which explains phase0's decent 22.2 tok/s vs pp 13.5 tok/s.
  - What made Mali slow at prefill: scalar MMQ warptiles + no int_dot + only 32 KiB shared (limits BK tiling) + extra dequant overhead per shader launch.
  - 4B Q4_K_M `ngl 99 → exit 137` (OOM kill): unified memory + zram pressure in phase0 (MemAvailable 7.66 GB and a 2.5 GB model plus KV prealloc); jobs should retest with `n_ctx` trimmed or smaller `-ub`/per-layer ged data RAM.
- So for tuning: Vulkan full the config → expect best tg, poor pp; Vulkan hybrid bodies via `ngl` (e.g. ~1/2 n-layer for pp+cpu tg trunk, or nothing) or `-ncmoe` (MoE experts on CPU; qwen3-4B dense → not applicable) — engine supports but Mali's per-side advantages need measurement, not chokes.

---

## Cross-check: same engine sanity for on-device planners
- llama-bench from this commit exposes (tools/llama-bench/llama-bench.cpp): `-t threads`, `-C/--cpu-mask <hex>` (703-711), `--cpu-strict` (710), `--poll <0..100>` (472, defaults 50; llama-bench default = the `ggml_threadpool_params` default since the whole ggml is OpenMP → poll is a knob inside non-OpenMP code path; still consumes a CSV column), `-ngl` (724), `-fa/--flash-attn` (865), `-nkvo` (842), `-ctk/-ctv` (635/655, `0` resets f16), `-b/-ub` (621/628), `--repack` (912, toggles GGML extra-buffer repack — DEP: model must be REPACK-capable q4_0/q8_0; turn OFF to compare raw vs repack), `-ncmoe/--n-cpu-moe` (474), `-dev`/`--device` (675), `--list-devices` (693). `llama-bench` output CSV includes `nkvo`, `poll`, `repack` columns (1607-1650, 1922, 2036). `common_cpu_get_num_math()` for default threads; comparing `-C` masks with strict=1/0 changes exact pinning semantics (ggml_thread_cpumask_next ggml-cpu.c:2719-2742 with strict=false → all threads share global mask, migrating inside it).
- Dependency warning for hybrid sweeps: `-ngl` requires the Vulkan .so at load time (pf probes GPU BEFORE model load; llama-bench must run `--list-devices` to confirm the vulkan backend got scored in).
- `ff()` model files in device sandboxes: qwen25-1.5b-q40, qwen3-4b-q40, qwen3-4b-q4km (FINDINGS.md §2).
