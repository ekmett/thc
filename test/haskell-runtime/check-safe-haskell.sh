#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -euo pipefail
cd "$(dirname "$0")/../.."
safe_build_dir="${THC_SAFE_HASKELL_BUILD:-build/runtime-services-safe}"
mkdir -p "$safe_build_dir"
ghc --make -XHaskell2010 -fno-code -Wall -Werror -iruntime -odir "$safe_build_dir" -hidir "$safe_build_dir" \
  test/haskell-runtime/SafeClient.hs
if ghc --make -XHaskell2010 -fno-code -Wall -Werror -iruntime -odir "$safe_build_dir" -hidir "$safe_build_dir" \
    test/haskell-runtime/UnsafeClient.hs >"$safe_build_dir/unsafe-client.log" 2>&1; then
  echo 'Unsafe THC.Internal.JIT unexpectedly accepted by Safe Haskell' >&2
  exit 1
fi
if ! rg -q 'cannot be safely imported|Can.t be safely imported' "$safe_build_dir/unsafe-client.log"; then
  echo 'Negative client failed for a reason other than the expected Safe Haskell rejection' >&2
  sed -n '1,120p' "$safe_build_dir/unsafe-client.log" >&2
  exit 1
fi
echo 'Safe Haskell: stable imports accepted; THC.Internal.JIT correctly rejected'
