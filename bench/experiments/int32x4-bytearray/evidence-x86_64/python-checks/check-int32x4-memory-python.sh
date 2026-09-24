#!/usr/bin/env bash
set -euo pipefail
cd /home/ekmett/ai/thc-int32x4-bytearray-01a0cdeb
out=build/int32x4-memory-python
test ! -e "$out"
mkdir -p "$out"
for mode in normal optimized; do
  for test in test-audit-core.py test-core-vectors.py test-core-vector-memory.py test-int32x4-bytearray-model.py test-core-bytearrays.py test-primop-coverage.py; do
    options=()
    if [[ "$mode" == optimized ]]; then options=(-O); fi
    prefix="$out/$mode-${test%.py}"
    printf '%q ' python3 "${options[@]}" "scripts/$test" > "$prefix.command.txt"
    printf '\n' >> "$prefix.command.txt"
    set +e
    python3 "${options[@]}" "scripts/$test" > "$prefix.log" 2>&1
    status=$?
    set -e
    printf '%s\n' "$status" > "$prefix.exit-status.txt"
    if (( status != 0 )); then
      echo "Failed check; retained $prefix.log" >&2
      exit "$status"
    fi
    echo "PASS $mode $test"
  done
done
