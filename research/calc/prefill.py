"""Prefill (prompt-processing) roofline for CPU LLM inference.

Prefill is compute-bound: weights are read once per chunk (amortized over T
tokens); GEMM flops dominate. Attention adds cost quadratic in total prompt
length (per token: ~4*avg_ctx*H*hd FLOPs), and at very long ctx the KV READ
traffic itself (quadratic: T^2/2 * kv_bytes_per_token) can exceed compute time.
Both bounds reported; verdict from max-time side.

Per-core NEON fp16-dotprod GEMM throughput: EXPLICITLY CONFIGURABLE ESTIMATES.
  DEFAULT_GFLOPS_PER_CORE = {"X4": 35.0, "A720": 22.0}   # EST; recalibrate by
  solving per-core rate from a measured prefill tok/s (phase0 1.5B pp512 armv8.0_1
  (scalar fp32, weakest kernels) measured 96.7 tok/s => effective >= 75 GFLOPS/core
  est on X4, so 35 is deliberately conservative for fp16 dotprod kernels).
conservative: GFLOPS_total * sustained_thermal_frac (throttle derate) / flops.
optimistic:   GFLOPS_total * OPTIMISTIC_MULT (1.25 EST: tuned fp16-dotprod GEMM +
              flash-attention; recalibrate from measured pp tok/s).
"""
from typing import Dict, Optional

from device import DeviceClass
from model import (ModelSpec, canonical_quant, bytes_per_weight,
                   KV_ELEM_BYTES_DEFAULT)

# Per-core matmul GFLOPS at sustained clock (ESTIMATES; recalibrate from benches).
DEFAULT_GFLOPS_PER_CORE = {"X4": 35.0, "A720": 22.0}
# Optimistic multiplier over the configured param (tuned fp16-dotprod path; EST).
OPTIMISTIC_MULT = 1.25


def total_gflops(m=None, d: DeviceClass = None,
                 gflops_per_core: Optional[Dict[str, float]] = None) -> float:
    """Aggregate GEMM GFLOPS over all listed cores at sustained clocks (param-driven).
    Cluster kinds missing from the config default to ClusterSpec.matmul_gflops_per_core_est."""
    gf = dict(DEFAULT_GFLOPS_PER_CORE)
    if gflops_per_core:
        gf.update(gflops_per_core)
    total = 0.0
    for c in d.clusters:
        rate = gf.get(c.kind, c.matmul_gflops_per_core_est)
        total += c.core_count * rate
    return total


def prefill_bytes_moved(m: ModelSpec, tokens: int, quant: str,
                        kv_elem_bytes: float = KV_ELEM_BYTES_DEFAULT) -> Dict:
    """Min bytes moved for a T-token chunk (EST coarse): weights pass once +
    KV write (T tokens) + KV READ (quadratic: sum_t t * kv/token) + act traffic
    (coarse EST: T*L*(4*hidden+3*ffn)*2B*r+w; ~harmless at T<=4k, huge at 32k)."""
    bpw = bytes_per_weight(canonical_quant(quant))
    weights = m.weight_bytes_storage(bpw)
    kv_write = m.kv_bytes_per_token(kv_elem_bytes) * tokens
    kv_read = m.kv_bytes_per_token(kv_elem_bytes) * (tokens * (tokens + 1) / 2.0)
    act = tokens * m.n_layers * (3 * m.hidden + m.ffn) * 2.0 * 2.0  # EST r+w traffic
    return {
        "weights_bytes_est": weights,
        "kv_write_bytes_est": kv_write,
        "kv_read_bytes_est": kv_read,   # quadratic in tokens (long-ctx pressure point)
        "act_bytes_est": act,
        "bytes_total_est": weights + kv_write + kv_read + act,
    }


def prefill_roofline(m: ModelSpec, d: DeviceClass, tokens: int,
                     quant: str = "q4km",
                     kv_elem_bytes: float = KV_ELEM_BYTES_DEFAULT,
                     thermal_frac: Optional[float] = None,
                     gflops_per_core: Optional[Dict[str, float]] = None,
                     optimistic_mult: float = OPTIMISTIC_MULT,
                     ctx0: int = 0) -> Dict:
    """Compute-side estimate for T prompt tokens (all numbers EST parameters).

    conservative = GF * thermal / flops ; optimistic = GF * OPTIMISTIC_MULT / flops.
    """
    q = canonical_quant(quant)
    flops_per_tok = m.flops_per_token_prefill(tokens + ctx0)  # incl. avg attn cost
    flops_total = flops_per_tok * tokens
    gf = total_gflops(m, d, gflops_per_core)  # param GFLOPS at sustained clocks (EST)
    th = (thermal_frac if thermal_frac is not None else d.sustained_thermal_frac)
    tok_s_conservative = gf * th * 1e9 / flops_per_tok
    tok_s_optimistic = gf * optimistic_mult * 1e9 / flops_per_tok

    bm = prefill_bytes_moved(m, tokens, q, kv_elem_bytes)
    bw_eff = d.effective_bw_gbs(apply_thermal=True, thermal_frac=th)
    tok_s_mem_bound = bw_eff * 1e9 * tokens / bm["bytes_total_est"]
    t_mem_s = bm["bytes_total_est"] / (bw_eff * 1e9)
    t_conservative = tokens / tok_s_conservative
    pf_bottleneck = "MEM" if t_mem_s > t_conservative else "COMPUTE"
    return {
        "quant": q,
        "chunk_tokens": tokens,
        "flops_per_token_est": flops_per_tok,
        "flops_total_est": flops_total,
        "gflops_total_param_est": gf,
        "gflops_effective_conservative_est": gf * th,
        "gflops_effective_optimistic_est": gf * optimistic_mult,
        "bytes_moved_est": bm,
        "tok_s_conservative_est": tok_s_conservative,
        "tok_s_optimistic_est": tok_s_optimistic,
        "tok_s_mem_bound_est": tok_s_mem_bound,
        "t_conservative_s_est": t_conservative,
        "t_optimistic_s_est": tokens / tok_s_optimistic,
        "t_mem_s_est": t_mem_s,
        "bottleneck": pf_bottleneck,
        "annotated": "EST params: gflops/core + thermal_frac + optimistic_mult; "
                     "recalibrate from measured pp tok/s",
    }
