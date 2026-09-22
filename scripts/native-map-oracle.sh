#!/usr/bin/env bash
# Match THC's containers source build, without loading the exporter plugin.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/build/map/native"
. "$ROOT/compiler/toolchain.sh"
mkdir -p "$BUILD"
cd "$ROOT/examples"
"$GHC" --make -O2 -fforce-recomp -dcore-lint -dstg-lint \
  -ddump-simpl -ddump-to-file -dsuppress-all -dsuppress-uniques \
  -i. -i"$ROOT/vendor/containers-0.8/src" -I"$ROOT/vendor/containers-0.8/include" \
  -odir "$BUILD" -hidir "$BUILD" -dumpdir "$BUILD/" \
  NativeOracle.hs -o "$BUILD/native-oracle" >&2
exec "$BUILD/native-oracle" "$@"
