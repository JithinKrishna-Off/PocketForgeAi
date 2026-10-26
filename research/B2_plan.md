# B2 Plan — tuned-baseline matrix + plugin surface design for pf_llm.cpp
Owner: agent B2 | Date: 2026-10-23. Engine = llama.cpp 0.7.0 @ `0a1ef30`, ggml 0.27.0. No device actions in this doc; all kernels/flags cited are from code audit (B2_engine_audit.md).

## A. Tuned-baseline matrix (on-device, llama-bench from `0a1ef30`)
Priority order = expected effect size x cost. Baseline model set: qwen25-1.5b-q40 (screen/tuning), qwen3-4b-q40 + q4km (memory/thermal check).

A0. Precondition sanity (1 run): `llama-bench --list-devices`; confirm the CPU device description shows expected feature tags (DOTPROD/I8MM/SVE2 flag set, no SME) and vulkan backend registered. Record `battery temp`, `loadavg` before each stage (phase0 confound doc).

A1. **Kernel variant + repack axis** (highest stretch factor per phase0: tg128 variance 6-30 tok/s on armv8.0_1):
- Sweep `-dev` anchored CPU .so name (llama-bench auto-picks top score armv9.0_1; force lower ones via spare libs in /data/local/tmp/pfbench/spare per phase0 LAYOUT): armv8.0_1 (control), armv8.2_1, armv8.2_2, armv8.6_1, armv9.0_1. armv9.2_* expected auto-reject (score 0, no SME in hwcap2) - skip unless list-devices shows SME=1.
- `--repack 0/1` at fixed (1.5B Q4_0): repack=1 is the ggml REPACK extra-buffer path, expect: pp +10-25% (i8mm repack), tg +5-15%.
- Q8_0 vs Q4_0 vs Q4_K_M decode-only comparison: KleidiAI only covers Q8_0 on this SoC (audit §3) → `-ctk`... no; instead quantize experts/KV... simplest: `-nkvo 0` + KV q8_0 unlocks nothing for KleidiAI (that's weights, not KV). For weight-level: use `-ncmoe` off-axis (1.5B dense, n/a) — instead use `-m qwen3-4b-q4km.gguf` vs `-m qwen3-4b-q40.gguf` as real-world weight-quant effect.
Expected: variant 8.0_1 → 9.0_1 pp +30-80% (dotprod+i8mm), tg +50-150% (repack + dotprod + i8mm MUL_MAT MMVQ). Biggest single lever.

A2. **Thread count / mask / priority**:
- `-t 1..8` at fixed best variant + fixed (-C). Confirm phase0's t=8 regression is kernel-vs-thread noise; suspect best at t=6 (pp) and t=2..4 (tg).
- `-C 0xffffff (all 8), 0x1e (4-7 sub/big), 0x80 (cpu7 only)`; also `--cpu-strict 0` vs 1 (1 pins per-thread dp; 0 shares global mask, migrates inside).
- `-C 0x80 --cpu-strict 1 -t 1` as fastest-single-core-of-8 probe (Turbo X4 lane).
- `-t 4 vs 6 vs 8 -C 0xffffffff` at pp512 and tg128 both. Skip `-fa` while threading (FAttn turns提升了 pp but uses skulls of scalar FA path on Mali).
Expected effect on tg128: 1.5-3x vs phase0 strawman (which had huge variance + wrong kernel + un-pinned).

A3. **KV types / -fa / batch** (fixed at best variant, best threads, fixed mask):
- `-ctk q8_0 -ctv q8_0` vs default f16 (qwen3-4b, ctx 4096) → KV size halves; only helps memory headroom (and possibly pp if KV bandwidth-bound), tg nearly unchanged.
- `-fa 0/1` (llama_bench `-fa`); on CPU with scalar FA enabled adds fixup cost; with Vulkan FA is scalar path (no coopmat) → likely HARMFUL both; still measure once.
- `-b 512 -ub 512` default; `-ub 128/256/512`; `-b 1024 -ub 512`. Vulkan-hybrid: `-b` interacts with full GPU offload only.
Expected: ubatch 256 vs 512 on pp512 marginal (<5%) unless Vulkan hybrid; KV q8_0 worthwhile only for 4B at long ctx (RAM savings > speed).

A4. **ngl / Vulkan split** (final因为这stage, once CPU config locked):
- Full GPU: `-ngl 99`. Expect: 1.5B pp ~13 tok/s (phase0), tg ~22; 4B OOM risk → retry with `-b 256 -ub 256 -ctk q8_0 -ctv q8_0 --no-kv-offload` and trimmed ctx.
- CPU-full with `-dev cpu -t N` as reference.
- Hybrid: llama-bench does NOT split per-layer (llama.cpp supports `-ngl` only fully-biased; intermediate layers go host→host `n_gpu_layers` partial numbers, use `-ngl 14/28 (n_layer/2 -1)` for 1.5B/4B) to find layer-split crossover for pp and tg.
Expected: tg at full GPU 1.5B worse than CPU tuned (~90 tok/s at 1.5B on 9.0_1 repack should be possible) but 4B may flip the ranking due to CPU GEMM saturation; Vuklkon likely wins tg at 4B if not OOM-killed.

A5. **Poll axis**: `--poll 0/25/50/75/100` at chosen config. With GGML_OPENMP=ON this knob lives in the non-OpenMP threadpool branch (audit §4) and is likely a **no-op** in this build; run 2 points (0, 50) to falsify, then delete from the matrix. Same for `--cpu-strict` (that one is real in both paths).

Also llama-bench CSV flags to capture per row: `nkvo,poll,cpu_mask,cpu_strict,flash_attn,repack,n_cpu_moe` columns (fields at llama-bench.cpp:1607-1650, "poll" and "nkvo" positional mapping at 1922).

## B. Plugin surface design (pf_llm.cpp C ABI)
Goal: runtime-pluggable "mechanisms" that sit between JNI calls and llama.cpp calls; each mechanism gets first-class probe/plan/act hooks with a per-device toggle table.

### B.1 Minimum C ABI (add to app/src/main/cpp/pf_llm.cpp and a new header app/src/main/cpp/pf_plugin.h)
```c
typedef enum { PF_Mech_NONE=0, PF_Mech_SPECDEC, PF_Mech_KV_REQUANT,
               PF_Mech_PREFETCH, PF_Mech_LAYER_SPLIT, PF_Mech_THREADPOLICY } pf_mech_type;

typedef struct pf_device_probe {
    char   backend_name[64];   // "armv9.0_1" etc (from ggml_backend_reg_name after load)
    int    n_cores_online;
    unsigned long hwcap, hwcap2;  // via getauxval in pf probe
    bool   has_sme, has_sme2, has_i8mm, has_dotprod, has_sve, has_sve2;
    bool   vulkan_ok;          // probeBackendJson result sticky
    size_t mem_available_bytes;
    float  cached_therm_c;
} pf_device_probe;

typedef struct pf_plan {
    int  n_threads, n_threads_batch;
    int  poll;                  // 0..100 (kept even if likely no-op; free upgrade door)
    int  cpu_mask_bits;         // 0 = OS default
    bool cpu_strict;
    enum { PFKV_F16=0, PFKV_Q8_0 } kv_type_k, kv_type_v;
    int  n_batch, n_ubatch;
    int  n_gpu_layers;          // -1 = auto per plan
} pf_plan;

// mechanism vtable for runtime plug-ins (C ABI; mechanisms compiled into pf_llm.cpp for now, but keep surface dlopen-ready)
struct pf_mech {
    pf_mech_type type;
    const char*  name;         // stable string id, Kotlin table keyed by this
    void (*probe)(pf_device_probe* out);
    int  (*plan_select)(const pf_device_probe*, const char* plan_id, pf_plan* out); // returns 0 ok
    void (*attach)(struct llama_context*);   // e.g. llama_attach_threadpool, abort cb
    void (*detach)(void);
};
// registry
int  pf_mech_register(struct pf_mech m);
int  pf_mech_count(void);
bool pf_mech_get(int idx, struct pf_mech* out);
```
Free-standing JNI entries (added, not breaking existing signatures):
- `pfProbeDeviceJson() -> String` (wraps pf_device_probe, includes hwcap bits + chosen variant name + vulkan probe sticky)
- `pfApplyPlan(jstring planJson) -> String(errOrNull)`  — rebuilds context with new n_ctx/n_batch/kv types/ngl via `pfPlan_to_ctx_params()`; thread-only changes (threads/flags/poll) applied live via `llama_attach_threadpool` / `ggml_threadpool_resume` without unload.

### B.2 Per-device toggle table (Kotlin side, lives in com.pocketforge.ai.inference.PlanStore)
- Read `/data/local/tmp/pfbench/plans.json` (matches harness data dirs, bypasses APK updates); fallback = built-in table keyed by (backend_name, core-topology bucket). Keys: `armv9.0_1+SME-no`, `armv8.6_1+SVE2-no`, `vulkan-only`, `cpu+vulkan-hybrid`.
- Each row = a `pf_plan` + which mechanisms are enabled (specdec, kv_requant, prefetch, layer_split).

### B.3 Integration point
- Kotlin "Plan" object lives beside LlamaBridge: `com/pocketforge/ai/inference/PfPlans.kt` (new). Plan selected from PlanStore at load time → passed DOWN via new JNI `pfApplyPlan` before `loadModel` (current `init` keeps loading backends only).
- All existing `loadModel` params stay (multith.) but become plan-driven: pf loads model with `pf_plan` values instead of raw ints, keeping Kotlin's single-threaded dispatcher contract.

## C. 5-item shortlist: exact pf_llm.cpp edits before any prototype
| # | change | file + insertion anchor |
|---|---|---|
| 1 | **Threadpool control surface**: add `ggml_threadpool_params` capture + `llama_attach_threadpool(...)` wrapper JNI (`setThreadPlan(nThreads, maskHex, poll, strict)`) and a `ThreadPolicy` pf_mech. Need to unwind the GGML_OPENMP=ON build (or accept that only the non-OpenMP path honors poll — flag in comment). Reuses knobs already audited (ggml-cpu.c:2609-2742, ggml.h:3009). | app/src/main/cpp/pf_llm.cpp:292-300 (ctx_params block) and new extern "C" JNI after line 486 (generateNextToken) |
| 2 | **Plugin loader stub + vtable registry** (`pf_plugin.h`, `pf_mech_register/get/count`, built-in `pf_device_probe` from hwcap + output of `listBackendsJson` mechanism so future dlopen() plug-ins are drop-in). | new file app/src/main/cpp/pf_plugin.h; wiring in pf_llm.cpp:237-249 (init) and after 575 (listBackendsJson) |
| 3 | **KV/flash-attn/op-offload hooks in loadModel**: plumb `flash_attn_type`, `type_k/type_v`, `offload_kqv`, `op_offload`, `swa_full` + split `n_ubatch` from `n_batch`, all exposed through `pfPlan`-driven params (not raw loadModel args), so bench-matrix winners translate into app runs without new Kotlin signatures. | app/src/main/cpp/pf_llm.cpp:268-308 (llama_context_params block). ALSO adjust llama-bench-derived parameters for 4B OOM (trim n_ctx or streaming K/V when Q4KM) |
| 4 | **Per-option sensor hooks**: add tiny non-blocking samplers inside generateNextToken loop = 1×/token: battery temp (/sys/power_supply paths used by phase0), MemAvailable, taskset verification of current PID affinity; rotated into `g_recent_logs` ring and `perfJson`. Plugins (specdec, kv_requant) consume these **through C structs (non-JNI)** to avoid cross-language cost per token. | app/src/main/cpp/pf_llm.cpp:486-532 (inside generateNextToken, before sampler sample call) |
| 5 | **Cancellation (real abort)**: pass `ctx_params.abort_callback` reading g_cancel_flag so a cancelled decode stops inside llama rather than between tokens; required for speculative-decode controller to stop early safely. | app/src/main/cpp/pf_llm.cpp:292-300 (abort_callback install) + cancellation check inside the callback fn defined near line 452 |

Sequencing rule: #1+#2 first (they unlock bench-matrix parity in-app and the mechanism surface); #3 partially pre-proto (needed for KV sweep numbers to be applicable); #4/#5 tight coupling to prototype phases (specdec + prefetch controllers need them).

## Risk register for B (plugin design)
- GGML_OPENMP=ON means the poll/strict/threadpool knobs may be no-ops in the app build; if a tuned CPU path needs the native threadpool, the app CMake must switch to GGML_OPENMP=OFF (audited: threadpool struct exists guarded `#ifndef GGML_USE_OPENMP`) — that is a **rebuild** decision, must be settled before A2/A5 sweeps, else numbers measured on llama-bench with the same OpenMP build remain valid for app.
- `pf_try_backend` uses fixed most-capable-first order; adding a plug-in variant .so means upstreaming the pf list OR switching pf to `ggml_backend_load_best`-equivalent enumeration — slightly rewrites audit §3 Layer 2 (safe).
- Bionic sched_setaffinity works, but per-thread masks + Android EAS task placement may interact with Vivo's own governor (phase0's loadavg=16 confound); plugin thread policy must be plan-verified on device, not assumed.
