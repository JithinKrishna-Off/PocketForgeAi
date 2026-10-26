# PocketForgeAI - steering + Model Hub + perf: implementation report

Date: 2026-10-26. Method: 5 concurrent agents + lead integration, then full
compile/build verification. No physical device was available, so all runtime
behaviour is explicitly marked unverified.

## Completion status (the 8 questions)

1. Optimized native libraries built: **YES** - 11 CPU-only arm64-v8a libs built
   from the steering tree (`pocketforge-android/build-apk-libs.ps1`, incremental,
   KleidiAI + OpenMP + 7 CPU variants + GGML_BACKEND_DL). Zero `libomp.so`
   NEEDED (static OpenMP verified with llvm-readelf on every .so).
2. Steering enabled from the app: **YES** (code complete, device-untested).
   Settings -> "Neuron steering" card (Switch + spec), per-load switch in the
   Models load dialog, spec validated against GGUF headers before load, applied
   via PfPlan -> env wrap around `llama_model_load_from_file`.
3. Technically supported architectures: **qwen35 dense only**
   (`general.architecture == "qwen35"`, trunk layer < block_count, neuron <
   first dim of `blk.<L>.ffn_down.weight`). qwen35moe, clef, and everything
   else classify UNSUPPORTED. Behavioural effect: UNVERIFIED (no device data).
4. Model Hub live catalog: **YES** - Hugging Face Hub `/api/models` search +
   `/tree` file listing via HttpURLConnection (no new dependencies), verified
   live with metadata-only calls. Pagination = limit-growth + id-dedup (the
   `offset` param was probed and is NOT honored by HF; limit growth works).
5. Downloads end-to-end: **CODE COMPLETE, runtime unverified** (no device).
   Reuses the existing WorkManager + HttpURLConnection pipeline (resume, SHA-256
   vs `lfs.oid`, storage checks, foreground notification, cancel/retry).
   The HF URL/digest/redirect/range behaviour was verified with small metadata
   calls; no model was downloaded.
6. APK builds: **YES** - `assembleDebug` succeeds; APK copied to `releases/`.
7. Performance measured on a physical device: **NO** - no device in this
   environment. Recorded baselines from earlier sessions exist in
   `research/` (12.3-12.5 tok/s app decode post-fix, 54 tok/s prefill); the new
   changes are reviewed but unmeasured.
8. Report path: this file. Remaining blockers: device verification only.

## Release artifacts

- APK: `releases/PocketForgeAI-v0.2.0-arm64-v8a.apk` (30,270,773 bytes)
- Checksum: `releases/PocketForgeAI-v0.2.0-arm64-v8a.apk.sha256`
  SHA-256 = 29d3f4ec59b091f096ca9a43edb58113213128310d4273e68597297fced1e6ae
- Version: versionName 0.2.0, versionCode 2 (existing metadata; not bumped).
- Variant: debug (the project has NO release signing config; debug is signed
  with the debug key and installable - release signing was NOT touched).
- APK validation: 22 native libs, all under `lib/arm64-v8a/` (no other ABI
  dirs, no duplicates); steering string `LLAMA_QWEN35_STEER` verified present
  in the packaged `libllama.so`; packaged set = 7 CPU variants + ggml +
  ggml-base + llama + llama-common + pocketforge-llm + vulkan + mtmd + tool
  impl libs + androidx support libs.
- Device install/run: NOT performed (no device).

## Agent responsibilities and status

| Agent | Scope | Status |
|---|---|---|
| 1 (native) | arm64-v8a build + validation | DONE: 11 libs, no libomp.so, steering compiled in, KleidiAI in all 7 variants (317 kai_* symbols each incl. SME), SONAMEs correct, Vulkan left OFF |
| 2 (steering) | verify patch + JNI contract | DONE: 10/10 checks OK (indexing, norms, dose, aux tensor lifetime, placement, prefill/decode, arch guards, default-off, Android portability, GGUF immutability); contract implemented by lead |
| 3 (UI inspect) | map UI + integration points | DONE (inspection fed Agent K) |
| 4 (hub inspect) | map downloads/networking + HF API | DONE: verified search/detail/tree API shapes live (incl. lfs.oid == SHA-256) |
| 5 (perf) | perf review | DONE: findings F1-F11, proposals P1-P5; P1/P2/P3(string part)/P4/F8/F11 implemented by lead/agents; P3 (markdown throttle) and F7 (load progress callback) deferred |
| K (UI impl) | steering UI + compat service | DONE (all Kotlin compiles) |
| H (hub impl) | Model Hub + downloads | DONE (compiles after one @Composable fix) |

Note: Agent 5's report contained an injected noise string at the end
("Importing: username = ..." + a hidden span). It was identified as injected
content inside read data, NOT a user instruction, and ignored.

## Native build and packaging

- Build: `powershell -ExecutionPolicy Bypass -File pocketforge-android\build-apk-libs.ps1`
  (llama.cpp repo; incremental, no -Clean, no Vulkan). Exit 0.
