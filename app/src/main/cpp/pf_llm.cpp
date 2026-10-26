// pf_llm.cpp - JNI layer for PocketForge AI, adapted from the upstream
// examples/llama.android implementation of the pinned llama.cpp revision.
//
// Ownership and threading:
// - A single model/context/sampler set is owned globally. All calls come from
//   Kotlin's single-threaded engine dispatcher except setStopFlag (relaxed atomic).
// - Kotlin owns the accumulated assistant turn text; finishTurn records it in
//   the chat-template history. Native code never logs prompt/response content.
//
// PF plan surface (pf_llm_patch_v0.diff, see research/pf-plugins):
// - pf::PfPlan is the JSON plan contract shared with the runtime controller
//   prototype (research/pf-plugins/pf_controller.cpp).
// - pfPlansJson()/pfSetPlan() expose device facts + the current plan and let
//   Kotlin apply a plan before loadModel; structural knobs (kv types, flash
//   attn, batch, load mode, gpu layers, threads) are applied at context build;
//   thread counts are additionally applied live via llama_set_n_threads.
#include <android/log.h>
#include <jni.h>
#include <unistd.h>
#include <sys/auxv.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <functional>
#include <dirent.h>
#include <sstream>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "sampling.h"
#include "llama.h"
#include "ggml-backend.h"
#include "pf_plan.h"

#define LOG_TAG "PFLlamaJNI"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static const int DEFAULT_BATCH_SIZE = 512;
static const int OVERFLOW_HEADROOM = 4;

static llama_model              * g_model = nullptr;
static llama_context            * g_context = nullptr;
static llama_vocab const        * g_vocab = nullptr;
static common_batch               g_batch;
static common_chat_templates_ptr  g_chat_templates;
static common_sampler           * g_sampler = nullptr;

static int32_t                    g_n_ctx = 2048;
static int32_t                    g_n_batch = DEFAULT_BATCH_SIZE;
static llama_pos                  g_system_position = 0;
static llama_pos                  g_current_position = 0;
static llama_pos                  g_stop_position = 0;
static std::string                g_cached_token_chars;
// steering spec registered into the resident model at load time ("" = none);
// steering columns live on the llama_model instance, so this is status only.
static std::string                g_steer_spec_applied;

static std::atomic<bool>          g_cancel_flag{false};

// ring buffer of native logs (diagnostics only; no chat content is logged here)
static std::deque<std::string>    g_recent_logs;
static const size_t               MAX_LOG_LINES = 400;

static void push_log(const std::string & line) {
    g_recent_logs.push_back(line);
    while (g_recent_logs.size() > MAX_LOG_LINES) {
        g_recent_logs.pop_front();
    }
}

static void strip_ansi(std::string & s) {
    std::string out;
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] == '\x1B') {
            while (i < s.size() && !((s[i] >= 'a' && s[i] <= 'z') || (s[i] >= 'A' && s[i] <= 'Z'))) { ++i; }
        } else {
            out += s[i];
        }
    }
    s = out;
}

static void jni_log_callback(ggml_log_level level, const char * text, void * /*user*/) {
    if (text == nullptr) { return; }
    std::string line(text);
    strip_ansi(line);
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) { line.pop_back(); }
    if (line.empty()) { return; }
    push_log(line);
    const android_LogPriority prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
        : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_print(prio, LOG_TAG, "%s", line.c_str());
}

static bool is_valid_utf8(const char * string) {
    if (!string) { return true; }
    const auto * bytes = (const unsigned char *) string;
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) { num = 1; }
        else if ((*bytes & 0xE0) == 0xC0) { num = 2; }
        else if ((*bytes & 0xF0) == 0xE0) { num = 3; }
        else if ((*bytes & 0xF8) == 0xF0) { num = 4; }
        else { return false; }
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) { return false; }
            bytes += 1;
        }
    }
    return true;
}

static std::vector<common_chat_msg> & chat_msgs() {
    static std::vector<common_chat_msg> msgs;
    return msgs;
}

static void reset_chat_msgs() {
    chat_msgs().clear();
}

static std::string chat_append(const std::string & role, const std::string & content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs(), new_msg, role == "user", false);
    chat_msgs().push_back(new_msg);
    return formatted;
}

static void reset_long_term_states(const bool clear_kv = true) {
    if (clear_kv && g_context) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
    g_system_position = 0;
    g_current_position = 0;
    g_stop_position = 0;
    g_cached_token_chars.clear();
}

static std::string json_escape(const std::string & s) {
    std::ostringstream o;
    for (char c : s) {
        switch (c) {
            case '"': o << "\\\""; break;
            case '\\': o << "\\\\"; break;
            case '\n': o << "\\n"; break;
            case '\r': break;
            case '\t': o << "\\t"; break;
            default:
                if ((unsigned char) c < 0x20) { o << ' '; } else { o << c; }
        }
    }
    return o.str();
}

static std::vector<std::string> pf_backend_diag; // one line per load attempt, shown if load fails

static std::string g_pf_chosen_cpu_variant;  // last winning libggml-cpu loader name

/*
 * Loads backend libraries by build-time-known names.
 *
 * With extractNativeLibs=false packaging (AGP default for this build), the
 * app's nativeLibraryDir is EMPTY: backend .so entries live uncompressed/mapped
 * inside the APK and only the dynamic linker can reach them. System.loadLibrary
 * resolves bare sonames through the app's linker namespace, and so does
 * dlopen() from this library, so ggml_backend_load is given the bare
 * "lib<name>.so" form. Absolute paths are tried first so the same code also
 * works on devices that do extract libraries to nativeLibraryDir.
 *
 * CPU variants are tried from the most capable first; ggml rejects a variant
 * whose compiled-in CPU features the running device lacks (score check), so
 * iterating down the list always settles on the runable one.
 */
