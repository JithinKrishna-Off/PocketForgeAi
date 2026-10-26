// pf_llm_new_parts.cpp - REFERENCE extract: everything pf_llm_patch_v0.diff
// adds to app/src/main/cpp/pf_llm.cpp, consolidated.
//
// This file does NOT compile standalone (it references pf_llm.cpp statics like
// g_model/g_context/LOGi); it exists so reviewers can read the new engine
// surface in one place. The authoritative content lives in
// pf_llm_patch_v0.diff (apply with: git apply research/pf-plugins/pf_llm_patch_v0.diff
// from the repo root; `git apply --check` passes against branch research/breakthrough).
// The plan contract itself (pf_plan.h/.cpp) is verbatim in this directory and,
// via the patch, becomes compilable as part of the app target.
//
// Engine: llama.cpp 0.7.0 @ 0a1ef30, ggml 0.27.0 (llama context knobs audited
// in B2_engine_audit.md §5 / B2_plan.md §C).

/////////////////////////////////////////////////////////////////////////////
// 1. New includes + state (pf_llm.cpp statics region)
/////////////////////////////////////////////////////////////////////////////
#if 0 // reference only - context shown for review
#include <sys/auxv.h>      // getauxval(AT_HWCAP / AT_HWCAP2), bionic API 23+
#include <cstdio>         // fopen/scaling-freq + meminfo readers
#include "pf_plan.h"      // plan contract module (added to CMake target)

// stored in the statics block, next to g_model/g_context:
static std::string g_pf_chosen_cpu_variant; // winning libggml-cpu loader name
static pf::PfPlan g_pf_plan;                // Kotlin-driven plan; defaults = engine defaults
#endif

/////////////////////////////////////////////////////////////////////////////
// 2. Winning-variant capture (pf_load_backends, CPU variant loop)
/////////////////////////////////////////////////////////////////////////////
#if 0
    for (const char * name : PF_CPU_VARIANTS) {
        if (pf_try_backend(lib_dir_s, name, (...).c_str())) {
            g_pf_chosen_cpu_variant = name;      // NEW: remember chosen variant
            break;
        }
    }
#endif

/////////////////////////////////////////////////////////////////////////////
// 3. Device facts collector (new statics section after pf_load_backends)
/////////////////////////////////////////////////////////////////////////////
#if 0
static unsigned long long pf_read_number_file(const std::string & path); // fscanf first %llu

static pf::PfDeviceFacts pf_collect_device_facts() {
    pf::PfDeviceFacts facts;
    facts.backendName = g_pf_chosen_cpu_variant;   // "libggml-cpu-android_armv9.0_1.so" -> "armv9.0_1"
    facts.nCoresOnline = (int) sysconf(_SC_NPROCESSORS_ONLN);
    // per-core freqs: /sys/devices/system/cpu/cpu%d/cpufreq/scaling_{max,min}_freq
    facts.hwcap  = getauxval(AT_HWCAP);            // same bits ggml cpu-feats.cpp uses
    facts.hwcap2 = getauxval(AT_HWCAP2);
    facts.hasDotprod = hwcap  & (1ull << 20);
    facts.hasFp16    = hwcap  & (1ull << 10);      // ASIMDHP (+ FPHP 1<<9)
    facts.hasSve     = hwcap  & (1ull << 22);
    facts.hasSve2    = hwcap2 & (1ull << 1);
    facts.hasI8mm    = hwcap2 & (1ull << 13);
    facts.hasSme     = hwcap2 & (1ull << 23);
    facts.hasSme2    = hwcap2 & (1ull << 37);
    facts.vulkanOk   = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU) != nullptr
                    || ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_IGPU) != nullptr;
    // MemAvailable (kB) from /proc/meminfo -> facts.memAvailableBytes
    facts.factsValid = nCoresOnline > 0 && memAvailableBytes > 0;
    return facts;
}
#endif

/////////////////////////////////////////////////////////////////////////////
// 4. Knob mappers (new static helper section)
/////////////////////////////////////////////////////////////////////////////
#if 0
static enum llama_flash_attn_type pf_flash_attn_from_str(const std::string & s); // on/off/auto
static enum ggml_type pf_kv_type_from_str(const std::string & s);                // f16/q8_0/q4_0
#endif
// mmap knob maps onto model_params.load_mode (llama.cpp 0.7.0 changed from
// the old use_mmap bool): "mlock" -> LLAMA_LOAD_MODE_MLOCK,
// "mmap_mlock", "none" (LLAMA_LOAD_MODE_NONE), "direct_io", or engine default
// "auto"/"mmap" (left at llama_context/model_default_params()).
// llama-load-side: model_params.load_mode = llama_load_mode_from_str(str).

