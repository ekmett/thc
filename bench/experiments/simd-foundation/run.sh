#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
RUNTIME="${THC_RUNTIME_ROOT:-$ROOT}"
OUT="${1:-$ROOT/build/simd-foundation-probe}"
if [[ -z "${JAVA_HOME:-}" ]]; then source "$ROOT/scripts/java-home.sh"; fi
[[ -d "$RUNTIME/build/install/thc/lib" ]] || { echo "Build installDist first or set THC_RUNTIME_ROOT." >&2; exit 1; }
mkdir -p "$OUT/classes"
OUT="$(cd "$OUT" && pwd)"
CP="$OUT/classes:$RUNTIME/build/install/thc/lib/*"
EXPORTS=(--add-modules jdk.graal.compiler
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED)
"$JAVA_HOME/bin/java" -version > "$OUT/java-version.txt" 2>&1
cp "$JAVA_HOME/release" "$OUT/jdk-release.txt"
uname -m > "$OUT/architecture.txt"
"$JAVA_HOME/bin/javac" --add-modules jdk.incubator.vector -cp "$CP" -d "$OUT/classes" "$HERE/VectorApiProbe.java"
"$JAVA_HOME/bin/javac" "${EXPORTS[@]}" -d "$OUT/classes" "$ROOT/tools/GraphInspect.java"
"$JAVA_HOME/bin/java" --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
  -XX:+UseCompactObjectHeaders -Djdk.graal.Dump=Truffle:2 -Djdk.graal.PrintGraph=File \
  -Djdk.graal.PrintGraphWithSchedule=true -Djdk.graal.PrintBackendCFG=true \
  "-Djdk.graal.DumpPath=$OUT/graphs" -cp "$CP" VectorApiProbe > "$OUT/probe.log" 2>&1
for graph in "$OUT/graphs/"*'[VectorApiProbe.VectorRoot@'*'.bgv'; do
  "$JAVA_HOME/bin/java" -XX:-UseJVMCICompiler "${EXPORTS[@]}" -cp "$OUT/classes" GraphInspect "$graph" "$OUT/parsed" '.*'
done
python3 "$HERE/audit.py" "$OUT"
