# Qwen3.5-4B Neuron-Steering Integration Audit

Static audit of the PocketForgeAI llama.cpp integration and its ability to host the
separately modified llama.cpp Qwen3.5-4B neuron-steering implementation
(layer 14 / neuron 8949 / dir +1, layer 13 / neuron 7956 / dir -1, dose from the
down-projection weight-column norm and residual-stream norms).

Audit date: 2026-10-26. Method: source inspection only. No device tests, no
runtime claims.

---

## 1. Existing inference architecture

PocketForgeAI runs llama.cpp directly through a hand-written JNI bridge. There is
no Kotlin binding library and no embedded server.

| Concern | Where |
|---|---|
| JNI bridge (model load, context, decode) | `app/src/main/cpp/pf_llm.cpp` (1072 lines) |
| Plan engine v0 (uncommitted work in tree) | `app/src/main/cpp/pf_llm.cpp`, `pf_plan.h`, `pf_plan.cpp` |
| Native build (out-of-Gradle, NDK) | `native/build-native.ps1`, CMake project at `app/src/main/cpp/CMakeLists.txt`, build dir `native/build-arm64/` |
| Packaged native libraries | `app/src/main/jniLibs/arm64-v8a/` (AGP packages them; `useLegacyPackaging = true` so ggml can dlopen CPU-variant backends) |
| llama.cpp source | git submodule `third_party/llama.cpp` |
| Kotlin JNI boundary | `app/src/main/java/com/pocketforge/ai/inference/LlamaBridge.kt` |
| Engine (single model, single dispatcher) | `app/src/main/java/com/pocketforge/ai/inference/LlamaInferenceEngine.kt` |
| Config types | `app/src/main/java/com/pocketforge/ai/inference/InferenceTypes.kt` (`InferenceConfiguration`) |
| Load orchestration | `app/src/main/java/com/pocketforge/ai/inference/ModelLoadService.kt` (builds `InferenceConfiguration`, calls `engine.loadModel`) |
| Settings persistence / UI config | `SettingsViewModel.kt` (also builds `InferenceConfiguration`), `settings/SettingsStore.kt` |
| Kotlin-side GGUF parser (arch detection available) | `app/src/main/java/com/pocketforge/ai/data/gguf/GgufMetadata.kt` (exposes `general.architecture`, line 64), `GgufParser.kt`, `MemoryEstimator.kt` |
| Model catalogue (Qwen3.5-4B and 9B listed) | `app/src/main/assets/model_catalogue.json` |

Key facts:

- **ABIs**: `arm64-v8a` only (`abiFilters` in `app/build.gradle.kts`; the CMake
  project fatals on anything else). NDK 28.2.13676358, minSdk 26, compileSdk 35.
- **Backends**: ggml built with `GGML_BACKEND_DL=ON`, `GGML_CPU_ALL_VARIANTS=ON`
  (7 CPU-variant .so files) and optional `GGML_VULKAN=ON`
  (`native/build-native.ps1 -EnableVulkan`). Backends are dlopened at runtime by
  `pf_load_backends()`; since the uncommitted plan-engine change, Vulkan is
  dlopened **lazily** only when the plan requests GPU offload. Default
  configuration is CPU-only (`nGpuLayers = 0`, and the loader explicitly strips
  GPU devices).
- **Threading / ownership**: one global model/context/sampler in pf_llm.cpp; all
  JNI calls serialized on a single Kotlin dispatcher; loading a second model is
  refused while one is resident.
- **Config flow**: `InferenceConfiguration` -> JNI args of `loadModel`
  (modelPath, nCtx, nThreads, nGpuLayers, batchSize) plus sampler params; the
  PfPlan JSON surface (`pfPlansJson()` / `pfSetPlan()`) carries structural knobs
  (kv types, flash attn, nBatch/nUbatch, load mode, nGpuLayers override, thread
  counts, applied at next context build; threads also apply live).
- **Generation loop**: system prompt + user prompt are tokenized and decoded in
  batches (`decode_tokens_in_batches` -> `llama_process(..., DECODE, ...)`),
  then `generateNextToken()` runs one decode step per call
  (sample -> accept -> decode 1 token). Context shift trims the KV cache on
  overflow. KV cache is memory-only, never persisted.

## 2. Current llama.cpp revision and Qwen3.5 support

