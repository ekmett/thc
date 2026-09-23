#!/usr/bin/env bash
# Standalone library checks; the generalized corpus/manifest has separate ownership.
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
