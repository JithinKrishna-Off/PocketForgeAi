"""Decode (autoregressive generation) roofline for CPU LLM inference.

t_token = max( bytes/token_min / bw_effective ,  flops/token / gflops_effective )
tok/s   = 1 / t_token

bytes/token_min = decode weights (all layers + lm_head, streamed every token)
                  + state: KV cache READ for the full ctx (linear in ctx!)
                  + small activation traffic (~negligible at 1 token; folded into est).
flops/token     = 2*decode_params (GEMV dense math) + attention overhead
                  4*L*n_heads*head_dim*ctx  (QK^T + A*V; linear growth in ctx).

Two bounds are reported separately:
  tok_s_mem_bound     = bw_effective / bytes_per_token   -> "kern-bound" (streaming)
  tok_s_compute_bound = gflops_effective / flops_per_token -> "ideal-attention" GEMV bound
  verdict = min of both; all numbers are ESTIMATES driven by device/model params.

The whole result is parametric in ctx: KV contribution grows linearly (kv_bytes/ctx
is a slope; weights fixed), so decode tok/s decays with ctx until compute bound
becomes the max (see decode_ctx_curve).
"""
from typing import Dict, List, Optional

from device import DeviceClass
from model import (ModelSpec, canonical_quant, bytes_per_weight,
                   KV_ELEM_BYTES_DEFAULT)


def bytes_per_token_min(m: ModelSpec, quant: str, ctx: int,
                        kv_elem_bytes: float = KV_ELEM_BYTES_DEFAULT) -> Dict:
    """Bytes a decode token must move, minimum: weights + state (KV read at ctx)."""
    bpw = bytes_per_weight(quant)
    weights = m.weight_bytes_per_token(bpw)          # EST: uniform-rate quant mix
    kv_per_tok = m.kv_bytes_per_token(kv_elem_bytes)
    kv_read = kv_per_tok * ctx                       # exact linear-in-ctx model
    acts = 4 * m.hidden * 2.0 * 2.0                  # EST: few hidden-size vector r/w (~10 KB)
    return {
        "weights_bytes_est": weights,
        "kv_read_bytes_est": kv_read,
        "act_bytes_est": acts,
        "state_bytes_est": kv_read + acts,
        "bytes_per_token_min_est": weights + kv_read + acts,
        "kv_slope_bytes_per_ctx_token_est": kv_per_tok,
    }


def decode_roofline(m: ModelSpec, d: DeviceClass, ctx: int,
                    quant: str = "q4km",
                    kv_elem_bytes: float = KV_ELEM_BYTES_DEFAULT,
                    apply_thermal: bool = True,
                    bw_frac: Optional[float] = None,
                    thermal_frac: Optional[float] = None) -> Dict:
    """All returned numbers are ESTIMATES (key suffix _est); ctx parametric (linear KV)."""
    q = canonical_quant(quant)
    bt = bytes_per_token_min(m, q, ctx, kv_elem_bytes)
    bpt = bt["bytes_per_token_min_est"]

    bw_eff = d.effective_bw_gbs(apply_thermal=apply_thermal, bw_frac=bw_frac,
                                thermal_frac=thermal_frac) * 1e9
    tok_s_mem = bw_eff / bpt

    flops = m.flops_per_token_decode(ctx)
    flops_dense = 2.0 * m.decode_params()
    flops_attn = flops - flops_dense
    th = (thermal_frac if thermal_frac is not None else d.sustained_thermal_frac) \
        if apply_thermal else 1.0
    gflops_eff = d.total_matmul_gflops_est() * th * 1e9
    tok_s_comp = gflops_eff / flops

    bottleneck = "MEM" if tok_s_mem <= tok_s_comp else "COMPUTE"
    headroom = max(tok_s_mem, tok_s_comp) / min(tok_s_mem, tok_s_comp)
    return {
        "quant": q,
        "ctx": ctx,
        "bytes_per_token_est": bt,
        "flops_per_token_min_est": flops_dense,       # dense GEMV math only
        "flops_per_token_attn_overhead_est": flops_attn,  # grows linearly with ctx
        "flops_per_token_total_est": flops,
        "bw_effective_gbs_est": bw_eff / 1e9,
        "gflops_effective_est": gflops_eff / 1e9,
        "tok_s_mem_bound_est": tok_s_mem,
        "tok_s_compute_bound_est": tok_s_comp,
        "tok_s_decode_est": min(tok_s_mem, tok_s_comp),
        "bottleneck": bottleneck,
        "compute_headroom_x": headroom,
        "apply_thermal": apply_thermal,
        "annotated": "parametric EST; recalibrate bw/thermal/gflops via --bw/--thermal/--bw-frac",
    }


def decode_ctx_curve(m: ModelSpec, d: DeviceClass, ctxs: List[int],
                     quant: str = "q4km", **kw) -> List[Dict]:
    """decode_roofline over a ctx grid (KV grows linearly -> tok/s decays)."""
    return [decode_roofline(m, d, c, quant=quant, **kw) for c in ctxs]
