#!/bin/sh
set -eu
THC_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$THC_ROOT"
. "$THC_ROOT/scripts/java-home.sh"
scripts/prepare-tests.sh
# Normal dependency downloads are enabled. Pass --offline explicitly if desired.
scripts/gradle.sh --no-daemon test installDist "$@"
