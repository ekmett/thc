#!/usr/bin/env bash
# Execute only in the exclusive graph/JVM window after benchmark timing is complete.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
make --no-print-directory -s check-java
if (( $# < 2 || $# > 4 )); then
  echo 'Usage: capture.sh ast|bytecode OUTPUT_DIR [true|false] [FROZEN_RUNTIME_DIR]' >&2
  exit 2
fi
BACKEND="$1"
OUT="$2"
NOTES="${3:-true}"
FROZEN="${4:-work/source-notes-v1}"
case "$BACKEND" in ast|bytecode) ;; *) exit 2;; esac
case "$NOTES" in true|false) ;; *) exit 2;; esac
FROZEN="$(cd "$FROZEN" && pwd)"
LIBDIR="$FROZEN/lib"
CORE="$FROZEN/core/RepresentationAudit.json"
MANIFEST="$FROZEN/manifest.json"
ENTRY=joinLoop
BASE=10000
[[ -f "$CORE" && -f "$MANIFEST" && -d "$LIBDIR" ]]
if [[ -d "$OUT" && -n "$(ls -A "$OUT")" ]]; then
  echo "Refusing nonempty output: $OUT" >&2
  exit 2
fi
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
CMD=("$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -Xss2m
  "-Dthc.backend=$BACKEND" "-Dthc.sourceNotesEnabled=$NOTES"
  -Dthc.traceCompilation=true -Dthc.minimumWarmCalls=256
  -Djdk.graal.Dump=Truffle:1 -Djdk.graal.PrintGraph=File
  -Djdk.graal.PrintGraphWithSchedule=true -Djdk.graal.PrintBackendCFG=false
  -Djdk.graal.TrackNodeSourcePosition=true "-Djdk.graal.DumpPath=$OUT"
  -cp "$LIBDIR/*" thc.ProbeKt "$CORE" "$ENTRY" --steady 10 0.01 1 "$BASE")
printf '%q ' "${CMD[@]}" > "$OUT/command.sh"
printf '\n' >> "$OUT/command.sh"
python3 work/source-notes-graph-review/provenance.py pre "$OUT" "$FROZEN" "$BACKEND" "$NOTES" "$JAVA_HOME" "${CMD[@]}"
"${CMD[@]}" > "$OUT/run.tsv" 2> "$OUT/run.log"
EXPORTS=(--add-modules jdk.graal.compiler
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED)
mkdir "$OUT/tools"
"$JAVA_HOME/bin/javac" "${EXPORTS[@]}" -d "$OUT/tools" tools/GraphInspect.java
for graph in "$OUT"/TruffleHotSpotCompilation-*.bgv; do
  [[ -f "$graph" ]] || continue
  name="$(basename "$graph" .bgv)"
  "$JAVA_HOME/bin/java" -XX:-UseJVMCICompiler "${EXPORTS[@]}" -cp "$OUT/tools" GraphInspect \
    "$graph" "$OUT/parsed-$name" '(?i)After PE Tier|After Inline|Before phase HighTierLowering|After mid tier'
done
python3 tools/audit-call-packets.py "$OUT" --output "$OUT/packet-audit.json"
python3 work/source-notes-graph-review/audit.py "$OUT"
python3 work/source-notes-graph-review/provenance.py post "$OUT"
