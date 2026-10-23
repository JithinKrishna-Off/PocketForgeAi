// pf_controller_test.cpp - off-device regression tests for the PF plan controller.
// Static stub device facts; 3 device classes + unknown + JSON roundtrip.
// Build/run via build_controller.ps1 (host MSVC / clang / g++).
#include "pf_controller.h"

#include <cstdio>
#include <cstring>
#include <string>

using pf::PfDeviceClass;
using pf::PfDeviceFacts;
using pf::PfCpuCore;
using pf::PfPlan;

static int g_failures = 0;

static void check(bool cond, const char * what) {
    if (cond) { std::printf("  ok   %s\n", what); }
    else { std::printf("  FAIL %s\n", what); ++g_failures; }
}

static void check_eq(long long got, long long want, const char * what) {
    check(got == want, what);
    if (got != want) { std::printf("       got=%lld want=%lld\n", (long long) got, (long long) want); }
}

static void check_eqs(const std::string & got, const std::string & want, const char * what) {
    check(got == want, what);
    if (got != want) { std::printf("       got=%s want=%s\n", got.c_str(), want.c_str()); }
}

static PfCpuCore core(int id, long long mhz) { return PfCpuCore{id, mhz * 1000, mhz * 1000}; }

static PfDeviceFacts x200fe_stub() {
    PfDeviceFacts f;
    f.factsValid = true;
    f.backendName = "libggml-cpu-android_armv9.0_1.so";
    f.nCoresOnline = 8;
    // 4x A720 small @1.9 GHz, 4x X4 prime @3.3-3.63 GHz (cluster tops)
    f.cores = {core(0, 1856), core(1, 1856), core(2, 1856), core(3, 1856),
               core(4, 3300), core(5, 3300), core(6, 3300), core(7, 3630)};
    const unsigned long long hwcap
        = (1ull << 9) | (1ull << 10)          // FPHP | ASIMDHP
        | (1ull << 20)                        // DOTPROD
        | (1ull << 22);                       // SVE
    const unsigned long long hwcap2 = (1ull << 1) | (1ull << 13); // SVE2 | I8MM
    f.hwcap = hwcap;
    f.hwcap2 = hwcap2;
    f.hasDotprod = true; f.hasFp16 = true; f.hasI8mm = true;
    f.hasSve = true; f.hasSve2 = true;
    f.hasSme = false; f.hasSme2 = false;
    f.vulkanOk = true;
    f.memAvailableBytes = 7920ull * 1024 * 1024;
    return f;
}

static PfDeviceFacts sd8gen3_stub() {
    PfDeviceFacts f;
    f.factsValid = true;
    f.backendName = "libggml-cpu-android_armv9.2_1.so";
    f.nCoresOnline = 8;
    // alternate layout unknown - generic: 1xX4 @3.3, 5xA720 @3.0, 2xA520 @2.1
    f.cores = {core(0, 2100), core(1, 2100), core(2, 3000), core(3, 3000),
               core(4, 3000), core(5, 3000), core(6, 3000), core(7, 3300)};
    const unsigned long long hwcap = (1ull << 9) | (1ull << 10) | (1ull << 20);
    const unsigned long long hwcap2 = (1ull << 13); // I8MM, no SVE/SME
    f.hwcap = hwcap;
    f.hwcap2 = hwcap2;
    f.hasDotprod = true; f.hasFp16 = true; f.hasI8mm = true;
    f.hasSve = false; f.hasSve2 = false; f.hasSme = false; f.hasSme2 = false;
    f.vulkanOk = true;
    f.memAvailableBytes = 10240ull * 1024 * 1024;
    return f;
}

static PfDeviceFacts midrange2023_stub() {
    PfDeviceFacts f;
    f.factsValid = true;
    f.backendName = "libggml-cpu-android_armv8.2_2.so";
    f.nCoresOnline = 8;
    // 2x A77 @2.4 + 6x A55 @1.8
    f.cores = {core(0, 1800), core(1, 1800), core(2, 1800), core(3, 1800),
               core(4, 1800), core(5, 1800), core(6, 2400), core(7, 2400)};
    const unsigned long long hwcap = (1ull << 9) | (1ull << 10) | (1ull << 20);
    const unsigned long long hwcap2 = (1ull << 13);
    f.hwcap = hwcap;
    f.hwcap2 = hwcap2;
    f.hasDotprod = true; f.hasFp16 = true; f.hasI8mm = true;
    f.hasSve = false; f.hasSve2 = false; f.hasSme = false; f.hasSme2 = false;
    f.vulkanOk = false;
    f.memAvailableBytes = 4100ull * 1024 * 1024;
    return f;
}