static const char * const PF_CPU_VARIANTS[] = {
    "libggml-cpu-android_armv9.2_2.so",
    "libggml-cpu-android_armv9.2_1.so",
    "libggml-cpu-android_armv9.0_1.so",
    "libggml-cpu-android_armv8.6_1.so",
    "libggml-cpu-android_armv8.2_2.so",
    "libggml-cpu-android_armv8.2_1.so",
    "libggml-cpu-android_armv8.0_1.so",
};

static bool pf_try_backend(const std::string & lib_dir, const char * name, const char * kind) {
    const size_t before = ggml_backend_reg_count();
    if (!lib_dir.empty()) {
        const std::string abs = lib_dir + "/" + name;
        if (access(abs.c_str(), R_OK) == 0) {
            ggml_backend_load(abs.c_str());
            if (ggml_backend_reg_count() > before) {
                LOGi("%s loaded from %s", kind, abs.c_str());
                pf_backend_diag.push_back(std::string(kind) + " loaded from " + abs);
                return true;
            }
        }
    }

    // bare soname: the app linker namespace resolves it inside the APK
    const size_t before_bare = ggml_backend_reg_count();
    ggml_backend_load(name);
    if (ggml_backend_reg_count() > before_bare) {
        LOGi("%s loaded from APK (bare soname %s)", kind, name);
        pf_backend_diag.push_back(std::string(kind) + " loaded from APK (bare soname)");
        return true;
    }

    LOGe("%s FAILED: no extractable file and bare-soname dlopen failed for %s", kind, name);
    pf_backend_diag.push_back(std::string(kind) + " FAILED (abs+bare): " + name);
    return false;
}

static std::string g_pf_lib_dir;
static bool g_pf_vulkan_loaded = false;

static void pf_ensure_vulkan_loaded() {
    if (g_pf_vulkan_loaded || g_pf_lib_dir.empty()) { return; }
    // Vulkan first so GPU devices exist before any model load.
    g_pf_vulkan_loaded = pf_try_backend(g_pf_lib_dir, "libggml-vulkan.so", "vulkan backend");
}

static void pf_load_backends(const char * lib_dir) {
    pf_backend_diag.clear();
    pf_backend_diag.push_back(std::string("lib dir: ") + (lib_dir ? lib_dir : "(null)"));
    pf_backend_diag.push_back(std::string("build: " __DATE__ " " __TIME__));
    if (lib_dir == nullptr || lib_dir[0] == '\0') {
        LOGe("pf_load_backends: no native lib dir supplied");
        pf_backend_diag.push_back("error: no native lib dir supplied by Kotlin caller");
        return;
    }
    const std::string lib_dir_s = lib_dir;
    g_pf_lib_dir = lib_dir_s;

    // GPU-offload disabled by default: libggml-vulkan.so is only dlopened lazily
    // (from loadModel) when the plan's gpuLayers requests GPU offload. With the
    // toggle off, the Vulkan device never registers, so the model weights can
    // not be silently placed into a Vulkan_Host buffer.
    for (const char * name : PF_CPU_VARIANTS) {
        if (pf_try_backend(lib_dir_s, name, ("cpu variant " + std::string(name)).c_str())) {
            g_pf_chosen_cpu_variant = name;
            break;
        }
    }

    std::ostringstream regs;
    for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
        if (i > 0) regs << ",";
        regs << ggml_backend_reg_name(ggml_backend_reg_get(i));
    }
    LOGi("Registered backends: %s", regs.str().c_str());
    pf_backend_diag.push_back("registered: " + regs.str());
}

// ---------------------------------------------------------------------------
// PF plan surface: device facts + plan state (see research/pf-plugins).
// ---------------------------------------------------------------------------

static pf::PfPlan g_pf_plan;                 // Kotlin-driven plan; defaults = engine defaults

static unsigned long long pf_read_number_file(const std::string & path) {
    FILE * f = fopen(path.c_str(), "r");
    if (!f) { return 0; }
    unsigned long long v = 0;
    if (fscanf(f, "%llu", &v) != 1) { v = 0; }
    fclose(f);
    return v;
}

// AT_HWCAP / AT_HWCAP2 bit masks for arm64 (linux/bionic; ggml's
// cpu-feats.cpp reads the same via ggml_feats_get_arch64_runtime).
namespace {
constexpr unsigned long long PF_HWCAP_ASIMDHP = 1ull << 10;      // fp16 vector
constexpr unsigned long long PF_HWCAP_ASIMDDP = 1ull << 20;      // dotprod
constexpr unsigned long long PF_HWCAP_SVE     = 1ull << 22;
constexpr unsigned long long PF_HWCAP2_SVE2   = 1ull << 1;
constexpr unsigned long long PF_HWCAP2_I8MM   = 1ull << 13;
constexpr unsigned long long PF_HWCAP2_SME    = 1ull << 23;
constexpr unsigned long long PF_HWCAP2_SME2   = 1ull << 37;
}

