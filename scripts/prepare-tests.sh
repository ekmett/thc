#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Generate the real GHC inputs required by all JVM tests, from a fresh checkout.
set -eu
cd "$(dirname "$0")/.."
python3 scripts/primop-coverage.py --check
python3 scripts/generate-scalar-signatures.py
compiler/build.sh
python3 scripts/prepare-io-main-pap.py
python3 scripts/prepare-floating-audit.py
python3 scripts/prepare-floating-tuples.py
python3 scripts/prepare-scalar-bitcasts.py
python3 scripts/prepare-tag-to-enum-audit.py
python3 scripts/prepare-unsafe-equality-audit.py
python3 scripts/prepare-show-int.py
python3 scripts/prepare-show-word-list.py
python3 scripts/prepare-bignat-literals.py
python3 scripts/prepare-narrow-literal-proofs.py
python3 scripts/prepare-short-bytes-slices.py
sh scripts/prepare-aggregate-frontier.sh
python3 scripts/check-sum-layout.py --prepare
python3 scripts/prepare-sum-result-audit.py
python3 scripts/prepare-tuple-return-audit.py
python3 scripts/prepare-state-tuple-audit.py
python3 scripts/prepare-empty-tuple-input-audit.py
python3 scripts/prepare-tuple-input-audit.py
python3 scripts/prepare-tuple-join-audit.py
python3 scripts/prepare-empty-join-input.py
cabal build exe:thc-fixtures --offline
fixture_bin=$(cabal list-bin exe:thc-fixtures --offline)
"$fixture_bin" integer
"$fixture_bin" bit
"$fixture_bin" word-floating
"$fixture_bin" floating-address
"$fixture_bin" floating-byte-offset
"$fixture_bin" narrow-byte-offset
"$fixture_bin" int32-byte-offset
"$fixture_bin" explicit64-arrays
"$fixture_bin" fused-floating
"$fixture_bin" sqrt
"$fixture_bin" original-stack
"$fixture_bin" original-stack-formatter
"$fixture_bin" boxed-array-extensions
python3 scripts/prepare-bytearray.py
python3 scripts/prepare-mutable-bytearrays.py
python3 scripts/prepare-resize-bytearrays.py
python3 scripts/prepare-mutable-bytearray-size.py
python3 scripts/prepare-compare-byte-arrays.py
python3 scripts/prepare-boxed-arrays.py
"$fixture_bin" small-arrays
python3 scripts/prepare-array-slices.py
python3 scripts/prepare-address-fields.py
python3 scripts/prepare-data-to-tag.py
"$fixture_bin" mutvar
python3 scripts/prepare-managed-mvars.py --refresh
rm -rf -- build/synchronous-exceptions
python3 scripts/prepare-synchronous-exceptions.py
"$fixture_bin" core-continuation
"$fixture_bin" live-async
"$fixture_bin" thread-async
"$fixture_bin" uncaught-self
"$fixture_bin" mask-functions
"$fixture_bin" interface-core
python3 scripts/prepare-managed-md5.py
"$fixture_bin" original-stdio --require-supported
"$fixture_bin" original-stdio-read
"$fixture_bin" original-handle-readiness
