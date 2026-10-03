#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Fixture rationale (099 original-stdio)
# Purpose: Check public writes preserve bytes/counts and descriptor behavior.
# Produces/consumed result: Installed/Core fixtures and oracle.json for write operations.
# Cost and overlap: One package I/O integration test can cover this. 144 native process
#   starts for four write operations are unjustified; batch cases and retire the separate
#   conformance harness.
# Build status: Value review only; admission still requires explicit inputs and single-
#   owner outputs.
# Detailed file inputs/outputs: docs/fixture-inputs.log, entry 099.

set -eu
cd "$(dirname "$0")/.."
. bin/toolchain.sh
"${CABAL:-cabal}" build exe:thc-fixtures --offline --with-compiler="$GHC" --with-hc-pkg="$GHC_PKG"
fixture_bin=$("${CABAL:-cabal}" list-bin exe:thc-fixtures --offline --with-compiler="$GHC" --with-hc-pkg="$GHC_PKG")
exec "$fixture_bin" original-stdio "$@"