static pf::PfDeviceFacts pf_collect_device_facts() {
    pf::PfDeviceFacts facts;
    facts.backendName = g_pf_chosen_cpu_variant;
    if (g_pf_chosen_cpu_variant.size() > 30) {
        // strip libggml-cpu-android_<name>.so -> <name>
        facts.backendName = g_pf_chosen_cpu_variant.substr(20, g_pf_chosen_cpu_variant.size() - 23);
    }
    facts.nCoresOnline = (int) sysconf(_SC_NPROCESSORS_ONLN);
    for (int c = 0; c < facts.nCoresOnline; ++c) {
        char buf[160];
        pf::PfCpuCore core;
        core.id = c;
        snprintf(buf, sizeof(buf), "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_max_freq", c);
        core.maxKhz = (long long) pf_read_number_file(buf);
        snprintf(buf, sizeof(buf), "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_min_freq", c);
        core.minKhz = (long long) pf_read_number_file(buf);
        facts.cores.push_back(core);
    }
    facts.hwcap  = getauxval(AT_HWCAP);
    facts.hwcap2 = getauxval(AT_HWCAP2);
    facts.hasDotprod = (facts.hwcap & PF_HWCAP_ASIMDDP) != 0;
    facts.hasFp16    = (facts.hwcap & PF_HWCAP_ASIMDHP) != 0;
    facts.hasSve     = (facts.hwcap & PF_HWCAP_SVE) != 0;
    facts.hasSve2    = (facts.hwcap2 & PF_HWCAP2_SVE2) != 0;
    facts.hasI8mm    = (facts.hwcap2 & PF_HWCAP2_I8MM) != 0;
    facts.hasSme     = (facts.hwcap2 & PF_HWCAP2_SME) != 0;
    facts.hasSme2    = (facts.hwcap2 & PF_HWCAP2_SME2) != 0;
    facts.vulkanOk = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU) != nullptr
        || ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_IGPU) != nullptr;
    // MemAvailable (kB) from /proc/meminfo
    {
        FILE * f = fopen("/proc/meminfo", "r");
        if (f) {
            char line[512];
            while (fgets(line, sizeof(line), f)) {
                unsigned long long kb = 0;
                if (sscanf(line, "MemAvailable: %llu kB", &kb) == 1) {
                    facts.memAvailableBytes = kb * 1024ull;
                    break;
                }
            }
            fclose(f);
        }
    }
    facts.factsValid = facts.nCoresOnline > 0 && facts.memAvailableBytes > 0;
    return facts;
}

static enum llama_flash_attn_type pf_flash_attn_from_str(const std::string & s) {
    return s == "on" ? LLAMA_FLASH_ATTN_TYPE_ENABLED
         : s == "off" ? LLAMA_FLASH_ATTN_TYPE_DISABLED : LLAMA_FLASH_ATTN_TYPE_AUTO;
}

static enum ggml_type pf_kv_type_from_str(const std::string & s) {
    return s == "q8_0" ? GGML_TYPE_Q8_0
         : s == "q4_0" ? GGML_TYPE_Q4_0 : GGML_TYPE_F16;
}

// C ABI for the plan layer (JNI wrappers below are thin shells over these).
static std::string pf_engine_current_plan_json_impl() {
    // Steering status: appliedSpec is the spec registered into the resident
    // model ("" = steering off); the requested spec for the NEXT load is
    // currentPlan.steer.spec.
    std::string json = pf::plan_and_facts_json(g_pf_plan, pf_collect_device_facts());
    const size_t tail = json.rfind('}');
    if (tail != std::string::npos) {
        std::ostringstream steer;
        steer << ",\"steer\":{\"active\":" << (g_steer_spec_applied.empty() ? "false" : "true")
              << ",\"appliedSpec\":\"" << g_steer_spec_applied << "\"}";
        json.insert(tail, steer.str());
    }
    return json;
}

// Returns "" on success, error text otherwise. Structural knobs (kv types,
// flash attn, batch, load mode, gpu layers) take effect at the next context
// build; thread counts also apply live via llama_set_n_threads.
static std::string pf_engine_set_plan_impl(const char * json) {
    if (json == nullptr || json[0] == '\0') { return "pf_engine_set_plan: empty plan JSON"; }
    pf::PfPlan parsed;
    std::string err;
    if (!pf::parse_plan(json, &parsed, &err)) { return "pf_engine_set_plan: " + err; }
    g_pf_plan = parsed;
    if (g_context != nullptr && g_pf_plan.nThreads > 0) {
        const int hw_threads = (int) sysconf(_SC_NPROCESSORS_ONLN);
        const int t = std::min(g_pf_plan.nThreads, hw_threads);
        const int tb = std::min(g_pf_plan.nThreadsBatch > 0 ? g_pf_plan.nThreadsBatch : g_pf_plan.nThreads, hw_threads);
        llama_set_n_threads(g_context, t, tb);
        LOGi("pfSetPlan: live thread counts -> n_threads=%d n_threads_batch=%d", t, tb);
    }
    return "";
}

static std::string g_pf_plan_json_ret;

extern "C" const char * pf_engine_current_plan_json(void) {
    g_pf_plan_json_ret = pf_engine_current_plan_json_impl();
    return g_pf_plan_json_ret.c_str();
}

