#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Fixture rationale (088 sum-results)
# Purpose: Check sum alternatives and aggregate result layouts carry the right values
#   through execution.
# Produces/consumed result: Aggregate-frontier CBDs and native oracle.
# Cost and overlap: Keep this flow quarantined pending semantic consolidation;
#   aggregate-layout has an independent named CMake rule and two consumers.
# Build status: QUARANTINED: excluded from the new fixture build; see docs/fixture-quarantine.log.
# Detailed file inputs/outputs: docs/fixture-inputs.log, entry 088.

# Staged aggregate coverage; retain its native oracle separately from library rows.
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/bin/toolchain.sh"
"${CABAL:-cabal}" build exe:thc-fixtures --offline --with-compiler="$GHC" --with-hc-pkg="$GHC_PKG"
THC_FIXTURES=$("${CABAL:-cabal}" list-bin exe:thc-fixtures --offline --with-compiler="$GHC" --with-hc-pkg="$GHC_PKG")
export THC_FIXTURES
THC_CORE_OUT="$root/build/aggregate-core" THC_GHC_OUT="$root/build/aggregate-ghc" \
  bin/export-core.sh t/fixtures/compiler/AggregateFrontier.hs
THC_CORE_OUT="$root/build/aggregate-post-core" THC_GHC_OUT="$root/build/aggregate-post-ghc" \
  bin/export-core.sh -fplugin-opt=THC.Plugin:post-tidy t/fixtures/compiler/AggregateFrontier.hs
mkdir -p build/aggregate-native
"$GHC" --make -v0 -O2 -fforce-recomp -dcore-lint -it/fixtures/compiler \
  -odir build/aggregate-native -hidir build/aggregate-native \
  -o build/aggregate-native/aggregate-frontier t/fixtures/compiler/AggregateFrontierNative.hs
build/aggregate-native/aggregate-frontier > build/aggregate-native/oracle.tsv
python3 bin/check-aggregate-frontier.py
