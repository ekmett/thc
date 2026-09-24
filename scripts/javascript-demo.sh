#!/bin/sh
# Export the real JavaScript FFI source in both Core stages, audit IO main, and run it.
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/compiler/toolchain.sh"
. "$root/scripts/java-home.sh"

compiler/build.sh
for stage in pre post; do
  set --
  if [ "$stage" = post ]; then set -- -fplugin-opt=THC.Plugin:post-tidy; fi
  THC_CORE_OUT="$root/build/javascript/$stage-core" \
  THC_GHC_OUT="$root/build/javascript/$stage-ghc" \
    compiler/export.sh -fplugin-opt=THC.Plugin:closure=main "$@" examples/THC/JavaScriptDemo.hs
  python3 scripts/audit-core.py \
    "build/javascript/$stage-core/THC.JavaScriptDemo.json" \
    "build/javascript/$stage-core/THC.InterfaceClosure.json" \
    --entry main:THC.JavaScriptDemo.main --io-main \
    --output "build/javascript/$stage-audit.json"
done

exec scripts/gradle.sh polyglotDemo --console=plain \
  --args='build/javascript main:THC.JavaScriptDemo.main THC.JavaScriptDemo.json THC.InterfaceClosure.json'
