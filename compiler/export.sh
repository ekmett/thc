#!/bin/sh
# Usage: compiler/export.sh [GHC options] path/to/Module.hs ...
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/compiler/toolchain.sh"
if [ ! -d "$root/build/compiler/package.conf.d" ]; then
  "$root/compiler/build.sh"
fi
out=${THC_CORE_OUT:-$root/build/core}
obj=${THC_GHC_OUT:-$root/build/ghc}
mkdir -p "$out" "$obj"
if [ "${THC_SOURCE_NOTES:-true}" = true ]; then
  set -- -g -fplugin-opt=Thc.Plugin:source-notes "$@"
fi
exec "$GHC" --make -no-link -O2 -dynamic -fforce-recomp -dcore-lint \
  -package-db "$root/build/compiler/package.conf.d" -package thc-core-plugin \
  -fplugin=Thc.Plugin "-fplugin-opt=Thc.Plugin:$out" \
  -i"$root/examples" -odir "$obj" -hidir "$obj" "$@"
