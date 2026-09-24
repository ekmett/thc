#!/usr/bin/env bash
set -euo pipefail
out=build/floatx4-memory-python-final-reader
test ! -e "$out"
mkdir -p "$out"
git rev-parse HEAD > "$out/source-revision.txt"
for mode in normal optimized; do
  flags=()
  if test "$mode" = optimized; then flags=(-O); fi
  for name in test-audit-core test-core-vectors test-core-vector-memory test-core-word32-vector-memory test-core-float-vector-memory test-int32x4-bytearray-model test-word32x4-bytearray-model test-floatx4-bytearray-model test-core-bytearrays test-primop-coverage; do
    prefix="$out/$mode-$name"
    printf '%q ' python3 "${flags[@]}" "scripts/$name.py" > "$prefix.command.txt"
    printf '\n' >> "$prefix.command.txt"
    set +e
    python3 "${flags[@]}" "scripts/$name.py" > "$prefix.output.log" 2>&1
    status=$?
    set -e
    printf '%s\n' "$status" > "$prefix.exit-status.txt"
    tail -5 "$prefix.output.log"
    if test "$status" != 0; then exit "$status"; fi
    if rg -q 'skipped=' "$prefix.output.log"; then echo "Unexpected skipped tests: $prefix"; exit 1; fi
  done
  prefix="$out/$mode-graph-reader"
  printf '%q ' python3 "${flags[@]}" bench/experiments/floatx4-bytearray/test-runtime-audit.py > "$prefix.command.txt"
  printf '\n' >> "$prefix.command.txt"
  set +e
  python3 "${flags[@]}" bench/experiments/floatx4-bytearray/test-runtime-audit.py > "$prefix.output.log" 2>&1
  status=$?
  set -e
  printf '%s\n' "$status" > "$prefix.exit-status.txt"
  tail -5 "$prefix.output.log"
  if test "$status" != 0; then exit "$status"; fi
done
