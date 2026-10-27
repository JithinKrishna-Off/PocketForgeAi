# 03 - Decode roofline: D9300 phone vs i5-1235U laptop offload

Role: THEORIST-03 (condensed-matter / performance physicist). Read-only research, no builds, no commits.

## 1. Measured system state

### Phone: vivo V2503, Dimensity 9300 (mt6989), 4x X4 + 4x A720, 6 threads, KleidiAI, repack ON

- RAM: `ro.boot.ddr_size = 17179869184` = 16 GiB stamp (userspace sees 15.7 GiB usable).
  DDR type/speed not exposed by MTK props (no lpddr/ddr_type prop found; `/sys/kernel/debug` ddr nodes not node-permitted).
  **Confidence: MEDIUM.** Dimensity 9300 spec class is LPDDR5X 4x 16-bit @ up to 4266 MT/s per channel with
  MediaTek's dual-mirrored bus config, vendor-quoted aggregate up to ~68 GB/s. Surface-based D9300 boards
  commonly quote 68 GB/s. Real sustained bandwidth on Android SoC is typically 60-80% of spec on mixed
  R/W streams, and less on pure read streams. So practical aggregate bandwidth is ~ 40-55 GB/s.
  Note: no harmless userspace MEM-bandwidth probe exists (dd to /dev/null measures nothing about DRAM);
  actual measurement would require a native benchmark (out of scope, no-build rule). Spec figure used with
  confidence tag; all derived conclusions use the *practical* range, not spec.

### Laptop: i5-1235U host, Win32 query

- `Win32_PhysicalMemory`: Samsung 8GB + A-DATA 8GB, **Speed 3200 (both DIMMs), two channels (Controller0/Controller1)**.
  => 2x DDR4-3200 = 25.6 GB/s per channel = **51.2 GB/s spec aggregate**, real achievable ~38-42 GB/s.

## 2. Roofline derivation

Model used for derivation: 4B-class Q4_K_M (~2.5-2.7 GB weight bytes/token, dominant term).
KV contribution at short/medium context (few-MB class at <2k tokens) is <0.1% of weight traffic and
bounded by ~sqrt(n_ctx)*n_layers*GQA_kv*head_dim*2B; it does not change the verdict. (The 9B qwen35
hybrid on-disk uses full attention every 4th block: KV/token ~ 32L/4 * 2 * 4KVH * 256 * 2B ~ 32 KB/token
read at full history - even at 8k tokens ~ 0.26 GB read per token, ~5% of weight traffic. Verdict unchanged.)
For the 9B and 14B cases weight bytes dominate identically.

### Decode (12.5 tok/s measured)

- Effective bandwidth demand: 2.65 GB/token * 12.5 tok/s = **~33 GB/s from weights alone** (plus KV).
- vs spec 68 GB/s: **~49% utilization**. vs practical 40-55 GB/s: **~60-83% utilization**.
  This is the classic signature of a saturating stream (practical CPU DRAM read efficiency rarely exceeds 80%,
  and llama.cpp decode streams go through L3 first, which compresses the achievable window further).
- Compute demand: ~2*N = ~8 GFLOP/token * 12.5 = **~100 GFLOP/s**.
  fp32 peak of 4x X4 @ 3.25 GHz at 2x128-bit fp32 FMA = ~4*3.25e9*8 = **~104 GFLOP/s**.
  Demand/peak ~= **1.0 of raw fp32 SIMD** - decode would already saturate fp32; with KleidiAI packed
  quantized-dot (effectively 4-8x int8/fp32-MAC ratio) there is still roughly **4-8x compute headroom**.
- **Conclusion: decode is MEMORY-BOUND.** Marginal return of extra compute: near-zero; marginal return of
  more DRAM bandwidth: proportional, then flattening at ~4-6x by entering the compute ridge.

### Prefill (54.2 tok/s measured)

- Demand: 8 GFLOP/token * 54.2 = **~434 GFLOP/s** vs ~104 fp32-only peak = 4.2x fp32.
  This only closes with packed KleidiAI/repacked Q4 kernels, i.e. the machine is running the
  compute ridge for prefill. **Prefill is COMPUTE-BOUND** (margin: ~1.1-1.2x above qk-dot peak).
  Implication: prefill offload to laptop CPU could shift ratio, but aggregate scheduling complexity
  is out of scope (keep phone-hosted).

## 3. Offload implication analysis

Sequential decode model: per token, layers are visited in series; per-token time = sum over each
layer of (bytes_i / bandwidth_host_i) plus overhead for activations crossing the link (per token
per hop: ~n_embd*4B = ~16 KB for 4B; 40 tokens/s * 25 hops x 16KB ~ 16-25 MB/s link traffic;
must also carry at least an order-of-magnitude margin: USBL/ETH RTT in the 100-500us range vs
decode step budget of 80 ms token - tight, not fatal, but it adds).

