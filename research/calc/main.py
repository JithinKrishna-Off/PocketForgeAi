"""CLI: parametric decode/prefill calculator for GGUF quant classes on phone SoCs.

Example:
  python research/calc/main.py --preset vivo-x200-fe --model qwen3-4b --quant q4km --ctx 2048

All printed numbers are ESTIMATES (annotated); override params with --bw (GB/s
theory override) and --thermal (0..1 sustained fraction), --bw-frac, --gflops-scale.
Recalibration notes: research/R4_theory.md.
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from device import get_device          # noqa: E402
from model import get_model, canonical_quant, bytes_per_weight  # noqa: E402
from decode import decode_roofline     # noqa: E402
from prefill import prefill_roofline, DEFAULT_GFLOPS_PER_CORE  # noqa: E402

GB = 1e9


def run_calc(args):
    d = get_device(args.preset)
    m = get_model(args.model)
    q = canonical_quant(args.quant)
    thermal = args.thermal if args.thermal is not None else None
    # --bw overrides the THEORY bandwidth (the exact formula's channel geometry knob)
    if args.bw is not None:
        mt = args.bw * 1000.0 / (d.dram_channels * d.dram_width_bits / 8.0)
        d.dram_mt_per_s = mt
    if args.bw_frac is not None:
        d.achievable_bw_frac = args.bw_frac
    if thermal is not None:
        d.sustained_thermal_frac = thermal

    dec = decode_roofline(m, d, args.ctx, quant=q,
                          kv_elem_bytes=args.kv_elem_bytes,
                          apply_thermal=not args.no_thermal)
    pre = prefill_roofline(m, d, args.prefill_tokens, quant=q,
                           kv_elem_bytes=args.kv_elem_bytes,
                           thermal_frac=(None if thermal is None else thermal),
                           ctx0=0)
    levers = build_levers(m, d, dec, args)
    return d, m, dec, pre, levers


def build_levers(m, d, dec, args):
    """Top-3 actionable levers, ranked by impact on decode tok/s (all EST)."""
    bpt = dec["bytes_per_token_est"]["bytes_per_token_min_est"]
    bw_eff = dec["bw_effective_gbs_est"]
    tok0 = dec["tok_s_decode_est"]

    def tok_with_bytes(new_bpt):
        return bw_eff * GB / new_bpt if dec["bottleneck"] == "MEM" else tok0

    candidates = []
    if dec["bottleneck"] == "MEM":
        # 1) bandwidth
        candidates.append((
            "raise usable DRAM bw +20% (kernel/scheduling; --bw-frac 0.65->0.78 est)",
            bw_eff * 1.2 * GB / bpt))
        # 2) thermal headroom: 0.85 -> 1.00
        th = d.sustained_thermal_frac
        if th < 1.0:
            candidates.append((
                f"recover thermal derate (sustained frac {th:.2f} -> 1.00: cooling/clocks)",
                bw_eff / th * GB / bpt))
        # 3) KV cache width
        kv = dec["bytes_per_token_est"]["kv_read_bytes_est"]
        w = dec["bytes_per_token_est"]["weights_bytes_est"]
        a = dec["bytes_per_token_est"]["act_bytes_est"]
        for label, div in (("8-bit (q8_0 est)", 2.0 / (34.0 / 32.0)),
                           ("4-bit (q4_0 est)", 2.0 / (18.0 / 32.0))):
            candidates.append((f"KV cache -> {label}", bw_eff * GB / (w + kv / div + a)))
        # 4) weights one step lower
        q_now = bytes_per_weight(dec["quant"])
        if dec["quant"] in ("q4km",):
            candidates.append(("weights Q4_K_M -> Q4_0 (0.57->0.5625 B/w est)",
                               bw_eff * GB / (w * (18 / 32.0) / q_now + kv + a)))
        # 5) shorter context
        half = args.ctx // 2
        candidates.append((f"halve ctx {args.ctx} -> {half} (KV linear in ctx)",
                           bw_eff * GB / (w + dec["bytes_per_token_est"]["kv_slope_bytes_per_ctx_token_est"] * half + a)))
    else:
        candidates.append(("raise matmul GFLOPS +20% (--gflops-scale est)",
                           dec["tok_s_compute_bound_est"] * 1.2))
        candidates.append(("recover thermal derate on compute (cooling/clocks)",
                           dec["tok_s_compute_bound_est"] / (d.sustained_thermal_frac)))
        candidates.append(("increase tokens per weights pass (batch/speculative; moves roofline)",
                           dec["tok_s_decode_est"] * 1.2))

    levers = sorted(candidates, key=lambda x: x[1], reverse=True)[:3]
    return [(label, tok0, tok1) for label, tok1 in levers]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="parametric decode/prefill roofline calc (EST)")
    ap.add_argument("--preset", default="vivo-x200-fe",
                    help="device preset key (see device.PRESETS)")
    ap.add_argument("--model", default="qwen3-4b", help="model preset key")
    ap.add_argument("--quant", default="q4km", help="q4km|q4_0|q5km|q6k|q8_0|fp16")
    ap.add_argument("--ctx", type=int, default=2048, help="context (KV) tokens")
    ap.add_argument("--prefill-tokens", type=int, default=None, help="prompt chunk T (default: ctx)")
    ap.add_argument("--bw", type=float, default=None, help="OVERRIDE theory DRAM GB/s")
    ap.add_argument("--bw-frac", type=float, default=None, help="OVERRIDE achievable bw frac (est 0.5-0.75)")
    ap.add_argument("--thermal", type=float, default=None, help="OVERRIDE sustained thermal frac (0..1 est)")
    ap.add_argument("--kv-elem-bytes", type=float, default=2.0, help="KV cache bytes/elem (2=fp16 est)")
    ap.add_argument("--no-thermal", action="store_true", help="drop thermal derate (burst benchmark mode)")
    args = ap.parse_args(argv)
    args.prefill_tokens = args.prefill_tokens if args.prefill_tokens is not None else args.ctx

    d, m, dec, pre, levers = run_calc(args)

    bt = dec["bytes_per_token_est"]
    print("=" * 78)
    print(f"Device : {d.summary()}")
    print(f"         usable bw est {d.dram_theory_bw_gbs:.2f} x {d.achievable_bw_frac:.2f} (frac est)"
          f" x {d.sustained_thermal_frac:.2f} (thermal est) = {d.effective_bw_gbs():.1f} GB/s"
          f" | matmul param est {d.total_matmul_gflops_est():.0f} GFLOPS"
          f" {DEFAULT_GFLOPS_PER_CORE}")
    print(f"Model  : {m.display}  L{m.n_layers} H{m.n_heads}/KV{m.n_kv_heads} hd{m.head_dim}"
          f" hidden{m.hidden} ffn{m.ffn} vocab{m.vocab}"
          f" tied={m.tied_embeddings}  [{m.config_confidence}]")
    print(f"         total params est {m.storage_params()/1e9:.2f}B"
          f" (card {m.total_params_hf/1e9:.2f}B) | quant {dec['quant']}"
          f" @ {bytes_per_weight(dec['quant']):.4f} B/w (est; recalibrate: file_GB/params)")
    print(f"         storage est {m.weight_bytes_storage(bytes_per_weight(dec['quant']))/GB:.2f} GB")
    print("-" * 78)
    print(f"DECODE @ ctx {dec['ctx']} (all est):")
    print(f"  weights/token : {bt['weights_bytes_est']/GB:.3f} GB"
          f" | KV/token@{dec['ctx']}: {bt['kv_read_bytes_est']/GB:.3f} GB"
          f" (linear slope {bt['kv_slope_bytes_per_ctx_token_est']/1e3:.1f} KB/ctx-tok)"
          f" | total {bt['bytes_per_token_min_est']/GB:.3f} GB")
    print(f"  flops/token   : {dec['flops_per_token_min_est']/1e9:.2f} GFLOP dense"
          f" + {dec['flops_per_token_attn_overhead_est']/1e9:.2f} GFLOP attn overhead (linear in ctx)")
    print(f"  MEM bound     : {dec['tok_s_mem_bound_est']:.1f} tok/s"
          f"   [{dec['bw_effective_gbs_est']:.1f} GB/s / {(bt['bytes_per_token_min_est']/GB):.2f} GB]")
    print(f"  COMPUTE bound : {dec['tok_s_compute_bound_est']:.1f} tok/s   (ideal GEMV)")
    print(f"  VERDICT       : BOTTLENECK={dec['bottleneck']}"
          f" (headroom {dec['compute_headroom_x']:.2f}x over bound)")
    print("-" * 78)
    print(f"PREFILL @ {pre['chunk_tokens']} tokens (all est):")
    print(f"  flops/token   : {pre['flops_per_token_est']/1e9:.2f} GFLOP"
          f" (total {pre['flops_total_est']/1e12:.1f} TFLOP)")
    print(f"  compute       : conservative {pre['tok_s_conservative_est']:.0f} tok/s"
          f" ({pre['t_conservative_s_est']:.0f}s) | optimistic {pre['tok_s_optimistic_est']:.0f} tok/s"
          f" ({pre['t_optimistic_s_est']:.0f}s)")
    print(f"  MEM bound     : {pre['tok_s_mem_bound_est']:.0f} tok/s"
          f" (weights {pre['bytes_moved_est']['weights_bytes_est']/GB:.2f} GB +"
          f" KV-w {pre['bytes_moved_est']['kv_write_bytes_est']/GB:.2f} GB +"
          f" KV-read {pre['bytes_moved_est']['kv_read_bytes_est']/GB:.2f} GB +"
          f" acts {pre['bytes_moved_est']['act_bytes_est']/GB:.2f} GB)")
    print(f"  VERDICT       : BOTTLENECK={pre['bottleneck']}")
    print("-" * 78)
    print("TOP-3 LEVERS (est, on decode tok/s):")
    for i, (label, t0, t1) in enumerate(levers, 1):
        print(f"  {i}. {label}: {t0:.1f} -> {t1:.1f} tok/s ({100*(t1/t0-1):+.1f}%)")
    print("=" * 78)
    print("All values ESTIMATED; recalibrate via --bw/--thermal/--bw-frac and")
    print("R4_theory.md (confidence + how-to-recalibrate per parameter).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
