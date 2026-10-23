"""Device-class SoC parameter presets for the decode/prefill roofline calculator.

EVERY numeric parameter here is an ESTIMATE unless the formula is exact by
construction (only the DRAM theory-bw formula is exact: channels * width_bytes *
transfer_rate). Confidence + recalibration playbook lives in research/R4_theory.md.

Recalibration quick rules:
  - dram_* fields        : read from SoC memory spec / on-device dumps
                           (e.g. cpufreq + LPDDR comms); exact by construction.
  - achievable_bw_frac   : run a big-cluster-pinned streaming memcpy/GEMV bench;
                           typical ARM-phone LPDDR5X CPU lands 0.5-0.75 == EST.
                           (llama.cpp ggml kernels reach only ~40-70% of this;
                           see R4_theory.md slack analysis.)
  - sustained_ghz        : clock while ALL listed cores are ~100% busy for >5 min
                           (peak numbers are marketing); read /sys cpufreq on device.
  - instret_rate_gips_est: informational compute ceiling (retired inst/s); fine with
                           perf counters if present.
  - matmul_gflops_per_core_est: per-core GEMM throughput at sustained clock used by
                           prefill.py (see DEFAULT_GFLOPS_PER_CORE there);
                           recalibrate by solving from a measured prefill tok/s.
  - sustained_thermal_frac: multiplier on BOTH bandwidth and compute for sustained
                           (multi-minute) loads; 0.85 default == EST.
"""
from dataclasses import dataclass, field, replace
from typing import List, Optional


def dram_theory_bw_gbs(channels: int, width_bits: int, mt_per_s: float) -> float:
    """Exact LPDDR theory bandwidth, GB/s: channels * (width_bits/8) * MT/s / 1000.

    e.g. 4ch * 16bit * 8533MT/s -> 4 * 2 B * 8533e6/s = 68.264 GB/s.
    """
    return channels * (width_bits / 8.0) * mt_per_s / 1000.0


@dataclass
class ClusterSpec:
    kind: str           # "X4" | "A720" | "A78" | "A55" ...
    core_count: int
    peak_ghz: float          # marketing peak (source: vendor/leaks, low-med confidence)
    sustained_ghz: float     # EST: sustained under all-core load; recalibrate on device
    instret_rate_gips_est: float          # EST informational; perf-counters to recalibrate
    matmul_gflops_per_core_est: float     # EST per-core GEMM (see prefill.py defaults)


@dataclass
class DeviceClass:
    name: str
    clusters: List[ClusterSpec]
    dram_channels: int                    # exact from memory spec
    dram_width_bits: int                  # exact from memory spec
    dram_mt_per_s: float                  # exact from memory spec (per-channel transfer rate)
    achievable_bw_frac: float = 0.65      # EST (0.5-0.75 band); memcpy/GEMV bench to recalibrate
    l2_mb: float = 4.0                    # EST aggregate core-L2 (informational; not in roofline)
    l3_mb: float = 12.0                   # EST system L3/SLC (informational; not in roofline)
    sustained_thermal_frac: float = 0.85  # EST: sustained-capacity multiplier on bw & compute
    notes: str = ""

    @property
    def dram_theory_bw_gbs(self) -> float:
        """Exact theoretical DRAM bandwidth (GB/s) from the interface geometry."""
        return dram_theory_bw_gbs(self.dram_channels, self.dram_width_bits, self.dram_mt_per_s)

    @property
    def total_cores(self) -> int:
        return sum(c.core_count for c in self.clusters)

    def total_matmul_gflops_est(self) -> float:
        """Param-driven aggregate GEMM GFLOPS (all cores, sustained clock, no thermal derate).
        EST because per-core rates are EST; recalibrate via measured prefill."""
        return sum(c.core_count * c.matmul_gflops_per_core_est for c in self.clusters)

    def effective_bw_gbs(self, apply_thermal: bool = True,
                         bw_frac: Optional[float] = None,
                         thermal_frac: Optional[float] = None) -> float:
        """Effective streaming bandwidth (GB/s): theory * achievable_frac*
        (=) * sustained_thermal_frac (optional; EST). All three factors are parameters."""
        bw = self.dram_theory_bw_gbs * (bw_frac if bw_frac is not None else self.achievable_bw_frac)
        if apply_thermal:
            bw *= (thermal_frac if thermal_frac is not None else self.sustained_thermal_frac)
        return bw

    def summary(self) -> str:
        cs = ", ".join(f"{c.core_count}x{c.kind}@{c.peak_ghz:.1f}GHz"
                       f"(sust~{c.sustained_ghz:.1f}, est)" for c in self.clusters)
        return (f"{self.name}: [{cs}] | DRAM {self.dram_channels}x{self.dram_width_bits}bit"
                f"@{self.dram_mt_per_s:.0f}MT/s = {self.dram_theory_bw_gbs:.2f} GB/s theory")


