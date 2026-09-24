#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -eu
THC_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$THC_ROOT"
. "$THC_ROOT/scripts/java-home.sh"
scripts/prepare-tests.sh
# Normal dependency downloads are enabled. Pass --offline explicitly if desired.
scripts/gradle.sh --no-daemon test installDist "$@"
