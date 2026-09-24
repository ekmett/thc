#!/usr/bin/env bash
set -euo pipefail
cd /home/ekmett/ai/thc-word32x4-bytearray-01a0cdeb
out=build/word32x4-memory-python-canonical
if [[ -e "$out" ]]; then
  echo "Refusing to overwrite $out" >&2
  exit 2
fi
mkdir -p "$out"
git rev-parse HEAD > "$out/revision.txt"
git diff > "$out/working.diff"
for mode in normal optimized; do
  options=()
  if [[ "$mode" == optimized ]]; then options=(-O); fi
  for source in scripts/test-audit-core.py scripts/test-core-vectors.py scripts/test-core-vector-memory.py scripts/test-core-word32-vector-memory.py scripts/test-int32x4-bytearray-model.py scripts/test-word32x4-bytearray-model.py scripts/test-core-bytearrays.py scripts/test-primop-coverage.py bench/experiments/word32x4-bytearray/test-runtime-audit.py; do
    stage="$out/$mode-$(basename "$source" .py)"
    printf '%q ' python3 "${options[@]}" "$source" > "$stage.command.txt"
    printf '\n' >> "$stage.command.txt"
    set +e
    python3 "${options[@]}" "$source" > "$stage.output.log" 2>&1
    status=$?
    set -e
    printf '%s\n' "$status" > "$stage.exit-status.txt"
    if (( status != 0 )); then cat "$stage.output.log"; exit "$status"; fi
    tail -4 "$stage.output.log"
  done
done
