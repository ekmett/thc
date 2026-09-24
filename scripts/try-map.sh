#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
. "$ROOT/scripts/java-home.sh"
scripts/prepare-tests.sh
scripts/prepare-map.sh
DIAGNOSTIC="${THC_DIAGNOSTIC_UNSUPPORTED:-false}"
scripts/gradle.sh --no-daemon test installDist "$@"
THC_JAVA="$JAVA_HOME/bin/java"
"$THC_JAVA" --enable-native-access=ALL-UNNAMED -Xss2m -XX:+UseCompactObjectHeaders -Dthc.traceCompilation=true "-Dthc.diagnosticUnsupported=$DIAGNOSTIC" \
  -cp 'build/install/thc/lib/*' thc.MapCheckKt build/map/modules.txt build/map/oracle.tsv \
  2>&1 | tee build/map/check.log