int main() {
    std::printf("pf_controller_test\n");

    std::printf("classification\n");
    auto x = x200fe_stub();
    auto s = sd8gen3_stub();
    auto m = midrange2023_stub();
    PfDeviceFacts invalid;
    check(pf::classify_device(x) == PfDeviceClass::X200FE_Like, "x200-fe-like classifies as X200FE_Like");
    check_eq(pf::big_core_count(x), 4, "x200-fe-like big cores = 4");
    check(pf::classify_device(s) == PfDeviceClass::SD8Gen3_Like, "sd-8gen3-like classifies as SD8Gen3_Like (generic)");
    check_eq(pf::big_core_count(s), 6, "sd-8gen3-like big cores = 6 (unknown layout)");
    check(pf::classify_device(m) == PfDeviceClass::Midrange2023, "midrange-2023 classifies as Midrange2023");
    check_eq(pf::big_core_count(m), 2, "midrange-2023 big cores = 2");
    check(pf::classify_device(invalid) == PfDeviceClass::Unknown, "invalid facts classify as Unknown");

    std::printf("plans per device class\n");
    const PfPlan px = pf::plan_for_class(x, PfDeviceClass::X200FE_Like);
    const PfPlan ps = pf::plan_for_class(s, PfDeviceClass::SD8Gen3_Like);
    const PfPlan pm = pf::plan_for_class(m, PfDeviceClass::Midrange2023);
    check_eq(px.nThreads, 4, "x200 plan decode threads = 4 (prime cluster)");
    check_eq(px.nThreadsBatch, 6, "x200 plan batch threads = 6");
    check_eqs(px.flashAttn, "off", "x200 plan flash-attn off (no coopmat)");
    check_eq(px.nUbatch, 256, "x200 plan ubatch = 256");
    check_eq(ps.nThreads, 4, "sd8gen3 plan threads = 4 (conservative generic)");
    check_eqs(ps.flashAttn, "auto", "sd8gen3 plan flash-attn auto (probe coopmat)");
    check_eqs(ps.kvTypeK, "f16", "sd8gen3 plan kv f16");
    check_eq(pm.nThreads, 2, "midrange plan threads = 2 (A77 only)");
    check_eqs(pm.kvTypeK, "q8_0", "midrange plan kv q8_0 (low mem)");
    check_eq(pm.nUbatch, 128, "midrange plan ubatch = 128");
    check(pm.maxHeapBytes > 0, "midrange plan has heap budget");
    check(pf::serialize_plan(px) != pf::serialize_plan(ps) &&
          pf::serialize_plan(px) != pf::serialize_plan(pm) &&
          pf::serialize_plan(ps) != pf::serialize_plan(pm), "the 3 plan structs are pairwise distinct");
    // low-mem variant of x200 flips KV type
    PfDeviceFacts x_low = x200fe_stub();
    x_low.memAvailableBytes = 4900ull * 1024 * 1024;
    const PfPlan pxl = pf::decide_plan(x_low);
    check_eqs(pxl.kvTypeK, "q8_0", "x200 low-mem plan flips KV to q8_0");

    std::printf("decision pipeline\n");
    const PfPlan pd = pf::decide_plan(m);
    check_eq(pd.nThreads, 2, "decide_plan(midrange facts) threads = 2");
    const PfPlan pu = pf::decide_plan(invalid);
    check_eq(pu.nThreads, -1, "decide_plan(unknown facts) keeps engine defaults");

    std::printf("json contract roundtrip\n");
    const std::string json_px = pf::serialize_plan(px);
    PfPlan reparsed; std::string err;
    check(pf::parse_plan(json_px, &reparsed, &err), "plan JSON parses back");
    check(pf::serialize_plan(reparsed) == json_px, "roundtrip is byte-stable");
    check(pf::parse_plan(pf::plan_and_facts_json(px, x), &reparsed, &err), "plan+facts wrapper parses");
    const PfPlan planned = pf::parse_plan(
        "{\"pfPlanVersion\":1,\"cpu\":{\"nThreads\":6},\"kv\":{\"typeK\":\"q8_0\",\"flashAttn\":\"on\"}}",
        &reparsed, &err) ? reparsed : PfPlan();
    check_eq(planned.nThreads, 6, "hand-written plan JSON applies");
    check_eqs(planned.kvTypeK, "q8_0", "hand-written plan JSON kv applied");
    std::string err2;
    check(!pf::parse_plan("{\"pfPlanVersion\":1,\"kv\":{\"typeK\":\"bogus\"}}", nullptr, &err2), "invalid kv type rejected");
    check(!pf::parse_plan("{\"pfPlanVersion\":1,\"cpu\":{\"nThreads\":0}}", nullptr, &err2), "nThreads=0 rejected");

    std::printf("%s: %d failure(s)\n", g_failures == 0 ? "PASS" : "FAIL", g_failures);
    return g_failures == 0 ? 0 : 1;
}
