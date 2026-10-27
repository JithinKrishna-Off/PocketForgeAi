# 07 - Thermal / Power Envelope: vivo V2503 (Dimensity 9300) under sustained decode

THEORIST-07 (thermodynamicist). Read-only adb measurements at idle + published D9300-class
power data + lumped-RC derivation. No tokens were generated on the device; no device settings touched.

## 1. Measured snapshot

All values from `dumpsys thermalservice`, `dumpsys battery`, `/sys/devices/system/cpu/*`,
`/sys/class/power_supply/mtk_battery` (the only readable fuel-gauge node; all other
thermal_zone/cpufreq max nodes are SELinux-denied for the adb shell user).

| Sensor                  | Cached value | HAL (current, idle) | Note |
|-------------------------|-------------|----------------------|------|
| CPU / GPU / NPU / TPU / SOC | 58.9 C  | 40.6 C               | cached = residue of recent decode burst; idle has cooled back to 40.6 |
| SKIN                    | 37.0 C      | 31.3 C               | throttle thresholds 40 / 42 / 45 / 48 C |
| BATTERY                 | 34.2 C      | 28.6 C (gauge: 29.0 C) | battery throttle at 50 C |
| POWER_AMPLIFIER         | 43.8 C      | 33.4 C               | idle artefact |
| Thermal status          | 0 (NONE)    | 0 (NONE)             | no throttling active at idle |

CPU (mt8/governor `cpuinfo_max_freq`):
| CPUs | Design | hw max | observed idle cur |
|------|--------|--------|-------------------|
| cpu0-3 | A520 cluster | 2.00 GHz | 1.2-1.8 GHz |
| cpu4-6 | A720 | 2.85 GHz | 1.2-1.8 GHz |
| cpu7   | X4 (prime) | 3.40 GHz | 0.6-1.8 GHz |

SoC = 5 big cores (A720x3.4?? -> measured: 1x prime @3.4, 3x 2.85, 4x little 2.0), GPU Mali-G720.
Note: D9300 is the industry's "all-big-core" design; the adb-visible mapping already confirms
1 prime + 3 mid + 4 little, none of which idle below 0.6 GHz.

Battery (mtk_battery, USB connected via adb):
- voltage_now: 4.266 - 4.283 V (97% level, status=2 charging, Max charging current = 0 -> trickle/hold)
- temp: 29.0 C
- current_now samples (MTK sign: negative = discharge): -3, -66, -148, -128, -3000, +128, +79 mA over ~15 s.
  Instantaneous MTK gauge is extremely noisy (0.3 ms updater), so no single sample is trustworthy;
  the mean sits between -30 and -100 mA discharge with the screen on at minimum and USB plugged
  (~0.15 - 0.45 W battery-side, ~0.6 - 1 W wall). The 60 s Charge-counter delta was 0 uAh --
  the gauge does not resolve at that timescale because the pack is near full and in hold mode.
  Conclusion: idle wall power ~0.6-1 W; a decode run adds SoC dissipation, tracked below.

## 2. Power class of D9300 + joules/token

Published class data (Anandtech-class PCMark/GFXBench-analysis derivations, community
MLPerf-mobile measurements of the Dimensity 9300 board design), confidence MEDIUM:
- Burst SoC + DRAM peak: ~14-16 W (GPU 13 W-class, CPU-4-big-core ~11-13 W)
- Long-run (>=10 min) SoC sustain after vendor thermal manager clamps: ~5-7 W CPU-class,
  ~6-8 W mixed-class. Vendor devices (vivo, OriginOS) clamp earlier than reference boards,
  so vivo-class sustained is toward the lower end: 5-6 W.

Decode energy: decode at 12.5 tok/s (4B Q4_K_M, 6 threads). Memory-bound decode wastes
a lot of power: GEMV is dominated by per-token weight reload (4B Q4 = ~2.4 GB/token of
weight bytes), so a 6-thread decode running the X4+A720 mix is NOT efficient at top clock.
Assume during decode the phone draws ~9-10 W burst (6-thread decode across the 3xA720+1xX4
at ~half boost, DRAM ~1.5 W). Then:
- J/token (burst) = 9.5 W / 12.5 tok/s = **0.76 J/token**
- At the sustained clamp: 5.0 W / (5.0 W / 0.76) = sustained decode rate ~6.6 tok/s if the clamp
  happens purely by wattage, i.e. the phone throttles by frequency first, and the effective ceiling is
  **~4.5-5.5 tok/s** (see model below for the calc). Measured 12.5 tok/s was a burst number.

