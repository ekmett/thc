#!/bin/sh
# Generate the real GHC inputs required by all JVM tests, from a fresh checkout.
set -eu
cd "$(dirname "$0")/.."
python3 scripts/primop-coverage.py
python3 scripts/generate-scalar-signatures.py
compiler/build.sh
python3 scripts/prepare-floating-audit.py
python3 scripts/prepare-floating-tuples.py
python3 scripts/prepare-sqrt-audit.py
python3 scripts/prepare-tag-to-enum-audit.py
sh scripts/prepare-aggregate-frontier.sh
python3 scripts/check-sum-layout.py --prepare
python3 scripts/prepare-sum-result-audit.py
python3 scripts/prepare-tuple-return-audit.py
python3 scripts/prepare-state-tuple-audit.py
python3 scripts/prepare-empty-tuple-input-audit.py
python3 scripts/prepare-tuple-input-audit.py
python3 scripts/prepare-tuple-join-audit.py
python3 scripts/prepare-integer-primops.py
python3 scripts/prepare-bit-primops.py
python3 scripts/prepare-bytearray.py
python3 scripts/prepare-boxed-arrays.py
python3 scripts/prepare-address-fields.py
python3 scripts/prepare-mutvar.py
python3 scripts/prepare-int-arrays.py
python3 scripts/prepare-double-arrays.py
python3 scripts/prepare-int32-arrays.py
python3 scripts/prepare-float-word-arrays.py
python3 scripts/prepare-int16-arrays.py
# GHC9.14 AArch64 NCG requires LLVM for SIMD. The macOS job deliberately
# validates pre-Core/model execution; the x86 job also requires native + post-Tidy.
for simd_vector in int64x2 int32x4; do
  case "$(uname -m)" in
    arm64|aarch64) python3 scripts/prepare-simd-audit.py --vector "$simd_vector" --export-only ;;
    *) python3 scripts/prepare-simd-audit.py --vector "$simd_vector" ;;
  esac
done
case "$(uname -m)" in
  arm64|aarch64) python3 scripts/prepare-floatx4-audit.py --export-only ;;
  *) python3 scripts/prepare-floatx4-audit.py ;;
esac
case "$(uname -m)" in
  arm64|aarch64) python3 scripts/prepare-doublex2-audit.py --export-only ;;
  *) python3 scripts/prepare-doublex2-audit.py ;;
esac
python3 scripts/prepare-tuple-arithmetic.py
case "$(uname -m)" in
  arm64|aarch64) python3 scripts/prepare-int16x8-audit.py --export-only ;;
  *) python3 scripts/prepare-int16x8-audit.py ;;
esac
python3 scripts/prepare-signed-narrow-primops.py
python3 scripts/prepare-explicit64-primops.py
compiler/export.sh examples/THC/Fixtures.hs compiler/test-fixtures/StrictFields.hs compiler/test-fixtures/SpeculationAudit.hs compiler/test-fixtures/RepresentationAudit.hs compiler/test-fixtures/SourceNotes.hs compiler/test-fixtures/CbvAudit.hs compiler/test-fixtures/CbvJoinAudit.hs compiler/test-fixtures/CbvCoercionAudit.hs compiler/test-fixtures/ConstructorFieldAudit.hs compiler/test-fixtures/DemandAudit.hs
python3 scripts/check-speculation-metadata.py
python3 scripts/check-representation-metadata.py
python3 scripts/check-demand-metadata.py --ghc-api
# Compare pre-Tidy contract proposals with the real native Tidy result.
THC_CORE_OUT="$PWD/build/cbv-post-core" THC_GHC_OUT="$PWD/build/cbv-post-ghc" \
  compiler/export.sh -fplugin-opt=Thc.Plugin:post-tidy compiler/test-fixtures/CbvAudit.hs compiler/test-fixtures/CbvJoinAudit.hs compiler/test-fixtures/CbvCoercionAudit.hs
python3 scripts/check-cbv-metadata.py
python3 scripts/check-constructor-field-metadata.py
# Exercise genuine GHC notes in CI even when ordinary workload exports opt out.
THC_SOURCE_NOTES=true THC_CORE_OUT="$PWD/build/source-core" THC_GHC_OUT="$PWD/build/source-ghc" \
  compiler/export.sh compiler/test-fixtures/SourceNotes.hs compiler/test-fixtures/RepresentationAudit.hs
python3 scripts/check-source-metadata.py --fixture build/source-core/SourceNotes.json build/source-core/RepresentationAudit.json
python3 compiler/export-boot.py
mkdir -p build/native
scripts/native-oracle.sh > build/native/oracle.tsv
python3 scripts/prepare-corpus.py
