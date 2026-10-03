#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Usage: bin/export-core.sh [GHC options] path/to/Module.hs ...
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/bin/toolchain.sh"
# Explicitly supplied publication is a build-graph input. Legacy standalone
# callers retain their build entry point until their fixtures are migrated.
if [ "${1:-}" = --plugin-manifest ]; then
  [ "${2:-}" = "$root/build/compiler/plugin.json" ] || {
    echo "Expected this checkout's build/compiler/plugin.json" >&2; exit 2;
  }
  shift 2
else
  "$root/bin/build-compiler.sh"
fi
plugin_db=$("${THC_PYTHON:-python3}" bin/plugin.py --field packageDb)
out=${THC_CORE_OUT:-$root/build/core}
obj=${THC_GHC_OUT:-$root/build/ghc}
mkdir -p "$out" "$obj"
if [ "${THC_SOURCE_NOTES:-true}" = true ]; then
  set -- -g -fplugin-opt=THC.Plugin:source-notes "$@"
fi
plugin=$("${THC_PYTHON:-python3}" bin/plugin.py --external-plugin "$out" "$@")
exec "$GHC" --make -no-link -O2 -dynamic -fforce-recomp -dcore-lint \
  -package-db "$plugin_db" "$plugin" \
  -i"$root/src/examples" -i"$root/t/fixtures/core" -i"$root/src/runtime" -i"$root/t/fixtures/compiler" -odir "$obj" -hidir "$obj" "$@"
