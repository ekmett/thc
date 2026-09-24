#!/usr/bin/env bash
set -euo pipefail
source /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
out=build/floatx4-memory-fresh-preparation
test ! -e "$out"
test -z "$(git status --porcelain)"
mkdir -p "$out"
git rev-parse HEAD > "$out/source-revision.txt"
printf '%s\n' 'scripts/prepare-tests.sh' > "$out/command.txt"
set +e
scripts/prepare-tests.sh > "$out/output.log" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$out/exit-status.txt"
tail -35 "$out/output.log"
exit "$status"
