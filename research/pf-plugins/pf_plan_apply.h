// pf_plan_apply.h - plan application log surface (schema pf_plan_v1).
//
// REAL subset implemented here, portable C++17 (host + NDK):
//  - pf_plan_setapplied(path, plan): appends ONE line of LINE-JSON to the file
//    at path (one plan per line, no SQLite - the app just tails/shows the file).
//  - pf_set_plan(path, plan): IDEMPOTENT wrapper - if the file's last applied
//    plan already carries the same (non-empty) planId, nothing is written.
//    Same plan id applied twice = one file line = byte-stable behaviour.
//
// Line format (single line, newline terminated):
//   {"event":"planApplied","tsSec":1761200000,"plan":{"pfPlanVersion":2,...}}
#pragma once

#include "pf_plan.h"

namespace pf {

enum PfApplyResult {
    PF_APPLY_OK = 0,             // line written
    PF_APPLY_SKIPPED_SAME_ID = 1, // idempotent no-op (same planId already last)
    PF_APPLY_ERR = -1,           // file/io error, see err
};

// Appends one LINE-JSON record for the plan (not idempotent; also used
// internally by pf_set_plan). planId "" is serialized as-is.
PfApplyResult pf_plan_setapplied(const char * path, const PfPlan & plan, std::string * err);

// Idempotent apply: skip when the last line in path has the same non-empty
// planId. planId "" always applies. File-missing counts as fresh (applies).
PfApplyResult pf_set_plan(const char * path, const PfPlan & plan, std::string * err);

// planId of the last applied plan in the file, "" if none/unreadable.
std::string last_applied_plan_id(const char * path);

} // namespace pf
