#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Export real Haskell IO, audit every reachable path, then run it with GraalJS.
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/bin/toolchain.sh"
make check-java >/dev/null

bin/build-compiler.sh
for stage in pre post; do
  set --
  if [ "$stage" = post ]; then set -- -fplugin-opt=THC.Plugin:post-tidy; fi
  THC_CORE_OUT="$root/build/polyglot/$stage-core" \
  THC_GHC_OUT="$root/build/polyglot/$stage-ghc" \
    bin/export-core.sh -fplugin-opt=THC.Plugin:closure=main "$@" src/examples/THC/PolyglotDemo.hs
  python3 bin/audit-core.py \
    "build/polyglot/$stage-core/THC.Polyglot.json" \
    "build/polyglot/$stage-core/THC.Internal.Polyglot.json" \
    "build/polyglot/$stage-core/THC.PolyglotDemo.json" \
    "build/polyglot/$stage-core/THC.InterfaceClosure.json" \
    --entry main:THC.PolyglotDemo.main --io-main \
    --output "build/polyglot/$stage-audit.json"
done

exec ./gradlew polyglotDemo --console=plain
