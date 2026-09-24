#!/usr/bin/env bash
set -euo pipefail
source /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
export THC_GRADLE_USER_HOME=/home/ekmett/ai/thc-sequence-current-main-01a0cdeb/.gradle-user-home
out=build/double-memory-native-focused
test ! -e "$out"
mkdir -p "$out"
git rev-parse HEAD > "$out/base-commit.txt"
git diff --binary > "$out/tracked-diff.patch"
cp src/test/kotlin/thc/runtime/SimdDoubleByteArrayTest.kt "$out/"
run_stage() {
  local name="$1"
  shift
  mkdir "$out/$name"
  printf '%q ' "$@" > "$out/$name/command.txt"
  printf '\n' >> "$out/$name/command.txt"
  set +e
  "$@" > "$out/$name/output.log" 2>&1
  local status=$?
  set -e
  printf '%s\n' "$status" > "$out/$name/exit-status.txt"
  if [[ "$name" == focused ]] && test -d build/test-results/test; then cp -a build/test-results/test "$out/$name/test-results"; fi
  tail -20 "$out/$name/output.log"
  if test "$status" != 0; then return "$status"; fi
}
run_stage fresh-native python3 scripts/prepare-doublex2-bytearray-audit.py
run_stage model-normal python3 scripts/test-doublex2-bytearray-model.py
run_stage model-optimized python3 -O scripts/test-doublex2-bytearray-model.py
run_stage proof-normal python3 scripts/test-core-double-vector-memory.py
run_stage proof-optimized python3 -O scripts/test-core-double-vector-memory.py
run_stage focused scripts/gradle.sh --offline --no-daemon --max-workers=4 test \
  --tests thc.runtime.DoubleVectorMemoryProofTest --tests thc.runtime.DoubleVectorStorageTest --tests thc.runtime.SimdDoubleByteArrayTest
