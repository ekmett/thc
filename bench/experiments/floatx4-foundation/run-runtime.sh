#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
OUT="${1:-$ROOT/build/floatx4-runtime}"
if [[ -z "${JAVA_HOME:-}" ]]; then source "$ROOT/scripts/java-home.sh"; fi
mkdir -p "$OUT/classes"
OUT="$(cd "$OUT" && pwd)"
CP="$OUT/classes:$ROOT/build/install/thc/lib/*"
EXPORTS=(--add-modules jdk.graal.compiler
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED)
python3 "$HERE/runtime-audit.py" prepare "$ROOT" "$OUT"
"$JAVA_HOME/bin/javac" -cp "$CP" -d "$OUT/classes" "$HERE/FloatX4RuntimeGraphProbe.java"
"$JAVA_HOME/bin/javac" "${EXPORTS[@]}" -d "$OUT/classes" "$ROOT/tools/GraphInspect.java"
"$JAVA_HOME/bin/java" -version > "$OUT/java-version.txt" 2>&1
cp "$JAVA_HOME/release" "$OUT/jdk-release.txt"
uname -m > "$OUT/architecture.txt"
while IFS= read -r stage; do
  for backend in ast bytecode; do
    for entry in plusCase minusCase timesCase; do
      destination="$OUT/$stage-$backend-$entry"
      mkdir -p "$destination"
      "$JAVA_HOME/bin/java" --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
        -XX:+UseCompactObjectHeaders -Djdk.graal.Dump=Truffle:2 -Djdk.graal.PrintGraph=File \
        -Djdk.graal.PrintGraphWithSchedule=true -Djdk.graal.PrintBackendCFG=true \
        "-Djdk.graal.DumpPath=$destination/graphs" -cp "$CP" FloatX4RuntimeGraphProbe \
        "$ROOT/build/simd-floatx4/$stage-core/SimdFloatX4.json" "$OUT/oracle.tsv" "$entry" "$backend" inline native > "$destination/run.log" 2>&1
      graphs=("$destination/graphs/"*.bgv)
      if [[ ${#graphs[@]} != 1 || ! -f "${graphs[0]}" ]]; then
        echo "Expected exactly one target BGV for $stage/$backend/$entry" >&2; exit 1
      fi
      "$JAVA_HOME/bin/java" -XX:-UseJVMCICompiler "${EXPORTS[@]}" -cp "$OUT/classes" GraphInspect "${graphs[0]}" "$destination/parsed" 'Before phase HighTierLowering'
    done
  done
done < "$OUT/stages.txt"
python3 "$HERE/runtime-audit.py" check "$ROOT" "$OUT"
