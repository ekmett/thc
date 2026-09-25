# BigNat literals and original Integer/Natural conversions

The exporter retains GHC 9.14.1 `LitNumBigNat` as a nonnegative decimal `bignat` literal. Both loaders construct a private primitive `byte[]` constant, with the exact evaluated `OBJECT` / `BoxedRep (Just Unlifted)` proof. This is the existing `ByteArray#` carrier. A host arbitrary-precision parser runs only while loading the constant; guest Integer arithmetic does not use it.

The encoding follows the pinned `GHC.CoreToStg.Prep.cpeBigNatLit`: least-significant word first, native byte order within each word, no high zero word, and zero as an empty array. This repository's Core preparation supports 64-bit targets; the new preparer checks target word size and native byte order. Core JSON does not currently carry a general cross-target ABI. Each literal gets separate storage; there is no global interning or result-pool ownership.

Negative Integers use GHC's `IN` constructor around a nonnegative magnitude. `IP` and Natural `NB` carry the same unlifted reference shape, while `IS` and `NS` hold machine scalars. Complete original `GHC.Internal.Bignum.BigNat`, `Integer`, and `Natural` modules are exported after Tidy in their original wired unit, with source notes and hashes. No cold definitions are removed. Public `toInteger`/`fromInteger` and Natural conversion fixtures retain opaque calls and real constructors in both export stages; large constants retain their literal nodes and original conversion workers.

`prepare-bignat-literals.py` builds fresh native oracles, checks every returned sign/size/word/byte against an independent integer model, and audits eight supported roots at both stages. Bounds sentinels surround every byte/word observation. Cases include machine extrema, zero, word carries, internal zero limbs, and positive/negative magnitudes up to 256 bits. The JVM tests exercise AST and bytecode with normal and disabled guest inlining, require an actual compiled entry on every measured native row, retain original/active target validity and identity, and check result-pool cleanup. Native/source evidence alone does not establish guest execution.

Malformed decimal and contradictory representation proofs are rejected. GHC Core lint forbids BigNat literal alternatives (`litIsLifted LitNumBigNat`), so both loaders and the auditor reject those explicitly; reference identity is never substituted for a BigNat pattern.

The arithmetic audit controls retain the complete original dependency closure.
Both stages accept the seven shrink calls used by Integer addition and five
used by Natural addition. The original GMP calls are now recognized: both roots
retain two `__gmpn_add` calls and one `__gmpn_add_1`; Integer addition also retains
one `__gmpn_cmp` and one `__gmpn_sub`. Natural addition passes the static audit.
Integer addition still rejects exactly the missing original `raiseUnderflow`
worker. These arithmetic controls establish audit outcomes only; this fixture's
native and JVM corpus remains limited to literals and conversions. Original GMP
execution is covered separately in [the GMP provider tests](gmp-limb-provider.md).

Reproduce the fresh preparation and focused controls with the pinned GHC/JDK toolchain:

```sh
python3 scripts/prepare-bignat-literals.py
python3 scripts/test-bignat-literals.py
./gradlew test --tests thc.runtime.BigNatLiteralTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew test --rerun --tests thc.runtime.BigNatLiteralTest
```
