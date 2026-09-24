#!/usr/bin/env bash
set -euo pipefail
cd /home/ekmett/ai/thc-word32x4-bytearray-01a0cdeb
source /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
# The shared lease serializes this behind the suites; any failed/missing suite
# prevents graph execution instead of silently continuing after a failed test.
python3 build/word32x4-memory-suite-report.py \
  --validation build/word32x4-memory-validation-canonical --expected-tests 515 \
  --expected-proof-tests 6 --output build/word32x4-memory-final-junit-report.json
for mode in direct-proof-green focused-default; do
  test "$(<"build/word32x4-memory-validation-canonical/$mode/exit-status.txt")" = 0
done
test -z "$(git status --porcelain)"
bash bench/experiments/word32x4-bytearray/run-runtime.sh build/word32x4-bytearray-runtime
python3 build/word32x4-memory-suite-report.py \
  --validation build/word32x4-memory-validation-canonical --expected-tests 515 \
  --expected-proof-tests 6 --capture build/word32x4-bytearray-runtime \
  --output build/word32x4-memory-final-suite-report.json
