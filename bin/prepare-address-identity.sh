#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
. "$root/bin/toolchain.sh"
out="$root/build/addr-identity"
rm -rf -- "$out"
mkdir -p "$out/native-objects"
"$GHC" --make -O2 -dynamic -i"$root/t/fixtures/compiler" \
  -outputdir "$out/native-objects" -o "$out/native" \
  t/fixtures/compiler/AddressIdentityAuditNative.hs
"$out/native" > "$out/oracle.txt"
for stage in pre post; do
  if [ "$stage" = post ]; then set -- -fplugin-opt=THC.Plugin:post-tidy; else set --; fi
  THC_CORE_OUT="$out/$stage-core" THC_GHC_OUT="$out/$stage-ghc" \
    bin/export-core.sh "$@" t/fixtures/compiler/AddressIdentityAudit.hs
  python3 bin/audit-core.py --entry probe --output "$out/$stage.audit.json" \
    "$out/$stage-core/AddressIdentityAudit.json"
done
