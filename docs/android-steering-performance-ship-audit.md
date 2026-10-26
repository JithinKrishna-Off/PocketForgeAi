# Android perf libs + Qwen3.5 steering: ship-readiness audit

Scope: can we ship together (A) `pocketforge-android/build-apk-libs.ps1` (arm64-v8a
perf build of the steering repo's llama libs) and (B) the Qwen3.5 neuron-steering
patch (`src/models/qwen35.cpp`, `src/models/models.h`, env `LLAMA_QWEN35_STEER`)
into the PocketForgeAI APK.

Method: static audit only (no builds started, no device tests, no model
downloads). All file identities below were verified by SHA-256.

## Executive verdict

- **CPU-only perf libs + steering: shippable after one rebuild.** The libs
  currently staged in `pocketforge-android/libs/arm64-v8a` cannot ship for two
  independent reasons: they were built Oct 23 (before the steering edits, which
  are dated Oct 26) and every one of them carries an unresolvable
  `NEEDED: libomp.so`. The current script + build cache already contain the
  fix (static libomp seed); a rebuild is required.
- **`-EnableVulkan`: blocked on this machine.** No Vulkan SDK installed;
  `ggml/src/ggml-vulkan/CMakeLists.txt:14` does
  `find_package(SPIRV-Headers CONFIG REQUIRED)` with no FetchContent fallback,
  so configure fails regardless of the script's glslc/vcvars handling.
- **`libpocketforge-llm.so` does NOT need a rebuild.** All headers it includes
  are byte-identical between the two trees (or ABI-safe supersets), and its
  `NEEDED` set is exactly the four libs the script produces. Replace only the
  llama/ggml libs in jniLibs; keep the existing pf JNI lib.
- **Steering activation on Android needs an in-process hook** (Option A from
  the prior audit: `setenv` in JNI around `llama_model_load_from_file`). The
  env var cannot travel from a PowerShell build script into the app process.
  Without that small JNI change, the rebuilt libs carry steering but it stays
  dormant (safe default).

## Compatibility matrix (desktop tree vs app submodule 0a1ef309cdbb)

| File | Result | Impact |
|---|---|---|
| `include/llama.h` | identical | `llama_process` API (llama.h:1092), `llama_load_mode_from_str` (:217), threads (:1100), state (:830), perf (:1700) all line-identical |
| `common/common.h` | identical | `common_batch` (common.h:1082+) |
| `common/sampling.h` | identical | pf sampler path |
| `common/chat.h` | identical | chat template path |
| `ggml/include/ggml-backend.h` | identical | backend probe/list API |
| `ggml/include/ggml-cpp.h` | identical | buffer_ptr/context_ptr RAII used by steering |
| `ggml/include/ggml.h` | desktop is a superset: +21 lines in desktop, zero removals | ABI-safe: pf compiled against the smaller header; nothing it calls was removed |
| `src/llama-cparams.h` | differs | internal header; not used by pf_llm.cpp |
| `ggml/src/ggml-vulkan/ggml-vulkan.cpp` | identical | Vulkan op surface identical in both trees |
| `src/models/qwen35.cpp` | differs | difference = exactly the steering patch (desktop only) |

Supporting evidence:

- `libpocketforge-llm.so` NEEDED: `libllama-common.so`, `libllama.so`,
  `libggml.so`, `libggml-base.so` + system libs. No mtmd. Its
  `app/src/main/cpp/CMakeLists.txt` links only `llama` + `llama-common`.
- `pf_llm.cpp` includes: `chat.h common.h sampling.h llama.h ggml-backend.h`
  (+ `pf_plan.h`) - all identical or superset.
- CPU variant names produced by the desktop tree (staged Oct 23) match the
  app's `PF_CPU_VARIANTS` dlopen list exactly:
  `libggml-cpu-android_armv8.0_1.so` ... `armv9.2_2.so` (7 variants).
- `pf_load_backends` (pf_llm.cpp:230) uses `ggml_backend_load()` with absolute
  paths / bare sonames and logs-and-continues on failure (pf_llm.cpp:216).
- Steering code is portable: no MSVC-only constructs in `qwen35.cpp`
  (`std::getenv`, loader APIs, ggml RAII only). Raw GGUF reads via
  `ml->load_data_range` + `ml->get_weight` are mandatory on Android too:
  quantized `ffn_down` goes through CPU_REPACK whose repacked buffer has
  `get_tensor = nullptr`, so the old `ggml_backend_tensor_get` path would
  silently read repacked bytes as if they were raw quant data (garbage). The
  desktop fix (already in tree) covers this.
- Steering load order is safe on Android: `load_arch_tensors` ->
  `init_mappings` -> `load_all_data`; `load_steering()` runs after the base
  `load_tensors` returns, so file mappings are live. The null-buffer fallback
  (fall back to plain CPU buft when `t_down->buffer` is not set / is CPU)
  covers deferred-load modes.
- Desktop ggml-vulkan registers every op steering needs:
  GET_ROWS (:8690), ADD/MUL (:8720-8752), SCALE (:8810), SQR (:8815),
  SQRT (:8821), SUM_ROWS (:9102/12682), ARGSORT (:9574/12666/15812).

## Blockers (with paths)

1. **Staged libs are stale and broken**
   `pocketforge-android/libs/arm64-v8a/*.so` (Oct 23, 17:08-17:19) predate the
   steering edits (qwen35.cpp Oct 26 13:23, models.h Oct 26 12:57) AND carry
   `NEEDED: libomp.so` (unresolvable on Android - dlopen fails on device).
   Cause: the Oct 23 run used an earlier script revision without the
   `OpenMP_omp_LIBRARY` seed. The current build tree proves the fix landed:
   `build-arm64/CMakeCache.txt` has
   `OpenMP_omp_LIBRARY=...lib/clang/19/lib/linux/aarch64/libomp.a` and
   `-static-openmp` in both `CMAKE_SHARED_LINKER_FLAGS` and
   `CMAKE_MODULE_LINKER_FLAGS`. That reconfigure happened 17:20:39, AFTER the
   libs were staged; the rebuild that followed was interrupted at 17:22
   (only `bin/libggml-base.so` produced; tree is 0.1 GB).
   Consequence: rebuild mandatory; staged files must not ship.
2. **`-EnableVulkan` cannot configure here**
   `ggml/src/ggml-vulkan/CMakeLists.txt:14` requires a CMake CONFIG package
   for SPIRV-Headers; no Vulkan SDK exists on this machine (no C:\VulkanSDK)
   and the script seeds only `Vulkan_GLSLC_EXECUTABLE` (line 88; NDK glslc
   exists) plus a vcvars host compiler for `vulkan-shaders-gen`
   (lines 110-124). Ship CPU-only now; fix Vulkan later (SDK install or a
   FetchContent fallback).
3. **Steering activation on Android**
   `LLAMA_QWEN35_STEER` is read with `std::getenv` inside
   `load_steering()` at model-load time. A PowerShell script cannot set env
   vars in the app's process. Activation requires the planned JNI hook
   (setenv before `llama_model_load_from_file`, unset after / on unload).
   Note: the env var is process-global and persists across model loads until
   unset - clear it on model unload/switch so a later qwen35 model does not
   inherit the previous model's steering spec.

## Risks (not blockers)

- **Stale-ship pattern**: `-Clean` removes `build-arm64` only (line 52), never
  `libs/arm64-v8a` (lines 140-143 copy over it), and `-CopyTo` copies the
  whole OutDir (lines 184-188). Today this is benign because the rebuild
  produces exactly the same 11 filenames, but a future variant-name change
  would leave old files staged. Clear OutDir before each staging run.
- **Vulkan generation skew in jniLibs**: the app's `libggml-vulkan.so`
  (Oct 21, submodule build) stays in jniLibs when we swap in the desktop
  llama/ggml libs. ggml-base is an ABI superset, so the lazy dlopen likely
  succeeds; if it fails, `pf_load_backends` logs and continues (CPU-only run).
  Cleanest: refresh `libggml-vulkan.so` from the same tree once the Vulkan
  build is unblocked, or delete it.
- **Dead weight in jniLibs**: `libmtmd.so` and the `libllama-*-impl.so` tool
  libs are packaged but not NEEDED by `libpocketforge-llm.so`. Harmless;
  prune later for APK size.
- **PowerShell array quirk is benign**: lines 65-66 of the script omit a
  comma between two option elements; PowerShell treats a line break as an
  array-element separator, so the option list still has all elements
  (verified empirically: 4-element array parses as 4 distinct items).
  Cosmetic only.
- **Resources**: 12 logical CPUs, 15.7 GB RAM (~2.5 GB free at audit time),
  22.5 GB free disk. Build feasible; Ninja at full parallelism may pressure
  RAM - reduce `--parallel` or close apps if it thrashes.

## Minimum changes to ship (ordered)

1. Rebuild CPU-only from the steering tree (see command below). Expected
   output: the same 11 `.so` files, no `libomp.so` NEEDED, steering strings
   present in `libllama.so`.
2. Hand over to the app: copy the 4 core libs + 7 CPU variants into
   `app/src/main/jniLibs/arm64-v8a` (script `-CopyTo`); keep
   `libpocketforge-llm.so` and the app-only libs untouched.
3. Add the JNI env hook for steering activation (small change in
   `pf_llm.cpp`; design already agreed as Option A in
   `qwen35-steering-integration-audit.md`). Ship-able without it (steering
   simply stays off).
4. (Later) Unblock Vulkan: install Vulkan SDK incl. SPIRV-Headers CMake
   config, or add a FetchContent fallback; then refresh
   `libggml-vulkan.so` in the same ABI generation.

## Recommended build commands

From `C:\Users\ASUS\Desktop\llama.cpp`:

```powershell
# 1) clear stale staging (same 11 names today, but keep the habit)
Remove-Item .\pocketforge-android\libs\arm64-v8a\*.so

# 2) rebuild CPU-only; use -Clean because the Oct 23 17:22 rebuild was
#    interrupted mid-tree. NOTE: -Clean also removes fetched deps
#    (KleidiAI), so this requires network for the re-fetch.
.\pocketforge-android\build-apk-libs.ps1 -Clean
```

Fast path alternative (no `-Clean`): the CMake cache is current and correct;
re-running the script without `-Clean` makes Ninja rebuild only the steering
files and relink - minutes instead of a full build. Use `-Clean` only if the
incremental result looks odd (interrupted-tree leftovers).

```powershell
# 3) stage into the app (copies OutDir contents; never deletes app libs)
.\pocketforge-android\build-apk-libs.ps1 -CopyTo "C:\Users\ASUS\Desktop\PocketForgeAI\app\src\main\jniLibs\arm64-v8a"
```

Estimated: configure 1-2 min; full clean build ~10-25 min on 12 cores
(KleidiAI vendoring dominates the first clean run).

## Validation plan (post-rebuild, pre-ship)

1. Script's readelf pass: no `.so` reports `libomp.so` (lines 156-171).
2. Steering compiled in: ASCII scan of `libllama.so` for
   `LLAMA_QWEN35_STEER` / `steering layer` strings.
3. ABI drop-in: install on device; confirm normal (non-steering) generation
   works with the 4 swapped libs and existing `libpocketforge-llm.so`.
4. GGUF immutability: hash a model file before/after a steering-enabled run
   (once the JNI hook exists); steering never writes the GGUF.
5. logcat: steering logs (`steering layer`, per-target dose values) on load;
   A/B output sanity with a spec of `14:8949:+1,13:7956:-1`.

## Verified statically vs left to runtime

- Verified here: header/ABI identity, NEEDED sets, variant-name match,
  load order, op coverage, script logic, toolchain presence
  (NDK 28.2.13676358, SDK cmake 3.31.6, NDK libomp.a, NDK glslc).
- Left to runtime: device load success, steering effect/quality,
  KleidiAI kernel benefit, exact perf numbers.

## Steering scope reminders (from the prior audit)

qwen35 arch only (not qwen35moe/qwen3next); MTP/draft graphs unsteered;
median is per-ubatch; T==1 decode falls back to the current token's own
residual norm; ffn_down_s gains cancel out; LoRA on ffn_down unaccounted.
