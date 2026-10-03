#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Fixture rationale (113 runtime-core-native)
# Purpose: Check representative real GHC Core executes correctly on both THC
#   backends/modes.
# Produces/consumed result: Fixtures/THC.Prim.Test CBDs and build/native/oracle.tsv.
# Cost and overlap: This is the natural shared semantic smoke corpus. Extend it for
#   ordinary behavior instead of adding a new exporter/native harness for each primop.
# Build status: Value review only; admission still requires explicit inputs and single-
#   owner outputs.
# Detailed file inputs/outputs: docs/fixture-inputs.log, entry 113.

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/build/native"
. "$ROOT/bin/toolchain.sh"
mkdir -p "$BUILD"
cd "$ROOT/t/fixtures/core"
"$GHC" --make -O2 -fforce-recomp -dcore-lint -dstg-lint \
  -ddump-simpl -ddump-to-file -dsuppress-all -dsuppress-uniques \
  -i. -i"$ROOT/t/fixtures/compiler" -odir "$BUILD" -hidir "$BUILD" -dumpdir "$BUILD/" \
  NativeOracle.hs -o "$BUILD/native-oracle" >&2
exec "$BUILD/native-oracle" "$@"
