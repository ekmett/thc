#!/usr/bin/env bash
set -euo pipefail
source /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
python3 build/floatx4-memory-suite-report.py \
  --validation build/floatx4-memory-validation --expected-tests 530 \
  --expected-proof-tests 6 --output build/floatx4-memory-final-junit-report.json
test "$(<build/floatx4-memory-validation/focused-default/exit-status.txt)" = 0
test -z "$(git status --porcelain)"
bash bench/experiments/floatx4-bytearray/run-runtime.sh build/floatx4-bytearray-runtime
python3 build/floatx4-memory-suite-report.py \
  --validation build/floatx4-memory-validation --expected-tests 530 \
  --expected-proof-tests 6 --capture build/floatx4-bytearray-runtime \
  --output build/floatx4-memory-final-suite-report.json
