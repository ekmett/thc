#!/usr/bin/env bash
# Run only after throughput timing has finished. Keep scheduled BGVs, not huge backend CFGs.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
. "$ROOT/scripts/java-home.sh"
OUT="${1:-work/graphs/map-packets-$(date +%Y%m%d-%H%M%S)}"
BASELINE="${2:-}"
mkdir -p "$OUT" build/graph-tools
OUT="$(cd "$OUT" && pwd)"
"$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -Xss2m \
  -Dthc.traceCompilation=true -Dthc.minimumWarmCalls=256 \
  "-Dthc.diagnosticUnsupported=${THC_DIAGNOSTIC_UNSUPPORTED:-false}" \
  -Djdk.graal.Dump=Truffle:1 -Djdk.graal.PrintGraph=File \
  -Djdk.graal.PrintGraphWithSchedule=true -Djdk.graal.PrintBackendCFG=false \
  "-Djdk.graal.DumpPath=$OUT" \
  -cp "${THC_GRAPH_CLASSPATH:-build/install/thc/lib/*}" thc.ProbeKt \
  "$(paste -sd, build/map/modules.txt)" mapAggregate --steady 10 0.01 1 10000 \
  > "$OUT/run.tsv" 2> "$OUT/run.log"
printf 'Guest capture finished: %s\n' "$OUT"
EXPORTS=(--add-modules jdk.graal.compiler \
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED \
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED)
"$JAVA_HOME/bin/javac" "${EXPORTS[@]}" -d build/graph-tools tools/GraphInspect.java
for graph in "$OUT"/TruffleHotSpotCompilation-*.bgv; do
  [[ -f "$graph" ]] || continue
  name="$(basename "$graph" .bgv)"
  # Guest lambda roots cover insert/adjust/lookup/fold/range/balance/entry.
  # Argument-thunk roots are retained raw and can be parsed separately if needed.
  [[ "$name" == *'[lambda_'* ]] || continue
  "$JAVA_HOME/bin/java" -XX:-UseJVMCICompiler "${EXPORTS[@]}" -cp build/graph-tools GraphInspect \
    "$graph" "$OUT/parsed-$name" \
    '(?i)After PE Tier|After Inline|Before phase HighTierLowering|After mid tier'
done
AUDIT=(python3 tools/audit-call-packets.py "$OUT" --output "$OUT/packet-audit.json" --require-clean-lookup)
if [[ -n "$BASELINE" ]]; then AUDIT+=(--baseline "$BASELINE"); fi
"${AUDIT[@]}"
printf 'Packet graph audit: %s/packet-audit.json\n' "$OUT"
