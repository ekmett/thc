#!/usr/bin/env bash
set -euo pipefail
source /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
out=build/floatx4-bytearray-runtime-family-dispatch
test ! -e "$out/recheck.command.txt"
command=(python3 bench/experiments/floatx4-bytearray/runtime-audit.py check "$PWD" "$PWD/$out" "$JAVA_HOME" --recheck-after-checker-fix)
printf '%q ' "${command[@]}" > "$out/recheck.command.txt"
printf '\n' >> "$out/recheck.command.txt"
set +e
"${command[@]}" > "$out/recheck.log" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$out/recheck.exit-status.txt"
tail -10 "$out/recheck.log"
if test "$status" != 0; then exit "$status"; fi
python3 build/floatx4-memory-suite-report.py --validation build/floatx4-memory-validation-family-dispatch \
  --expected-tests 530 --expected-proof-tests 6 --capture "$out" --output build/floatx4-memory-final-suite-report.json
