#!/system/bin/sh
# phase0b.sh - tuned-baseline sweep (variant matrix, thread sweep, cross-check, vulkan check)
# Device-side. Park vulkan for CPU runs, swap CPU variant libs (phase0 pattern).
cd /data/local/tmp/pfbench || exit 1
mkdir -p results
LOG=results/phase0b.log
CSV=results/phase0b.csv
now() { date +%H:%M:%S; }
therm_log() { echo "[$(now)] temp=$(dumpsys battery | grep '^  temperature' | awk '{print $2}') load=$(cat /proc/loadavg)" >> "$LOG"; }
therm_full() { dumpsys thermalservice 2>/dev/null | head -14 >> "$LOG" 2>&1; }
cooldown() {
  n=0
  while [ $n -lt 84 ]; do
    T=$(dumpsys battery | grep '^  temperature' | awk '{print $2}')
    if [ -n "$T" ] && [ "$T" -le 335 ]; then break; fi
    sleep 5; n=$((n+1))
  done
  echo "[$(now)] cooldown waited=$n temp=$T" >> "$LOG"
  therm_log
}
park_all() { for L in libggml-cpu-android_*.so libggml-vulkan.so; do [ -f "$L" ] && mv -f "$L" spare/; done; }
pick() { park_all; cp -f "spare/libggml-cpu-android_$1.so" .; }
restore_all() { for L in spare/libggml-cpu-android_*.so; do cp -f "$L" .; done; }

M4K=models/qwen3-4b-q4km.gguf
M40=models/qwen3-4b-q40.gguf
M15=models/qwen25-1.5b-q40.gguf
VAR=?

bench() {
  # $1 model $2 threads $3 ngl
  echo "[$(now)] === BENCH v=$VAR t=$2 ngl=$3 $1" >> "$LOG"
  LD_LIBRARY_PATH=. ./llama-bench -m $1 -ngl $3 -p 512 -n 128 -t $2 -r 3 -o csv -oe none 1>>"$CSV" 2>>"$LOG"
  echo "[$(now)] === exit=$?" >> "$LOG"
  therm_log
}

echo "[$(now)] === phase0b start ===" >> "$LOG"
therm_full

for V in armv9.0_1 armv8.6_1 armv8.2_2 armv8.0_1; do
  VAR=$V
  park_all; cp -f "spare/libggml-cpu-android_$V.so" . || { echo "[$(now)] pick $V fail" >> "$LOG"; continue; }
  bench $M4K 6 0
  bench $M15 6 0
done

VAR=armv9.0_1; park_all; cp -f spare/libggml-cpu-android_armv9.0_1.so .
bench $M4K 4 0
bench $M4K 5 0
bench $M4K 7 0
bench $M4K 8 0
bench $M40 6 0

# Vulkan check last (full offload; keep one CPU lib for non-offloaded ops)
VAR=vulkan; park_all; cp -f spare/libggml-vulkan.so .
cp -f spare/libggml-cpu-android_armv9.0_1.so .
bench $M4K 4 99

park_all; restore_all
echo "[$(now)] === phase0b DONE ===" >> "$LOG"
therm_full
