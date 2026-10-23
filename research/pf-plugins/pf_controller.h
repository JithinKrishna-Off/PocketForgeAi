// pf_controller.h - standalone PF plan controller prototype (host-testable).
#pragma once

#include "pf_plan.h"

namespace pf {

enum class PfDeviceClass {
    Unknown,
    X200FE_Like,   // 4x Cortex-X4 prime + 4x small
    SD8Gen3_Like,  // 8 cores, big-cluster layout unknown / generic (SD 8 Gen 3 class)
    Midrange2023,  // 2x Cortex-A77 + 6x A55
};

PfDeviceClass classify_device(const PfDeviceFacts & facts);
int big_core_count(const PfDeviceFacts & facts);
PfPlan plan_for_class(const PfDeviceFacts & facts, PfDeviceClass cls);
PfPlan decide_plan(const PfDeviceFacts & facts);

} // namespace pf
