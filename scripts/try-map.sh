#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
make --no-print-directory -s -C "$ROOT" check-java
scripts/prepare-tests.sh
scripts/prepare-map.sh
DIAGNOSTIC="${THC_DIAGNOSTIC_UNSUPPORTED:-false}"
./gradlew --no-daemon test installDist "$@"
THC_JAVA="$JAVA_HOME/bin/java"
"$THC_JAVA" --enable-native-access=ALL-UNNAMED -Xss2m -XX:+UseCompactObjectHeaders -Dthc.traceCompilation=true "-Dthc.diagnosticUnsupported=$DIAGNOSTIC" \
  -cp 'build/install/thc/lib/*' thc.MapCheckKt build/map/modules.txt build/map/oracle.tsv \
  2>&1 | tee build/map/check.log
