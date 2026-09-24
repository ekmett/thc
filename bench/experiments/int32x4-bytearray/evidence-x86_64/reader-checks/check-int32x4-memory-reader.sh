#!/usr/bin/env bash
set -euo pipefail
cd /home/ekmett/ai/thc-int32x4-bytearray-01a0cdeb
out=build/int32x4-memory-reader-checks
test ! -e "$out"
mkdir -p "$out"
for mode in normal optimized; do
  options=()
  if [[ "$mode" == optimized ]]; then options=(-O); fi
  prefix="$out/$mode"
  printf '%q ' python3 "${options[@]}" bench/experiments/int32x4-bytearray/test-runtime-audit.py > "$prefix.command.txt"
  printf '\n' >> "$prefix.command.txt"
  set +e
  python3 "${options[@]}" bench/experiments/int32x4-bytearray/test-runtime-audit.py > "$prefix.log" 2>&1
  status=$?
  set -e
  printf '%s\n' "$status" > "$prefix.exit-status.txt"
  if (( status != 0 )); then exit "$status"; fi
  tail -n 4 "$prefix.log"
done
