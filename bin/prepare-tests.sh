#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Generate the real GHC inputs required by all JVM tests, from a fresh checkout.
set -eu
cd "$(dirname "$0")/.."
. bin/toolchain.sh
"${CABAL:-cabal}" run exe:thc-primops --with-compiler="$GHC" --with-hc-pkg="$GHC_PKG" -- coverage --check
"${CABAL:-cabal}" run exe:thc-primops --with-compiler="$GHC" --with-hc-pkg="$GHC_PKG" -- scalars
bin/build-compiler.sh
python3 bin/prepare-io-main-pap.py
python3 bin/prepare-floating-audit.py
python3 bin/prepare-floating-tuples.py
python3 bin/prepare-tag-to-enum-audit.py
python3 bin/prepare-show-int.py
python3 bin/prepare-show-word-list.py
python3 bin/prepare-narrow-literal-proofs.py
python3 bin/prepare-short-bytes-slices.py
sh bin/prepare-aggregate-frontier.sh
python3 bin/check-sum-layout.py --prepare
python3 bin/prepare-sum-result-audit.py
python3 bin/prepare-tuple-return-audit.py
python3 bin/prepare-state-tuple-audit.py
python3 bin/prepare-empty-tuple-input-audit.py
python3 bin/prepare-tuple-input-audit.py
python3 bin/prepare-tuple-join-audit.py
python3 bin/prepare-empty-join-input.py
"${CABAL:-cabal}" build exe:thc-fixtures --offline --with-compiler="$GHC" --with-hc-pkg="$GHC_PKG"
fixture_bin=$("${CABAL:-cabal}" list-bin exe:thc-fixtures --offline --with-compiler="$GHC" --with-hc-pkg="$GHC_PKG")
printf '%s\n' "$fixture_bin" > build/thc-fixtures.path
"$fixture_bin" backend-annotations
"$fixture_bin" sum-join
"$fixture_bin" unsafe-equality
"$fixture_bin" proxy-void
"$fixture_bin" selector-proof
"$fixture_bin" unix-libc
"$fixture_bin" text-cbits
"$fixture_bin" integer
"$fixture_bin" bit
"$fixture_bin" word-floating
"$fixture_bin" scalar-bitcasts
"$fixture_bin" bignat-literals
"$fixture_bin" rubbish-literals
"$fixture_bin" float-decode
"$fixture_bin" floating-remainder
"$fixture_bin" floating-address
"$fixture_bin" atomic-address
"$fixture_bin" floating-byte-offset
"$fixture_bin" unaligned-scalar-memory
"$fixture_bin" explicit64-arrays
"$fixture_bin" fused-floating
"$fixture_bin" simd-calls
"$fixture_bin" vector-api
"$fixture_bin" truffle-strings
"$fixture_bin" simd-floatx4-fma
"$fixture_bin" simd-wide-floating-fma
"$fixture_bin" sqrt
"$fixture_bin" original-stack
"$fixture_bin" original-stack-formatter
"$fixture_bin" package-native-gc-carriers
"$fixture_bin" boxed-array-extensions
"$fixture_bin" boxed-cas
"$fixture_bin" bytearray
"$fixture_bin" mutable-bytearrays
"$fixture_bin" resize-bytearrays
"$fixture_bin" mutable-bytearray-size
"$fixture_bin" compare-byte-arrays
python3 bin/prepare-boxed-arrays.py
"$fixture_bin" small-arrays
"$fixture_bin" scalar-memory-utilities
python3 bin/prepare-array-slices.py
python3 bin/prepare-address-fields.py
python3 bin/prepare-data-to-tag.py
"$fixture_bin" mutvar
"$fixture_bin" stable-pointers
"$fixture_bin" stable-names
"$fixture_bin" weak-explicit
"$fixture_bin" shrink-bytearrays
"$fixture_bin" fetch-add-int-array
"$fixture_bin" atomic-int-arrays
python3 bin/prepare-managed-mvars.py --refresh
rm -rf -- build/synchronous-exceptions
python3 bin/prepare-synchronous-exceptions.py
"$fixture_bin" core-continuation
"$fixture_bin" live-async
"$fixture_bin" thread-async
"$fixture_bin" thread-status
"$fixture_bin" thread-inventory
"$fixture_bin" thread-scheduling
"$fixture_bin" delimited-continuations
"$fixture_bin" ghc-bco
"$fixture_bin" thread-label
"$fixture_bin" hint-trace
"$fixture_bin" closure-inspection
"$fixture_bin" uncaught-self
"$fixture_bin" mask-functions
"$fixture_bin" scalar-exception-results
"$fixture_bin" exception-result-layouts
"$fixture_bin" deep-evaluation
"$fixture_bin" interface-core
"$fixture_bin" record-fields
python3 bin/prepare-managed-md5.py
"$fixture_bin" original-stdio --require-supported
"$fixture_bin" original-stdio-read
"$fixture_bin" bytestring-utf8
"$fixture_bin" original-memset
"$fixture_bin" original-memory-search
"$fixture_bin" original-handle-readiness
"$fixture_bin" original-posix-stat
# Genuine GMP fixture and managed provider are currently Linux x86_64 only.
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) "$fixture_bin" original-gmp --require-supported ;;
esac
"$fixture_bin" original-stdio-close
"$fixture_bin" original-posix-dup
"$fixture_bin" original-open
"$fixture_bin" original-termios
"$fixture_bin" original-fcntl
"$fixture_bin" original-errno
"$fixture_bin" original-process-identity
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) "$fixture_bin" process-lifecycle ;;
esac
"$fixture_bin" original-tcsetattr
"$fixture_bin" original-tcgetattr
"$fixture_bin" original-rts-locks --require-supported
"$fixture_bin" rts-diagnostics
"$fixture_bin" original-stdio-seek
"$fixture_bin" native-addresses
"$fixture_bin" process-signals
"$fixture_bin" rts-shutdown
"$fixture_bin" original-strerror
"$fixture_bin" original-stdio-truncate
"$fixture_bin" original-fd-ready
"$fixture_bin" pinned-addresses
"$fixture_bin" pinned-pointer-cells
"$fixture_bin" address-array-copy
"$fixture_bin" aligned-scalar-memory
"$fixture_bin" wide-char-address
bin/prepare-address-identity.sh
"$fixture_bin" managed-address-reads
"$fixture_bin" int-arrays double-arrays int32-arrays float-word-arrays int16-arrays int8-arrays
# GHC9.14 AArch64 NCG requires LLVM for SIMD. The macOS job deliberately
# validates pre-Core/model execution; the x86 job also requires native + post-Tidy.
for simd_vector in int64x2 int32x4; do
  case "$(uname -m)" in
    arm64|aarch64) python3 bin/prepare-simd-audit.py --vector "$simd_vector" --export-only ;;
    *) python3 bin/prepare-simd-audit.py --vector "$simd_vector" ;;
  esac
