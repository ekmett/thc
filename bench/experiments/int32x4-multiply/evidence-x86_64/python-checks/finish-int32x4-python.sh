#!/usr/bin/env bash
set -euo pipefail
# The earlier wrapper named a nonexistent coverage script. Retain its failed
# command/log/status, and run only the unattempted checks with the correct name.
for mode in normal optimized; do
  flags=()
  scripts=(scripts/test-primop-coverage.py bench/experiments/int32x4-multiply/test-runtime-audit.py)
  if [[ "$mode" == optimized ]]; then
    flags=(-O)
    scripts=(scripts/test-audit-core.py scripts/test-core-vectors.py scripts/test-int32x4-multiply-model.py "${scripts[@]}")
  fi
  for script in "${scripts[@]}"; do
    prefix="build/int32x4-multiply-python/$mode-${script##*/}"
    test ! -e "$prefix.log"
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
