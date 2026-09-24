#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/build/native"
. "$ROOT/compiler/toolchain.sh"
mkdir -p "$BUILD"
cd "$ROOT/examples"
"$GHC" --make -O2 -fforce-recomp -dcore-lint -dstg-lint \
  -ddump-simpl -ddump-to-file -dsuppress-all -dsuppress-uniques \
  -i. -odir "$BUILD" -hidir "$BUILD" -dumpdir "$BUILD/" \
  NativeOracle.hs -o "$BUILD/native-oracle" >&2
exec "$BUILD/native-oracle" "$@"
