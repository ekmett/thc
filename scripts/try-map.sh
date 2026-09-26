#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
make --no-print-directory -s -C "$ROOT" check-java
scripts/prepare-tests.sh
scripts/prepare-map.sh
DIAGNOSTIC="${THC_DIAGNOSTIC_UNSUPPORTED:-false}"
./gradlew --no-daemon test installDist toolsJar "$@"
THC_JAVA="$JAVA_HOME/bin/java"
"$THC_JAVA" --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xss2m -XX:+UseCompactObjectHeaders -Dthc.traceCompilation=true "-Dthc.diagnosticUnsupported=$DIAGNOSTIC" \
  -cp 'build/install/thc/lib/*:build/diagnostics/thc-tools.jar' thc.MapCheckKt build/map/modules.txt build/map/oracle.tsv \
  2>&1 | tee build/map/check.log
