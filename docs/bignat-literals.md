# BigNat literals and public Integer/Natural behavior

The exporter retains GHC 9.14.1 `LitNumBigNat` as a nonnegative magnitude.
Both loaders use the exact evaluated `OBJECT` / `BoxedRep (Just Unlifted)`
proof and the invoking context's heap or native `ByteArray#` storage policy.
A host arbitrary-precision parser runs while loading constants; guest Integer
arithmetic executes the original Haskell library.

The encoding follows `GHC.CoreToStg.Prep.cpeBigNatLit`: least-significant word
first, native byte order within each word, no high zero word, and zero as an
empty array. Core preparation supports 64-bit targets. Fixture-free tests cover
canonical bytes, checked sizes, independent literal storage, context ownership,
and heap/native storage in both backends.

Negative Integers use GHC's `IN` constructor around a nonnegative magnitude.
`IP` and Natural `NB` carry the same unlifted reference shape; `IS` and `NS`
hold machine scalars. The public package test in `run-library-memory` compares
original Integer/Natural carry, signed quotient/remainder and boundary/truncating
conversions with independent expectations and native GHC in both backends and
handoff modes.

Malformed decimal and contradictory representation proofs are rejected. GHC
Core lint forbids BigNat literal alternatives (`litIsLifted LitNumBigNat`), so
both loaders and the auditor reject those explicitly. Fixture-free tests retain
these negative controls.

These cases do not imply complete Integer/Natural library coverage.
