#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
. "$ROOT/scripts/java-home.sh"
scripts/prepare-tests.sh
DIAGNOSTIC="${THC_DIAGNOSTIC_UNSUPPORTED:-false}"
case "$DIAGNOSTIC" in true|false) ;; *) echo 'THC_DIAGNOSTIC_UNSUPPORTED must be true or false' >&2; exit 2;; esac
compiler/export-map.sh
AUDIT_STATUS=0
python3 scripts/audit-core.py --entry mapAggregate --module-list build/map/modules.txt --output build/map/audit.json || AUDIT_STATUS=$?
if [ "$AUDIT_STATUS" != 0 ]; then
  if [ "$DIAGNOSTIC" != true ] || [ "$AUDIT_STATUS" != 1 ]; then exit "$AUDIT_STATUS"; fi
  echo 'DIAGNOSTIC RUN: capability audit is incomplete; unsupported paths will trap if reached.' >&2
fi
scripts/native-map-oracle.sh mapAggregate 10000
python3 scripts/check-map-oracle.py
scripts/gradle.sh --no-daemon test installDist "$@"
THC_JAVA="$JAVA_HOME/bin/java"
"$THC_JAVA" --enable-native-access=ALL-UNNAMED -Xss2m -XX:+UseCompactObjectHeaders -Dthc.traceCompilation=true "-Dthc.diagnosticUnsupported=$DIAGNOSTIC" \
  -cp 'build/install/thc/lib/*' thc.MapCheckKt build/map/modules.txt build/map/oracle.tsv \
  2>&1 | tee build/map/check.log
