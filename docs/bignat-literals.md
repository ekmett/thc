# BigNat literals and original Integer/Natural conversions

The exporter retains GHC 9.14.1 `LitNumBigNat` as a nonnegative decimal `bignat` literal. Both loaders construct a private primitive `byte[]` constant, with the exact evaluated `OBJECT` / `BoxedRep (Just Unlifted)` proof. This is the existing `ByteArray#` carrier. A host arbitrary-precision parser runs only while loading the constant; guest Integer arithmetic does not use it.

The encoding follows the pinned `GHC.CoreToStg.Prep.cpeBigNatLit`: least-significant word first, native byte order within each word, no high zero word, and zero as an empty array. This repository's Core preparation supports 64-bit targets; the new preparer checks target word size and native byte order. Core JSON does not currently carry a general cross-target ABI. Each literal gets separate storage; there is no global interning or result-pool ownership.

Negative Integers use GHC's `IN` constructor around a nonnegative magnitude. `IP` and Natural `NB` carry the same unlifted reference shape, while `IS` and `NS` hold machine scalars. Complete original `GHC.Internal.Bignum.BigNat`, `Integer`, and `Natural` modules are exported after Tidy in their original wired unit, with source notes and hashes. No cold definitions are removed. Public `toInteger`/`fromInteger` and Natural conversion fixtures retain opaque calls and real constructors in both export stages; large constants retain their literal nodes and original conversion workers.

`prepare-bignat-literals.py` builds fresh native oracles, checks every returned sign/size/word/byte against an independent integer model, and audits eight supported roots at both stages. Bounds sentinels surround every byte/word observation. Cases include machine extrema, zero, word carries, internal zero limbs, and positive/negative magnitudes up to 256 bits. The JVM tests exercise AST and bytecode with normal and disabled guest inlining, require an actual compiled entry on every measured native row, retain original/active target validity and identity, and check result-pool cleanup. Native/source evidence alone does not establish guest execution.

Malformed decimal and contradictory representation proofs are rejected. GHC Core lint forbids BigNat literal alternatives (`litIsLifted LitNumBigNat`), so both loaders and the auditor reject those explicitly; reference identity is never substituted for a BigNat pattern.

Integer/Natural addition remains a strict frontier. Both stages retain the exact GMP foreign identities and mutable-size/shrink primitive issues; Integer addition also retains `raiseUnderflow`. The supported WordC primops remove exactly one carry/borrow issue from each addition closure. The source export does not provide foreign implementations or shrink semantics. Multiplication, quotient, general arbitrary-precision arithmetic, and exception/Typeable dependencies are outside this slice.

Reproduce the fresh preparation and focused controls with the pinned GHC/JDK toolchain:

```sh
python3 scripts/prepare-bignat-literals.py
python3 scripts/test-bignat-literals.py
./gradlew test --tests thc.runtime.BigNatLiteralTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew test --rerun --tests thc.runtime.BigNatLiteralTest
```
