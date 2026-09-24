#!/bin/sh
# Staged aggregate coverage; retain its native oracle separately from library rows.
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/compiler/toolchain.sh"
THC_CORE_OUT="$root/build/aggregate-core" THC_GHC_OUT="$root/build/aggregate-ghc" \
  compiler/export.sh compiler/test-fixtures/AggregateFrontier.hs
THC_CORE_OUT="$root/build/aggregate-post-core" THC_GHC_OUT="$root/build/aggregate-post-ghc" \
  compiler/export.sh -fplugin-opt=THC.Plugin:post-tidy compiler/test-fixtures/AggregateFrontier.hs
mkdir -p build/aggregate-native
"$GHC" --make -v0 -O2 -fforce-recomp -dcore-lint -icompiler/test-fixtures \
  -odir build/aggregate-native -hidir build/aggregate-native \
  -o build/aggregate-native/aggregate-frontier compiler/test-fixtures/AggregateFrontierNative.hs
build/aggregate-native/aggregate-frontier > build/aggregate-native/oracle.tsv
python3 scripts/check-aggregate-frontier.py
python3 scripts/check-aggregate-layout.py --prepare
