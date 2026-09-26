#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -eu
THC_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$THC_ROOT"
make --no-print-directory -s -C "$THC_ROOT" check-java
scripts/prepare-tests.sh
# Normal dependency downloads are enabled. Pass --offline explicitly if desired.
./gradlew --no-daemon test "$@" installDist toolsJar
