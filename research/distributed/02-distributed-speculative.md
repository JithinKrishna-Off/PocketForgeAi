# 02 - Distributed speculative decoding: PC drafts, phone verifies

Author: THEORIST-02 (research, information theory). Date: 2026-10-27.
Status: research only. Read-only analysis of ggml-org/llama.cpp (this tree) and
PocketForgeAI. No builds, no commits.

## 0. Executive summary

- Chosen scheme: PC-side greedy drafting (0.5-0.6B Q4 llama.cpp CPU build),
  phone-side batched greedy verification. Verification compute lives on the
  phone either way; the two candidate protocols (A: re-prefill per round,
  B: batched verify returning logits) differ only in packaging, not compute.
  Protocol B as "phone returns raw logits" is rejected as overkill; the
  recommended transport payload is (n_accepted, emitted token ids) from a new
  additively-versioned `/v1/spec/` endpoint pair.
- Round math: E[T] = sum_{k=0..K} alpha^k (greedy-match verification). With the
  measured phone numbers (decode 12.5 tok/s, prefill 54.2 tok/s headline) the
  decisive unknowns are (1) real small-batch verify throughput r_v(K+1), and
  (2) PC draft speed r_d. Both must be measured (Phase 0) before any code.
- Sequential mode (draft on PC, wait, verify on phone) is dead on arrival with
  these hardware numbers: best case K=4, alpha=0.8 is about 7.8 tok/s.
- Pipelined mode (PC drafts round i+1 while the phone verifies round i) is the
  only regime that can beat 12.5 tok/s: it needs alpha >= ~0.65-0.75 at K=4 and
  r_d >= 20 tok/s. Best modeled case: ~16.8 tok/s (+34%) at alpha=0.8, K=4,
  r_v = 54.2 tok/s. At a plausible realistic alpha of 0.55-0.65 it loses.
- Verdict: viable only as an opt-in pipelined "turbo" prototype behind a
  measurement gate; do not implement blind.

## 1. What llama.cpp speculative decoding actually does (this tree)

All references are to the pinned tree at C:\Users\ASUS\Desktop\llama.cpp.

Draft side (`common/speculative.cpp`, impl `draft-simple` at lines 220-479):

- A draft chain is per-sequence: `draft()` seeds with `id_last` at `pos0`
  (line 385), runs one `llama_process(DECODE)`, samples greedily with a
  top_k=10 sampler (lines 275-283, 406-417), appends the token, decodes it,
  repeats, until `n_max` tokens or early stop when the candidate prob
  `p < p_min` (line 423). Drafts shorter than `n_min` are dropped (line 470).
- Drafted tokens have no logits returned to the caller; the caller only gets
  `result`. `p_min` is the phone-side equivalent of "adaptive K": it trades
  round length for wasted verify rows.
- Greedy drafting defaults: `n_max = 3`, `n_min = 0`, `p_split = 0.1`,
  `p_min = 0.0` (`common/common.h:328-357`), CLI `--spec-draft-n-max`,
  `--spec-draft-n-min`, `--spec-draft-p-min`, `--spec-draft-sampling`
  (`common/arg.cpp:4181-4315`).