- Submodule commit: `0a1ef309cdbb9f2024f58a38fb4c42352c5db569`
  ("mtmd: add state object for mtmd_gen (#30902)", 2026-10-21).
- Local submodule modification (uncommitted): `ggml/src/ggml-vulkan/ggml-vulkan-buffers.cpp`
  (+8 lines).
- Qwen3.5 support is present in the submodule: `LLM_ARCH_QWEN35` /
  `LLM_ARCH_QWEN35MOE` (`src/llama-arch.h:46`), `llama_model_qwen35`
  (`src/models/models.h:2478`), `llama_model_clef` derives from it
  (`models.h:2525`), `llama_model_qwen35moe` (`models.h:2734`). The
  Qwen3.5-4B models in the app catalogue are therefore supported by the
  integrated revision.
- The Android JNI uses `llama_process` (this revision's decode entry point);
  pf_llm.cpp is adapted to this revision, not to the desktop tree's one.

Reference tree: the modified desktop llama.cpp used for the steering
implementation lives outside this repo (path known to the author; verified
during this audit as `C:\Users\ASUS\Desktop\llama.cpp`, commit `f5027fb21`,
tag `b11836`). Its steering edits are also **uncommitted** in that tree.

## 3. Steering patch compatibility assessment

The desktop steering change touches only two files:
`src/models/qwen35.cpp` (+ includes, a dequant helper, `load_steering()`,
a `load_tensors()` override, `steer_down()`, one hook line in the trunk FFN
loop) and `src/models/models.h` (a `steer_target` struct, steer vectors, the
`load_tensors` override declaration, `graph` ctor parameter type changed from
`const llama_model &` to `const llama_model_qwen35 &`, `steer_down()` declaration).

### 3.1 Patch vs submodule diff (verified by `git diff --no-index`)

- `src/models/qwen35.cpp`: the 283-line difference between the desktop tree and
  the submodule is **exactly** the steering additions. The submodule file is
  otherwise identical to the desktop pre-patch version.
- `src/models/models.h`: the 55-line difference is the steering declarations
  plus one unrelated drift: the desktop tree additionally contains the
  `sensenova_u1` and `flint` model classes, which the submodule does not have.
  These do not touch qwen35 and do not affect the patch.

Conclusion: **the patch ports 1:1 to the submodule.**

### 3.2 Dependency checklist (all verified present in the submodule)

| Patch dependency | Submodule location | Status |
|---|---|---|
| `load_tensors` virtual (override point) | `src/llama-model.h:906` | present |
| `get_weight()`, `load_data_range()`, `no_alloc` | `src/llama-model-loader.h:221, 259, 82` | present |
| `init_mappings` before steering read (`llama_model_base::load_tensors` order: `load_arch_tensors` 1713 -> `init_mappings` 1893 -> `load_all_data` 2031) | `src/llama-model.cpp` | present, same order as desktop |
| `ggml_context_ptr` / `ggml_backend_buffer_ptr` | `ggml/include/ggml-cpp.h` (via `llama-adapter.h`) | present |
| ggml ops used in-graph: `sqr`, `sum_rows`, `sqrt`, `argsort`, `get_rows`, `view_1d`, `reshape_2d`, `add`, `mul`, `scale` | `ggml` core + CPU backend | all implemented on CPU |
| `ggml_get_type_traits(...)->to_float`, `ggml_fp16_to_fp32_row`, `ggml_bf16_to_fp32_row` (raw-GGUF dequant read) | `ggml` core | present |

### 3.3 Android backend op support

- **CPU backend (default app configuration, `nGpuLayers=0`)**: every op the
  steering subgraph uses is implemented. Steering will run on CPU in the
  default configuration.
- **Vulkan backend (only when GPU offload is enabled via the plan)**: static
  inspection of `ggml/src/ggml-vulkan/ggml-vulkan.cpp` shows support for
  `GET_ROWS` (F32 src + I32 indices), `SUM_ROWS` (F32, contiguous rows),
  `SQR`, `SQRT`, `SCALE`, `MUL`, `ADD`, and `ARGSORT`. Constraint found:
  Vulkan `ARGSORT` requires contiguous tensors and, when
  `vulkan_memory_model` is not available, caps the sort length at
  `2^min(max_workgroup_size_log2, num_argsort_pipelines-1)`. Prompt-sized
  sorts (ubatch token counts) can exceed that cap, in which case the backend
  scheduler must fall back to CPU for that op. This is a device-behaviour
  question, not a build blocker; see Uncertainties.
- The steering intervention itself (`ffn_out += col * median`) is a broadcast
  add of a `[n_embd]` F32 vector, applied inside the trunk FFN loop of
  `llama_model_qwen35::graph::build_graph`; layers 13 and 14 of Qwen3.5-4B are
  recurrent (GDN) layers but both have dense FFN branches, which is where the
  hook sits (verified in the identical trunk loop).

### 3.4 What transfers unchanged

1. Qwen3.5 architecture, tensor names, `ffn_down` orientation
   (`[n_ff, n_embd]`, neuron = ne[0] index, one element per row).
2. Column extraction from quantized GGUF via raw file reads
   (`load_data_range`), which also sidesteps repacked weight buffers
   (CPU_REPACK on the host; on Android the CPU variants also repack specific
   quant types) - the norm is computed from the raw GGUF values, matching the
   reference recipe.
3. Aux constant tensor ownership: one F32 `[n_embd]` column per target baked
   at load time into a weight-like backend buffer, kept alive by model members
   (`steer_ctxs_bufs`); graph context is no_alloc so nothing is copied at
   build time.
4. Dose semantics: per-ubatch in-graph median of per-token residual norms
   (token 0 excluded; single-token decode steps fall back to their own
   residual norm), dose scaled by the config value, direction folded into the
   baked column. `ffn_down_s` per-tensor scale cancels out of the dose.
5. Failure behaviour: invalid steering config throws during
   `llama_model_load`; the JNI `loadModel` already converts load failure into
   an error string surfaced to Kotlin.

### 3.5 What does NOT transfer unchanged

- **The `LLAMA_QWEN35_STEER` environment variable.** On Android there is no
  shell; the app process cannot be given an env var from outside, and the
  patch reads it once per model load inside `llama_model_qwen35::load_steering()`.
  A same-process `setenv()` from JNI would technically work (the engine is
  single-model by design), but an explicit native API is preferred; see the
  design in section 5.
- **Silent no-op on other architectures**: the env var is only read inside the
  qwen35 model class; for any other arch (e.g. Qwen3-4B in the catalogue) a
  set steering spec is silently ignored. The app must surface this at the
  Kotlin level (the GGUF parser already exposes `general.architecture`).

## 4. Exact files and functions that would need changes

Native (llama.cpp submodule - apply the existing desktop patch):

1. `third_party/llama.cpp/src/models/models.h`
   - `llama_model_qwen35`: add `steer_target` struct, `steer`,
     `steer_ctxs_bufs`, `load_steering()`, `load_tensors` override;
     change `graph` ctor param to `const llama_model_qwen35 &`; declare
     `steer_down()`.
2. `third_party/llama.cpp/src/models/qwen35.cpp`
   - add includes; add `qwen35_raw_to_f32()` helper; add
     `llama_model_qwen35::load_steering()`; add
     `llama_model_qwen35::load_tensors()` override; add
     `llama_model_qwen35::graph::steer_down()`; hook it into the trunk loop
     (`cur = steer_down(cur, ffn_residual, il);` before
     `cb(cur, "ffn_out", il);`).
   - one deliberate modification vs the desktop version (see section 5):
     source the steering spec from an explicit parameter path instead of
     (or in addition to) `getenv`.

Native (app JNI layer):

3. `app/src/main/cpp/pf_llm.cpp`
   - `Java_com_pocketforge_ai_inference_LlamaBridge_loadModel`: accept a
     steering spec string (or read it from `g_pf_plan`), validate, and hand
     it to the model-load path; log the effective steering config in the
     existing "PF effective plan" log block; clear it in
     `Java_com_pocketforge_ai_inference_LlamaBridge_unloadModel`.
4. `app/src/main/cpp/pf_plan.h` / `pf_plan.cpp` (optional, recommended)
   - extend `PfPlan` with a steering field (list of
     `{layer, neuron, dir, dose}` or a spec string) so it flows through the
     existing plan JSON contract used by `pfSetPlan`/`pfPlansJson`.

Kotlin:

5. `app/src/main/java/com/pocketforge/ai/inference/LlamaBridge.kt`
   - new `external fun` parameter or a `pfSetPlan` passthrough (no new
     symbol needed if PfPlan carries it).
6. `app/src/main/java/com/pocketforge/ai/inference/InferenceTypes.kt`
   - add steering fields to `InferenceConfiguration` + `validate()`
     (layers 0..n, dir in {+1,-1}, dose >= 0).
7. `app/src/main/java/com/pocketforge/ai/inference/ModelLoadService.kt`
   - pass the persisted steering setting into the configuration; gate on
     `GgufMetadata.architecture == "qwen35"` for imported models; warn
     (not silently no-op) when steering is configured for a non-qwen35 arch.
8. `SettingsViewModel.kt` / `SettingsStore.kt` (and optionally a Chat/Models
   screen toggle)
   - persist the steering toggle + spec; default OFF (no behaviour change).
9. `app/src/main/assets/model_catalogue.json` (optional)
   - the two preset targets are model-specific; if the UI should offer them
     per model, annotate the qwen35-4b catalogue entry.

Build:

10. Re-run `native\build-native.ps1` (submodule rebuild picks up the two
    changed llama.cpp files; no CMake changes needed - `qwen35.cpp` is already
    part of the llama library sources) and `.\gradlew :app:assembleDebug`.

No changes are needed to: Gradle files, ABI filters, signing/release config,
packaging rules, the GGUF model file, or the ggml backends.

## 5. Required Android build and JNI changes (design)

Recommended shape of the native configuration API - two viable options,
smallest first:

**Option A - recommended (smallest maintainable step):**

Apply the desktop patch to the submodule verbatim, keep the env-var read as
the native entry point, and make the JNI layer explicit:

- `pf_llm.cpp` gains a `g_pf_steering_spec` (parsed from the new
  `InferenceConfiguration` field / PfPlan field). In `loadModel`, before
  `llama_model_load_from_file`: `setenv("LLAMA_QWEN35_STEER", spec, 1)` when
  steering is enabled for this load, `unsetenv` otherwise. `unloadModel`
  clears it again.
- This is safe here because the engine is strictly single-model
  (`g_model` guard + single dispatcher), so the env var can never leak
  between concurrent loads. The env var becomes an internal detail of the
  pinned third_party tree rather than a user-facing contract.
- Preserves the desktop patch unchanged, keeps the third_party diff to exactly
  the two model files, and makes the app-facing API explicit (JNI/Kotlin).

**Option B - cleaner, larger (only if multi-context is ever planned):**

Extend the native API (`llama_model_params` or a setter in `llama.h`, e.g. a
steering spec on the model loader) and remove the env read. This changes the
llama.cpp API surface in the submodule, which raises maintenance cost across
submodule updates. Not recommended now.

Either way:

- Steering is **disabled by default**; unset/empty spec => identical graphs
  and behaviour to today (verified on desktop: no steering => no extra nodes).
- Non-qwen35 models: native side never steers (patch is qwen35-class-only);
  Kotlin additionally gates the UI/config path on
  `GgufMetadata.architecture == "qwen35"` and logs a warning when a steering
  spec is set for another arch - no silent enable, no silent ignore.
- The GGUF model file is never modified: steering columns are extracted at
  load time from the file and baked into separate in-memory constants.
- Runtime enable/disable: steering is a load-time configuration (columns and
  graph nodes are built at load). Disable == reload without the spec.
  Live per-turn toggle would require either an explicit "zero-dose" reload
  or graph surgery (not part of v0; document as future work).

## 6. Potential performance and memory risks

- **Memory**: one F32 `[n_embd=2560]` column per target = 10 KB each
  (~20 KB total) plus small graph-node overhead per steered layer. Negligible
  against a 2.7 GB model.
- **Compute (CPU default)**: per steered layer per ubatch, the dose subgraph
  is O(T log T) for the sort plus O(T * n_embd) for the norms; the
  intervention is one broadcast add. At decode (T=1) this is a handful of
  tiny ops per token; at prompt processing it is per-ubatch, not per-token.
  No measurable risk on the D9300+/Mali-G720 class hardware the app targets,
  but it is unmeasured (see Uncertainties).
- **Compute (Vulkan offload)**: the dose subgraph may straddle
  backend boundaries (argsort size cap, section 3.3), forcing
  device->host->device copies of small tensors per steered layer per ubatch.
  Small absolute cost, but it sits inside the prompt-processing path and
  could add latency in GPU-offload mode. Unverified on device.
- **Determinism / numerics**: the median is computed in F32 on whatever
  backend the scheduler picks; CPU and Vulkan results may differ slightly,
  changing the dose marginally between load configurations. The intervention
  add is F32.
- **Recurrent layers**: layers 13/14 are GDN layers; the steering add sits in
  the FFN branch after `down_proj`, so it feeds the residual stream that the
  recurrent state consumes - by design (that is the point of the intervention)
  but it also means a large dose can destabilize GDN state propagation; dose
  tuning is per-model and out of scope for this audit.
- **Error surface**: invalid steering specs throw at model load; the app
  already maps that to a load-failure string. A zero-norm column logs a
  warning and skips that target (inherited desktop behaviour).

## 7. Recommended implementation plan (dependency-ordered)

1. **Submodule patch port** - apply the desktop steering diff to
   `third_party/llama.cpp` (`src/models/qwen35.cpp`, `src/models/models.h`),
   including the deliberate local mod: parameterize the spec source (Option A:
   keep `getenv` as the native entry point; the JNI layer owns it).
2. **JNI plumbing** - extend `pf_llm.cpp` `loadModel` (or PfPlan) with the
   steering spec, `setenv`/`unsetenv` around `llama_model_load_from_file`,
   plan-log line, clear on unload. Validate layer/neuron ranges natively and
   surface failures as load errors.
3. **Kotlin config plumbing** - `InferenceConfiguration` fields +
   validation; `LlamaBridge.kt` signature; `ModelLoadService.kt` passes the
   persisted setting and gates on arch `qwen35` (warning otherwise);
   persistence in `SettingsStore` (default OFF).
4. **Build** - `native\build-native.ps1` (CPU default; optionally
   `-EnableVulkan` for the offload path), `.\gradlew :app:assembleDebug`;
   update `jniLibs`.
5. **Verification (device)** - with a catalogue Qwen3.5-4B GGUF:
   - steering OFF: logs identical to today, no "steering" lines.
   - steering ON (`14:8949:1,13:7956:-1`): the load log prints the two
     `steering layer ... neuron ... ||W_down[:, n]||` lines from
     `load_steering`; generation runs; outputs differ from OFF.
   - invalid spec (e.g. `32:1:1`) fails the load with the native error text.
   - a non-qwen35 model with steering configured: visible warning, no
     steering.
   - CPU backend first; then repeat once with GPU offload to observe the
     argsort fallback behaviour.
6. **Optional UI** - a Settings/Models toggle with the two preset targets,
   visible only for qwen35-arch models.

## 8. Uncertainties (not resolvable by static inspection)

1. **Vulkan dose-subgraph behaviour on Mali-G720**: whether the scheduler
   falls back to CPU for `argsort`/`sum_rows`/`get_rows` at prompt ubatch
   sizes, and what latency that adds in GPU-offload mode. Requires a device
   run with the GPU-offload plan.
2. **Actual GGUF tensor geometry of the catalogue model** (`abl-qwen35-4b-q4km`):
   the code assumes trunk `n_layer=32`, `n_embd=2560`, `ffn_down` shape
   `[n_ff, n_embd]` with the requested neuron indices in range. The model file
   was not present on the audit machine (no download per constraints); verify
   with `gguf_dump` or the app's GGUF parser before wiring presets.
3. **Numeric effect of steering** on the abliterated checkpoint (does the
   intervention behave as intended) - a modelling question, not an
   integration question; needs generation A/B runs on device.
4. **Exact ubatch token composition at runtime** (n_batch/n_ubatch plan
   knobs, chat template placement) shifts which tokens are excluded by the
   "token 0" rule per ubatch; the resulting dose differs slightly from the
   reference recipe's single fixed-dose derivation (documented discrepancy,
   inherited from the desktop implementation).
5. **Uncommitted working-tree state**: both the desktop steering edits and the
   PocketForgeAI plan-engine edits (`pf_llm.cpp`, `LlamaInferenceEngine.kt`,
   the Vulkan buffer fix, the submodule Vulkan change) are uncommitted. The
   integration must be based on the current working trees; committing both
   first is recommended before applying the patch.
6. **`llama_process` / revision drift**: the desktop tree predates the
   submodule; the steering patch touches no public API, but future submodule
   updates may move `qwen35.cpp` again - re-verify the 3.1 diff after any
   bump.
