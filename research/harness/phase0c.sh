#!/system/bin/sh
# phase0c.sh - missing configs: thread sweep + 4b_q40 + vulkan check (coordinator)
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
    if [ -n "$T" ] && [ "$T" -le 340 ]; then break; fi
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
VAR=armv8.6_1

bench() {
  echo "[$(now)] === BENCH v=$VAR t=$2 ngl=$3 $1" >> "$LOG"
  LD_LIBRARY_PATH=. ./llama-bench -m $1 -ngl $3 -p 512 -n 128 -t $2 -r 3 -o csv -oe none 1>>"$CSV" 2>>"$LOG"
  echo "[$(now)] === exit=$?" >> "$LOG"
  therm_log
}

echo "[$(now)] === phase0c start ===" >> "$LOG"
therm_full
pick armv8.6_1
cooldown
bench $M4K 4 0
cooldown
bench $M4K 5 0
cooldown
bench $M4K 7 0
cooldown
bench $M4K 8 0
cooldown
bench $M4K 2 0

# 4B Q4_0 pure CPU t=6
bench $M40 6 0

# Vulkan check: full offload 4B q4km, t=4 (keep one CPU lib for non-offloaded ops)
cooldown
park_all; cp -f spare/libggml-vulkan.so .
cp -f spare/libggml-cpu-android_armv8.6_1.so .
VAR=vulkan
bench $M4K 4 99
cooldown
bench $M40 4 99

park_all; restore_all
echo "[$(now)] === phase0c DONE ===" >> "$LOG"
therm_full
