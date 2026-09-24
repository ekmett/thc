#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

set -eu
cd "$(dirname "$0")/.."
. compiler/toolchain.sh
directory="$PWD/build/core-continuation"
mkdir -p "$directory/core" "$directory/ghc" "$directory/native"
THC_CORE_OUT="$directory/core" THC_GHC_OUT="$directory/ghc" \
  compiler/export.sh compiler/test-fixtures/CoreContinuationAudit.hs
python3 scripts/audit-core.py "$directory/core/CoreContinuationAudit.json" \
  --entry sharedAnswer --output "$directory/audit.json"
"$GHC" -O2 -i./compiler/test-fixtures -odir "$directory/native" -hidir "$directory/native" \
  -o "$directory/native-oracle" \
  compiler/test-fixtures/CoreContinuationNative.hs compiler/test-fixtures/CoreContinuationAudit.hs
"$directory/native-oracle" > "$directory/native-output.txt"
test "$(cat "$directory/native-output.txt")" = 108
