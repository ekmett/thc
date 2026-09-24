#!/usr/bin/env bash
set -euo pipefail
cd /home/ekmett/ai/thc-word32x4-bytearray-01a0cdeb
source /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
export THC_GRADLE_USER_HOME=/home/ekmett/ai/thc-sequence-current-main-01a0cdeb/.gradle-user-home
out=build/word32x4-memory-validation-canonical
if [[ -e "$out" ]]; then echo "Refusing to overwrite $out" >&2; exit 2; fi
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
  if [[ -d build/test-results/test ]]; then cp -a build/test-results/test "$out/$stage/test-results"; fi
  if [[ -d build/reports/tests/test ]]; then cp -a build/reports/tests/test "$out/$stage/test-report"; fi
  tail -12 "$out/$stage/output.log"
  if (( status != 0 )); then return "$status"; fi
}
run_stage direct-proof-green scripts/gradle.sh --offline --no-daemon --max-workers=4 test \
  --tests thc.runtime.Int32VectorMemoryProofTest.allOperationsRejectWrongArgumentsFlagsArityAndResultProofs \
  --tests thc.runtime.Word32VectorMemoryProofTest.allOperationsRejectWrongArgumentsFlagsArityAndResultProofs \
  --tests thc.runtime.SimdVectorTest.exactVectorMetadataAndDurableStorageAreDistinctFromTuples
run_stage focused-default scripts/gradle.sh --offline --no-daemon --max-workers=4 installDist test \
  --tests thc.runtime.Word32VectorStorageTest \
  --tests thc.runtime.Word32VectorMemoryProofTest \
  --tests thc.runtime.SimdWord32ByteArrayTest
run_stage full-default scripts/gradle.sh --offline --no-daemon --max-workers=4 test
run_stage full-dense env JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true \
  scripts/gradle.sh --offline --no-daemon --max-workers=4 installDist test --rerun-tasks
