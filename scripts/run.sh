#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -eu
THC_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
make --no-print-directory -s -C "$THC_ROOT" check-java
exec "$THC_ROOT/build/install/thc/bin/thc" \
    "$THC_ROOT/build/core/THC.Prim.json,$THC_ROOT/build/core/THC.Fixtures.json" "$@"