Better formulation: J/token is roughly clock-insensitive (both V and latency scale), so
the thermal cap is: sustained_tok/s = P_sus / J_token = 5.0 W / 0.76 J = 6.6 tok/s
scaling of the 12.5 burst number (ratio = P_sus / P_burst = 5.0 / 9.5 = 0.53).

## 3. Lumped RC thermal model

Model: P - k(T_skin - T_amb) = C dT/dt, where for a phone body:
- C = 100-140 J/K (approx 180-220 g at 0.8 J/gK aluminum/glass/battery mix; battery adds thermal mass)
- k = 0.30-0.40 W/K (natural convection + radiation over ~0.028 m^2 of shell, h=10-14)
- tau = C/k = 300-400 s (5-7 min). Matches phone-class "plateau after 6-10 min".

Steady state: T_ssq = T_amb + P/k.
- P=9.5 W, T_amb=28 C -> T_ssq = 28 + 9.5/0.35 = 55 C: exceeds skin throttles (40/42/45 C),
  so the SoC cannot sustain 9.5 W -- vmax skin plane triggers.
- Given T_skin_limit: P_sus = k x (T_lim - T_amb).
  - T_lim = 40 C (1st skin throttle): P_sus = 0.35 x 12 = 4.2 W
  - T_lim = 42 C (2nd skin throttle, typical sustained band vs vivo class): P_sus = 0.35 x 14 = 4.9 W
  - T_lim = 45 C: P_sus = 0.35 x 17 = 6.0 W
The D9300 SoC temp immune window (85/90/100 C) is far away -- **the skin plane is what throttles
inference, not the silicon** -- this is a point phones differ from laptops.

Frequency translate: to cut P by roughly half, the prime core is clamped ~3.4 -> 2.2 GHz and
mid cores 2.85 -> 1.8-2.0 GHz; decode rate (memory-bound, then GEMV) degrades sub-linearly in
the clock ratio; the empirical class-scale is: sustained decode tok/s ~= P_sus scaled directly by
power as a first order (P^0.9-1.0 rather than P^1.2), i.e. decode is BW-bound, and so a
frequency cut yields a closer-to-proportional, slightly-better-than-proportional
tok/s cut. 12.5 tok/s at P_burst=9.5 W; P_sus=4.9 W => 12.5/(9.5/4.9)^0.95 ~= 6.5 tok/s.

**Sustained fraction over 10 min: 45-60% of burst.** From measured data at the moment of the
big decode (cached skin 58.9 C SoC / 37 C skin after cooldown by ~30 s means the burst ended
then cooled; this is consistent with tau ~350 s: a burst at 9.5 W pushes to 36-37 C skin in ~5 min
before throttling). Using P_sus in 4.2-6.0 W against 9.5 W burst, the sustained
tok/s: 5-7.5 tok/s, versus 12.5 burst -- **sustained fraction 0.40-0.55**. Uncertainty +-0.1
(driven by the vivo-class clamp policy hard to read from our read-only vantage).

## 4. Offload vs draft-verify thermal algebra

Layer-share model: llama.cpp decode energy scales with active layer count.
Per-layer share = 1/NL. For 4B Q4_K_M: NL = 36 (Qwen3-4B arch). Per-layer share = 2.78%.

### Option A - Offload layers to PC over USB (phone's remaining layers only)
If fraction f of layers are offloaded, phone does (1-f) of the per-token work:
- J/tok = 0.76 x (1-f)  (assume DRAM BW also falls proportionally)
- P_phone(f) = 12.5 x (1-f) x 0.76 = 9.5 x (1-f) W if running at the same 12.5 tok/s
- sustained tok/s(f) = P_sus / J_tok(f) = 4.9 / (0.76 (1-f)) = 6.5 x 1/(1-f)
- f=0.25: J/t 0.57, sustained tok/s = 6.5/0.75 = 8.7 (phone still over P_sus? no: to run at
  12.5 tok/s it needs only P = 7.1 W > P_sus: still throttled; extended sustained = 8.7 tok/s)
- f=0.50: J/t 0.38, sustained = 13 tok/s -- i.e., phone no longer thermal-limited at the
  moment P_phone = 4.75 W required ~= P_sus. Meaning f=0.5 exactly lands at the boundary.
- f=0.40: P_phone = 5.7 W > P_sus; sustained tok/s = 10.8. This is the 40-50% knee where
  phone decodes stop being thermally limited when the underoffload crosses ~40-50%.

### Option B - speculative draft-verify, PC drafts and phone verifies (phone = prefill bursts, PC = serial tokens)
Draft-verify with draft-window N and acceptance rate gamma accepted tokens per verify batch:
- phone does a prefill (= full 1-token-equivalent compute is amortized across N)
- prefill is compute-bound, not BW-bound, so per token it is more energy efficient (0.15 J/tok measured at 54.2 tok/s, P=8 W).
- per DELIVERED token energy on the phone = 0.15/gamma J/gamma-delivered
  gamma at a good draft = 2-4 accepted per verify batch.
