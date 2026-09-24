#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/int32x4-multiply-python
for mode in normal optimized; do
  flags=()
  if [[ "$mode" == optimized ]]; then flags=(-O); fi
  for script in scripts/test-audit-core.py scripts/test-core-vectors.py scripts/test-int32x4-multiply-model.py scripts/test-coverage-report.py bench/experiments/int32x4-multiply/test-runtime-audit.py; do
    label="${script##*/}"
    prefix="build/int32x4-multiply-python/$mode-$label"
    printf '%q ' python3 "${flags[@]}" "$script" > "$prefix.command.txt"
    printf '\n' >> "$prefix.command.txt"
    if python3 "${flags[@]}" "$script" > "$prefix.log" 2>&1; then
      printf '0\n' > "$prefix.exit-status.txt"
    else
      status=$?
      printf '%s\n' "$status" > "$prefix.exit-status.txt"
      exit "$status"
    fi
  done
done