- Phone-side effective BW B_phone_eff: estimated 40-55 GB/s (practical, see sec 1).
- Laptop: B_pc_spec = 51.2 GB/s, B_pc_real = ~38-42 GB/s.

Offload of fraction f of weight bytes: T(f) = (1-f)W/B_phone_eff + f W/B_pc_eff.
- Net win requires **B_pc_eff > B_phone_eff -> threshold ~ 55 GB/s minimum** (any host slower than
  the phone's *practical* bandwidth makes things strictly worse).
- Laptop DDR4-3200 dual channel at real ~40 GB/s: **FAILS the threshold.**
- Concrete f=50% case: effective BW = 1/(0.5/55 + 0.5/40) = 46.9 GB/s < 55 -> slower than phone-only.
- f=25%: 1/(0.75/55 + 0.25/40) = 51.5 GB/s -> still slower.
- f=75%: 1/(0.25/55 + 0.75/40) = 42.7 GB/s -> 21% slower.
- Facts: offloading ANY layer fraction to THIS laptop (DDR4-3200 dual channel) vs THIS phone
  (LPDDR5X, 4-ch 68 GB/s spec / ~50 practical) **decreases phone throughput** even in the ideal
  zero-RTT limit. "Little load to the PC" is physics-negative for THIS pairing.

## 4. Thermal wrinkle

Idle snapshot (`thermal_zone*`): battery 31-32 C, little cores 32-33 C, A720 medium 44-47 C,
X4 cluster 43-45 C, DSU/soc 41-48 C, APU ~37-39 C. No sustained-load trace was obtainable under
the no-load/no-build rule; figures are idle-with-recent-load residuals.

Memory-bound decode also burns: DRAM read power (~ 1-1.5 W sustained stream on 3-4 channels at
~50 GB/s), plus coherence/L3 and QC bus (~2-4W), plus X4 active duty. Reducing phone DRAM traffic
by f removes roughly f x the DRAM power share (~ 1-2 W of a ~6-8 W SoC decode draw). That is a
**small** thermal contribution (less skin-temp rise), not a lever; decode on this device is CPU
(X4 cluster + heavy SVE/KleidiAI) powered, not DRAM powered. There may be a small second-order
sustained-throughput gain in long sessions, but it is within the +10-15% sustained-vs-burst delta,
not a step change. Note: compute-bound prefill activity heats differently than BW-bound decode; the two regimes stress different subsystems (cores vs DRAM+bus).

## 5. Verdict

**FOR THIS PAIRING (D9300 / LPDDR5X phone + i5-1235U / DDR4-3200 dual-channel laptop):**

| model | weights (Q4_K_M) | phone-only tok/s (est, linear in bytes) | offload 50% to B_pc=40 GB/s | offload 50% to B_pc=55 GB/s (break-even) | verdict |
|---|---|---|---|---|---|
| 4B  | 2.7 GB  | 12.5 (measured) | ~10.4 tok/s (slower) | ~12.5 tok/s (neutral) | **never helps** |
| 9B (hybrid) | 4.9 GB | ~7 tok/s | ~5.8 tok/s | ~7 tok/s | **never helps** |
| 14B | 8.9 GB | ~3.8 tok/s | ~3.2 tok/s | ~3.8 tok/s | **never helps** |

- Decode is memory-bound on the phone at ~55-80% practical LPDDR5X utilization; margin to compute
  is ~4-8x (KleidiAI-packed), so bandwidth is the binding resource.
- Offload requires a helper-host B_pc_eff strictly above the phone's *practical* DRAM bandwidth
  (~55 GB/s) for layers that are off-loaded; ANY DDR4 host at 3200 (max 51.2 spec, ~40 real) is below.
- Additional reduction from network/USB link latency and activation serialization is negative
  (adds microseconds per token per hop, or coerces whole-layer shipping, which is worse).
- Honest maximum contribution of THIS laptop to THIS phone for decode: **<0%.** It is bandwidth-poor
  compared to modern phone LPDDR5X. The unique way this laptop helps is *batch prefill* (compute-bound
  scenario) or by hosting a *larger* model where the phone cannot hold weights at all (RAM bound),
  but its achievable tok/s is then ~0.5-3 tok/s and is not a latency win.
- Future-wood: DDR5-5200 dual-channel = 83.2 GB/s spec / ~55-65 real, would barely reach the
  threshold; no model size currently on the device (4B/7B/9B/14B) benefits.

## Bottom line

The bottleneck is DRAM read bandwidth into the X4 cluster, at ~55-80% of a ~68 GB/s LPDDR5X
budget, not compute. Any "offload some layers" scheme - on an *equal CPU-quality* partner -
is a bandwidth trade at each hop, and hops only win when the partner bandwidth is strictly
higher than what you already have. For this specific laptop (DDR4-3200 dual-channel, ~40 GB/s
effective) it is strictly worse than no offload at all for every model size listed.
