#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Export the real JavaScript FFI source in both Core stages, audit IO main, and run it.
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/bin/toolchain.sh"
make check-java >/dev/null

bin/build-compiler.sh
for stage in pre post; do
  set --
  if [ "$stage" = post ]; then set -- -fplugin-opt=THC.Plugin:post-tidy; fi
  THC_CORE_OUT="$root/build/javascript/$stage-core" \
  THC_GHC_OUT="$root/build/javascript/$stage-ghc" \
    bin/export-core.sh -fplugin-opt=THC.Plugin:closure=main "$@" src/examples/JavaScriptDemo.hs
  python3 bin/audit-core.py \
    "build/javascript/$stage-core/JavaScriptDemo.json" \
    "build/javascript/$stage-core/THC.InterfaceClosure.json" \
    --entry main:JavaScriptDemo.main --io-main \
    --output "build/javascript/$stage-audit.json"
done

exec ./gradlew polyglotDemo --console=plain \
  --args='build/javascript main:JavaScriptDemo.main JavaScriptDemo.json THC.InterfaceClosure.json'
