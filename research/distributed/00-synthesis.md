# Distributed Inference Synthesis (phone <-> PC)

Lead synthesis of the 10 theory agents (00-10). Agent 06 (transport math) was
cancelled; its key numbers were re-measured by agent 01, so nothing critical is
missing. All numbers below come from the per-topic docs in this folder; read
them for derivations. Machine facts: phone = Dimensity 9300, LPDDR5x, 15.7 GB;
laptop = i5-1235U, DDR4-3200 dual channel, Iris Xe (no Vulkan); baseline = 4B
Q4_K_M decode 12.5 tok/s, prefill 54.2 tok/s.

## The honest physics

| Quantity | Phone (demonstrated) | Laptop (PC, this one) |
|---|---|---|
| Weight-stream bandwidth | ~29-33 GB/s effective (2.30-2.55 GB weights x 12.5 tok/s) | 20-23 GB/s measured under load; ~38-42 GB/s spec-real idle |
| Compute | ample for decode | ~250 GFLOP/s numpy peak |
| Per-token energy/heat | ~0.76 J/token, SoC clamps to 4-6 W after ~6 min | ~0.24 J/token class work, no throttle observed |
| Latency to phone | LAN RTT 4-12 ms; USB adb 125 MB/s | same |

Agents 03 and 04 disagree on laptop bandwidth (04 measured 20-23 GB/s while the
machine was 40% loaded, 03 derived 38-42 GB/s from spec + real dual-channel
behavior). The discrepancy does not change the verdict: decode is memory-bound
and the phone already streams weights faster than this laptop can, before
adding 2 RTT/token of network overhead.

## Verdict by strategy

1. **RPC layer offload (PC hosts layers): does NOT speed up this pairing.**
   Whole-graph RPC recomputes the graph per token (~4 RTT); even cached-weight
   layer offload loses because laptop DDR4-3200 < phone LPDDR5x effective
   bandwidth for 4B/9B/14B quantized sizes. Break-even only for models that do
   not fit the phone at all (e.g. 14B at high quant: ~2x vs nothing). Useful as
   a RAM lifeline, not a speedup. Details: 01, 03.
2. **Speculative decoding (PC drafts, phone verifies): the real speedup.**
   Pipelined K=4 draft-verify hides RTT; +15-35% decode at acceptance
   alpha >= 0.72-0.8 (alpha* ~ 0.72 from 02); 14.5 tok/s modeled at alpha 0.8
   on the 4B baseline with a 0.5B draft. Thermally it is the biggest win:
   ~0.24 J/token PC drafting means the phone spends verify-only energy and the
   sustained rate stops collapsing. Cost: effort L - new JNI verify primitive
   (llama_decode on multiple drafted sequences + accept logic), /v1/spec/turn
   + /v1/spec/verify endpoints, acceptance measurement on real qwen3.5 chats.
   Greedy/sampling caveats in 02.
3. **Thermal is the hidden ceiling.** Burst 12.5 tok/s decays to ~5-7 tok/s
   sustained (skin-plane limited 4-6 W). Any plan must be judged on sustained
   numbers. Draft-verify keeps the phone near burst; nothing else does (07).
4. **KV q8_0 is free money and already shipped as a plan flag** (kvTypeK/kvTypeV
   in pf_plan): -46% KV RAM, +6-18% decode speed, quality impact negligible for
   chat. Default-on candidate (05, 09). IQ4_XS weights add ~+9% decode; 9B fits
   on the phone with IQ4_XS + q8_0 KV at 16k ctx (05).
5. **Batching is not worth it** for 2 streams on this SoC (08). Replace the
   bare 409 with a turn-level bounded priority queue in Kotlin (~100-150 LOC):
   queue_depth + retry_after instead of client-side retry storms.
6. **Two real bugs/risks found:** context shift desyncs DeltaNet state on
   qwen35 (09); pf_plan's stale rule "q4_0 + FA invalid" blocks safe
   combinations (09). MCP loops should always reset_session between unrelated
   turns to exploit KV reuse (5.3x wall-clock, 18.6x prefill per 09).

## Recommended ladder

- **P0 (do now, phone-only, no distributed code):** default KV q8_0 for new
  plans; offer IQ4_XS in the hub; fix context-shift state bug; add turn
  priority queue (429 + queue_depth + retry_after); call reset_session in the
  desktop MCP loop. Expected combined: sustained decode noticeably above the
  5-7 tok/s floor, KV headroom for 9B.
- **P1 (one evening, settles the offload question with data):** build
  -DGGML_RPC=ON, run the layer sweep from 01 on LAN, record tok/s vs f in
  [0, 0.75]. Expected result: flat or worse - but then the claim is measured,
  not argued.
- **P2 (the payoff, effort L):** pipelined speculative prototype per 02:
  acceptance measurement first (cheap: log phone-only draft-free outputs vs a
  0.5B draft on the laptop for 20 real prompts), then the verify primitive.
  Target: sustained >= 10-12 tok/s on 4B, and the same path is what makes a
  9B usable at 8+ tok/s sustained.

## Bottom line for "let the PC take a little load"

Not as extra layers (physics says no for this hardware pair), but as a draft
engine and as a thermal sink - that is where the PC genuinely buys tok/s, and
the cheap wins in P0 are worth shipping regardless.
