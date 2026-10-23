"""Plain assert tests for research/calc (run: python research/calc/test_calc.py).

Exit code 0 == all pass. All thresholds below tolerate the EST parameter values;
they test FORMULAS and invariants, not calibration.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from device import get_device, dram_theory_bw_gbs  # noqa: E402
from model import get_model, bytes_per_weight      # noqa: E402
from decode import decode_roofline                 # noqa: E402
from prefill import prefill_roofline               # noqa: E402

GB = 1e9
PASS = []


def check(name, cond, detail=""):
    assert cond, f"FAIL {name}: {detail}"
    PASS.append(name)


def test_bw_formula():
    d = get_device("vivo-x200-fe")
    theory = dram_theory_bw_gbs(4, 16, 8533.0)
    check("bw_formula_exact", abs(theory - 68.264) < 1e-9, f"{theory}")
    check("bw_formula_property", abs(d.dram_theory_bw_gbs - theory) < 1e-9)
    check("bw_effective_chain",
          abs(d.effective_bw_gbs() - 68.264 * 0.65 * 0.85) < 1e-9,
          f"{d.effective_bw_gbs()}")
    check("bw_effective_no_thermal",
          abs(d.effective_bw_gbs(apply_thermal=False) - 68.264 * 0.65) < 1e-9)


def test_kv_growth():
    m, d = get_model("qwen3-4b"), get_device("vivo-x200-fe")
    check("kv_cache_linear", abs(m.kv_cache_bytes(4096) - 2 * m.kv_cache_bytes(2048)) < 1e-6)
    # decode bytes/token: KV part doubles with ctx, weights fixed
    b1024 = decode_roofline(m, d, 1024, quant="q4_0")["bytes_per_token_est"]
    b2048 = decode_roofline(m, d, 2048, quant="q4_0")["bytes_per_token_est"]
    check("kv_growth_decode_linear",
          abs((b2048["kv_read_bytes_est"] - b1024["kv_read_bytes_est"])
              - b1024["kv_read_bytes_est"]) < 1e-6)
    check("kv_weights_invariant",
          abs(b2048["weights_bytes_est"] - b1024["weights_bytes_est"]) < 1e-6)
    check("kv_slope_known",
          abs(b2048["kv_slope_bytes_per_ctx_token_est"]
              - 2 * m.n_kv_heads * m.head_dim * m.n_layers * 2.0) < 1e-6)


def test_7b_vs_4b_bytes_per_token():
    m7, m4 = get_model("qwen2.5-7b-instruct"), get_model("qwen3-4b")
    d = get_device("vivo-x200-fe")
    b7 = decode_roofline(m7, d, 1024, quant="q4km")["bytes_per_token_est"]["bytes_per_token_min_est"]
    b4 = decode_roofline(m4, d, 1024, quant="q4km")["bytes_per_token_est"]["bytes_per_token_min_est"]
    r = b7 / b4
    check("ratio_7b_over_4b_within_15pct_of_1.75",
          abs(r - 1.75) / 1.75 <= 0.15, f"ratio={r:.3f}")
    # param-count sanity vs HF card values (HIGH-confidence geometry)
    check("params_4b_sanity", abs(m4.storage_params() - 4.02e9) / 4.02e9 < 0.01,
          f"{m4.storage_params()}")
    check("params_7b_sanity", abs(m7.storage_params() - 7.62e9) / 7.62e9 < 0.01,
          f"{m7.storage_params()}")


def test_quant_byte_math():
    check("q8_0_exact", bytes_per_weight("q8_0") == 34 / 32)
    check("q4_0_exact", bytes_per_weight("q4_0") == 18 / 32)
    check("q6k_exact", bytes_per_weight("q6k") == 210 / 256)
    check("q4km_est_band", 0.50 < bytes_per_weight("q4km") < 0.62)


def test_decode_bottleneck_and_overrides():
    m, d = get_model("qwen3-4b"), get_device("vivo-x200-fe")
    r = decode_roofline(m, d, 2048, quant="q4km")
    check("decode_mem_bound_4b_2048", r["bottleneck"] == "MEM")
    check("decode_headroom_positive", r["compute_headroom_x"] > 1.0)
    check("decode_tok_s_positive", 0 < r["tok_s_decode_est"] < 1e4)
    check("decode_ideal_compute_ge_mem", r["tok_s_compute_bound_est"] >= r["tok_s_mem_bound_est"])
    # thermal off => strictly faster (same bytes)
    r_nt = decode_roofline(m, d, 2048, quant="q4km", apply_thermal=False)
    check("thermal_off_faster", r_nt["tok_s_decode_est"] > r["tok_s_decode_est"])
    # --bw override scales linearly (MEM-bound)
    r_bw = decode_roofline(m, d, 2048, quant="q4km")
    scale = 1.25
    r2 = decode_roofline(m, d, 2048, quant="q4km")
    r2["bw_effective_gbs_est"]  # noqa: B018 (shape check only)
    d2 = get_device("vivo-x200-fe")
    d2.achievable_bw_frac *= scale
    r2s = decode_roofline(m, d2, 2048, quant="q4km")
    check("bw_override_linear",
          abs(r2s["tok_s_mem_bound_est"] / r_bw["tok_s_mem_bound_est"] - scale) < 1e-9)


def test_prefill_shape():
    m, d = get_model("qwen3-4b"), get_device("vivo-x200-fe")
    p = prefill_roofline(m, d, 2048, quant="q4km")
    check("prefill_cons_lt_opt",
          p["tok_s_conservative_est"] < p["tok_s_optimistic_est"])
    check("prefill_compute_bound_2k", p["bottleneck"] == "COMPUTE")
    check("prefill_faster_than_decode",
          p["tok_s_conservative_est"] > decode_roofline(m, d, 2048, quant="q4km")["tok_s_decode_est"])
    # quadratic KV read: 4x when tokens double
    kv2 = prefill_roofline(m, d, 4096, quant="q4km")["bytes_moved_est"]["kv_read_bytes_est"]
    kv1 = prefill_roofline(m, d, 2048, quant="q4km")["bytes_moved_est"]["kv_read_bytes_est"]
    check("prefill_kv_read_quadratic", abs(kv2 / kv1 - 4.0) < 0.01, f"{kv2/kv1:.3f}")


def test_all_presets_and_models_run():
    from device import PRESETS
    from model import MODELS
    for dk in PRESETS:
        d = get_device(dk)
        check(f"device_{dk}_bw_positive", d.dram_theory_bw_gbs > 1)
        for mk in MODELS:
            m = get_model(mk)
            r = decode_roofline(m, d, 1024, quant="q4km")
            p = prefill_roofline(m, d, 1024, quant="q4km")
            check(f"runs_{dk}_{mk}", r["tok_s_decode_est"] > 0 and p["tok_s_conservative_est"] > 0)


def test_cli_report():
    import main as cli
    args = cli.main.__globals__  # not used; build namespace manually
    class NS:  # minimal argparse.Namespace stand-in
        preset = "vivo-x200-fe"; model = "qwen3-4b"; quant = "q4km"
        ctx = 2048; prefill_tokens = 2048; bw = None; bw_frac = None
        thermal = None; kv_elem_bytes = 2.0; no_thermal = False
    ns = NS()
    d, m, dec, pre, levers = cli.run_calc(ns)
    check("cli_run_ok", dec["tok_s_decode_est"] > 0 and len(levers) == 3)
    check("cli_preset_applied", d.name == "vivo-x200-fe")


if __name__ == "__main__":
    test_bw_formula()
    test_kv_growth()
    test_7b_vs_4b_bytes_per_token()
    test_quant_byte_math()
    test_decode_bottleneck_and_overrides()
    test_prefill_shape()
    test_all_presets_and_models_run()
    test_cli_report()
    print(f"ALL TESTS PASSED ({len(PASS)} checks)")
    sys.exit(0)
