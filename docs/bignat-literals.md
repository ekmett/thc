# BigNat literals and original Integer/Natural conversions

The exporter retains GHC 9.14.1 `LitNumBigNat` as a nonnegative decimal `bignat` literal. Both loaders construct a private primitive `byte[]` constant, with the exact evaluated `OBJECT` / `BoxedRep (Just Unlifted)` proof. This is the existing `ByteArray#` carrier. A host arbitrary-precision parser runs only while loading the constant; guest Integer arithmetic does not use it.

The encoding follows the pinned `GHC.CoreToStg.Prep.cpeBigNatLit`: least-significant word first, native byte order within each word, no high zero word, and zero as an empty array. This repository's Core preparation supports 64-bit targets; the new preparer checks target word size and native byte order. Core JSON does not currently carry a general cross-target ABI. Each literal gets separate storage; there is no global interning or result-pool ownership.

Negative Integers use GHC's `IN` constructor around a nonnegative magnitude. `IP` and Natural `NB` carry the same unlifted reference shape, while `IS` and `NS` hold machine scalars. Complete original `GHC.Internal.Bignum.BigNat`, `Integer`, and `Natural` modules are exported after Tidy in their original wired unit, with source notes and hashes. No cold definitions are removed. Public `toInteger`/`fromInteger` and Natural conversion fixtures retain opaque calls and real constructors in both export stages; large constants retain their literal nodes and original conversion workers.

The Haskell `thc-fixtures bignat-literals` command builds fresh native oracles, checks every returned sign/size/word/byte against an independent integer model, and audits eight supported roots at both stages. Bounds sentinels surround every byte/word observation. Cases include machine extrema, zero, word carries, internal zero limbs, and positive/negative magnitudes up to 256 bits. The JVM tests exercise AST and bytecode with normal and disabled guest inlining, require an actual compiled entry on every measured native row, retain original/active target validity and identity, and check result-pool cleanup. Native/source evidence alone does not establish guest execution.

Malformed decimal and contradictory representation proofs are rejected. GHC Core lint forbids BigNat literal alternatives (`litIsLifted LitNumBigNat`), so both loaders and the auditor reject those explicitly; reference identity is never substituted for a BigNat pattern.

Fixture orchestration and the integer-only corpus model live in Haskell; Kotlin
defines its own domains and byte/limb model and retains the malformed-value,
forged-proof, arithmetic-frontier and missing-source controls. The existing
shared Python `compiler/export-boot.py` and `scripts/audit-core.py` remain
explicit dependencies for original-source export and capability auditing.
The auditor's nine private representation-API assertions remain in its existing
`scripts/test-audit-core.py` self-test; no BigNat Python entrypoint or model remains.
The manifest is published last and fingerprints every required source and
artifact, including auditor inputs and command logs. Check-only re-runs the
22 audits and rejects changed bytes or missing inventory entries without
resealing the manifest.

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
cabal run exe:thc-fixtures --offline -- bignat-literals
cabal run exe:thc-fixtures --offline -- bignat-literals --check-only
./gradlew test --tests thc.runtime.BigNatLiteralTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew test --rerun --tests thc.runtime.BigNatLiteralTest
```
