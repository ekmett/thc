#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
OUT="${1:-$ROOT/build/word16x8-runtime}"
if [[ -z "${JAVA_HOME:-}" ]]; then source "$ROOT/scripts/java-home.sh"; fi
# A failed capture remains immutable evidence; use another directory for a new run.
if [[ -e "$OUT" ]]; then
  echo "Choose a new output directory: $OUT" >&2
  exit 1
fi
mkdir -p "$OUT/classes"
OUT="$(cd "$OUT" && pwd)"
CP="$OUT/classes:$ROOT/build/install/thc/lib/*"
EXPORTS=(--add-modules jdk.graal.compiler
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED
  --add-exports jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED)

run_logged() {
  local prefix="$1"
  shift
  printf '%q ' "$@" > "$prefix.command.txt"
  printf '\n' >> "$prefix.command.txt"
  if "$@" > "$prefix.log" 2>&1; then
    printf '0\n' > "$prefix.exit-status.txt"
  else
    local result=$?
    printf '%s\n' "$result" > "$prefix.exit-status.txt"
    echo "Command failed ($result); retained log: $prefix.log" >&2
    return "$result"
  fi
}

run_logged "$OUT/prepare" python3 "$HERE/runtime-audit.py" prepare "$ROOT" "$OUT" "$JAVA_HOME"
run_logged "$OUT/javac-probe" "$JAVA_HOME/bin/javac" -cp "$CP" -d "$OUT/classes" "$HERE/Word16X8RuntimeGraphProbe.java"
run_logged "$OUT/javac-reader" "$JAVA_HOME/bin/javac" "${EXPORTS[@]}" -d "$OUT/classes" "$ROOT/tools/GraphInspect.java"
"$JAVA_HOME/bin/java" -version > "$OUT/java-version.txt" 2>&1
cp "$JAVA_HOME/release" "$OUT/jdk-release.txt"
uname -m > "$OUT/architecture.txt"
while IFS= read -r stage; do
  for backend in ast bytecode; do
    for entry in plusCase minusCase timesCase; do
      destination="$OUT/$stage-$backend-$entry"
      mkdir "$destination"
      run_logged "$destination/run" "$JAVA_HOME/bin/java" \
        --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
        -XX:+UseCompactObjectHeaders -Djdk.graal.Dump=Truffle:2 -Djdk.graal.PrintGraph=File \
        -Djdk.graal.PrintGraphWithSchedule=true -Djdk.graal.PrintBackendCFG=true \
        "-Djdk.graal.DumpPath=$destination/graphs" -cp "$CP" Word16X8RuntimeGraphProbe \
        "$ROOT/build/simd-word16x8/$stage-core/SimdWord16X8.json" "$OUT/oracle.tsv" "$entry" "$backend" inline native
      graphs=("$destination/graphs/"*.bgv)
      if [[ ${#graphs[@]} != 1 || ! -f "${graphs[0]}" ]]; then
        echo "Expected exactly one target BGV for $stage/$backend/$entry" >&2
        exit 1
      fi
      run_logged "$destination/parse" "$JAVA_HOME/bin/java" -XX:-UseJVMCICompiler "${EXPORTS[@]}" \
        -cp "$OUT/classes" GraphInspect "${graphs[0]}" "$destination/parsed" 'Before phase HighTierLowering'
    done
  done
done < "$OUT/stages.txt"
run_logged "$OUT/check" python3 "$HERE/runtime-audit.py" check "$ROOT" "$OUT" "$JAVA_HOME"
echo "Word16X8 graph evidence: $OUT/evidence.json"
