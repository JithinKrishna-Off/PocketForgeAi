// pf_controller_test.cpp - off-device regression tests for the PF plan controller.
// Static stub device facts; 3 device classes + unknown + JSON roundtrip.
// Build/run via build_controller.ps1 (host MSVC / clang / g++).
#include "pf_plan_apply.h"
#include "pf_controller.h"

#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>

using pf::PfDeviceClass;
using pf::PfDeviceFacts;
using pf::PfCpuCore;
using pf::PfPlan;
using pf::PfApplyResult;
using pf::PF_APPLY_OK;
using pf::PF_APPLY_SKIPPED_SAME_ID;

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
    check_eq(px.flashAttn, 0, "x200 plan flash-attn = 0 (no coopmat)");
    check_eq(px.nUbatch, 256, "x200 plan ubatch = 256");
    check_eq(px.nCtx, 4096, "x200 plan nCtx = 4096");
    check_eqs(px.cpuVariant, "armv9.0_1", "x200 plan variant pinned armv9.0_1 (sve2 ok, no sme)");
    check(px.decodeRate == 0.0f && px.prefillRate == 0.0f, "x200 plan rates start unmeasured");
    check_eqs(ps.kvTypeK, "f16", "sd8gen3 plan kv f16");
    check_eqs(pm.kvTypeK, "q8_0", "midrange plan kv q8_0 (low mem)");
    check_eq(pm.nUbatch, 128, "midrange plan ubatch = 128");
    check(pm.maxHeapBytes > 0, "midrange plan has heap budget");
    check(!px.planId.empty() && !ps.planId.empty() && !pm.planId.empty(),
          "every known class plan carries a planId (idempotency key)");
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

    std::printf("pf_plan_v1 schema + sanity rules\n");
    const std::string s1 = pf::serialize_plan(px);
    check(s1.find("\"pfPlanVersion\":2") != std::string::npos, "plan JSON carries pfPlanVersion:2 (pf_plan_v1)");
    check(s1.find("\"planId\":\"") != std::string::npos, "plan JSON carries planId");
    check(s1.find("\"ctx\":{\"nCtx\":") != std::string::npos, "plan JSON carries ctx.nCtx");
    check(s1.find("\"flashAttn\":") != std::string::npos, "plan JSON has numeric flashAttn (0|1)");
    check(s1.find("\"flashAttn\":\"") == std::string::npos, "plan JSON flashAttn is 0|1, not a string");
    check(s1.find("\"decodeRate\":") != std::string::npos && s1.find("\"prefillRate\":") != std::string::npos,
          "plan JSON carries both profile rates");
    check(s1.find("\"mlock\":") != std::string::npos, "plan JSON carries split.mlock knob");
    check(s1.find('\n') == std::string::npos, "plan JSON is LINE-JSON (single line, no newline)");
    check(!pf::parse_plan("{\"pfPlanVersion\":2,\"cpu\":{\"cpuVariant\":\"armv9.9_9\"},\"planId\":\"x\"}", nullptr, &err2),
          "unknown cpuVariant rejected (must be one of the 7 shipped)");
    check(!pf::parse_plan("{\"pfPlanVersion\":2,\"kv\":{\"typeK\":\"q4_0\",\"typeV\":\"q4_0\",\"flashAttn\":1},\"planId\":\"x\"}", nullptr, &err2),
          "q4_0 KV + flashAttn=1 INVALID pairing rejected");
    check(!pf::parse_plan("{\"pfPlanVersion\":2,\"kv\":{\"typeK\":\"f16\",\"flashAttn\":2},\"planId\":\"x\"}", nullptr, &err2),
          "flashAttn=2 rejected (0|1 only)");
    check(pf::validate_plan_rules([]{ PfPlan p; p.flashAttn = 1; p.kvTypeV = "q8_0"; return p; }(), &err2),
          "q8_0 KV + flashAttn=1 is a legal pairing (migrated v0-on values work)");
    PfPlan mlock_plan; std::string mlock_err;
    check(pf::parse_plan("{\"pfPlanVersion\":2,\"planId\":\"m1\",\"split\":{\"loadMode\":\"mmap\",\"mlock\":1}}",
                         &mlock_plan, &mlock_err) && mlock_plan.mlock, "split.mlock parses as separate knob");
    check(pf::parse_plan("{\"pfPlanVersion\":1,\"kv\":{\"typeK\":\"f16\",\"typeV\":\"f16\",\"flashAttn\":\"auto\"},\"split\":{\"loadMode\":\"mmap_mlock\"}}",
                         nullptr, nullptr) == false,
          "v0 loadMode 'mmap_mlock' rejected (mlock now separate knob; v0 string forms migrated)");
    PfPlan vm1; std::string v_err;
    check(pf::parse_plan("{\"pfPlanVersion\":1,\"kv\":{\"flashAttn\":\"on\"},\"split\":{\"loadMode\":\"mlock\"}}", &vm1, &v_err),
          "v0 string flashAttn 'on' migrated (compat path)");
    check_eq(vm1.flashAttn, 1, "v0 'on' -> flashAttn 1");
    check_eqs(vm1.loadMode, "mlock", "v0 plain 'mlock' loadMode accepted");

    std::printf("planner variant ladder (hwcap feature downgrade)\n");
    PfDeviceFacts nodotprod = midrange2023_stub();
    nodotprod.hasDotprod = false;
    nodotprod.hwcap &= ~(1ull << 20);
    check_eqs(pf::highest_supported_variant(nodotprod), "armv8.0_1",
              "no dotprod -> armv8.0_1 (planner downgrades to lowest shipped variant)");
    PfDeviceFacts dotprod_only = sd8gen3_stub();
    dotprod_only.hasI8mm = false;
    dotprod_only.hwcap2 = 0;
    check_eqs(pf::highest_supported_variant(dotprod_only), "armv8.2_1",
              "dotprod only -> armv8.2_1 (fp16/i8mm not usable)");
    PfDeviceFacts dotprod_fp16 = x200fe_stub();
    dotprod_fp16.hwcap2 = 0;
    dotprod_fp16.hasI8mm = false; dotprod_fp16.hasSve2 = false;
    check_eqs(pf::highest_supported_variant(dotprod_fp16), "armv8.2_2",
              "dotprod+fp16 -> armv8.2_2");
    PfDeviceFacts with_i8mm = dotprod_fp16;
    with_i8mm.hasI8mm = true; with_i8mm.hwcap2 = (1ull << 13);
    check_eqs(pf::highest_supported_variant(with_i8mm), "armv8.6_1", "dotprod+fp16+i8mm -> armv8.6_1");
    PfDeviceFacts sme_dev = x200fe_stub();
    sme_dev.hasSve = true; sme_dev.hasSme = true;
    sme_dev.hwcap |= (1ull << 22);
    sme_dev.hwcap2 = (1ull << 1) | (1ull << 13) | (1ull << 23);
    check_eqs(pf::highest_supported_variant(sme_dev), "armv9.2_2",
              "sve2+sme present -> armv9.2_2 (highest shipped)");
    check_eqs(pf::highest_supported_variant(x), "armv9.0_1",
              "x200 stub facts (sve2, no sme) -> armv9.0_1 as decided");
    PfPlan pinned_hi; pinned_hi.cpuVariant = "armv9.2_2"; pinned_hi.planId = "reseek";
    const PfPlan demoted = pf::demote_variant_if_unsupported(pinned_hi, s);
    check_eqs(demoted.cpuVariant, "armv8.2_1",
              "planner demotes armv9.2_2 plan to the device-supported armv8.2_1");
    const PfPlan passthrough = pf::demote_variant_if_unsupported(pinned_hi, sme_dev);
    check_eqs(passthrough.cpuVariant, "armv9.2_2", "supported pin passes through untouched");
    check_eqs(pf::demote_variant_if_unsupported(pinned_hi, x).cpuVariant, "armv9.0_1",
              "demote picks highest fully-supported variant (armv9.2_x unscoreable on device)");

    std::printf("pf_plan_setapplied / pf_set_plan idempotency (LINE-JSON log)\n");
    const std::string log_path = "pf_apply_test_log.jsonl";
    std::remove(log_path.c_str());
    PfPlan ap = px; ap.planId = "apply-one";
    std::string apply_err;
    const PfApplyResult r1 = pf::pf_set_plan(log_path.c_str(), ap, &apply_err);
    check(r1 == PF_APPLY_OK, "pf_set_plan first apply -> OK");
    check_eqs(pf::last_applied_plan_id(log_path.c_str()), "apply-one", "last planId readback");
    const PfApplyResult r2 = pf::pf_set_plan(log_path.c_str(), ap, &apply_err);
    check(r2 == PF_APPLY_SKIPPED_SAME_ID, "pf_set_plan same planId -> SKIPPED_SAME_ID (idempotent)");
    PfPlan other = ap; other.planId = "apply-two"; other.nThreads = 6;
    const PfApplyResult r3 = pf::pf_set_plan(log_path.c_str(), other, &apply_err);
    check(r3 == PF_APPLY_OK, "pf_set_plan new planId -> OK");
    {
        std::ifstream f(log_path.c_str());
        int lines = 0; std::string iline; bool all_line_json = true;
        while (std::getline(f, iline)) {
            if (iline.empty()) { continue; }
            ++lines;
            if (iline.find("{\"event\":\"planApplied\",\"tsSec\":") != 0 ||
                iline.find("\"plan\":{\"pfPlanVersion\":2") == std::string::npos) { all_line_json = false; }
        }
        check_eq(lines, 2, "idempotent apply leaves exactly 2 plan lines in file");
        check(all_line_json, "every line is LINE-JSON with event+tsSec+plan");
    }
    pf::pf_plan_setapplied(log_path.c_str(), pf::PfPlan(), nullptr);
    const PfPlan anon_id = []{ PfPlan p; p.planId = ""; p.nThreads = 3; return p; }();
    check(pf::pf_set_plan(log_path.c_str(), anon_id, &apply_err) == PF_APPLY_OK,
          "empty planId always applies (no idempotency key)");
    std::remove(log_path.c_str());

    std::printf("%s: %d failure(s)\n", g_failures == 0 ? "PASS" : "FAIL", g_failures);
    return g_failures == 0 ? 0 : 1;
}
