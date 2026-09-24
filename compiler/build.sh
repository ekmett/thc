#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
selected_ghc=${GHC-}
selected_ghc_pkg=${GHC_PKG-}
. "$root/compiler/toolchain.sh"
set -- build lib:thc --offline
if [ -n "$selected_ghc" ] && [ "$(command -v "$GHC")" != "$(command -v ghc)" ]; then
  set -- "$@" --with-compiler="$GHC"
fi
if [ -n "$selected_ghc_pkg" ] && [ "$(command -v "$GHC_PKG")" != "$(command -v ghc-pkg)" ]; then
  set -- "$@" --with-hc-pkg="$GHC_PKG"
fi
"${CABAL:-cabal}" "$@"
python3 compiler/plugin.py --publish --ghc-pkg "$GHC_PKG" >/dev/null
printf '%s\n' "Built THC Core plugin with Cabal"
