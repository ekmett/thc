#!/usr/bin/env bash
set -euo pipefail
cd /home/ekmett/ai/thc-word32x4-bytearray-01a0cdeb
source /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
export THC_GRADLE_USER_HOME=/home/ekmett/ai/thc-sequence-current-main-01a0cdeb/.gradle-user-home
out=build/word32x4-memory-direct-proof-red
if [[ -e "$out" ]]; then echo "Refusing to overwrite $out" >&2; exit 2; fi
mkdir -p "$out"
git rev-parse HEAD > "$out/revision.txt"
git status --porcelain > "$out/git-status.txt"
command=(scripts/gradle.sh --offline --no-daemon --max-workers=4 test
  --tests thc.runtime.Int32VectorMemoryProofTest.allOperationsRejectWrongArgumentsFlagsArityAndResultProofs
  --tests thc.runtime.Word32VectorMemoryProofTest.allOperationsRejectWrongArgumentsFlagsArityAndResultProofs)
printf '%q ' "${command[@]}" > "$out/command.txt"
printf '\n' >> "$out/command.txt"
set +e
"${command[@]}" > "$out/output.log" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$out/exit-status.txt"
if [[ -d build/test-results/test ]]; then cp -a build/test-results/test "$out/test-results"; fi
tail -50 "$out/output.log"
exit "$status"