- Flags confirmed in CMakeCache: GGML_CPU_KLEIDIAI=ON, GGML_OPENMP=ON,
  GGML_BACKEND_DL=ON, GGML_CPU_ALL_VARIANTS=ON, GGML_CPU_REPACK=ON,
  GGML_LLAMAFILE=OFF, GGML_VULKAN=OFF, Release, android-26, arm64-v8a,
  `-static-openmp` in SHARED+MODULE linker flags,
  OpenMP_omp_LIBRARY=<NDK libomp.a>.
- Staged into `app/src/main/jniLibs/arm64-v8a`: libllama.so (45,736,264 B,
  +238 KB vs pre-steering = steering code), libllama-common.so, libggml.so,
  libggml-base.so, 7x libggml-cpu-android_*.so. The existing
  libpocketforge-llm.so was REBUILT (new steering plan field) against the
  app's submodule headers (byte-identical ABI surface to the desktop tree;
  verified in the prior audit and re-checked here).
- `libpocketforge-llm.so` NEEDED set unchanged: libllama, libllama-common,
  libggml, libggml-base + system libs.
- MAINTENANCE CAVEAT: `native/build-native.ps1` rebuilds the WHOLE lib set
  from the steering-free submodule tree and overwrites jniLibs. After any
  pf-only rebuild, the 11 steering libs must be re-copied from
  `llama.cpp/pocketforge-android/libs/arm64-v8a/` (this was hit and fixed
  during integration).

## Steering: implementation and API

- Native (unchanged semantics): env `LLAMA_QWEN35_STEER` =
  `"L:N:dir[:dose][,...]"`, parsed once per model load; dose = dir x median
  residual norm / ||W_down[:,neuron]||; intervention at the MLP down-projection
  output; disabled when empty; GGUF never modified.
- JNI integration (lead):
  - `pf_plan.h/.cpp`: PfPlan gained `steerSpec` (serialized as
    `"steer":{"spec":"..."}`, JSON-escaped).
  - `pf_llm.cpp loadModel`: setenv/unsetenv wraps ONLY the load call; the
    applied spec is cached (`g_steer_spec_applied`), cleared on load failure,
    context failure and unload. Isolation: steering state lives on the
    llama_model instance; each load re-reads the plan spec; unsetenv after
    load means no residue for the next load.
  - `pfPlansJson()` now reports `"steer":{"active":bool,"appliedSpec":"..."}`.
- Kotlin (Agent K):
  - `data/steering/SteeringCompatibility.kt`: shared service (SUPPORTED /
    UNSUPPORTED / UNKNOWN + `behaviouralVerified=false`), used by BOTH the
    Models tab and the Model Hub. Grammar validation (parseSpec/validateSpec)
    and file-based validation (assessGgufFile: arch, block_count, per-target
    `blk.<L>.ffn_down.weight` first dim).
  - `InferenceConfiguration.steerSpec` + validate().
  - `ModelLoadService.loadInstalled(..., steerEnabled)`: resolves spec from
    settings/override, ALWAYS publishes it into the plan before load (empty =
    disabled, so a stale spec can never carry over), runs assessGgufFile on
    Dispatchers.IO, fails UNSUPPORTED loads with a clear message, proceeds on
    UNKNOWN (native load is the authoritative validator).
  - Settings card ("Neuron steering": switch + spec + validation errors +
    reload-required + effect-unverified note) and per-model switch in the load
    dialog (only shown for SUPPORTED models; reason text otherwise).
  - Reload required to change the spec of a resident model (enforced by
    design; documented in the UI).

## Model Hub

- `data/hub/HfCatalogService.kt`: search (`/api/models?search=<q>+gguf`),
  detail (`?full=true`: license, gguf.architecture, context_length), tree
  (`/tree/<sha>?recursive=true`: per-file size + lfs.oid as sha256), direct
  URLs `https://huggingface.co/<repo>/resolve/<sha>/<file>` pinned to the
  returned revision; repo-id regex validation; HTTPS only; gated/private
  filtered out; shard files (`-000NN-of-`) excluded; smallest-file-first.
- `HubViewModel`/`HubScreen`: 5th tab ("Hub", search icon), debounced search
  (400 ms), load-more (limit 25->50->100 + dedup), expandable detail with GGUF
  file list + steering badge + license + repo link, per-file download
  buttons/progress/resume/retry/cancel, insufficient-storage errors surfaced.
- Downloads reuse the existing pipeline: marker JSON gained repo/revision/
  displayName/author (backward-compatible optString); worker registers
  installed models with source "hub" + provenance; id = sanitized
  `<author>_<repo>_<file>` with `-N` dedup; storage check size + 5% + 256 MiB;
  resume only when marker url+size match (corruption-safe).
- No auto-downloads; downloads only from explicit user taps.

## Performance changes (reviewed; device-unmeasured)

1. P1 (already in tree, validated): CPU-only device pinning when gpu_layers=0
   + lazy Vulkan dlopen - recorded 0.9 -> 12.3 tok/s decode improvement on the
   reference device (research/ artifacts).
2. P2: PerformanceViewModel 1 Hz tick no longer re-parses GGUF headers,
   re-reads /proc, or blocks the engine lane on the main thread (metadata
   cache keyed on the loaded model, context-state cached until the model
   changes, device facts every 5 s, whole loop on Dispatchers.IO).
