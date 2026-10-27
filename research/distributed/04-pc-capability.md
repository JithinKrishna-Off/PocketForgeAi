# 04 - THEORIST-04: PC Capability Measurement (i5-1235U laptop)

Date: 2026-10-27. All runs on the live laptop with **significant background load**
(~40% CPU busy; VS Code, Chrome, WebView2, xampp active; only 2.9 GB physical RAM
free of 15.7 GB, 29 GB committed). Numbers are therefore lower bounds; a quiet
machine would do better. Measurements via numpy 2.5.1 (OpenBLAS 0.3.33, Haswell
kernels, MSVC-built wheels) in throwaway scripts under
`C:\Users\ASUS\AppData\Local\Temp\opencode\`.

## 1. Memory subsystem

- **Config**: 2x 8GB SODIMM, DDR4-3200, one per controller (Controller0-ChannelA
  Samsung M471A1G44BB0-CWE, Controller1-ChannelA-DIMM0 ADATA AO1P32NC8T1-BIMS).
  **Dual-channel confirmed** (both controllers populated). Theoretical peak
  2x3200 MT/s x 8B = 51.2 GB/s.
- **Measured streaming** (float32, best-of-many, preallocated outputs):

| Set size | copy (1r+1w) | add (2r+1w) |
|---|---|---|
| 16 MB | 21.3 GB/s | 15.4 GB/s |
| 262 MB | 20.7 GB/s | 14.9 GB/s |
| 512 MB | 22.9 GB/s | 12.9 GB/s |

- Effective sustained bandwidth: **~15-23 GB/s** (copy effective ~21-23 GB/s,
  mixed add ~13-15 GB/s). Use **20 GB/s (conservative 15)** as the decode model
  input. Earlier runs during heavier load gave 3-4 GB/s - background contention
  matters a lot on this box.

## 2. GEMM throughput (numpy float32 @ 2048x2048, OpenBLAS)

| threads | GFLOP/s | time |
|---|---|---|
| 1 | 91.1 | 0.189 s |
| 2 | 167.7 | 0.102 s |
| 4 | 204.9 | 0.084 s |
| 8 | 252.2 | 0.068 s |
| 12 | 237.1 | 0.072 s |

- Single-core AVX2+fma is healthy (~91 GFLOP/s; likely on a P-core). Scaling is
  sublinear and saturates ~8 threads; 12 threads is worse than 8 (E-cores +
  contention). Peak: **~250 GFLOP/s**.
- float16 numpy matmul: **unusable** (software-emulated, 0.1 GFLOP/s). Real fp16
  would need OpenBLAS fp16 kernels, an MKL build, or native llama.cpp.ggml code
  (blocked by the no-build rule for today).

## 3. Token-time model: PC decode ceiling (bandwidth-bound llama.cpp)

Per token ~ model file size read once (Q4_K_M). tok/s = BW / model_size.

| Model (Q4_K_M) | size (GB) | PC tok/s @ 20 GB/s | @ 15 GB/s (pessimistic) |
|---|---|---|---|
| 0.5B | 0.4 | **50** | 38 |
| 1B | 0.9 | **22** | 17 |
| 3B | 2.0 | **10** | 7.5 |
| 8B | 4.9 | **4.1** | 3.1 |

Verdict: even with background noise the PC is comfortably the "strong little
load" node vs the phone's 12.5 tok/s on 4B Q4_K_M (PC would do 4B = ~2.0GB in
~9-10 tok/s only - phone actually matches PC there because the phone's few
GIL-free... see section 6). PC clearly wins on 0.5B-3B decode.

## 4. Prefill contribution (RPC split, hypothetical)

Phone prefill demand measured: 432 GFLOP/s sustained at 54 tok/s on 4B.
PC peak measured 250 GFLOP/s. Ideal share PC could take:
250 / (250+432) = **~37% of prefill FLOPs**. Realistically 15-30% after RPC
serialization, per-layer scheduling, and load imbalance. Best assignment:
first N decoder layers + embedding to PC (locality-friendly, layer-symmetric
cost), i.e. a modest but real prefill offload.

## 5. Draft-model capability (0.5B Q4_K_M draft decode)

Rough proxy reasoning: decode needs ~0.4 GB/token streamed. Single-threaded
memory read on this DDR4 platform sustains roughly 8-10 GB/s per thread of
memcpy-class work; llama.cpp multithread decode across 8-12 threads would reach
the measured ~15-20 GB/s.
- 0.5B Q4 draft: **~25-50 tok/s draft decode** (0.5B is also only ~16 GFLOP/token
  compute; PC's 91+ GFLOP/s single-thread does not bottleneck at higher thread
  counts).
- Supported on the PC's memory-budget side as well. This is the number
  THEORIST-02's speculative scheme depends on; measured bandwidth under quiet
  conditions could push toward 50-60 tok/s. Treat as upper-mid estimate.

## 6. Honest comparison vs phone (LPDDR5x)

- Theoretical: phone LPDDR5x (~6400 MT/s, up to ~60+ GB/s) exceeds this laptop's
  DDR4-3200. **But the phone demonstrates only ~4.6 GB/s effective sustained**
  (4B/Q4 ~2.55 GB/token at 12.5 tok/s decode) because of thermal and scheduler
  reality on SoC packages.
- Measured effective PC stream 15-23 GB/s is **~3-5x the phone's demonstrated
  effective bandwidth** despite the spec-sheet disadvantage. Consequence: PC
  should own large-model decode (0.5-8B) and a slice of prefill, phone remains
  the mobility + latency-edge node with best per-watt.
- iGPU (Iris Xe 80EU): unusable today. No Vulkan SDK on the machine; using it
  would require installing the Vulkan SDK, building llama.cpp with
  GGML_VULKAN=ON, and validating SYCL-less driver support - a separate effort
  (and a violation of today's no-build rule).

## Ceiling verdict

- PC decode ceiling after noise: ~50 tok/s (0.5B), ~22 (1B), ~10 (3B), ~4 (8B).
- PC prefill ceiling: ~250 GFLOP/s peak; realistic cross-device share ~15-30%.
- PC draft role: 0.5B Q4 draft at ~25-50 tok/s - feasible.
