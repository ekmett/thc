#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Export real Haskell IO, audit every reachable path, then run it with GraalJS.
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/compiler/toolchain.sh"
make check-java >/dev/null

compiler/build.sh
for stage in pre post; do
  set --
  if [ "$stage" = post ]; then set -- -fplugin-opt=THC.Plugin:post-tidy; fi
  THC_CORE_OUT="$root/build/polyglot/$stage-core" \
  THC_GHC_OUT="$root/build/polyglot/$stage-ghc" \
    compiler/export.sh -fplugin-opt=THC.Plugin:closure=main "$@" examples/THC/PolyglotDemo.hs
  python3 scripts/audit-core.py \
    "build/polyglot/$stage-core/THC.Polyglot.json" \
    "build/polyglot/$stage-core/THC.PolyglotDemo.json" \
    "build/polyglot/$stage-core/THC.InterfaceClosure.json" \
    --entry main:THC.PolyglotDemo.main --io-main \
    --output "build/polyglot/$stage-audit.json"
done

exec ./gradlew polyglotDemo --console=plain