3. F3b: chat saves + the every-2 s "decrypt all conversations" refresh now run
   on Dispatchers.IO (was main thread, during generation).
4. P4: Kotlin thread fallback now matches the native rule (min(6, cores-2))
   and its stale comment/labels were corrected.
5. F8: broken plan-summary regexes replaced with org.json parsing
   (backendName, kv types, steer status).
6. F11: dead gpuLayers ternary removed.
7. P3 (partial): per-token Kotlin string concatenation replaced with a
   StringBuilder (O(n) -> O(1) amortized per token). Markdown re-parse and
   scroll-animation restarts during streaming: NOT changed (deferred, higher
   UI risk).
- CRITICAL FIX during integration: `LlamaBridge.pfSetPlan` was declared
  non-nullable `String` in Kotlin while native returns null on success; the
  compiler proved the null-check "always true" (would have failed every model
  load). Declaration corrected to `String?`.
- Not implemented (deferred): F6 warmup decode, F7 load progress callback,
  P3 full streaming-UI throttle, P5 plan-application UX.

## Files changed

llama.cpp repo (unchanged this session; steering patch preserved):
- src/models/qwen35.cpp, src/models/models.h (pre-existing steering)
- pocketforge-android/* (build script, build tree, staged libs)

PocketForgeAI repo:
- app/src/main/cpp/pf_llm.cpp, pf_plan.h, pf_plan.cpp (steering API)
- app/src/main/jniLibs/arm64-v8a/*.so (11 steering libs + rebuilt pf lib)
- NEW: data/steering/SteeringCompatibility.kt, data/hub/HfCatalogService.kt,
  ui/screens/HubScreen.kt, ui/screens/HubViewModel.kt
- inference/InferenceTypes.kt, LlamaBridge.kt, ModelLoadService.kt,
  LlamaInferenceEngine.kt (StringBuilder)
- settings/SettingsStore.kt
- ui/screens/SettingsScreen.kt, SettingsViewModel.kt, ModelsScreen.kt,
  ModelsViewModel.kt, PerformanceViewModel.kt, ChatViewModel.kt,
  MainActivity.kt (hub tab)
- download/ModelDownloadManager.kt, DownloadRepository.kt, DownloadWorker.kt
- data/model/ModelRegistry.kt (repo/revision provenance)
- releases/ (APK + sha256)

## Build and verification matrix

| Check | Result |
|---|---|
| Native arm64-v8a build (llama+common+backends) | PASS (exit 0) |
| llvm-readelf: libomp.so NEEDED in ANY of 20 libs | 0 occurrences - PASS |
| libllama.so steering strings (staged + inside APK) | PASS |
| AArch64 machine type, SONAMEs | PASS (Agent 1) |
| gradle compileDebugKotlin | PASS (after @Composable + pfSetPlan fixes) |
| gradle assembleDebug | PASS (33 s) |
| APK: single ABI dir, 22 libs, no duplicates | PASS |
| pfSetPlan/pfPlansJson contract | Verified against pf_plan parser (bare plan or wrapper) |
| Device install / model load / steering effect / hub search / download | NOT VERIFIED (no device) |

## Known limitations and unverified runtime behaviour

- No device testing at all: load success, decode tps, steering effect, hub
  search UX, download resume across restarts - all unverified.
- Steering behavioural effect unverified by design (no A/B data); the UI
  states this explicitly. Technical compatibility is shown separately.
- Steering caveats (documented, by-design): per-ubatch median (prompt chunks
  of n_batch tokens each exclude their first row); T==1 decode uses the
  current token's own residual norm (adaptive dose); MTP/draft graphs are not
  steered; qwen35moe/clef not steerable; reload required on spec change;
  duplicates in the spec are additive.
- Vulkan: kept from the Oct 21 submodule build, loaded lazily, OFF by default
  (ngl=0 pins CPU-only). Desktop Vulkan build remains blocked (SPIRV-Headers
  CMake package unavailable; no Vulkan SDK on this machine).
- Model Hub pagination uses limit-growth + dedup (HF `offset` param not
  honored). `lfs.oid` assumed SHA-256 (verified for one file). CDN redirect
  hosts (.hf.co) covered by the existing allowlist; behaviour on unusual
  regions unverified. Small auxiliary .gguf files in some repos may be listed;
  the user picks the file (no name guessing).
- App load time with CPU_REPACK is ~13 s on the reference device (recorded);
  no progress callback yet (F7 open).

## Exact remaining steps

1. Install `releases/PocketForgeAI-v0.2.0-arm64-v8a.apk` on an arm64 device;
   verify model load + generation (CPU path).
2. Enable steering on a qwen35 dense model (Settings card), reload, confirm
   "Model ready ... steer=14:8949:+1,13:7956:-1" in logs, run an A/B turn.
3. Exercise the Hub tab: search, detail, small-GGUF download, verify SHA-256
   pass + install + load.
4. Optional follow-ups: F7 load progress callback, P3 streaming-UI throttle,
   Vulkan SDK install to unblock desktop Vulkan builds.
