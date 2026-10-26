// pf_plan.h - PF engine plan contract, schema name "pf_plan_v1" (pfPlanVersion 2).
//
// Plain C++17, zero dependencies, no llama.cpp / NDK / Android headers so this
// compiles anywhere (host MSVC/clang/gcc, and the NDK arm64 build).
//
// Consumers:
// - pf_controller.cpp / pf_controller_test.cpp (standalone, host-tested)
// - pf_llm.cpp via the patch (adds pf_plan.cpp to the app CMake target and
//   exposes pfPlansJson()/pfSetPlan() JNI wrappers over this module)
//
// JSON contract (schema pf_plan_v1; module output and accepted input are the
// same shape; all on one line = LINE-JSON, one plan per log line):
// {
//   "pfPlanVersion":2, "planId":"x200fe-default",
//   "cpu": {"cpuVariant":"armv9.0_1","nThreads":4,"nThreadsBatch":6},
//   "ctx": {"nCtx":4096},
//   "kv":  {"typeK":"f16","typeV":"f16","flashAttn":0},
//   "batch":{"nBatch":512,"nUbatch":256},
//   "split":{"nGpuLayers":-2,"loadMode":"mmap","mlock":0},
//   "rate":{"decodeRate":0.0,"prefillRate":0.0},
//   "heap":{"maxHeapBytes":0}
// }
// Semantics:
// - -1 / "" / 0 rate = engine default / unmeasured for their fields.
// - nGpuLayers -2 = defer to the caller's loadModel argument, -1 = all GPU.
// - flashAttn is the llama.cpp flash_attn_type gate as 0|1 (v0's auto/on/off
//   dropped: explicit off/on only; absence = off).
// - cpuVariant MUST be one of the 7 variants actually shipped in APP
//   jniLibs/arm64-v8a (libggml-cpu-android_<name>.so):
//   armv8.0_1 armv8.2_1 armv8.2_2 armv8.6_1 armv9.0_1 armv9.2_1 armv9.2_2
//   ("" = engine default order, most-capable-first per pf_load_backends).
// - loadMode maps to llama.cpp 0.7.0 load_mode enum (mmap/direct_io/none/auto);
//   mlock is a separate boolean knob (v0 folded it into loadMode).
// - rate.decodeRate / rate.prefillRate are measured tok/s filled in by the
//   bench layer after each run so the planner can rank stored plans.
//
// Sanity rules (enforced in validate_plan_rules / on parse):
// - kv q4_0 + flashAttn=1 is an INVALID pairing (llama.cpp FA has no q4_0 KV).
// - nUbatch <= nBatch when both explicit.
// - cpuVariant, kvTypeK/V, loadMode values must be in their enums.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace pf {

struct PfCpuCore {
    int id = 0;
    long long minKhz = 0;
    long long maxKhz = 0;
};

// Device facts as gathered at runtime on device (sysfs/meminfo/getauxval in the
// patched pf_llm.cpp); stubbed to static values in the standalone controller.
struct PfDeviceFacts {
    std::string backendName;        // chosen libggml-cpu variant, "" = not probed
    int nCoresOnline = 0;
    std::vector<PfCpuCore> cores;
    unsigned long long hwcap = 0;
    unsigned long long hwcap2 = 0;
    bool hasDotprod = false;
    bool hasFp16 = false;
    bool hasI8mm = false;
    bool hasSve = false;
    bool hasSve2 = false;
    bool hasSme = false;
    bool hasSme2 = false;
    bool vulkanOk = false;
    unsigned long long memAvailableBytes = 0;
    bool factsValid = false;
};

struct PfPlan {
    int planVersion = 2;            // pf_plan_v1 schema
    std::string planId;             // stable id; "" = auto ("pf-auto")
    // cpu
    std::string cpuVariant;         // one of the 7 shipped variants; "" = engine default
    int nThreads = -1;              // -1 = engine clamp
    int nThreadsBatch = -1;         // -1 = nThreads
    // context
    int nCtx = -1;                  // -1 = engine default
    // kv cache
    std::string kvTypeK = "f16";    // "f16" | "q8_0" | "q4_0"
    std::string kvTypeV = "f16";
    int flashAttn = 0;              // 0 = off, 1 = on (llama.cpp flash_attn gate)
    // batching
    int nBatch = -1;
    int nUbatch = -1;               // -1 = nBatch (current pf_llm behaviour)
    // split / load
    int nGpuLayers = -2;            // -2 = defer to loadModel arg; -1 = all GPU
    std::string loadMode = "auto";  // "auto"|"mmap"|"direct_io"|"none"
    bool mlock = false;             // llama.cpp mlock knob (v0 folded this into loadMode)
    // measured profile rates (tok/s), 0 = unmeasured; planner ranks on these
    float decodeRate = 0.0f;
    float prefillRate = 0.0f;
    // advisory heap budget for the plan layer (0 = engine decides)
    long long maxHeapBytes = 0;
};

// The 7 CPU variants actually produced/shipped in APP jniLibs (B2 audit §2).
extern const char * const k_cpu_variants[7];
extern const char * const k_kv_types[3];
extern const char * const k_load_modes[4];

bool parse_plan(const std::string & json, PfPlan * out, std::string * err);
std::string serialize_plan(const PfPlan & plan);
std::string serialize_facts(const PfDeviceFacts & facts);
std::string plan_and_facts_json(const PfPlan & plan, const PfDeviceFacts & facts);
bool valid_cpu_variant(const std::string & v);

// Sanity rule checker beyond enum/parse: e.g. q4_0 KV + flashAttn=1 -> false.
bool validate_plan_rules(const PfPlan & p, std::string * err);

} // namespace pf
