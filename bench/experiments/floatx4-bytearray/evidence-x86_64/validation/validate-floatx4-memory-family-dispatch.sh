#!/usr/bin/env bash
set -euo pipefail
source /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
export THC_GRADLE_USER_HOME=/home/ekmett/ai/thc-sequence-current-main-01a0cdeb/.gradle-user-home
out=build/floatx4-memory-validation-family-dispatch
test ! -e "$out"
test -z "$(git status --porcelain)"
mkdir -p "$out"
git rev-parse HEAD > "$out/revision.txt"
git status --porcelain > "$out/git-status.txt"
run_stage() {
  local stage="$1"
  shift
  mkdir -p "$out/$stage"
  printf '%q ' "$@" > "$out/$stage/command.txt"
  printf '\n' >> "$out/$stage/command.txt"
  set +e
  "$@" > "$out/$stage/output.log" 2>&1
  local status=$?
  set -e
  printf '%s\n' "$status" > "$out/$stage/exit-status.txt"
  if [[ "$stage" != fresh-native ]]; then
    if test -d build/test-results/test; then cp -a build/test-results/test "$out/$stage/test-results"; fi
  fi
  tail -12 "$out/$stage/output.log"
  if test "$status" != 0; then return "$status"; fi
}
run_stage fresh-native python3 scripts/prepare-floatx4-bytearray-audit.py
run_stage focused-default scripts/gradle.sh --offline --no-daemon --max-workers=4 installDist test \
  --tests thc.runtime.FloatVectorStorageTest \
  --tests thc.runtime.FloatVectorMemoryProofTest \
  --tests thc.runtime.SimdFloatByteArrayTest
run_stage full-default scripts/gradle.sh --offline --no-daemon --max-workers=4 test
run_stage full-dense env JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true \
  scripts/gradle.sh --offline --no-daemon --max-workers=4 installDist test --rerun-tasks
python3 build/floatx4-memory-suite-report.py --validation "$out" --expected-tests 530 \
  --expected-proof-tests 6 --output build/floatx4-memory-family-dispatch-junit-report.json
bash bench/experiments/floatx4-bytearray/run-runtime.sh build/floatx4-bytearray-runtime-family-dispatch
python3 build/floatx4-memory-suite-report.py --validation "$out" --expected-tests 530 \
  --expected-proof-tests 6 --capture build/floatx4-bytearray-runtime-family-dispatch \
  --output build/floatx4-memory-final-suite-report.json
# This shared AST node also serves the two established integer families.
bash bench/experiments/int32x4-bytearray/run-runtime.sh build/int32x4-bytearray-float-family-regression
bash bench/experiments/word32x4-bytearray/run-runtime.sh build/word32x4-bytearray-float-family-regression
