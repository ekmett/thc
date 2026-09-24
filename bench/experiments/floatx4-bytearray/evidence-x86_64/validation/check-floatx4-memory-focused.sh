#!/usr/bin/env bash
set -euo pipefail
source /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
export THC_GRADLE_USER_HOME=/home/ekmett/ai/thc-sequence-current-main-01a0cdeb/.gradle-user-home
out=build/floatx4-memory-focused-first-check
test ! -e "$out"
mkdir -p "$out"
git rev-parse HEAD > "$out/source-revision.txt"
printf '%s\n' 'scripts/gradle.sh --no-daemon test --tests thc.runtime.FloatVectorStorageTest --tests thc.runtime.FloatVectorMemoryProofTest --rerun-tasks' > "$out/command.txt"
set +e
scripts/gradle.sh --no-daemon test --tests thc.runtime.FloatVectorStorageTest --tests thc.runtime.FloatVectorMemoryProofTest --rerun-tasks > "$out/output.log" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$out/exit-status.txt"
if test -d build/test-results/test; then cp -R build/test-results/test "$out/test-results"; fi
tail -35 "$out/output.log"
exit "$status"
