#!/usr/bin/env bash
# Prepare and check ordinary containers workloads against native GHC.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
. "$ROOT/scripts/java-home.sh"
scripts/prepare-tests.sh
python3 scripts/prepare-library-tests.py
scripts/gradle.sh --no-daemon test installDist "$@"
for backend in ast bytecode; do
  "$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -Xss2m -XX:+UseCompactObjectHeaders \
    -cp 'build/install/thc/lib/*' thc.LibraryCheckKt build/libraries/cases.json "$backend" \
    2>&1 | tee "build/libraries/check-$backend.log"
done
