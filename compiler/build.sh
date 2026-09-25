#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/compiler/toolchain.sh"
set -- build lib:thc --offline --with-compiler="$GHC" --with-hc-pkg="$GHC_PKG"
"${CABAL:-cabal}" "$@"
python3 compiler/plugin.py --publish --ghc-pkg "$GHC_PKG" >/dev/null
printf '%s\n' "Built THC Core plugin with Cabal"
