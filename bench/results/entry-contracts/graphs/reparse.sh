#!/usr/bin/env bash
# Reparse original compiler output; does not execute the guest or benchmark.
set -euo pipefail
PUBLISH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$PUBLISH/../../../.." && pwd)"
cd "$ROOT"
. scripts/java-home.sh
[[ $# == 1 ]] || { echo 'Usage: reparse.sh NEW_OUTPUT_DIRECTORY' >&2; exit 2; }
[[ ! -e "$1" ]] || { echo 'Choose a new output directory.' >&2; exit 2; }
mkdir -p "$1/tools"
OUT="$(cd "$1" && pwd)"
python3 "$PUBLISH/selected-bgv.py" extract "$OUT/raw"
EXPORTS=(--add-modules jdk.graal.compiler
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED)
"$JAVA_HOME/bin/javac" "${EXPORTS[@]}" -d "$OUT/tools" "$PUBLISH/tools/original/work/perf-sprint-v2/GraphInspect.java"
for graph in "$OUT"/raw/*/*.bgv; do
  capture="$(basename "$(dirname "$graph")")"
  name="$(basename "$graph" .bgv)"
  mkdir -p "$OUT/$capture"
  "$JAVA_HOME/bin/java" -XX:-UseJVMCICompiler "-Xmx${THC_GRAPH_HEAP:-4g}" "${EXPORTS[@]}" -cp "$OUT/tools" GraphInspect \
    "$graph" "$OUT/$capture/parsed-$name" '(?i)After PE Tier|Before phase HighTierLowering|After mid tier'
done
python3 "$PUBLISH/selected-bgv.py" verify-reparse "$OUT"
