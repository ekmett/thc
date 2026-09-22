#!/bin/sh
set -eu
THC_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$THC_ROOT/scripts/java-home.sh"

GRADLE_USER_HOME=${THC_GRADLE_USER_HOME:-${GRADLE_USER_HOME:-$THC_ROOT/.gradle-user-home}}
export GRADLE_USER_HOME
cd "$THC_ROOT"
exec ./gradlew "$@"
