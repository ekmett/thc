#!/usr/bin/env bash
set -euo pipefail
out=build/double-memory-python-final
test ! -e "$out"
mkdir -p "$out"
git rev-parse HEAD > "$out/source-revision.txt"
for mode in normal optimized; do
  flags=()
  if test "$mode" = optimized; then flags=(-O); fi
  tests=(scripts/test-audit-core.py scripts/test-core-vectors.py
    scripts/test-core-vector-memory.py scripts/test-core-word32-vector-memory.py
    scripts/test-core-float-vector-memory.py scripts/test-core-double-vector-memory.py
    scripts/test-int32x4-bytearray-model.py scripts/test-word32x4-bytearray-model.py
    scripts/test-floatx4-bytearray-model.py scripts/test-doublex2-bytearray-model.py
    scripts/test-core-bytearrays.py scripts/test-primop-coverage.py
    bench/experiments/floatx4-bytearray/test-runtime-audit.py
    bench/experiments/doublex2-bytearray/test-runtime-audit.py)
  for test in "${tests[@]}"; do
    name="${test//\//_}"
    prefix="$out/$mode-$name"
    printf '%q ' python3 "${flags[@]}" "$test" > "$prefix.command.txt"
    printf '\n' >> "$prefix.command.txt"
    set +e
    python3 "${flags[@]}" "$test" > "$prefix.output.log" 2>&1
    status=$?
    set -e
    printf '%s\n' "$status" > "$prefix.exit-status.txt"
    tail -5 "$prefix.output.log"
    if test "$status" != 0; then exit "$status"; fi
    if rg -q 'skipped=' "$prefix.output.log"; then echo "Unexpected skipped checks: $prefix"; exit 1; fi
  done
done
