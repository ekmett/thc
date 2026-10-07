#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/bin/toolchain.sh"
set -- build lib:thc exe:thc-compact --with-compiler="$GHC" --with-hc-pkg="$GHC_PKG"
"${CABAL:-cabal}" "$@"
python3 bin/plugin.py --publish --ghc-pkg "$GHC_PKG" >/dev/null
printf '%s\n' "Built THC Core plugin with Cabal"
