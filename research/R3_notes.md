# R3 working notes (2026-10-23)

- Big correction captured: X200 FE ships **UFS 3.1** not 4.0 (notebookcheck AndroBench 1867 MB/s seq) — contradicts the UFS 4.0 assumption passed in the brief.
- RAM: retail configs are 12/16 GB; 16 GB variant exists per phonebench SKU list; owner probe says 16 GB — consistent.
- LPDDR5X: no measured bandwidth figure found for X200 FE specifically. Theory 68.3 GB/s @ 8533MT/s [estimated]; MTK SoC ceiling 76.8 GB/s @ LPDDR5T-9600 [published]. AnTuTu mem subscore 279k–295k.
- Sustained clocks: no direct MTK published DVFS table. Best proxy = notebookcheck thermal section + 3DMark Wild Life stability 58.9%. My sustained estimate (X4 ~2.0–2.3 GHz) is soft — mark for a probe run on device later (measure /sys cpufreq time-in-state under llama.cpp load via run-as... actually run-as can't read other UIDs sysfs; needs shell via adb, which is allowed on device).
- Cache split ambiguity: MTK says "18MB L3 + SLC" combined. D9300 3rd-party listings put X4 L3 at 10 MB + SLC 8 MB [Renesas/Anandtech-class analysis not fetched]; keep [estimated] tag.
- Vulkan probe conflict: our unit reports int_dot: none; PR #18493 author's G720 log says "int dot: 1". Could be driver-version dependent (X200 FE Android 16 vendor driver). Resolve at probe time with test-backend-ops.
- GPU measured tops at 12.7 t/s tg for 4B Q4_0 (PR #18493 author, later ~10 t/s tuned) — CPU-only CPU decode at ~15–25 t/s projected for 4–8B Q4 likely beats GPU on this part.
- Mali family bugs to track upstream: #23241 (MUL_MAT wrong results G720), #23057 (32-bit CEIL_DIV overflow, UMA Mali), #26921 (G925 narrow small-tile NaN — same tile-selection code reaches G720; workaround = skip small tile when m<=32 && n>32 on vendorID 0x13B5).
- APU 790: dead end for GGUF. Only MT6989-baked .litertlm models via LiteRT-LM work in principle, and #2507 shows crash even then on 9300+. NNAPI deprecated in Android 16 → no permissive fallback path. Baked verdict NO; do not budget time.
- Memory: the ~6 GB "free" number in owner report is free-RAM snapshot, not a hard cap. Real risk = vivo LMK + ZRAM behavior at >60% device allocation; needs empirical probe (allocate-then-observe) later.
- UFS streaming verdict: only warm-up/load benefit (~3.5–5% of DRAM bw seq, 0.44% rand-4KB). mmap weight pages will be drowned by DRAM residency pressure once model > free RAM. Keep model resident; don't plan cold streaming tricks.
- Word-budget rule kept: deliverable ≤350 words, full detail lives in R3_hardware.md.
