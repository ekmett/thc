#!/bin/sh
# Export real Haskell IO, audit every reachable path, then run it with GraalJS.
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/compiler/toolchain.sh"
. "$root/scripts/java-home.sh"

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

exec scripts/gradle.sh polyglotDemo --console=plain