- There is no `n_reuse` parameter in this tree (checked `common/arg.cpp` and
  `common/speculative.cpp`; `n_reused` elsewhere is only a ggml graph-reuse
  counter). Partial-draft reuse is instead done by `common_prompt_checkpoint`
  in the example: after a partial acceptance the leftover accepted ids become
  the next round's partial draft (`examples/speculative-simple/
  speculative-simple.cpp:214-219, 266-289`). The distributed design below maps
  this onto plain `llama_memory_seq_rm` rewinds on the phone and on the PC
  draft context.
- Vocab compatibility is a hard precondition: same tokenizer type, same BOS/
  EOS handling, |n_vocab difference| <= 128, and identical token text from id 5
  upward (`common/speculative.cpp:32-33, 108-171`).

Verify side - the core loop (`examples/speculative-simple/
speculative-simple.cpp:222-296` plus `common/sampling.cpp:690-722`):

1. The verify batch is `[id_last, d0, ..., d(K-1)]`, all rows marked
   `logits=true`, positions `n_past..n_past+K`, decoded in ONE
   `llama_process` DECODE call (batch, not repeat prefill: this is the
   batched-verify primitive).
2. `common_sampler_sample_and_accept_n(gsmpl, ctx, draft)` then walks the
   batch rows: for row i it samples the target token and compares it with
   `draft[i]`; on match the draft token is accepted and the walk advances to
   row i+1; on mismatch the walk stops and the target's own sample becomes the
   correction token (sampling.cpp:708). If all K match, a bonus token is
   sampled from the last row (sampling.cpp:713-719).
3. The accepted token ids are committed; the KV rows written for rejected
   positions are rewound (`llama_memory_seq_rm`, or checkpoint restore at
   speculative-simple.cpp:272-275). Sampler state stays consistent because
   only emitted tokens are ever accepted into the sampler.

So the phone needs exactly three things llama.cpp already has as primitives:
a K+1-row DECODE batch with per-row logits, per-row sampler access
(`common_sampler_sample(gsmpl, ctx, row)`), and a KV rewind
(`llama_memory_seq_rm`). The pf engine already uses two of the three
(`pf_llm.cpp:635-660` batch decode; `:744-745` seq_rm shift path); it is
missing "logits on every row + sampler over batch rows", which is a small
native addition, not a new subsystem.

Exactness note: greedy drafting + greedy matching verification reproduces the
model's own greedy output exactly (this is llama.cpp's default
`--spec-draft-sampling greedy` path; speculative.cpp:370-373 comment). The
probabilistic alternative (`common_sampler_sample_and_accept_n_rejection`,
sampling.cpp:736-830, accept test `min(1, p_target(d)/q_draft(d))` at line
795) is mathematically exact for temp>0 but its residual correction
`norm(max(p_target - q_draft, 0))` needs the FULL target distribution
in-process. Distributed, that would need the whole logits row on the wire;
see section 6.4.

## 2. Distributed round design (chosen scheme)

Roles follow docs/pocketforge-api-v1.md: the phone stays the session owner
(KV cache, chat history, sampler, stop detection); the PC is a pure proposer
that keeps its own aligned 0.5/0.6B draft context.

One round (steady state):

1. PC  : draft K tokens from its own context: K draft decodes at
         r_d ~ 20 tok/s (estimate; section 4.2). Time = K / r_d.
2. PC->Phone: POST /v1/spec/verify {"draft_tokens":[d1..dK]}(+ lock token).
   Payload is ~60-200 B either way on USB or Wi-Fi; bandwidth (125 MB/s USB,
   RTT 4-12 ms hotspot) is irrelevant; one RTT per round.
3. Phone: one (K+1)-row verify batch = [pending token, d1..dK] with per-row
   logits; walk rows with the phone's own sampler (greedy); emit j accepted
   + 1 correction (j < K) or j = K accepted + 1 bonus (j = K); rewind
   positions n+j+1..n+K on partial accept; stream emitted pieces through the
   existing StopDetector/utf8 path; advance sampler and KV position exactly
   as the lazy-feed loop does today. Time = (K+1) / r_v(K+1) + t_f.
4. Phone->PC: {"accepted": j, "issued": [ids j accepted + correction or
   bonus], "kv_pos": n+j, timing}. PC rewinds its draft KV and appends the
   correction as next round's pending; if j < K it discards draft rows j+1..K.

Invariant that makes this safe: the phone-side sampler only ever accepts
emitted tokens, so a rejection costs one rewind and zero sampler state repair
- identical to llama.cpp's in-process semantics.

Session contract deltas (v1.1 additive minor, pf_api stays "v1"):

- New `pf_state: "SpecActive"` while a distributed turn is in progress, with
  a lock token in every spec request. `/v1/chat/completions` during SpecActive
  returns 409 (existing busy semantics, no queueing change). New state machine
  states are additive; the 409 contract survives unchanged.
- Token ids must be exposed: today `/v1/chat/completions` streams text pieces
  only, and a PC-side 0.5B draft context must be token-aligned with the phone
  from turn start. Two options: (a) add `"pf_tokens":[ids]` to stream delta
  frames, or (b) put all token movement inside the new /v1/spec/ endpoints.
  Option (b) is cleaner; the first spec round includes the formatted prompt
  prefix ids in its response after `POST /v1/spec/turn {"start":true}`.
- Preflight vocab check mirroring llama.cpp's walker (ids >= 5, BOS/EOS,
  |diff| <= 128): `GET /v1/spec/preflight` returns n_vocab + sampled token
  texts; the PC validates its draft GGUF against it.

## 3. The two candidate phone-side verify protocols

### Protocol A - KV rewind + re-prefill each round (no JNI semantics change)

Phone re-prefills the drafted continuation every round at its current KV
position, using a variant of `decode_tokens_in_batches` (pf_llm.cpp:635-660)
with logits on every row instead of last-row-only, then does the greedy walk
in native code. Accepted rows stay in KV (they are exactly the tokens' forward
passes the next round would otherwise redo); rejected rows are rewound.

Cost accounting that is easy to get wrong, so spelled out:

- The verify batch is K+1 rows (pending + K drafts) every round, regardless
  of acceptance. The accepted rows are NOT recomputation waste; their KV is
  committed and reused next round.
- Per committed token, verification therefore does not pay K+1 rows per round
  forever; summing over an episode the phone runs (total tokens + rejected
  rows) forward passes. The wasted rows per round are (K+1) - E[T].
- The entire profit versus native decode (12.5 tok/s = 80 ms/token) is the
  batching efficiency of running K+1 rows in one graph vs K+1 separate decode
  steps. Expressed as r_v(K+1) tok/s, the gate is:
    r_v(K+1) > 12.5  (otherwise verification is slower than decoding)
  With the measured LONG-prompt prefill 54.2 tok/s the gate is trivially met;
  the open question is what r_v looks like at K+1 in {5..17} on the phone.
  Standard practice: small-B CPU batches cost per token closer to decode
  marginal plus a fixed per-call overhead than the headline PP throughput.
- Phone compute per round: (K+1)/r_v(K+1) + t_f. All acceptance/rewind/sampler
  logic is native.

### Protocol B - batched verify with logits returned (endpoint extension)

Variant B1 (recommended as the wire protocol of the chosen scheme - actually
identical to A in phone compute, different packaging):

- Same phone compute as A.
- Response carries NO logits: {accepted, correction/bonus as token ids,
  optional per-row q_target(d_i) floats (32 B) for future use}. PC does not
  need the target distribution to draft; it only re-aligns its draft context
  with (j, correction). The phone makes every sampling decision - consistent
  with "phone = session owner".

Variant B2 (rejected): phone returns full logits rows so the PC could run the
checker (or even its own sampler replica). n_vocab ~151k -> ~610 KB float32
per row; on USB (125 MB/s) that is ~4.9 ms/row, which would survive, on
hotspot Wi-Fi it does not. It would also break the "phone owns sampling"
contract, force sampler-replica determinism (seed sync) for zero measurable
benefit, and require new JNI surface for logits extraction. Not worth it.

### Which one is "already profitable at prefill 54 tok/s"?

Protocol A as specified (K+1 rows per round) is profitable iff r_v(K+1)
approaches the headline figure. Numbers in section 4. From the model: with
r_v = 54.2 effective, K=4 and alpha >= 0 (any acceptance at all) the verify
cost alone (5 rows / 54.2 + ~3 ms fixed = ~92 ms per round) is below the
12.5 tok/s baseline's 80 ms/token only marginally - which is why the pipelined
variant and draft speed dominate the outcome. With the same 54.2 assumed for
K=8 or K=16 the round is draft-bound and the verdict is worse (sections 4-5).

## 4. The math

### 4.1 Tokens per round

Greedy-match verification with iid per-token acceptance alpha:

    E[T] = sum over partial accepts (j accepted + 1 correction)
         + P(all K accepted) * (K + 1)
         = sum_{k=0..K} alpha^k

Derivation check (K=1): 2a + (1-a) = 1 + a. (K=2): 3a^2 + 2a(1-a) + (1-a) =
a^2 + a + 1. Matches llama.cpp semantics (one bonus on full accept, one
correction otherwise, always >= 1 token emitted).

| K | a=0.4 | a=0.6 | a=0.8 |
|---|-------|-------|-------|
| 4 | 1.65  | 2.31  | 3.36  |
| 8 | 1.67  | 2.47  | 4.33  |
| 16| 1.67  | 2.50  | 4.86  |

Note the saturation: E[T] basically stops growing beyond K=6-8 because
alpha^k decays; K=16 buys almost nothing but adds K-p drafts of wasted PC and
phone work.

### 4.2 Timing model

Per round:

- PC draft: K / r_d  (K greedy decodes of the 0.6B; the following round's
  correction decode folds into its drafting seed step, so K, not K+1).
- Phone verify: (K+1) / r_v(K+1) + t_f, with t_f ~ 3 ms fixed per call
  (graph build + JSON + rewind, measured candidate: llama.cpp graph reuse
  makes repeated shape-identical calls cheaper; keep 3 ms as conservative).
- Link: one RTT per round. USB adb TCP RTT ~1 ms (assumption from 125 MB/s
  link, must be measured; payloads are <200 B). Hotspot Wi-Fi RTT 4-12 ms.

Serial mode:

    R_ser = E[T] / ( (K+1)/r_v + K/r_d + (RTT + t_f)/1000 )

Pipelined mode (PC drafts round i+1 optimistically while the phone verifies
round i; on partial accept the PC discards the drafted tail, which is cheap
because T_draft >= T_verify in the profitable cases):

    R_pipe = E[T] / max( K/r_d , (K+1)/r_v(K+1) + (RTT + t_f)/1000 )

### 4.3 r_d estimate (PC draft speed) - LOW-MODERATE confidence

0.5-0.6B Q4_K_M (~0.45 GB) is memory-bound: single-token decode =
model-bytes / effective bandwidth. DDR4-3200 dual channel gives a ~50-55 tok/s
physics ceiling; two P-cores (Golden Cove, AVX2+VNNI) under llama.cpp with
graph and scheduler overheads lands 60-75% of ceiling: estimate 20-25 tok/s
center, plausible band 15-35 tok/s; the brief's 15-25 tok/s is consistent.
Confidence: LOW-MODERATE (no measurement; class-based extrapolation from
community tg numbers for i5-1240P/11400H-class CPUs on 0.5-1B Q4 models).
This number must be measured before anything else (Phase 0 gate).

### 4.4 r_v scenario bands (phone small-batch prefill)

The measured 54.2 tok/s is a long-prompt figure. Model three scenarios for
the (K+1)-row batch, and treat K-band interpolation as the honest assumption:

- OPT: r_v = 54.2 for all K (full headline efficiency).
- MID: r_v(K) = 22 / 30 / 38 tok/s for K = 4 / 8 / 16 (0.5x to 0.75x of
  headline; batching helps but small batches pay the fixed cost per graph).
- PES: r_v = 12.5 (batch behaves like serial decode; no gain).

### 4.5 Main table - pipelined, r_d = 20 tok/s, t_f = 3 ms, USB RTT ~1 ms

Throughput in tok/s (12.5 baseline for reference). MID scenario unless noted.

| K | a=0.4 | a=0.6 | a=0.8 |
|---|-------|-------|-------|
| 4 | 7.1   | 10.0  | 14.5  |
| 8 | 4.2   | 6.2   | 10.8  |
| 16| 2.1   | 3.1   | 6.1   |

Scenario sensitivity at K=4 (the only viable K, see 4.6):

| scenario      | a=0.4 | a=0.6 | a=0.8 |
|---------------|-------|-------|-------|
| OPT r_v=54.2  | 8.2   | 11.5  | 16.8  |
| MID           | 7.1   | 10.0  | 14.5  |
| PES r_v=12.5  | 4.1   | 5.7   | 8.3   |

Serial mode (rarely worth implementing, shown for reference), K=4:

| scenario | a=0.4 | a=0.6 | a=0.8 |
|----------|-------|-------|-------|
| OPT      | 3.8   | 5.3   | 7.8   |
| MID      | 3.8   | 5.3   | 7.8   |
| PES      | 2.7   | 3.8   | 5.6   |

(LINK: Wi-Fi 12 ms RTT barely moves the pipelined numbers: K=4, a=0.8, MID:
13.9 tok/s. The 4-12 ms RTT is noise next to 200-300 ms of compute per round;
this is WHY all-in-one locality is moot: network is not the bottleneck.)

RTT tax per emitted token (ms), MID, r_d=20: K=4: 7.3 / 5.2 / 3.6 ms/token at
a = 0.4/0.6/0.8; K=16: down to ~2.5 ms/token at a=0.8. Amortized well.

### 4.6 Alpha threshold where the scheme beats 12.5 tok/s

Solved by bisection on the pipelined formula; "never" means even alpha -> 1
cannot reach 12.5 (the round is too slow).

| config                                | pipelined alpha* |
|---------------------------------------|------------------|
| K=4, r_d=20, MID r_v                  | 0.72             |
| K=4, r_d=20, OPT r_v=54.2             | 0.64             |
| K=4, r_d=25, OPT                      | 0.52             |
| K=4, r_d=15, MID/OPT                  | 0.80             |
| K=4, PES r_v=12.5                     | never            |
| K=8, r_d=20, MID/OPT                  | 0.84             |
| K=8, r_d=25, OPT                      | 0.78             |
| K=16, any r_d in 15..25, MID/OPT      | 0.93..never      |
| SERIAL mode, any config given         | >= 0.99 (never)  |

Reading: the scheme is worth building only if BOTH (a) measured PC draft
r_d >= 25 tok/s (0.6B Q4 on the i5 with 2 P-threads), and (b) measured
alpha >= 0.7 for the chosen draft/4B pair at greedy decoding. Typical
same-family greedy acceptance for a 0.5B drafting a 3-4B sibling sits around
0.55-0.75 (llama.cpp prints per-token accept rates in the spec examples);
that straddles the threshold, hence "prototype behind a gate", not a bet.

### 4.7 Why K=8/16 always lose

PC draft cost grows linearly (K/r_d = 400/800 ms at K=8/16 and r_d=20) while
E[T] saturates near alpha^K. The pipelined denominator max(K/r_d, ...)
is draft-bound at K >= 8 for every r_d in the 15-25 band. Additionally the
(1 - alpha) rejection of longer drafts wastes the phone's (K+1)-row prefill.
Adaptive K (stop drafting when the draft's own candidate prob < p_min,
llama.cpp line 423 semantics, ported PC-side) and pipelining does not change
this conclusion for the given r_d; it softens it only for HIGH alpha.

## 5. Failure modes

1. KV divergence after rejection (rewind cost). Partial acceptance leaves K-j
   rejected rows on the phone and on the PC draft. Phone rewind:
   llama_memory_seq_rm(mem, 0, n+j+1, -1) - already exercised by the context
   shift path (pf_llm.cpp:744-745); for f16 K/V on a 4B this is sub-ms memory
   work. PC side: same call on its draft context, also sub-ms. No recompute
   of accepted rows ever happens (unlike a full KV restart). Risk is state
   desync, not cost: see 5.5.
2. Sampler drift. Phone and PC sampling params must match or acceptance
   collapses: the phone verifies against ITS sampler's pick; the PC drafted
   greedily. llama.cpp has exactly this rule for local speculative decoding:
   spec_retune re-tunes the draft sampler to the target's temp/seed
   (speculative.cpp:37-72). Distributed equivalent: the /v1/spec/turn
   handshake must carry the phone's effective sampler chain (temp/top_k/
   top_p/seed) and the PC must use greedy drafting AND the phone verify must
   run at temp=0 semantics... Contract implication: distributed mode assumes
   the turn is greedy (UI temp > 0 must either disable DSD for that turn -
   recommended - or fall back to token-per-token phone decode; the exact
   distribution-preserving rejection would need full target logits, see 6.4).
   Also: penalties/grammar/gibi things that carry state across tokens
   (grammar masking with rescaling requires in-process access to the target
   distribution, sampling.cpp:758-781) must be rejected at handshake.
3. RTT amortization. 1-2 ms (USB) or 4-12 ms (Wi-Fi) per round against
   200-300 ms rounds: 0.5-5% overhead. Only becomes relevant if rounds are
   small AND draft speed is high (r_d >= 40 tok/s), in which case raising K to
   8 amortizes it. Not a design driver under the given numbers.
4. Prefill small-batch efficiency (the real risk for protocol A).
   Verified in 4.4: the scheme lives or dies on r_v(K+1), NOT on the headline
   54.2 tok/s. If PP5/PP9 on the 4B CPU build behaves like serial decode
   (PES), the scheme is a strict loss even at alpha=0.9. This is a 30-minute
   llama-bench measurement (Phase 0) and the single most important unknown in
   this document.
5. Session desync / crash recovery. If the phone kills the turn (process
   death, app cancellation, cancel endpoint) the PC draft context position may
   exceed the phone KV position. Handling: every /v1/spec/verify response
   echoes kv_pos; PC rolls back to the echoed position (its context may be
   re-derived by replaying the last tokens, at ~1/12.5 s/token penalty);
   a turn can restart with /v1/spec/turn. In-app chat invocations during
   SpecActive must 409 (contract), and phone-side user cancellation must
   propagate.
6. Stop sequences / EOG across the batch. llama.cpp refuses to accept draft
   tokens after an EOG (sampling.cpp:708); the phone verify must reproduce
   that guard or stop-sequences can leak a line after the trigger. The
   existing StopDetector works on pieces; accepted pieces must be fed in
   emission order before the round's response completes.

## 6. Phased implementation outline

### Phase 0 - measurements, no engine code

1. Phone: llama-bench (or a bench endpoint) on the 4B Q4_K_M: PP{5,9,17}, TG1
   to get r_v(K+1) and t_f. Decision gate: r_v(5) >= 22 tok/s else stop.
2. PC: llama.cpp build (CPU only, no GPU deps since Iris Xe has no usable
   toolchain): load Qwen-family 0.5-0.6B Q4_K_M draft + the SAME family 4B
   locally as a stand-in target (PC has 15.7 GB and can run the 4B slowly):
   run examples/speculative-simple (or llama-cli --spec-draft-model) purely to
   MEASURE rd and OFFLINE alpha for the pair on
   representative text. Decision gates: r_d >= 25 tok/s, alpha >= 0.70.
3. If gates pass, schedule Phase 1. If alpha between 0.65-0.7: prototype
   anyway with a kill switch; below that, abandon (expected throughput < 10).

### Phase 1 - phone primitives + endpoint (protocol A/B1 packaging)

Native (pf_llm.cpp), all on the existing single-threaded dispatcher:

- pf_verify_draft(const jint * draft_tokens, int K) -> JSON:
    1. clone-or-checkpoint the sampler (cheap; llama.cpp does exactly this
       before verify, speculative-simple.cpp:242-245).
    2. build g_batch: pending row + K draft rows at positions
       g_current_position..+K, logits=true on every row (new small variant of
       decode_tokens_in_batches; batching fits g_n_batch=512).
    3. walk rows with the existing g_sampler exactly like
       common_sampler_sample_and_accept_n: accept while pick == draft, first
       mismatch becomes the correction; full accept samples the bonus row;
       EOG guard from sampling.cpp:708.
    4. rewind rejected rows: llama_memory_seq_rm from the correct position
       (the code knows j); update g_current_position; pending = correction or
       bonus (not yet decoded - next round's pending row, llama.cpp lazy-feed
       semantics preserved).
    5. return {accepted:j, tokens:[ids...], kv_pos, t_ms}.
- pf_spec_turn_start(...) -> returns formatted prompt prefix token ids,
  kv_pos, sampler echo, for PC context alignment; also releases/attaches the
  SpecActive lock.
- pf_spec_turn_end(finish_reason): clears lock, restores ModelReady.

Kotlin: LlamaBridge JNI declarations + a SpecSession wrapper that owns the
lock token, reuses StopDetector and the utf8 piece accumulator across multi-
token responses, and surfaces per-round pieces as StreamEvent.Token. The
server (phone) exposes:
  POST /v1/spec/turn   (start/end, handshake, 409 semantics)
  POST /v1/spec/verify (one round; 401/409/413 as v1)
State additions: SpecActive, protected by the same dispatcher serialization;
/v1/chat/completions while SpecActive -> 409 (no change to busy semantics).

PC: a small native "proposer" process built on examples/speculative-simple's
draft half (common/speculative can be reused as draft-simple): maintains the
0.6B context, drafts K, calls /v1/spec/verify, rewinds/advances its draft KV
from the response, streams output. No llama.cpp modification needed on the
PC side unless reusing speculative-simple; both are additive.

### Phase 2 - pipelining + adaptivity (the mode that can actually win)

- PC optimistic drafting: draft round i+1 against the predicted full-accept
  state; on partial accept discard the tail (cheap: (K-j) wasted decodes).
  Steady-state: verify waits on nothing; per-round time = max(K/r_d, verify).
- Adaptive K PC-side using the draft's own confidence (port of p_min /
  running alpha estimate from round feedback in the response): bump K up to 5
  at high confidence; collapse to j+1 on rounds after rejections.
- Metrics surfaced in response timing so throughput is computed from true
  per-round numbers (matches PfBenchLogger pattern already in the engine).

### Phase 3 - optional extensions

- Per-row q_target(d_i) floats in the response (32-64 B): enables the
  distributed approximation of proportional verification for temp > 0 runs.
  Not exact (correction then draws from the target directly instead of
  norm((p_target - q_draft)+); llama.cpp drawn-from-residual would need the
  full distribution). Label as approximate, gate behind a UX toggle.
- Full logits transport over USB only (4.9 ms/row over 125 MB/s): allows
  grammar-aware verification PC-side. Recommend NOT pursuing for v1.1; revisit
  only if the desktop tool-calling protocol (already a laptop-side concern in
  the API contract) ever needs grammars on a phone model.
- Prompt-prefix draft cache on the PC (llama.cpp-style prompt checkpoint) so
  repetitive turn prefixes draft instantly (lookup/ngram drafting on the PC
  for prompts, cheap alpha). Out of scope for correctness work.

## 7. Open questions

1. r_v(K) on the phone - PP5/PP9/PP17 measurements (Phase 0.1). THE gate.
2. PC draft speed for 0.5-0.6B Q4_K_M on i5-1235U with 2 P-threads - THE gate
   (section 4.3 estimate: 15-35 tok/s band, center 20).
3. Measured alpha for the concrete pair (Qwen3.5-0.5B draft vs Qwen3.5-4B
   target, or whichever family is resident - llama.cpp compat check in 1.).
4. USB adb TCP true RTT (assumed ~1 ms): measure with a warm socket loop.
5. Whether phi graph reuse drops t_f below 3 ms for repeated identical-shape
   verify calls on the phone (llama.cpp n_reused counter is the probe).
