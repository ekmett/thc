#!/usr/bin/env bash
set -euo pipefail
source /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
export THC_GRADLE_USER_HOME=/home/ekmett/ai/thc-sequence-current-main-01a0cdeb/.gradle-user-home
out=build/double-memory-first-focused
test ! -e "$out"
mkdir -p "$out"
git rev-parse HEAD > "$out/base-commit.txt"
git diff --binary > "$out/tracked-runtime-diff.patch"
sha256sum src/test/kotlin/thc/runtime/DoubleVectorMemoryProofTest.kt src/test/kotlin/thc/runtime/SimdDoubleByteArrayTest.kt > "$out/new-test-sha256.txt"
set +e
scripts/gradle.sh --offline --no-daemon --max-workers=4 test \
  --tests thc.runtime.DoubleVectorMemoryProofTest --tests thc.runtime.DoubleVectorStorageTest > "$out/output.log" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$out/exit-status.txt"
if test -d build/test-results/test; then cp -a build/test-results/test "$out/test-results"; fi
tail -35 "$out/output.log"
exit "$status"