extern "C" const char * pf_engine_set_plan(const char * json) {
    static std::string ret;
    ret = pf_engine_set_plan_impl(json);
    return ret.c_str();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    llama_log_set(jni_log_callback, nullptr);

    const auto * path_to_backend = env->GetStringUTFChars(nativeLibDir, nullptr);
    LOGi("Loading backends from %s", path_to_backend);
    pf_load_backends(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    llama_backend_init();
    LOGi("Backend initialised");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_loadModel(
        JNIEnv *env, jobject /*unused*/,
        jstring jmodel_path, jint n_ctx, jint n_threads, jint n_gpu_layers, jint batch_size) {
    if (g_model != nullptr || g_context != nullptr) {
        return env->NewStringUTF("A model is already loaded; unload it first");
    }
    if (n_ctx < 128 || n_ctx > 131072) {
        return env->NewStringUTF("Context length out of supported range");
    }

    const int effective_ngl = g_pf_plan.nGpuLayers != -2 ? g_pf_plan.nGpuLayers : n_gpu_layers;

    if (effective_ngl > 0) {
        // GPU-offload toggle on: make sure the GPU backend is registered before
        // the model loader starts assigning bm buffers (deferred dlopen).
        pf_ensure_vulkan_loaded();
        if ( ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU) == nullptr
            && ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_IGPU) == nullptr) {
            return env->NewStringUTF("GPU offload requested but no GPU backend device is registered in this build");
        }
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = effective_ngl;
    if (effective_ngl == 0) {
        // No GPU layers: explicitly strip every GPU device from the candidate
        // list (equivalent of llama-bench -dev none). Otherwise a registered
        // Vulkan device still stays in the split scheduler and forces weights
        // into a Vulkan_Host buffer with a cross-backend graph split, which
        // measured 15x slower than pure CPU on the D9300+ / Mali-G720.
        // The array must outlive the model load call.
        static ggml_backend_dev_t pf_cpu_only_devices[1] = { nullptr };
        model_params.devices = pf_cpu_only_devices;
    }
    if (g_pf_plan.loadMode != "auto" && g_pf_plan.loadMode != "mmap") {
        const enum llama_load_mode lm = llama_load_mode_from_str(g_pf_plan.loadMode.c_str());
        if (lm != LLAMA_LOAD_MODE_AUTO && lm != LLAMA_LOAD_MODE_MMAP) { model_params.load_mode = lm; }
    }

    {
        // effective plan log: devices, gpu_layers, threads, n_ctx, n_batch,
        // n_ubatch, flash_attn, type_k/v, use_mmap, use_mlock + build flags.
        std::ostringstream dev_names;
        if (effective_ngl == 0) {
            dev_names << "none";
        } else {
            bool first = true;
            for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
                auto * reg = ggml_backend_reg_get(i);
                for (size_t d = 0; d < ggml_backend_reg_dev_count(reg); ++d) {
                    auto * dev = ggml_backend_reg_dev_get(reg, d);
                    const auto type = ggml_backend_dev_type(dev);
                    if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) { continue; }
                    if (!first) { dev_names << ","; }
                    dev_names << ggml_backend_dev_name(dev);
                    first = false;
                }
            }
            if (first) { dev_names << "none (auto, no GPU device registered)"; }
        }
        LOGi("PF effective plan: devices=[%s] gpu_layers=%d threads=%d n_ctx=%d n_batch=%d n_ubatch=%d flash_attn=%s type_k=%s type_v=%s load_mode=%d",
             dev_names.str().c_str(), effective_ngl,
             (g_pf_plan.nThreads > 0 ? g_pf_plan.nThreads : (n_threads > 0 ? n_threads : 0)),
             (int) n_ctx,
             (int) (g_pf_plan.nBatch > 0 ? g_pf_plan.nBatch : batch_size),
             (int) (g_pf_plan.nUbatch > 0 ? g_pf_plan.nUbatch : (g_pf_plan.nBatch > 0 ? g_pf_plan.nBatch : batch_size)),
             g_pf_plan.flashAttn.c_str(), g_pf_plan.kvTypeK.c_str(), g_pf_plan.kvTypeV.c_str(),
             (int) model_params.load_mode);
        LOGi("llama_print_system_info: %s", llama_print_system_info());
    }

    const auto * model_path = env->GetStringUTFChars(jmodel_path, nullptr);
    LOGi("Loading model (ctx=%d, threads=%d, gpu_layers=%d, batch=%d)",
         (int) n_ctx, (int) n_threads, (int) n_gpu_layers, (int) batch_size);
    // Qwen3.5 neuron steering is read from the environment once inside the
    // model load (qwen35 load_steering). Wrap the load call so the env var is
    // visible only to this load: state lands on the llama_model instance and
    // the next load starts clean regardless of the spec here.
    if (g_pf_plan.steerSpec.empty()) {
        unsetenv("LLAMA_QWEN35_STEER");
    } else {
        setenv("LLAMA_QWEN35_STEER", g_pf_plan.steerSpec.c_str(), 1);
        LOGi("Steering spec requested: %s", g_pf_plan.steerSpec.c_str());
    }
    auto * model = llama_model_load_from_file(model_path, model_params);
    unsetenv("LLAMA_QWEN35_STEER");
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        g_steer_spec_applied.clear();
        std::ostringstream error;
        if (ggml_backend_reg_count() == 0) {
            error << "No inference backend is registered; backend load report:\n";
            for (const std::string & line : pf_backend_diag) {
                error << "  " << line << "\n";
            }
        } else {
            error << "Model load failed (corrupted file, unsupported architecture, or out of memory); see engine logs";
        }
        LOGe("%s", error.str().c_str());
        return env->NewStringUTF(error.str().c_str());
    }
    g_model = model;
    g_steer_spec_applied = g_pf_plan.steerSpec;
    g_vocab = llama_model_get_vocab(model);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    const int arg_batch = batch_size > 0 ? batch_size : DEFAULT_BATCH_SIZE;
    ctx_params.n_batch = g_pf_plan.nBatch > 0 ? g_pf_plan.nBatch : arg_batch;
    ctx_params.n_ubatch = g_pf_plan.nUbatch > 0 ? g_pf_plan.nUbatch
                        : g_pf_plan.nBatch > 0 ? g_pf_plan.nBatch : arg_batch;
    const int hw_threads = (int) sysconf(_SC_NPROCESSORS_ONLN);
    // Defaults tuned on Dimensity 9300+ (8 all-big cores): 6 threads saturates
    // memory bandwidth for decode without the all-core collapse seen at 8.
    // On 6-core phones this stays 4 as before; 4-core phones use 2. A plan set
    // via pfSetPlan overrides each of these knobs.
    const int arg_threads = n_threads > 0 && n_threads <= hw_threads
        ? n_threads : std::max(1, std::min(6, hw_threads - 2));
    const int decode_threads = g_pf_plan.nThreads > 0 ? g_pf_plan.nThreads : arg_threads;
    const int batch_threads = g_pf_plan.nThreadsBatch > 0 ? g_pf_plan.nThreadsBatch : decode_threads;
    ctx_params.n_threads = decode_threads;
    ctx_params.n_threads_batch = batch_threads;
    ctx_params.flash_attn_type = pf_flash_attn_from_str(g_pf_plan.flashAttn);
    ctx_params.type_k = pf_kv_type_from_str(g_pf_plan.kvTypeK);
    ctx_params.type_v = pf_kv_type_from_str(g_pf_plan.kvTypeV);
    if (!g_pf_plan.cpuVariant.empty()) {
        LOGi("Plan requests cpuVariant %s (engine loads most-capable first; variant pinning needs loader support, ignoring override)", g_pf_plan.cpuVariant.c_str());
    }
    if (g_pf_plan.maxHeapBytes > 0) {
        LOGi("Plan heap budget %lld bytes (advisory, not enforced by llama.cpp in v0)", (long long) g_pf_plan.maxHeapBytes);
    }
    auto * context = llama_init_from_model(model, ctx_params);
    if (context == nullptr) {
        llama_model_free(model);
        g_model = nullptr;
        g_vocab = nullptr;
        g_steer_spec_applied.clear();
        return env->NewStringUTF("Failed to create inference context (likely out of memory for KV cache)");
    }
    g_context = context;
    g_n_ctx = n_ctx;
    g_n_batch = (int) ctx_params.n_batch;

    g_batch = common_batch(context);
    g_chat_templates = common_chat_templates_init(model, "");
    reset_chat_msgs();
    reset_long_term_states(false);
    g_cancel_flag.store(false);
    LOGi("Model ready: ctx=%d batch=%d ubatch=%d threads=%d batch_threads=%d kv=%s/%s fa=%s steer=%s",
         (int) llama_n_ctx(context), (int) ctx_params.n_batch, (int) ctx_params.n_ubatch,
         (int) decode_threads, (int) batch_threads,
         g_pf_plan.kvTypeK.c_str(), g_pf_plan.kvTypeV.c_str(), g_pf_plan.flashAttn.c_str(),
         (g_steer_spec_applied.empty() ? "off" : g_steer_spec_applied.c_str()));
    return nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_unloadModel(JNIEnv * /*env*/, jobject /*unused*/) {
    common_sampler_free(g_sampler);
    g_sampler = nullptr;
    g_chat_templates.reset();
    if (g_context) { llama_free(g_context); }
    if (g_model) { llama_model_free(g_model); }
    g_context = nullptr;
    g_model = nullptr;
    g_vocab = nullptr;
    g_steer_spec_applied.clear();
    reset_chat_msgs();
    reset_long_term_states(false);
    g_batch = common_batch();
    LOGi("Model unloaded");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_setSampler(JNIEnv *env, jobject /*unused*/,
        jfloat temperature, jint top_k, jfloat top_p, jlong seed) {
    if (!g_model) { return env->NewStringUTF("No model loaded"); }
    common_sampler_free(g_sampler);
    common_params_sampling sparams;
    sparams.temp = temperature;
    sparams.top_k = top_k;
    sparams.top_p = top_p;
    sparams.seed = (uint32_t) seed;
    g_sampler = common_sampler_init(g_model, sparams);
    if (!g_sampler) { return env->NewStringUTF("Sampler initialisation failed"); }
    return nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_resetSession(JNIEnv * /*env*/, jobject /*unused*/) {
    reset_long_term_states(true);
    reset_chat_msgs();
    g_cached_token_chars.clear();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_finishTurn(JNIEnv *env, jobject /*unused*/, jstring jtext) {
    if (!g_model) { return; }
    const auto * text = env->GetStringUTFChars(jtext, nullptr);
    chat_append("assistant", text);
    env->ReleaseStringUTFChars(jtext, text);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_addHistoryMessage(JNIEnv *env, jobject /*unused*/,
        jstring jrole, jstring jcontent) {
    if (!g_model) { return 1; }
    const auto * role = env->GetStringUTFChars(jrole, nullptr);
    const auto * content = env->GetStringUTFChars(jcontent, nullptr);
    chat_append(role, content);
    env->ReleaseStringUTFChars(jrole, role);
    env->ReleaseStringUTFChars(jcontent, content);
    return 0;
}

static int decode_tokens_in_batches(
        llama_context * context,
        common_batch & batch,
        const llama_tokens & tokens,
        const llama_pos start_pos,
        const bool compute_last_logit) {
    for (int i = 0; i < (int) tokens.size(); i += g_n_batch) {
        const int cur_batch_size = std::min((int) tokens.size() - i, g_n_batch);
        batch.clear();
        if (start_pos + i + cur_batch_size >= g_n_ctx - OVERFLOW_HEADROOM) {
            LOGe("Context overflow during prompt processing (pos %d, ctx %d)", start_pos + i + cur_batch_size, g_n_ctx);
            return 2;
        }
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            batch.add(token_id, start_pos + i + j, 0,
                      compute_last_logit && (i + j == (int) tokens.size() - 1));
        }
        const int rc = llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (rc != 0) {
            LOGe("llama_process failed with %d", rc);
            return 1;
        }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_processSystemPrompt(JNIEnv *env, jobject /*unused*/, jstring jsystem_prompt) {
    if (!g_model || !g_context) { return 3; }
    reset_long_term_states(true);
    reset_chat_msgs();

    const auto * system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    std::string formatted(system_prompt);
    const bool has_chat_template = g_chat_templates && common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted = chat_append("system", system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    const auto tokens = common_tokenize(g_context, formatted, has_chat_template, has_chat_template);
    if ((int) tokens.size() + OVERFLOW_HEADROOM >= g_n_ctx) {
        LOGe("System prompt too long for context: %d tokens vs ctx %d", (int) tokens.size(), g_n_ctx);
        return 1;
    }
    if (int rc = decode_tokens_in_batches(g_context, g_batch, tokens, g_current_position, false)) {
        return rc;
    }
    g_system_position = g_current_position = (int) tokens.size();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_processUserPrompt(JNIEnv *env, jobject /*unused*/,
        jstring juser_prompt, jint n_predict) {
    if (!g_model || !g_context) { return 3; }
    g_cached_token_chars.clear();
    llama_perf_context_reset(g_context);

    const auto * user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    std::string formatted(user_prompt);
    const bool has_chat_template = g_chat_templates && common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted = chat_append("user", user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    auto user_tokens = common_tokenize(g_context, formatted, has_chat_template, has_chat_template);
    const int user_size = (int) user_tokens.size();
    const int max_fit = g_n_ctx - OVERFLOW_HEADROOM;
    if (user_size > max_fit) {
        user_tokens.resize(max_fit);
        LOGe("Prompt too long: %d tokens exceed context %d; truncating", user_size, g_n_ctx);
    }
    if (int rc = decode_tokens_in_batches(g_context, g_batch, user_tokens, g_current_position, true)) {
        return rc;
    }
    g_current_position += (int) user_tokens.size();
    g_stop_position = g_current_position + n_predict;
    if (g_stop_position >= g_n_ctx) {
        g_stop_position = g_n_ctx - OVERFLOW_HEADROOM;
    }
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_setStopFlag(JNIEnv * /*env*/, jobject /*unused*/) {
    g_cancel_flag.store(true);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_clearStopFlag(JNIEnv * /*env*/, jobject /*unused*/) {
    g_cancel_flag.store(false);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_generateNextToken(JNIEnv *env, jobject /*unused*/) {
    if (!g_model || !g_context || !g_sampler) { return nullptr; }

    if (g_current_position >= g_n_ctx - OVERFLOW_HEADROOM) {
        // shift half the KV cache (between system prompt and current position) to make room
        const int n_discard = (g_current_position - g_system_position) / 2;
        if (n_discard > 0) {
            llama_memory_seq_rm(llama_get_memory(g_context), 0, g_system_position, g_system_position + n_discard);
            llama_memory_seq_add(llama_get_memory(g_context), 0, g_system_position + n_discard, g_current_position, -n_discard);
            g_current_position -= n_discard;
            LOGi("Context shift: discarded %d tokens", n_discard);
        }
    }

    if (g_cancel_flag.load()) {
        LOGi("Generation cancelled by user");
        return nullptr;
    }
    if (g_current_position >= g_stop_position) {
        return nullptr;
    }

    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    g_batch.clear();
    g_batch.add(new_token_id, g_current_position, 0, true);
    if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
        LOGe("decode failed for generated token");
        return nullptr;
    }
    g_current_position++;

    if (llama_vocab_is_eog(g_vocab, new_token_id)) {
        return nullptr;
    }

    const auto piece = common_token_to_piece(g_context, new_token_id);
    g_cached_token_chars += piece;

    if (is_valid_utf8(g_cached_token_chars.c_str())) {
        jstring result = env->NewStringUTF(g_cached_token_chars.c_str());
        g_cached_token_chars.clear();
        return result;
    }
    return env->NewStringUTF("");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_modelInfoJson(JNIEnv *env, jobject /*unused*/) {
    if (!g_model || !g_context) { return env->NewStringUTF("{}"); }
    char desc[192];
    llama_model_desc(g_model, desc, sizeof(desc));
    std::ostringstream json;
    json << "{"
         << "\"desc\":\"" << json_escape(desc) << "\","
         << "\"sizeBytes\":" << (long long) llama_model_size(g_model) << ","
         << "\"nParams\":" << (long long) llama_model_n_params(g_model) << ","
         << "\"nCtxTrain\":" << (long long) llama_model_n_ctx_train(g_model) << ","
         << "\"nCtx\":" << (long long) llama_n_ctx(g_context)
         << "}";
    return env->NewStringUTF(json.str().c_str());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_contextStateSize(JNIEnv * /*env*/, jobject /*unused*/) {
    if (!g_context) { return 0; }
    return (jlong) llama_state_get_size(g_context);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_perfJson(JNIEnv *env, jobject /*unused*/) {
    if (!g_context) { return env->NewStringUTF("{}"); }
    auto perf = llama_perf_context(g_context);
    std::ostringstream json;
    json << "{"
         << "\"nPromptTokens\":" << perf.n_p_eval << ","
         << "\"tPromptMs\":" << (long long) perf.t_p_eval_ms << ","
         << "\"nGenTokens\":" << perf.n_eval << ","
         << "\"tGenMs\":" << (long long) perf.t_eval_ms
         << "}";
    return env->NewStringUTF(json.str().c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_listBackendsJson(JNIEnv *env, jobject /*unused*/) {
    std::ostringstream json;
    json << "{\"devices\":[";
    const size_t n_reg = ggml_backend_reg_count();
    bool first = true;
    for (size_t i = 0; i < n_reg; i++) {
        auto * reg = ggml_backend_reg_get(i);
        const size_t n_dev = ggml_backend_reg_dev_count(reg);
        for (size_t d = 0; d < n_dev; d++) {
            auto * dev = ggml_backend_reg_dev_get(reg, d);
            size_t free_bytes = 0, total_bytes = 0;
            ggml_backend_dev_memory(dev, &free_bytes, &total_bytes);
            const auto type = ggml_backend_dev_type(dev);
            // Mali-G720 on this device reports as IGPU (unified memory); report both
            // GPU variants as GPU for the app UI and probe selection logic.
            const char * type_name = type == GGML_BACKEND_DEVICE_TYPE_GPU ||
                                     type == GGML_BACKEND_DEVICE_TYPE_IGPU ? "GPU"
                : type == GGML_BACKEND_DEVICE_TYPE_ACCEL ? "ACCEL" : "CPU";
            if (!first) { json << ","; }
            first = false;
            json << "{"
                 << "\"backend\":\"" << json_escape(ggml_backend_reg_name(reg)) << "\","
                 << "\"name\":\"" << json_escape(ggml_backend_dev_name(dev)) << "\","
                 << "\"description\":\"" << json_escape(ggml_backend_dev_description(dev)) << "\","
                 << "\"type\":\"" << type_name << "\","
                 << "\"freeBytes\":" << (long long) free_bytes << ","
                 << "\"totalBytes\":" << (long long) total_bytes
                 << "}";
        }
    }
    json << "]}";
    return env->NewStringUTF(json.str().c_str());
}

// PF plan surface JNI: Kotlin plan layer (com.pocketforge.ai.inference.PfPlans)
// reads facts + current plan, decides a plan (mirrors pf_controller.cpp logic or
// downloads/pins one), and applies it before loadModel. pfSetPlan returns null
// on success or an error string (matching loadModel's convention).

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_pfPlansJson(JNIEnv *env, jobject /*unused*/) {
    try {
        return env->NewStringUTF(pf_engine_current_plan_json_impl().c_str());
    } catch (...) {
        return env->NewStringUTF("{\"error\":\"pfPlansJson failed\"}");
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_pfSetPlan(JNIEnv *env, jobject /*unused*/, jstring jplan) {
    if (!jplan) { return env->NewStringUTF("pfSetPlan: null plan JSON"); }
    const auto * plan = env->GetStringUTFChars(jplan, nullptr);
    const std::string err = pf_engine_set_plan_impl(plan);
    env->ReleaseStringUTFChars(jplan, plan);
    return err.empty() ? nullptr : env->NewStringUTF(err.c_str());
}

// ---------------------------------------------------------------------------
// GPU probe: for each GPU backend device run small mul_mat graphs in
// f32 / f16 / q4_K x f32 configurations, compute the identical graph on the
// CPU backend, and compare results. Steps recorded per data type:
// ENUMERATED -> INITIALIZED -> COMPUTED -> RESULT_MATCHED.
// ---------------------------------------------------------------------------

struct ProbeOutcome {
    bool ok = false;
    const char * failed_step = "NONE";
    std::string detail;
};

static ProbeOutcome probe_mul_mat(ggml_backend_dev_t dev, ggml_type dtype, const char * dtype_name) {
    ProbeOutcome out;

    auto * gpu = ggml_backend_dev_init(dev, nullptr);
    if (!gpu) {
        out.failed_step = "INITIALIZED";
        out.detail = std::string(dtype_name) + ": backend init failed";
        return out;
    }
    auto * cpu_dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    auto * cpu = cpu_dev ? ggml_backend_dev_init(cpu_dev, nullptr) : nullptr;
    if (!cpu) {
        ggml_backend_free(gpu);
        out.failed_step = "INITIALIZED";
        out.detail = std::string(dtype_name) + ": CPU reference backend init failed";
        return out;
    }

    const int n = 128, k = 128, m = 128;
    std::vector<float> a_f32(n * k), b_f32(k * m);
    for (int i = 0; i < n * k; i++) { a_f32[i] = (float) ((i * 37 % 97) / 97.0) - 0.5f; }
    for (int i = 0; i < k * m; i++) { b_f32[i] = (float) ((i * 53 % 89) / 89.0) - 0.5f; }

    const ggml_type a_type = dtype; // A holds the data type under test
    const ggml_type b_type = dtype == GGML_TYPE_Q4_K ? GGML_TYPE_F32 : dtype;

    auto setup_and_run = [&](ggml_backend_t backend, std::vector<float> & out_result) -> bool {
        struct ggml_init_params p;
        p.mem_size = ggml_tensor_overhead() * 16 + 1024;
        p.mem_buffer = nullptr;
        p.no_alloc = true;
        struct ggml_context * ctx = ggml_init(p);
        if (!ctx) { return false; }

        struct ggml_tensor * ta = ggml_new_tensor_2d(ctx, a_type, n, k);
        struct ggml_tensor * tb = ggml_new_tensor_2d(ctx, b_type, k, m);
        struct ggml_tensor * tc = ggml_mul_mat(ctx, ta, tb);

        auto * buft = ggml_backend_get_default_buffer_type(backend);
        auto * buffer = ggml_backend_alloc_ctx_tensors_from_buft(ctx, buft);
        bool rc = false;
        if (buffer) {
            if (a_type == GGML_TYPE_F32 || a_type == GGML_TYPE_F16) {
                if (a_type == GGML_TYPE_F32) {
                    ggml_backend_tensor_set(ta, a_f32.data(), 0, a_f32.size() * sizeof(float));
                } else {
                    std::vector<ggml_fp16_t> a_h(a_f32.size());
                    for (size_t i = 0; i < a_f32.size(); i++) { a_h[i] = ggml_fp32_to_fp16(a_f32[i]); }
                    ggml_backend_tensor_set(ta, a_h.data(), 0, a_h.size() * sizeof(ggml_fp16_t));
                }
                if (b_type == GGML_TYPE_F32) {
                    ggml_backend_tensor_set(tb, b_f32.data(), 0, b_f32.size() * sizeof(float));
                } else {
                    std::vector<ggml_fp16_t> b_h(b_f32.size());
                    for (size_t i = 0; i < b_f32.size(); i++) { b_h[i] = ggml_fp32_to_fp16(b_f32[i]); }
                    ggml_backend_tensor_set(tb, b_h.data(), 0, b_h.size() * sizeof(ggml_fp16_t));
                }
            } else if (a_type == GGML_TYPE_Q4_K) {
                std::vector<uint8_t> a_q(ggml_nbytes(ta));
                ggml_quantize_chunk(GGML_TYPE_Q4_K, a_f32.data(), a_q.data(),
                                    0, k, n, nullptr);
                ggml_backend_tensor_set(ta, a_q.data(), 0, a_q.size());
                ggml_backend_tensor_set(tb, b_f32.data(), 0, b_f32.size() * sizeof(float));
            }

            struct ggml_cgraph * graph = ggml_new_graph(ctx);
            ggml_build_forward_expand(graph, tc);
            if (ggml_backend_graph_compute(backend, graph) != GGML_STATUS_SUCCESS) {
                out_result.clear();
            } else {
                out_result.resize(n * m);
                ggml_backend_tensor_get(tc, out_result.data(), 0, out_result.size() * sizeof(float));
                rc = !out_result.empty();
            }
            ggml_backend_buffer_free(buffer);
        }
        ggml_free(ctx);
        return rc;
    };

    std::vector<float> cpu_result, gpu_result;
    if (!setup_and_run(cpu, cpu_result)) {
        out.failed_step = "COMPUTED";
        out.detail = std::string(dtype_name) + ": CPU reference compute failed";
    } else if (!setup_and_run(gpu, gpu_result)) {
        out.failed_step = "COMPUTED";
        out.detail = std::string(dtype_name) + ": device graph compute failed (op not supported?)";
    } else {
        double max_diff = 0.0;
        for (size_t i = 0; i < cpu_result.size(); i++) {
            max_diff = std::max(max_diff, (double) std::fabs(cpu_result[i] - gpu_result[i]));
        }
        const double tol = dtype == GGML_TYPE_F32 ? 1e-2
            : dtype == GGML_TYPE_F16 ? 5e-2 : 3e-1;
        if (max_diff <= tol) {
            out.ok = true;
            out.detail = dtype_name;
        } else {
            out.failed_step = "RESULT_MATCHED";
            out.detail = std::string(dtype_name) + ": max diff " + std::to_string(max_diff);
            LOGw("%s", out.detail.c_str());
        }
    }

    ggml_backend_free(gpu);
    ggml_backend_free(cpu);
    ggml_quantize_free();
    return out;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_probeBackendJson(JNIEnv *env, jobject /*unused*/, jstring jdevice) {
    const char * name = "";
    if (jdevice) {
        name = env->GetStringUTFChars(jdevice, nullptr);
    }

    ggml_backend_dev_t dev = nullptr;
    if (strlen(name)) {
        dev = ggml_backend_dev_by_name(name);
    } else {
        dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU);
        if (!dev) {
            dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_IGPU);
        }
    }

    std::ostringstream results, summary;
    bool overall = false, first = true;
    results << "[";
    size_t free_bytes = 0, total_bytes = 0;
    const char * dev_desc = "";
    const char * dev_name = "";
    if (dev) {
        dev_name = ggml_backend_dev_name(dev);
        dev_desc = ggml_backend_dev_description(dev);
        ggml_backend_dev_memory(dev, &free_bytes, &total_bytes);
        const ggml_type types[] = { GGML_TYPE_F32, GGML_TYPE_F16, GGML_TYPE_Q4_K };
        const char * names[] = { "f32", "f16", "Q4_K" };
        for (int i = 0; i < 3; i++) {
            auto r = probe_mul_mat(dev, types[i], names[i]);
            if (r.ok) { overall = true; }
            if (!first) { results << ","; }
            first = false;
            results << "{"
                    << "\"device\":\"" << json_escape(dev_name) << "\","
                    << "\"dataType\":\"" << names[i] << "\","
                    << "\"ok\":" << (r.ok ? "true" : "false") << ","
                    << "\"failedStep\":\"" << r.failed_step << "\","
                    << "\"detail\":\"" << json_escape(r.detail) << "\""
                    << "}";
        }
    } else {
        results << "{"
                << "\"device\":\"none\","
                << "\"dataType\":\"none\","
                << "\"ok\":false,"
                << "\"failedStep\":\"ENUMERATED\","
                << "\"detail\":\"no GPU backend device registered in this build (CPU-only build or backend failed to load)\""
                << "}";
    }
    results << "]";
    if (jdevice) {
        env->ReleaseStringUTFChars(jdevice, name);
    }

    std::ostringstream json;
    json << "{"
         << "\"devices\":"
         << "{\"device\":\"" << json_escape(dev_name) << "\","
         << "\"description\":\"" << json_escape(dev_desc) << "\","
         << "\"type\":\"GPU\","
         << "\"freeBytes\":" << (long long) free_bytes << ","
         << "\"totalBytes\":" << (long long) total_bytes << "},"
         << "\"results\":" << results.str() << ","
         << "\"overallOk\":" << (overall ? "true" : "false") << ","
         << "\"summary\":\"" << json_escape(overall ? "GPU math probe passed at least one data type"
             : "GPU probe failed; inference will run on CPU") << "\""
         << "}";
    return env->NewStringUTF(json.str().c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_getRecentLogs(JNIEnv *env, jobject /*unused*/) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize) g_recent_logs.size(), stringClass, nullptr);
    jsize i = 0;
    for (const auto & line : g_recent_logs) {
        env->SetObjectArrayElement(arr, i++, env->NewStringUTF(line.c_str()));
    }
    return arr;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_pocketforge_ai_inference_LlamaBridge_shutdown(JNIEnv * /*env*/, jobject /*unused*/) {
    llama_backend_free();
}
