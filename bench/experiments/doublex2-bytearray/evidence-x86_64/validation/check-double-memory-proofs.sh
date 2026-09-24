#!/usr/bin/env bash
set -euo pipefail
out=build/double-memory-first-proof-check
test ! -e "$out"
mkdir -p "$out"
for mode in normal optimized; do
  for test in test-core-vector-memory test-core-word32-vector-memory test-core-float-vector-memory test-core-double-vector-memory test-core-vectors test-audit-core; do
    args=()
    if test "$mode" = optimized; then args=(-O); fi
    set +e
    python3 "${args[@]}" "scripts/$test.py" > "$out/$mode-$test.output.log" 2>&1
    status=$?
    set -e
    printf '%s\n' "$status" > "$out/$mode-$test.exit-status.txt"
    tail -5 "$out/$mode-$test.output.log"
    if test "$status" != 0; then exit "$status"; fi
  done
done
