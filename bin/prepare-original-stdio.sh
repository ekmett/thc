#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -eu
cd "$(dirname "$0")/.."
. bin/toolchain.sh
"${CABAL:-cabal}" build exe:thc-fixtures --offline --with-compiler="$GHC" --with-hc-pkg="$GHC_PKG"
fixture_bin=$("${CABAL:-cabal}" list-bin exe:thc-fixtures --offline --with-compiler="$GHC" --with-hc-pkg="$GHC_PKG")
exec "$fixture_bin" original-stdio "$@"
