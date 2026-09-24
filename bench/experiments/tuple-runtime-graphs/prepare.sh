#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
EXPORT_ROOT="${THC_EXPORT_ROOT:-$ROOT}"
OUT="${1:-$ROOT/build/tuple-runtime-graphs/fixture}"
GHC="${GHC:-ghc}"; GHC_PKG="${GHC_PKG:-ghc-pkg}"
export GHC GHC_PKG
mkdir -p "$OUT/core" "$OUT/ghc" "$OUT/native"
OUT="$(cd "$OUT" && pwd)"
THC_CORE_OUT="$OUT/core" THC_GHC_OUT="$OUT/ghc" "$EXPORT_ROOT/compiler/export.sh" "$HERE/TupleRuntimeGraph.hs"
"$GHC" --make -O2 -fforce-recomp -dcore-lint -i"$HERE" -odir "$OUT/native" -hidir "$OUT/native" \
  "$HERE/TupleRuntimeGraphNative.hs" -o "$OUT/native/oracle"
"$OUT/native/oracle" > "$OUT/oracle.tsv"
python3 "$HERE/check-fixture.py" "$OUT" "$EXPORT_ROOT"
