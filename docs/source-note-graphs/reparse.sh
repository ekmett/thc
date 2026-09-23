#!/usr/bin/env bash
# Reparse archived compiler graphs; this does not execute the guest or benchmark it.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
. scripts/java-home.sh
[[ $# == 1 ]]
OUT="$1"
if [[ -e "$OUT" ]]; then echo 'Choose a new output directory.' >&2; exit 2; fi
mkdir -p "$OUT/raw" "$OUT/tools"
OUT="$(cd "$OUT" && pwd)"
tar -xf docs/source-note-graphs/selected-bgv.tar.xz -C "$OUT/raw"
EXPORTS=(--add-modules jdk.graal.compiler
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED)
"$JAVA_HOME/bin/javac" "${EXPORTS[@]}" -d "$OUT/tools" tools/GraphInspect.java
for graph in "$OUT"/raw/*/*.bgv; do
  capture="$(basename "$(dirname "$graph")")"
  name="$(basename "$graph" .bgv)"
  mkdir -p "$OUT/$capture"
  cp "docs/source-note-graphs/captures/$capture/run.log" "$OUT/$capture/run.log"
  "$JAVA_HOME/bin/java" -XX:-UseJVMCICompiler "${EXPORTS[@]}" -cp "$OUT/tools" GraphInspect \
    "$graph" "$OUT/$capture/parsed-$name" '(?i)After PE Tier|Before phase HighTierLowering|After mid tier'
  python3 tools/audit-call-packets.py "$OUT/$capture" --output "$OUT/$capture/packet-audit.json"
done