VIVO_X200_FE = DeviceClass(
    name="vivo-x200-fe",
    # mt6989 (Dimensity 9300+). Task spec: 4xX4@3.4 peak + 4xA720@2.5 peak.
    # NOTE (est): actual 9300+ big-cluster topology is commonly reported as
    # 1xX4@3.4GHz + 3xX4@2.85GHz + 4xA720@2.0GHz; the simplified 4+4 here is the
    # caller's parameterization (peak_ghz/sustained_ghz are the real knobs).
    # Recalibrate sustained clocks + topology on device (cpufreq during load).
    clusters=[
        ClusterSpec(kind="X4", core_count=4, peak_ghz=3.4, sustained_ghz=2.9,
                    instret_rate_gips_est=20.0,      # EST (perf counters: recalibrate)
                    matmul_gflops_per_core_est=35.0),  # EST; see prefill.DEFAULT_GFLOPS_PER_CORE
        ClusterSpec(kind="A720", core_count=4, peak_ghz=2.5, sustained_ghz=2.1,
                    instret_rate_gips_est=12.0,      # EST
                    matmul_gflops_per_core_est=22.0),  # EST
    ],
    dram_channels=4, dram_width_bits=16, dram_mt_per_s=8533.0,
    # -> 68.264 GB/s theory (exact formula). ANNOTATE: some D9300+ SKUs run LPDDR5X
    # 9600MT/s (76.8 GB/s); X200 FE assumed 8533 here (med-low confidence; verify on device).
    achievable_bw_frac=0.65,          # EST (0.5-0.75); memcpy/GEMV bench to recalibrate
    l2_mb=4.0,                        # EST aggregate
    l3_mb=12.0,                       # EST SLC/L3 (variants claim up to ~18MB; unused in math)
    sustained_thermal_frac=0.85,      # EST; thermal logs + long-run bench to recalibrate
)

GENERIC_FLAGSHIP_2024 = DeviceClass(
    name="generic-flagship-2024",
    # STUB (est): 8 Gen 3 / X4+A720-class, LPDDR5X 4x16bit@9600MT/s = 76.8 GB/s theory.
    clusters=[
        ClusterSpec(kind="X4", core_count=4, peak_ghz=3.3, sustained_ghz=2.8,
                    instret_rate_gips_est=19.0, matmul_gflops_per_core_est=32.0),  # EST
        ClusterSpec(kind="A720", core_count=4, peak_ghz=3.0, sustained_ghz=2.3,
                    instret_rate_gips_est=13.0, matmul_gflops_per_core_est=19.0),  # EST
    ],
    dram_channels=4, dram_width_bits=16, dram_mt_per_s=9600.0,
    achievable_bw_frac=0.62, l2_mb=4.0, l3_mb=12.0, sustained_thermal_frac=0.83,  # EST
)

MIDRANGE_2023 = DeviceClass(
    name="midrange-2023",
    # STUB (est): A78/A55-class, LPDDR4X 2x16bit@4266MT/s = 34.13 GB/s theory.
    clusters=[
        ClusterSpec(kind="A78", core_count=2, peak_ghz=2.6, sustained_ghz=2.2,
                    instret_rate_gips_est=11.0, matmul_gflops_per_core_est=16.0),  # EST
        ClusterSpec(kind="A55", core_count=6, peak_ghz=2.0, sustained_ghz=1.8,
                    instret_rate_gips_est=8.0, matmul_gflops_per_core_est=8.0),    # EST
    ],
    dram_channels=2, dram_width_bits=16, dram_mt_per_s=4266.0,
    achievable_bw_frac=0.55, l2_mb=1.5, l3_mb=4.0, sustained_thermal_frac=0.78,   # EST
)

PRESETS = {
    "vivo-x200-fe": VIVO_X200_FE,
    "generic-flagship-2024": GENERIC_FLAGSHIP_2024,
    "midrange-2023": MIDRANGE_2023,
}


def get_device(preset: str) -> DeviceClass:
    if preset not in PRESETS:
        raise KeyError(f"unknown device preset '{preset}'; known: {sorted(PRESETS)}")
    return PRESETS[preset]
