#!/usr/bin/env bash
set -euo pipefail
cd /home/ekmett/ai/thc-int32x4-bytearray-01a0cdeb
source /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
out=build/int32x4-memory-reader-correction
test ! -e "$out"
mkdir -p "$out"
logged() {
  local prefix="$1"
  shift
  test ! -e "$prefix.log"
  printf '%q ' "$@" > "$prefix.command.txt"
  printf '\n' >> "$prefix.command.txt"
  set +e
  "$@" > "$prefix.log" 2>&1
  local status=$?
  set -e
  printf '%s\n' "$status" > "$prefix.exit-status.txt"
  tail -n 5 "$prefix.log"
  if (( status != 0 )); then return "$status"; fi
}
logged "$out/normal" python3 bench/experiments/int32x4-bytearray/test-runtime-audit.py
logged "$out/optimized" python3 -O bench/experiments/int32x4-bytearray/test-runtime-audit.py
logged "$out/original-negative" python3 build/check-int32x4-memory-retained-failure.py "$out/retained-failure.json"
logged build/int32x4-bytearray-runtime-bounds-deopt/recheck python3 \
  bench/experiments/int32x4-bytearray/runtime-audit.py check "$PWD" \
  build/int32x4-bytearray-runtime-bounds-deopt "$JAVA_HOME" --recheck-after-checker-fix
