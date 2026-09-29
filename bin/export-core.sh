#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Usage: bin/export-core.sh [GHC options] path/to/Module.hs ...
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/bin/toolchain.sh"
"$root/bin/build-compiler.sh"
plugin_db=$(python3 bin/plugin.py --field packageDb)
out=${THC_CORE_OUT:-$root/build/core}
obj=${THC_GHC_OUT:-$root/build/ghc}
mkdir -p "$out" "$obj"
if [ "${THC_SOURCE_NOTES:-true}" = true ]; then
  set -- -g -fplugin-opt=THC.Plugin:source-notes "$@"
fi
plugin=$(python3 bin/plugin.py --external-plugin "$out" "$@")
exec "$GHC" --make -no-link -O2 -dynamic -fforce-recomp -dcore-lint \
  -package-db "$plugin_db" "$plugin" \
  -i"$root/src/examples" -i"$root/t/fixtures/core" -i"$root/src/runtime" -i"$root/t/fixtures/compiler" -odir "$obj" -hidir "$obj" "$@"
