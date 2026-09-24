#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -euo pipefail
[[ $# == 2 ]] || { echo 'Usage: run.sh EXISTING_CASES_JSON OUT' >&2; exit 1; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
RUNTIME="${THC_RUNTIME_ROOT:-$ROOT}"
if [[ -z "${JAVA_HOME:-}" ]]; then source "$ROOT/scripts/java-home.sh"; fi
mkdir -p "$2/classes"
OUT="$(cd "$2" && pwd)"
CP="$OUT/classes:$RUNTIME/build/install/thc/lib/*"
python3 "$HERE/inventory.py" "$1" "$OUT"
python3 "$HERE/inputs.py" "$OUT" "$RUNTIME"
"$JAVA_HOME/bin/java" -version > "$OUT/java-version.txt" 2>&1
"$JAVA_HOME/bin/javac" -cp "$CP" -d "$OUT/classes" "$HERE/SetDiagnosticProbe.java"
for backend in ast bytecode; do
  "$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseCompactObjectHeaders \
    -cp "$CP" SetDiagnosticProbe "$1" "$backend" > "$OUT/$backend.log" 2>&1
done
python3 "$HERE/summarize.py" "$OUT"
