#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
make --no-print-directory -s -C "$ROOT" check-java
make --no-print-directory -s -C "$ROOT" fixtures
bin/prepare-map.sh
DIAGNOSTIC="${THC_DIAGNOSTIC_UNSUPPORTED:-false}"
./gradlew --no-daemon test installDist toolsJar "$@"
THC_JAVA="$JAVA_HOME/bin/java"
. bin/benchmark-jvm-options.sh
"$THC_JAVA" --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xss2m "${THC_BENCH_JVM_OPTIONS[@]}" -Dthc.traceCompilation=true "-Dthc.diagnosticUnsupported=$DIAGNOSTIC" \
  -cp 'build/install/thc/lib/*:build/diagnostics/thc-tools.jar' thc.MapCheck build/map/modules.txt build/map/oracle.tsv \
  2>&1 | tee build/map/check.log
