// pf_controller.cpp - standalone PF plan controller prototype.
//
// Takes the PfDeviceFacts reported by the engine (see pfPlanJson() in the
// patched pf_llm.cpp) and decides the PfPlan per device class. This file has NO
// Android / NDK / llama.cpp dependencies, so policy logic is developed and
// regression-tested entirely on host; the same code is the reference for the
// Kotlin plan layer and for pf_llm.cpp's plan application.
//
// Device classes (R3_hardware / FINDINGS device set):
//  - X200FE_Like    : 4x Cortex-X4 (prime) + 4x A720 small (MT6989 / D9400-like)
//  - SD8Gen3_Like   : 8 cores, big-cluster layout unknown/generic (1-3 or 6 big)
//  - Midrange2023   : 2x Cortex-A77 + 6x A55
//  - Unknown        : singles-cluster CPUs, tablets, >8 cores -> conservative default
#include "pf_controller.h"

#include <algorithm>
#include <cmath>

namespace pf {

PfDeviceClass classify_device(const PfDeviceFacts & f) {
    if (!f.factsValid || f.cores.size() < 2) { return PfDeviceClass::Unknown; }
    std::vector<long long> freqs;
    for (const auto & c : f.cores) { freqs.push_back(c.maxKhz); }
    std::sort(freqs.begin(), freqs.end(), std::greater<long long>());
    // split at the first >15% relative drop; clusters below that line merge
    size_t big_count = 0;
    for (size_t i = 0; i + 1 < freqs.size(); ++i) {
        big_count = i + 1;
        if ((double) freqs[i + 1] < 0.85 * (double) freqs[i]) { break; }
        if (i + 2 == freqs.size()) { big_count = freqs.size(); break; }
    }
    if (big_count >= freqs.size()) { return PfDeviceClass::Unknown; } // single cluster
    if (big_count == 4) { return PfDeviceClass::X200FE_Like; }
    if (big_count == 2) { return PfDeviceClass::Midrange2023; }
    return PfDeviceClass::SD8Gen3_Like; // any other split of 8 cores: layout-unknown generic
}

int big_core_count(const PfDeviceFacts & f) {
    std::vector<long long> freqs;
    for (const auto & c : f.cores) { freqs.push_back(c.maxKhz); }
    std::sort(freqs.begin(), freqs.end(), std::greater<long long>());
    for (size_t i = 0; i + 1 < freqs.size(); ++i) {
        if ((double) freqs[i + 1] < 0.85 * (double) freqs[i]) { return (int) i + 1; }
    }
    return (int) freqs.size();
}

PfPlan plan_for_class(const PfDeviceFacts & f, PfDeviceClass cls) {
    PfPlan p = PfPlan();
    const bool low_mem = f.memAvailableBytes != 0 && f.memAvailableBytes < 6ull * 1024 * 1024 * 1024;
    const bool has_vulkan = f.vulkanOk;
    switch (cls) {
        case PfDeviceClass::X200FE_Like: {
            // All-big-ish 4+4: decode wants the 4 prime cores, prefill can use
            // primes + upper smalls. GPU: no SME/coopmat class expected (Mali
            // Immortalis scalar FA) -> FA off, ubatch halved to cut peak RAM.
            p.nThreads = 4;
            p.nThreadsBatch = 6;
            p.kvTypeK = low_mem ? "q8_0" : "f16";
            p.kvTypeV = low_mem ? "q8_0" : "f16";
            p.flashAttn = 0;
            p.nBatch = 512;
            p.nUbatch = 256;
            p.loadMode = "mmap";
            break;
        }
        case PfDeviceClass::SD8Gen3_Like: {
            // Layout unknown (per plan contract: treat as generic). Conservative
            // symmetric threads until on-device verify; Adreno 750 usually has
            // coopmat1 so AUTO flash-attn is safe-ish to probe.
            p.nThreads = 4;
            p.nThreadsBatch = 4;
            p.kvTypeK = "f16";
            p.kvTypeV = "f16";
            p.flashAttn = 0;
            p.nBatch = 512;
            p.nUbatch = 256;
            p.loadMode = "mmap";
            break;
        }
        case PfDeviceClass::Midrange2023: {
            // 2 big A77 only: more than 2 decode threads sit on A55 and stall.
            // Low RAM typical -> q8_0 KV; keep it CPU-only via nGpuLayers deferred.
            p.nThreads = 2;
            p.nThreadsBatch = 2;
            p.kvTypeK = "q8_0";
            p.kvTypeV = "q8_0";
            p.flashAttn = 0;
            p.nBatch = 512;
            p.nUbatch = 128;
            p.loadMode = "mmap";
            p.maxHeapBytes = 1536ull * 1024 * 1024;
            break;
        }
        case PfDeviceClass::Unknown:
        default: {
            // Engine defaults (all -1 / "auto" / engine-order variant choice).
            p.planId = "pf-class-unknown";
            return p;
        }
    }
    // Known classes: pin Id + the HWCAP-supported variant and n_ctx default.
    static const char * class_ids[]
        = {"pf-class-x200fe", "pf-class-sd8gen3", "pf-class-midrange2023"};
    p.planId = cls == PfDeviceClass::X200FE_Like ? class_ids[0]
             : cls == PfDeviceClass::SD8Gen3_Like ? class_ids[1]
             : class_ids[2];
    p.cpuVariant = highest_supported_variant(f);
    // n_ctx: Vulkan FA paths prefer a trimmed ctx for peak-RAM safety on the
    // 4B gguf; keep 4096 as the conservative default, halve when low_mem.
    p.nCtx = low_mem && has_vulkan ? 2048 : 4096;
    // rates unmeasured until the bench layer fills them (rank-ready plans).
    p.decodeRate = 0.0f;
    p.prefillRate = 0.0f;
    return p;
}

PfPlan decide_plan(const PfDeviceFacts & f) {
    return demote_variant_if_unsupported(plan_for_class(f, classify_device(f)), f);
}

std::string highest_supported_variant(const PfDeviceFacts & f) {
    // In declaration order of ggml/src/CMakeLists.txt:525-533 (Android block),
    // interleaved so the highest compiled capability is tried first. Feature
    // requirement matches the -march flags per variant (see pf_plan.h / audit).
    struct VarReq { const char * name; bool dotprod, fp16, i8mm, sve, sve2, sme; };
    static const VarReq ladder[] = {
        {"armv9.2_2", true, true, true, true,  true,  true},
        {"armv9.0_1", true, true, true, false, true,  false},
        {"armv8.6_1", true, true, true, false, false, false},
        {"armv8.2_2", true, true, false, false, false, false},
        {"armv8.2_1", true, false, false, false, false, false},
        {"armv8.0_1", false, false, false, false, false, false},
    };
    for (const VarReq & v : ladder) {
        if (v.dotprod && !f.hasDotprod) { continue; }
        if (v.fp16 && !f.hasFp16) { continue; }
        if (v.i8mm && !f.hasI8mm) { continue; }
        if (v.sve && !f.hasSve) { continue; }
        if (v.sve2 && !f.hasSve2) { continue; }
        if (v.sme && !f.hasSme) { continue; }
        return v.name;
    }
    return "armv8.0_1";
}

PfPlan demote_variant_if_unsupported(const PfPlan & plan, const PfDeviceFacts & f) {
    if (plan.cpuVariant.empty()) { return plan; } // engine default order decides
    const std::string best = highest_supported_variant(f);
    // Engine default order IS this same ladder (pf_llm.cpp most-capable-first);
    // a plan variant is unsupported exactly when it outranks the device best.
    static const char * order[] = {"armv9.2_2", "armv9.2_1", "armv9.0_1",
                                   "armv8.6_1", "armv8.2_2", "armv8.2_1", "armv8.0_1"};
    int plan_rank = -1, best_rank = -1;
    for (int i = 0; i < 7; ++i) {
        if (plan.cpuVariant == order[i]) { plan_rank = i; }
        if (best == order[i]) { best_rank = i; }
    }
    if (plan_rank < 0) { return plan; } // not pinned properly; engine rejects at parse
    if (best_rank < 0) { return plan; } // facts too odd; leave to engine
    if (plan_rank <= best_rank) { return plan; }
    PfPlan p = plan;
    p.cpuVariant = best;
    return p;
}

} // namespace pf
