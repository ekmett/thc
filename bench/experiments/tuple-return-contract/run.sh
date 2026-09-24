#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
RUNTIME="${THC_RUNTIME_ROOT:-$ROOT}"
OUT="${1:-$ROOT/build/tuple-return-contract}"
make --no-print-directory -s -C "$ROOT" check-java
[[ -d "$RUNTIME/build/install/thc/lib" ]] || { echo "Build installDist first, or set THC_RUNTIME_ROOT to a built THC checkout." >&2; exit 1; }
mkdir -p "$OUT/classes"
OUT="$(cd "$OUT" && pwd)"
CP="$OUT/classes:$RUNTIME/build/install/thc/lib/*"
EXPORTS=(--add-modules jdk.graal.compiler
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED)
"$JAVA_HOME/bin/java" -version > "$OUT/java-version.txt" 2>&1
cp "$JAVA_HOME/release" "$OUT/jdk-release.txt"
"$JAVA_HOME/bin/javac" -cp "$CP" -d "$OUT/classes" "$HERE/"*Probe.java
"$JAVA_HOME/bin/javac" "${EXPORTS[@]}" -d "$OUT/classes" "$ROOT/tools/GraphInspect.java"
COMMON=(--enable-native-access=ALL-UNNAMED -XX:+UseCompactObjectHeaders -cp "$CP")
"$JAVA_HOME/bin/java" "${COMMON[@]}" TupleDirectiveProbe true > "$OUT/directive-inline.log" 2>&1
"$JAVA_HOME/bin/java" "${COMMON[@]}" TupleDirectiveProbe false > "$OUT/directive-residual.log" 2>&1
"$JAVA_HOME/bin/java" "${COMMON[@]}" MixedTupleProbe false > "$OUT/mixed-residual.log" 2>&1
"$JAVA_HOME/bin/java" -Djdk.graal.Dump=Truffle:2 -Djdk.graal.PrintGraph=File \
  -Djdk.graal.PrintGraphWithSchedule=true -Djdk.graal.PrintBackendCFG=true \
  "-Djdk.graal.DumpPath=$OUT/graphs" "${COMMON[@]}" MixedTupleProbe true > "$OUT/mixed-inline.log" 2>&1
"$JAVA_HOME/bin/java" "-Djdk.graal.DumpPath=$OUT/escape-diagnostics" "${COMMON[@]}" VirtualEscapeProbe > "$OUT/virtual-escape.log" 2>&1
for graph in "$OUT/graphs/"*'[MixedTupleProbe.Consumer@'*'.bgv'; do
  "$JAVA_HOME/bin/java" -XX:-UseJVMCICompiler "${EXPORTS[@]}" -cp "$OUT/classes" GraphInspect \
    "$graph" "$OUT/parsed-consumer" '.*'
done
python3 "$HERE/audit.py" "$OUT"
printf 'Contract probe passed; evidence: %s/evidence.json\n' "$OUT"
