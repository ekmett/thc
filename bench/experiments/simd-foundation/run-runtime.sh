#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
OUT="${1:-$ROOT/build/simd-runtime-native}"
VECTOR="${2:-int64x2}"
case "$VECTOR" in
 int64x2) CORE_DIR="$ROOT/build/simd"; MODULE=SimdInt64X2 ;;
 int32x4) CORE_DIR="$ROOT/build/simd-int32x4"; MODULE=SimdInt32X4 ;;
 *) echo "Unsupported vector $VECTOR" >&2; exit 1 ;;
esac
make --no-print-directory -s -C "$ROOT" check-java
mkdir -p "$OUT/classes"
OUT="$(cd "$OUT" && pwd)"
CP="$OUT/classes:$ROOT/build/install/thc/lib/*"
EXPORTS=(--add-modules jdk.graal.compiler
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED)
# A fresh x86 preparation supplies a native oracle. AArch64 reuses the committed
# source-matched native x86 oracle explicitly, while its own Core remains pre-Tidy.
python3 "$HERE/runtime-audit.py" prepare "$ROOT" "$OUT" "$VECTOR"
"$JAVA_HOME/bin/javac" -cp "$CP" -d "$OUT/classes" "$HERE/SimdRuntimeGraphProbe.java"
"$JAVA_HOME/bin/javac" "${EXPORTS[@]}" -d "$OUT/classes" "$ROOT/tools/GraphInspect.java"
"$JAVA_HOME/bin/java" -version > "$OUT/java-version.txt" 2>&1
cp "$JAVA_HOME/release" "$OUT/jdk-release.txt"
uname -m > "$OUT/architecture.txt"
while IFS= read -r stage; do
 for backend in ast bytecode; do
  for entry in vectorCase subtractCase; do
   destination="$OUT/$stage-$backend-$entry"
   mkdir -p "$destination"
   "$JAVA_HOME/bin/java" --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
    -XX:+UseCompactObjectHeaders -Djdk.graal.Dump=Truffle:2 -Djdk.graal.PrintGraph=File \
    -Djdk.graal.PrintGraphWithSchedule=true -Djdk.graal.PrintBackendCFG=true \
    "-Djdk.graal.DumpPath=$destination/graphs" -cp "$CP" SimdRuntimeGraphProbe \
    "$CORE_DIR/$stage-core/$MODULE.json" "$OUT/oracle.tsv" "$entry" "$backend" inline native > "$destination/run.log" 2>&1
   for graph in "$destination/graphs/"*.bgv; do
    "$JAVA_HOME/bin/java" -XX:-UseJVMCICompiler "${EXPORTS[@]}" -cp "$OUT/classes" GraphInspect "$graph" "$destination/parsed" 'Before phase HighTierLowering'
   done
  done
 done
done < "$OUT/stages.txt"
python3 "$HERE/runtime-audit.py" check "$ROOT" "$OUT" "$VECTOR"
