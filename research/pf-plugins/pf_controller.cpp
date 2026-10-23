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

    switch (cls) {
        case PfDeviceClass::X200FE_Like: {
            // All-big-ish 4+4: decode wants the 4 prime cores, prefill can use
            // primes + upper smalls. GPU: no SME/coopmat class expected (Mali
            // Immortalis scalar FA) -> FA off, ubatch halved to cut peak RAM.
            p.nThreads = 4;
            p.nThreadsBatch = 6;
            p.kvTypeK = low_mem ? "q8_0" : "f16";
            p.kvTypeV = low_mem ? "q8_0" : "f16";
            p.flashAttn = "off";
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
            p.flashAttn = "auto";
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
            p.flashAttn = "off";
            p.nBatch = 512;
            p.nUbatch = 128;
            p.loadMode = "mmap";
            p.maxHeapBytes = 1536ull * 1024 * 1024;
            break;
        }
        case PfDeviceClass::Unknown:
        default: {
            // Engine defaults (all -1 / "auto" / engine-order variant choice).
            break;
        }
    }
    return p;
}

PfPlan decide_plan(const PfDeviceFacts & f) {
    return plan_for_class(f, classify_device(f));
}

} // namespace pf