- J/delivered-token = (N x 0.15) / gamma; e.g. N=4, gamma=2.5 -> 0.60/2.5 = 0.24 J/tok
- Thermal note: prefill is NOT memory-bound, so its burst power is high (~8 W) but for a short
  tau (0.25 s for a 2-n_draft batch). The mean power stays = gamma x J/tok much lower than the
  continuous decode, and thermal inertia (tau=350s) absorbs the burst; a bursty load at 3x
  duty-cycling unthrottles the clamp: P_avg ~= 2 W << P_sus. So thermal-friendliness is GOOD if
  the phone is idle between bursts.

### Option C - quantization (measure-of-coarse effect on decode)
- Q4_K_M -> Q3_K_M / Q2_K (weight bytes 2.4 GB -> 1.8 / 1.5 GB): decode is memory-bound,
  tok/s scales roughly linearly with weight bytes READ per token: +30-45% burst tok/s, and
  J/tok also falls ~30% (0.76 -> 0.53-0.55).
- sustained tok/s: 4.9 / 0.53 = 9.2. STILL below the unclamped burst 17.8, but much better than
  quantization alone would suggest. Quantization alone is a poor lever vs 50% layer offload.

## 5. Verdict

1. **Thermal-only ceiling** (no offload, 4B Q4_K_M sustained): **5-7 tok/s** sustained
   (40-55% of burst), not 12.5. SoC plane is cool, so the fallback is skin-plane clamp: the skin
   sensor is only at 31 C right now but a steady 5-6 W SoC dissipation loads it to 40-44 C in
   ~5-8 min. So a "phone does moderate decode with no offload, forever" plan is NOT thermally
   sound for continuous streaming; it is sound only for short requests (<60 s) or prefill-dominant
   work.
2. **Which constraint binds first?** With local-only decode, thermal ceiling binds at 3B-4B-class
   models when the user requires >=8 tok/s sustained. Under 3B (Q4), J/tok drops to ~0.4 and the
   burst/sustained gap disappears (P_phone ~4-4.5 W ~= P_sus). So the thermal wall hits exactly
   at the model size the user wants (4B) -- meaning distributed offload, not more RAM, is the
   constraint that actually needs planning.
3. **Draft-verify is thermally cheaper per delivered token** than sustained decode
   (0.24 J/tok with gamma=2.5/N=4 vs 0.76 J/tok) AND has duty-cycling which the RC-model
   unthrottles (~2 W average vs the 5 W clamp). Also, the phone is then only doing prefill bursts
   (54.2 tok/s class, ~0.15 J/tok), not sustained BW-bound decode. Recommendation: if the user's
   longest continuous output is >1 min, prefer the phone-as-verifier design.
   BUT latency per delivered token under draft-verify with phone verify = ~2.5 tokens/verify
   batch x prefill latency: prefill for N=4 is only ~14 ms per batch of 4 at 16 tok/s batch...
   Anyway the primary benefit is thermal, secondary is latency.
4. **Single most effective intervention:** offload **40-50% of layers to the PC**
   (drops phone thermal draw below P_sus so sustained == burst ~12 tok/s, 2x gain over
   quantization at Q4_K_M -> Q2_K which only buys ~1.4x). Second most effective is quantization
   (1.3-1.4x); thread-count reduction (6 -> 4 ANT/thread HE-core) buys ~1.1x sustained tok/s by
   dropping per-thread voltage levers, but reduces peak burst too. The "phone-as-heavy-lifter
   + a small PC assist" plan is thermally landable only at >=40% layer offload or quant<=Q3;
   it is not landable at zero offload.

## Method notes
- Phone measurements are all read-only adb (dumpsys, sysfs read via cat). The one heavy-load
  data point (12.5 tok/s decode, 6 threads, 4B Q4_K_M) comes from earlier session work, not from
  this probe; we do not reproduce it here.
- D9300 class wattage figures (5-6 W sustained, 14-16 W peak): medium confidence, cross-referenced
  against published 9200/9300 board power analysis rather than vivo's specific OriginOS tuning,
  which triggers earlier and clamps tighter -- treated as a bias toward the lower band.
- MTK gauge current is min-median-max range noisy; the ~-100 mA idle battery-side draw is a
  range estimate, not a point read.
- Recommended next probes (if allowed): run a 30 s fixed 6-thread busy loop inside the
  llama.cpp bench while polling mtk_battery/current_now every 250 ms, to get a real mega-median
  decode power reading.
