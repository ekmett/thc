#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
ENTRY="${1:-sumLoop}"
BASE="${2:-10000}"
OUT="${3:-$ROOT/work/graphs/$ENTRY-$(date +%Y%m%d-%H%M%S)}"
make --no-print-directory -s -C "$ROOT" check-java
THC_JDK="$JAVA_HOME"
mkdir -p "$OUT" build/graph-tools
OUT="$(cd "$OUT" && pwd)"
"$THC_JDK/bin/java" --enable-native-access=ALL-UNNAMED -Xss2m -XX:+UseCompactObjectHeaders -Dthc.traceCompilation=true \
  "-Dthc.minimumWarmCalls=${THC_GRAPH_MIN_WARM_CALLS:-20000}" \
  "-Dthc.diagnosticUnsupported=${THC_DIAGNOSTIC_UNSUPPORTED:-false}" \
  -Djdk.graal.Dump=Truffle:1 -Djdk.graal.PrintGraph=File \
  -Djdk.graal.PrintGraphWithSchedule=true -Djdk.graal.PrintBackendCFG=true \
  "-Djdk.graal.DumpPath=$OUT" -cp 'build/install/thc/lib/*' thc.ProbeKt \
  "${THC_GRAPH_MODULES:-build/core/THC.Prim.json,build/core/THC.Fixtures.json}" "$ENTRY" --steady 10 0.01 1 "$BASE" \
  > "$OUT/run.tsv" 2> "$OUT/run.log"
EXPORTS=(--add-modules jdk.graal.compiler \
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED \
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED)
"$THC_JDK/bin/javac" "${EXPORTS[@]}" -d build/graph-tools tools/GraphInspect.java
for graph in "$OUT"/*.bgv; do
  name="$(basename "$graph" .bgv)"
  "$THC_JDK/bin/java" -XX:-UseJVMCICompiler "${EXPORTS[@]}" -cp build/graph-tools GraphInspect \
    "$graph" "$OUT/parsed-$name" '.*'
done
printf 'Actual BGV, schedules and parsed phase graphs: %s\n' "$OUT"
