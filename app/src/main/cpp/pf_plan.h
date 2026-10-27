// pf_plan.h - PF engine plan v0 (shared contract).
//
// Plain C++17, zero dependencies, no llama.cpp / NDK / Android headers so this
// compiles anywhere (host MSVC/clang/gcc, and the NDK arm64 build).
//
// Consumers:
// - pf_controller.cpp / pf_controller_test.cpp (standalone, host-tested)
// - pf_llm.cpp via pf_llm_patch_v0.diff (adds pf_plan.cpp to the app CMake
//   target and exposes pfPlansJson()/pfSetPlan() JNI wrappers over this module)
//
// JSON contract (module output and accepted input are the same shape):
// {
//   "pfPlanVersion": 1,
//   "cpu":  { "cpuVariant":"", "nThreads":4, "nThreadsBatch":6 },
//   "kv":   { "typeK":"q8_0", "typeV":"q8_0", "flashAttn":"off" },
//   "batch":{ "nBatch":512, "nUbatch":256 },
//   "split":{ "nGpuLayers":-2, "loadMode":"mmap" },
//   "heap": { "maxHeapBytes":0 }
// }
// -1 / "" mean "engine default" for their fields; nGpuLayers -2 = defer to the
// caller's loadModel argument.
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
    int planVersion = 1;
    // cpu
    std::string cpuVariant;         // "" = engine default order (most-capable first)
    int nThreads = -1;              // -1 = engine clamp
    int nThreadsBatch = -1;         // -1 = nThreads
    // kv cache; q8_0 K+V is the default (halves KV RAM, ~+6-18% decode; see
    // research/distributed/09-kv-economics.md). "f16" stays opt-out.
    std::string kvTypeK = "q8_0";   // "f16" | "q8_0" | "q4_0"
    std::string kvTypeV = "q8_0";
    std::string flashAttn = "auto"; // "auto" | "on" | "off"
    // batching
    int nBatch = -1;
    int nUbatch = -1;               // -1 = nBatch (current pf_llm behaviour)
    // split / load
    int nGpuLayers = -2;            // -2 = defer to the caller's loadModel argument; -1 = all GPU
    std::string loadMode = "auto";  // "auto"|"mmap"|"mlock"|"mmap_mlock"|"direct_io"|"none"
    // Qwen3.5 neuron steering, "" = disabled. Spec grammar:
    // "<layer>:<neuron>:<dir>[:<dose>][,...]", dir is +1|-1, dose >= 0 (default 1).
    // Parsed once per model load (qwen35 load_steering); invalid specs fail the
    // load with an error in the engine logs. Non-qwen35 models ignore it.
    std::string steerSpec;
    // advisory heap budget for the plan layer (0 = engine decides)
    long long maxHeapBytes = 0;
};

bool parse_plan(const std::string & json, PfPlan * out, std::string * err);
std::string serialize_plan(const PfPlan & plan);
std::string serialize_facts(const PfDeviceFacts & facts);
std::string plan_and_facts_json(const PfPlan & plan, const PfDeviceFacts & facts);

} // namespace pf
