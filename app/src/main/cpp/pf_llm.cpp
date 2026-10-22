// pf_llm.cpp - JNI layer for PocketForge AI, adapted from the upstream
// examples/llama.android implementation of the pinned llama.cpp revision.
//
// Ownership and threading:
// - A single model/context/sampler set is owned globally. All calls come from
//   Kotlin's single-threaded engine dispatcher except setStopFlag (relaxed atomic).
// - Kotlin owns the accumulated assistant turn text; finishTurn records it in
//   the chat-template history. Native code never logs prompt/response content.
#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cmath>
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

    // Vulkan first so GPU devices exist before any model load.
    pf_try_backend(lib_dir_s, "libggml-vulkan.so", "vulkan backend");

    for (const char * name : PF_CPU_VARIANTS) {
        if (pf_try_backend(lib_dir_s, name, ("cpu variant " + std::string(name)).c_str())) {
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

    if (n_gpu_layers > 0 && ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU) == nullptr
        && ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_IGPU) == nullptr) {
        return env->NewStringUTF("GPU offload requested but no GPU backend device is registered in this build");
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = n_gpu_layers;

    const auto * model_path = env->GetStringUTFChars(jmodel_path, nullptr);
    LOGi("Loading model (ctx=%d, threads=%d, gpu_layers=%d, batch=%d)",
         (int) n_ctx, (int) n_threads, (int) n_gpu_layers, (int) batch_size);
    auto * model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
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
    g_vocab = llama_model_get_vocab(model);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = batch_size > 0 ? batch_size : DEFAULT_BATCH_SIZE;
    ctx_params.n_ubatch = batch_size > 0 ? batch_size : DEFAULT_BATCH_SIZE;
    const int hw_threads = (int) sysconf(_SC_NPROCESSORS_ONLN);
    const int threads = n_threads > 0 && n_threads <= hw_threads
        ? n_threads : std::max(1, std::min(4, hw_threads - 2));
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;
    auto * context = llama_init_from_model(model, ctx_params);
    if (context == nullptr) {
        llama_model_free(model);
        g_model = nullptr;
        g_vocab = nullptr;
        return env->NewStringUTF("Failed to create inference context (likely out of memory for KV cache)");
    }
    g_context = context;
    g_n_ctx = n_ctx;
    g_n_batch = batch_size > 0 ? batch_size : DEFAULT_BATCH_SIZE;

    g_batch = common_batch(context);
    g_chat_templates = common_chat_templates_init(model, "");
    reset_chat_msgs();
    reset_long_term_states(false);
    g_cancel_flag.store(false);
    LOGi("Model ready: ctx=%d batch=%d threads=%d", (int) llama_n_ctx(context), g_n_batch, threads);
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
