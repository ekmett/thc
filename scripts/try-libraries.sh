#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Prepare and check ordinary containers workloads against native GHC.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
make --no-print-directory -s -C "$ROOT" check-java
scripts/prepare-tests.sh
python3 scripts/prepare-library-tests.py
./gradlew --no-daemon test installDist toolsJar "$@"
for backend in ast bytecode; do
  "$JAVA_HOME/bin/java" --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xss2m -XX:+UseCompactObjectHeaders \
    -cp 'build/install/thc/lib/*:build/diagnostics/thc-tools.jar' thc.LibraryCheckKt build/libraries/cases.json "$backend" \
    2>&1 | tee "build/libraries/check-$backend.log"
done