done
case "$(uname -m)" in
  arm64|aarch64) python3 bin/prepare-floatx4-audit.py --export-only ;;
  *) python3 bin/prepare-floatx4-audit.py ;;
esac
case "$(uname -m)" in
  arm64|aarch64) python3 bin/prepare-doublex2-audit.py --export-only ;;
  *) python3 bin/prepare-doublex2-audit.py ;;
esac
python3 bin/prepare-simd-capability-smoke.py
"$fixture_bin" tuple-arithmetic
"$fixture_bin" integer-completion
"$fixture_bin" simd128-addresses
"$fixture_bin" simd128-arrays
"$fixture_bin" simd-wide-arrays
"$fixture_bin" simd-address-families
"$fixture_bin" simd-arithmetic
"$fixture_bin" signed-narrow
case "$(uname -m)" in
  arm64|aarch64) "$fixture_bin" int32x4-bytearray --export-only ;;
  *) "$fixture_bin" int32x4-bytearray ;;
esac
case "$(uname -m)" in
  arm64|aarch64) "$fixture_bin" word32x4-bytearray --export-only ;;
  *) "$fixture_bin" word32x4-bytearray ;;
esac
case "$(uname -m)" in
  arm64|aarch64) "$fixture_bin" floatx4-bytearray --export-only ;;
  *) "$fixture_bin" floatx4-bytearray ;;
esac
case "$(uname -m)" in
  arm64|aarch64) "$fixture_bin" doublex2-bytearray --export-only ;;
  *) "$fixture_bin" doublex2-bytearray ;;
esac
"$fixture_bin" explicit64
bin/export-core.sh t/fixtures/core/Fixtures.hs t/fixtures/compiler/StrictFields.hs t/fixtures/compiler/SpeculationAudit.hs t/fixtures/compiler/RepresentationAudit.hs t/fixtures/compiler/SourceNotes.hs t/fixtures/compiler/CBVAudit.hs t/fixtures/compiler/CBVJoinAudit.hs t/fixtures/compiler/CBVCoercionAudit.hs t/fixtures/compiler/ConstructorFieldAudit.hs t/fixtures/compiler/DemandAudit.hs
python3 bin/check-speculation-metadata.py
python3 bin/check-representation-metadata.py
python3 bin/check-demand-metadata.py --ghc-api
# Compare pre-Tidy contract proposals with the real native Tidy result.
THC_CORE_OUT="$PWD/build/cbv-post-core" THC_GHC_OUT="$PWD/build/cbv-post-ghc" \
  bin/export-core.sh -fplugin-opt=THC.Plugin:post-tidy t/fixtures/compiler/CBVAudit.hs t/fixtures/compiler/CBVJoinAudit.hs t/fixtures/compiler/CBVCoercionAudit.hs
python3 bin/check-cbv-metadata.py
python3 bin/check-constructor-field-metadata.py
# Exercise genuine GHC notes in CI even when ordinary workload exports opt out.
THC_SOURCE_NOTES=true THC_CORE_OUT="$PWD/build/source-core" THC_GHC_OUT="$PWD/build/source-ghc" \
  bin/export-core.sh t/fixtures/compiler/SourceNotes.hs t/fixtures/compiler/RepresentationAudit.hs
python3 bin/check-source-metadata.py --fixture build/source-core/SourceNotes.cbd build/source-core/RepresentationAudit.cbd
python3 bin/export-boot.py
mkdir -p build/native
bin/native-oracle.sh > build/native/oracle.tsv
python3 bin/prepare-corpus.py
