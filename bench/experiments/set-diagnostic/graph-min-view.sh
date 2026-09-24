#!/usr/bin/env bash
set -euo pipefail
[[ $# == 3 ]] || { echo 'Usage: graph-min-view.sh EXISTING_CASES_JSON ast|bytecode OUT' >&2; exit 1; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
RUNTIME="${THC_RUNTIME_ROOT:-$ROOT}"
make --no-print-directory -s -C "$ROOT" check-java
mkdir -p "$3/classes"
OUT="$(cd "$3" && pwd)"
CP="$OUT/classes:$RUNTIME/build/install/thc/lib/*"
python3 "$HERE/inventory.py" "$1" "$OUT"
python3 "$HERE/inputs.py" "$OUT" "$RUNTIME"
"$JAVA_HOME/bin/java" -version > "$OUT/java-version.txt" 2>&1
"$JAVA_HOME/bin/javac" -cp "$CP" -d "$OUT/classes" "$HERE/SetDiagnosticProbe.java"
"$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseCompactObjectHeaders \
  -Djdk.graal.Dump=Truffle:2 -Djdk.graal.PrintGraph=File -Djdk.graal.PrintGraphWithSchedule=true \
  -Djdk.graal.PrintBackendCFG=true "-Djdk.graal.DumpPath=$OUT/graphs" -cp "$CP" \
  SetDiagnosticProbe "$1" "$2" --graph-min-view > "$OUT/run.log" 2>&1
EXPORTS=(--add-modules jdk.graal.compiler
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED)
"$JAVA_HOME/bin/javac" "${EXPORTS[@]}" -d "$OUT/classes" "$ROOT/tools/GraphInspect.java"
for graph in "$OUT/graphs/"*.bgv; do
  name="$(basename "$graph" .bgv)"
  "$JAVA_HOME/bin/java" -XX:-UseJVMCICompiler "${EXPORTS[@]}" -cp "$OUT/classes" GraphInspect \
    "$graph" "$OUT/parsed-$name" '(?i).*(After PE Tier|Before phase HighTierLowering|After low tier).*'
done
if [[ "$2" == ast ]]; then python3 "$HERE/summarize-graph.py" "$OUT"; fi
