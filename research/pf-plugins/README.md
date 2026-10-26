# pf-plugins — PF engine extension surface (B4 prototype, v0)

Owner: agent B4 | 2026-10-23 | branch `research/breakthrough`
Context: `research/B2_engine_audit.md`, `research/B2_plan.md`.
Everything here is **unapplied**: the app tree (`app/src/main/cpp/pf_llm.cpp`,
`app/src/main/cpp/CMakeLists.txt`) is NOT modified; the deliverable is a patch
file that `git apply --check` verifies cleanly.

## File inventory

| file | what it is |
|---|---|
| `pf_llm_patch_v0.diff` | UNAPPLIED patch to `app/src/main/cpp/pf_llm.cpp` + `app/src/main/cpp/CMakeLists.txt`, and adds two new files (`pf_plan.h`, `pf_plan.cpp`). Passes `git apply --check`. |
| `pf_plan.h` / `pf_plan.cpp` | PF plan v0 contract: `PfPlan`, `PfDeviceFacts`, minimal JSON parser/serializer. Plain C++17, zero deps (host + NDK compilable). |
| `pf_controller.cpp` / `pf_controller.h` | Standalone plan controller: `classify_device()` (frequency-cluster heuristic) + `plan_for_class()` returning a per-class `PfPlan`. No NDK required. |
| `pf_controller_test.cpp` | Off-device tests with static stub facts for 3 device classes. |
| `build_controller.ps1` | One-liner host build+test stub (auto-detects cl via vcvars64.bat / clang++ / g++; graceful failure with instructions). |
| `pf_llm_new_parts.cpp` | Readable REFERENCE extract of the patch's new engine code (does not compile standalone). |

## The JSON plan contract (what pfPlansJson emits / pfSetPlan accepts)

```json
{"pfPlanVersion":1,
 "cpu":{"cpuVariant":"","nThreads":4,"nThreadsBatch":6},
 "kv":{"typeK":"f16","typeV":"f16","flashAttn":"auto"},
 "batch":{"nBatch":512,"nUbatch":256},
 "split":{"nGpuLayers":-2,"loadMode":"mmap"},
 "heap":{"maxHeapBytes":0}}
```
`-1`/`""` = engine default; `split.loadMode` maps to the llama.cpp 0.7.0
`load_mode` enum (mmap/mlock/mmap_mlock/direct_io/none/auto); `nGpuLayers:-2`
defers to the caller's `loadModel` argument, `-1` = all GPU.
`pfPlansJson()` returns `{"facts":{...},"currentPlan":{...}}` with realistic
device facts (chosen `armv9.x` CPU variant, per-core cpufreq min/max,
`AT_HWCAP`/`AT_HWCAP2` + derived feature flags dotprod/fp16/sve/sve2/i8mm/sme,
vulkan presence, `MemAvailable` now — exactly the inputs llama.cpp itself
probes). `pfSetPlan()` accepts a bare plan object OR the whole wrapper.

## What the patch adds to the app

1. `pf_plan.h/.cpp` compiled into the app target (CMake `add_library` line).
2. Winning CPU variant capture in `pf_load_backends` (g_pf_chosen_cpu_variant).
3. `pf_collect_device_facts()` — sysfs/meminfo/getauxval, no heavy probes.
4. Knob mappers + full llama.cpp wiring in `loadModel`: `flash_attn_type`,
   `type_k`, `type_v`, `n_batch`/`n_ubatch` split, `load_mode`, `n_gpu_layers`
   override, separate decode/batch thread counts (all still API-backwards
   compatible: no plan = today's behaviour).
5. JNI + C ABI: `pfPlansJson()`/`pfSetPlan()` and `pf_engine_current_plan_json()`/
   `pf_engine_set_plan()`. Live-apply: thread counts via `llama_set_n_threads()`;
   structural knobs apply at the next context build.

## How PF apps use it (planned flow)

Kotlin `com.pocketforge.ai.inference.PfPlans` calls `pfPlansJson()` at startup →
classifies device (same heuristic as `pf_controller.cpp`, or a pinned plan from
defaults) → `pfSetPlan(json)` before `loadModel(...)` → read back
`pfPlansJson()`/`perfJson()` after each run to eval+replan. Generation loop,
sampler, and existing loadModel signatures are untouched.

## Verified on host

```
.\research\pf-plugins\build_controller.ps1
```
25/25 tests PASS (MSVC 14.43 via `vcvars64.bat`); compile+run in one command.
Equivalent manual line:
```
cl /nologo /EHsc /std:c++17 /O2 /Fe:pf_controller_test.exe pf_plan.cpp pf_controller.cpp pf_controller_test.cpp
```
Patch validation: `git apply --check research/pf-plugins/pf_llm_patch_v0.diff` → clean.
NDK compile of the patched file was NOT attempted (timebox; patch is stylistically
complete against vendored headers — see Open risks).

## Open risks / unknown surfaces

- **NNK compile of patch unverified** (`llama_set_n_threads`, `load_mode` field
  names, `<sys/auxv.h>` availability at NDK r28 — should be one clean compile).
- **cpuVariant pinning is a no-op in v0**: PF hand-rolls backend loading and picks
  most-capable-first; forcing a lower variant (e.g. armv8.2_1 for A2 bench
  parity) needs loader support (`ggml_backend_load_best`-style enumeration).
- **OpenMP=ON build**: `llama_set_n_threads` adjusts counts, but cpumask/pinning
  (`pf_plan`'s future cpu-mask field), poll, and threadpool attach
  (`llama_attach_threadpool`) are not wired — under GGML_OPENMP the native
  threadpool machinery is compiled out (audit §4). Thread-policy sweep results
  must come from llama-bench with the same OpenMP build.
- **maxHeapBytes** is advisory/logging-only — llama.cpp has no heap knob; the
  real gate is all static KV pre-allocation — needs a different mechanism
  (e.g. n_ctx trimming driven by the plan layer).
- **mpss-on-device risks**: hwcap2 SME2 bit (1<<37) mapping per kernel version,
  Xiaomi-uniform kernel hiding SVE2 (audit §3), and X200-FE's `armv9.0_1` vs
  `armv8.6_1` choice is plan-assumed, not device-verified.
- **Speculative decoding / per-layer GPU split** not in this surface (B2 plan §B
  mechanisms untouched).
