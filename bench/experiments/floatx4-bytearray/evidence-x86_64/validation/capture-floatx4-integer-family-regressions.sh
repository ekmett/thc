#!/usr/bin/env bash
set -euo pipefail
source /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
test -z "$(git status --porcelain)"
test "$(<build/floatx4-memory-validation-family-dispatch/full-default/exit-status.txt)" = 0
test "$(<build/floatx4-memory-validation-family-dispatch/full-dense/exit-status.txt)" = 0
bash bench/experiments/int32x4-bytearray/run-runtime.sh build/int32x4-bytearray-float-family-regression
bash bench/experiments/word32x4-bytearray/run-runtime.sh build/word32x4-bytearray-float-family-regression
