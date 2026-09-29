# BigNat literals and original Integer/Natural conversions

The exporter retains GHC 9.14.1 `LitNumBigNat` as a nonnegative decimal `bignat` literal. Both loaders construct a private primitive `byte[]` constant, with the exact evaluated `OBJECT` / `BoxedRep (Just Unlifted)` proof. This is the existing `ByteArray#` carrier. A host arbitrary-precision parser runs only while loading the constant; guest Integer arithmetic does not use it.

The encoding follows the pinned `GHC.CoreToStg.Prep.cpeBigNatLit`: least-significant word first, native byte order within each word, no high zero word, and zero as an empty array. This repository's Core preparation supports 64-bit targets; the new preparer checks target word size and native byte order. Core JSON does not currently carry a general cross-target ABI. Each literal gets separate storage; there is no global interning or result-pool ownership.

Negative Integers use GHC's `IN` constructor around a nonnegative magnitude. `IP` and Natural `NB` carry the same unlifted reference shape, while `IS` and `NS` hold machine scalars. Complete original `GHC.Internal.Bignum.BigNat`, `Integer`, and `Natural` modules are exported after Tidy in their original wired unit, with source notes and hashes. No cold definitions are removed. Public `toInteger`/`fromInteger` and Natural conversion fixtures retain opaque calls and real constructors in both export stages; large constants retain their literal nodes and original conversion workers.

Malformed decimal and contradictory representation proofs are rejected. GHC Core lint forbids BigNat literal alternatives (`litIsLifted LitNumBigNat`), so both loaders and the auditor reject those explicitly; reference identity is never substituted for a BigNat pattern.

See [GMP operations](gmp-limb-provider.md) for arithmetic support. Literal
and conversion support alone does not imply a complete Integer/Natural library.
