// pf_plan_apply.cpp - LINE-JSON plan application log (see pf_plan_apply.h).
#include "pf_plan_apply.h"

#include <chrono>
#include <fstream>
#include <sstream>

namespace pf {

namespace {

std::string extract_plan_id(const std::string & line) {
    const std::string key = "\"planId\":\"";
    const size_t p = line.find(key);
    if (p == std::string::npos) { return ""; }
    const size_t begin = p + key.size();
    const size_t end = line.find('"', begin);
    if (end == std::string::npos) { return ""; }
    return line.substr(begin, end - begin);
}

long long epoch_seconds() {
    return (long long) std::chrono::duration_cast<std::chrono::seconds>(
               std::chrono::system_clock::now().time_since_epoch()).count();
}

} // namespace

PfApplyResult pf_plan_setapplied(const char * path, const PfPlan & plan, std::string * err) {
    if (!path || !*path) {
        if (err) { *err = "pf_plan_setapplied: null/empty path"; }
        return PF_APPLY_ERR;
    }
    std::ostringstream line;
    line << "{\"event\":\"planApplied\",\"tsSec\":" << epoch_seconds()
         << ",\"plan\":" << serialize_plan(plan) << "}";
    std::ofstream f(path, std::ios::app | std::ios::binary);
    if (!f.good()) {
        if (err) { *err = "pf_plan_setapplied: cannot open for append: " + std::string(path); }
        return PF_APPLY_ERR;
    }
    f << line.str() << "\n";
    f.flush();
    if (!f.good()) {
        if (err) { *err = "pf_plan_setapplied: write failed"; }
        return PF_APPLY_ERR;
    }
    return PF_APPLY_OK;
}

PfApplyResult pf_set_plan(const char * path, const PfPlan & plan, std::string * err) {
    if (!plan.planId.empty() && !path) {
        // No registry file: without a path we cannot be idempotent - treat as
        // apply-to-nowhere success identical to setapplied err path? No: the
        // real engine always has a log path; fail loudly here.
        if (err) { *err = "pf_set_plan: null path with non-empty planId"; }
        return PF_APPLY_ERR;
    }
    if (!plan.planId.empty()) {
        const std::string last_id = last_applied_plan_id(path);
        if (last_id == plan.planId) {
            if (err) { err->clear(); }
            return PF_APPLY_SKIPPED_SAME_ID;
        }
    }
    return pf_plan_setapplied(path, plan, err);
}

std::string last_applied_plan_id(const char * path) {
    if (!path || !*path) { return ""; }
    std::ifstream f(path, std::ios::binary);
    if (!f.good()) { return ""; }
    std::string line, last;
    while (std::getline(f, line)) {
        if (!line.empty()) { last = line; }
    }
    return extract_plan_id(last);
}

} // namespace pf
