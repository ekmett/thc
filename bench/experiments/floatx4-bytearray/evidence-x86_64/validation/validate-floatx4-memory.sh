#!/usr/bin/env bash
set -euo pipefail
source /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
export THC_GRADLE_USER_HOME=/home/ekmett/ai/thc-sequence-current-main-01a0cdeb/.gradle-user-home
out=build/floatx4-memory-validation
test ! -e "$out"
test -z "$(git status --porcelain)"
test "$(<build/floatx4-memory-fresh-preparation/exit-status.txt)" = 0
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
  if test -d build/test-results/test; then cp -a build/test-results/test "$out/$stage/test-results"; fi
  if test -d build/reports/tests/test; then cp -a build/reports/tests/test "$out/$stage/test-report"; fi
  tail -12 "$out/$stage/output.log"
  if test "$status" != 0; then return "$status"; fi
}
run_stage focused-default scripts/gradle.sh --offline --no-daemon --max-workers=4 installDist test \
  --tests thc.runtime.FloatVectorStorageTest \
  --tests thc.runtime.FloatVectorMemoryProofTest \
  --tests thc.runtime.SimdFloatByteArrayTest
run_stage full-default scripts/gradle.sh --offline --no-daemon --max-workers=4 test
run_stage full-dense env JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true \
  scripts/gradle.sh --offline --no-daemon --max-workers=4 installDist test --rerun-tasks