/////////////////////////////////////////////////////////////////////////////
// 5. C ABI (engine-side plan layer; JNI shells below)
/////////////////////////////////////////////////////////////////////////////
#if 0
static std::string pf_engine_current_plan_json_impl() {
    return pf::plan_and_facts_json(g_pf_plan, pf_collect_device_facts());
}

static std::string pf_engine_set_plan_impl(const char * json) {
    if (json == nullptr || json[0] == '\0') { return "pf_engine_set_plan: empty plan JSON"; }
    pf::PfPlan parsed;
    std::string err;
    if (!pf::parse_plan(json, &parsed, &err)) { return "pf_engine_set_plan: " + err; }
    g_pf_plan = parsed;
    if (g_context != nullptr && g_pf_plan.nThreads > 0) {         // live part only
        const int t  = std::min(g_pf_plan.nThreads, hw);
        const int tb = std::min(g_pf_plan.nThreadsBatch > 0 ? g_pf_plan.nThreadsBatch : g_pf_plan.nThreads, hw);
        llama_set_n_threads(g_context, t, tb);
    }
    return "";   // "" == ok
}

static std::string g_pf_plan_json_ret;
extern "C" const char * pf_engine_current_plan_json(void);   // -> facts+plan JSON
extern "C" const char * pf_engine_set_plan(const char * json); // -> "" or error text
#endif

/////////////////////////////////////////////////////////////////////////////
// 6. loadModel wiring (ctx/model params block; existing args stay valid)
/////////////////////////////////////////////////////////////////////////////
#if 0
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = g_pf_plan.nGpuLayers != -2 ? g_pf_plan.nGpuLayers : n_gpu_layers;
    if (g_pf_plan.loadMode != "auto" && g_pf_plan.loadMode != "mmap") {
        const enum llama_load_mode lm = llama_load_mode_from_str(g_pf_plan.loadMode.c_str());
        if (lm != LLAMA_LOAD_MODE_AUTO && lm != LLAMA_LOAD_MODE_MMAP) { model_params.load_mode = lm; }
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    const int arg_batch = batch_size > 0 ? batch_size : DEFAULT_BATCH_SIZE;
    ctx_params.n_batch  = g_pf_plan.nBatch > 0 ? g_pf_plan.nBatch : arg_batch;
    ctx_params.n_ubatch = g_pf_plan.nUbatch > 0 ? g_pf_plan.nUbatch
                        : g_pf_plan.nBatch > 0 ? g_pf_plan.nBatch : arg_batch;
    const int arg_threads = n_threads > 0 && n_threads <= hw ? n_threads : clamp4;
    const int decode_threads = g_pf_plan.nThreads > 0 ? g_pf_plan.nThreads : arg_threads;
    const int batch_threads  = g_pf_plan.nThreadsBatch > 0 ? g_pf_plan.nThreadsBatch : decode_threads;
    ctx_params.n_threads = decode_threads;
    ctx_params.n_threads_batch = batch_threads;
    ctx_params.flash_attn_type = pf_flash_attn_from_str(g_pf_plan.flashAttn);
    ctx_params.type_k = pf_kv_type_from_str(g_pf_plan.kvTypeK);
    ctx_params.type_v = pf_kv_type_from_str(g_pf_plan.kvTypeV);
    // cpuVariant pinning + maxHeapBytes are advisorylogged in v0 (see README gaps)
    g_n_batch = (int) ctx_params.n_batch;   // decode_tokens_in_batches chunks by g_n_batch
#endif

/////////////////////////////////////////////////////////////////////////////
// 7. JNI exports (placed after listBackendsJson)
/////////////////////////////////////////////////////////////////////////////
#if 0
extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_pfPlansJson(JNIEnv * env, jobject) {
    // "facts":{ backendName, nCoresOnline, cores[{id,minKhz,maxKhz}], hwcap,
    //           hwcap2, features{dotprod..sme2}, vulkanOk, memAvailableBytes,
    //           factsValid }, "currentPlan":{ pfPlanVersion, cpu{...},
    //           kv{typeK,typeV,flashAttn}, batch{nBatch,nUbatch},
    //           split{nGpuLayers,loadMode}, heap{maxHeapBytes} }
    return env->NewStringUTF(pf_engine_current_plan_json_impl().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_pfSetPlan(JNIEnv * env, jobject, jstring jplan) {
    const auto * plan = env->GetStringUTFChars(jplan, nullptr);
    const std::string err = pf_engine_set_plan_impl(plan);
    env->ReleaseStringUTFChars(jplan, plan);
    return err.empty() ? nullptr : env->NewStringUTF(err.c_str()); // null == ok
}
#endif
