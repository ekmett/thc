#!/usr/bin/env bash
set -euo pipefail
source /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
export THC_GRADLE_USER_HOME=/home/ekmett/ai/thc-sequence-current-main-01a0cdeb/.gradle-user-home
out=build/double-memory-validation
test ! -e "$out"
test -z "$(git status --porcelain)"
test "$(git rev-parse HEAD)" = b6ba65f3ba54f937e2b88f61f04e7e4f425a1e5c
mkdir -p "$out"
git rev-parse HEAD > "$out/revision.txt"
git status --porcelain > "$out/git-status.txt"
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
  if [[ "$name" == full-* ]] && test -d build/test-results/test; then cp -a build/test-results/test "$out/$name/test-results"; fi
  tail -18 "$out/$name/output.log"
  if test "$status" != 0; then return "$status"; fi
}
run_stage fresh-all scripts/prepare-tests.sh
test -z "$(git status --porcelain)"
run_stage python bash build/check-double-memory-python.sh
run_stage full-default scripts/gradle.sh --offline --no-daemon --max-workers=4 installDist test
run_stage full-dense env JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true \
  scripts/gradle.sh --offline --no-daemon --max-workers=4 installDist test --rerun-tasks
run_stage double-graphs bash bench/experiments/doublex2-bytearray/run-runtime.sh build/doublex2-bytearray-runtime
run_stage signed-graphs bash bench/experiments/int32x4-bytearray/run-runtime.sh build/int32x4-bytearray-double-family-regression
run_stage unsigned-graphs bash bench/experiments/word32x4-bytearray/run-runtime.sh build/word32x4-bytearray-double-family-regression
run_stage float-graphs bash bench/experiments/floatx4-bytearray/run-runtime.sh build/floatx4-bytearray-double-family-regression
test -z "$(git status --porcelain)"
