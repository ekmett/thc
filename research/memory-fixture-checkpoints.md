# Memory fixture checkpoints

This archive preserves earlier fixture and compiler-investigation descriptions
from [the documentation at 040b1433](https://github.com/ekmett/thc/tree/040b14338accd292e6c71b8f80bf31cb50ea5e40/docs).
Counts, commands and limitations below describe their recorded checkpoints,
not the current producer or runtime. Historical commands may name removed tools.
Use [the MutVar guide](../docs/mutvars.md) and
[the pinned-memory guide](../docs/pinned-memory.md) for current contracts and checks.
The underlying graph evidence is unchanged; no new run is reported here.

## Recorded MutVar fixture coverage

`compiler/test-fixtures/MutVarAudit.hs` uses public `runST`, `newSTRef`,
`readSTRef`, `writeSTRef`, `modifySTRef`, `modifySTRef'`, and `(==)`. Eight entry points
cover aliased and independent references, read snapshots, captured references,
lazy bottom and closure payloads, an unlifted boxed product with a lazy field,
explicit State sequencing, and a recursive local-join countdown. Two equality
entries add opaque aliases and dynamic reference selection, distinct cells
sharing one payload, identity before and after writes, and bottom payloads with
no `Eq` instance. Independent reads check which selected cell was modified.
An opaque ST action keeps the post-write equality computation reachable instead
of letting GHC share the caller's pre-write result.
Preparation retains all expected operations in each reachable pre- and post-Tidy
entry, verifies the pointer comparison's exact argument/result representations,
requires strict acceptance, and compares 2,120 native GHC rows with an
independent unbounded-integer model. Inputs include the signed endpoints,
wrap boundaries, both reference-selection branches, and every countdown length
from zero through 32. The equality entries contribute 530 native rows.

```sh
GHC=/path/to/ghc-9.14.1 GHC_PKG=/path/to/ghc-pkg \
  python3 scripts/prepare-mutvar.py
python3 scripts/test-core-mutvars.py
./gradlew test --tests thc.runtime.MutVarTest
```

Normal `prepare-tests.sh` and CI generate the same fixture and retain the
source/artifact hashes. The JVM checks execute both export stages on both
backends, with inlining enabled and disabled, check every result against the
native oracle, and require compiled guest entry and host/active target validity
for each measured row. Active target identities must remain unchanged.
Malformed metadata, bad State carriers, mutation order, reference identity and
direct tuple destinations have separate controls.

## Explicit Graal speculation warmup for the countdown

The equality countdown has a separate, bounded compilation warmup. On the pinned
macOS AArch64 compiler, the first AST host compilation speculates that the initial
count is positive. Bytecode peels an iteration and speculates that it exceeds one.
The valid zero/one cases can therefore cause a host-only `LoopLimitCheck`
deoptimization with speculation `LoopInitLimitRelation`. Results and compiled
guest targets remain correct. Interpreter warmup already covered every loop
count; it cannot train a speculation created by compilation.

The inlining test explicitly probes the existing native inputs 99 and
1,000,000,000,000 (counts zero and one). Both must produce the native answer,
enter compiled guest code, and leave the original and active guest targets
valid. It records whether the host was valid before each call: after an AST
zero-trip deoptimization, the one-trip probe can enter a compiled guest through
an interpreted host. After both probes, it recompiles the host **at most once**,
only if it invalidated. Every original corpus row must then keep the host and
active guest compiled. There is no retry loop or relaxed steady-state assertion.
The other seven workloads and all tests with inlining disabled keep their
first-install stability checks.

The [recorded graph evidence](../docs/mutvar-loop-speculation.json) includes both export
stages and both backends. Each recompilation removes the failed
`LoopInitLimitRelation` speculation; all 265 unchanged loop inputs then remain
compiled. This is compiler speculation learning, not a join-runtime fix. The
unsuccessful join normalization experiments were reverted.

The original strict first-install diagnostic remains reproducible, including
its unmodified inputs and assertions:

```sh
THC_MUTVAR_REQUIRE_INITIAL_STABILITY=true ./gradlew test --rerun-tasks \
  --tests 'thc.runtime.MutVarTest.nativeSTRefWithInliningAndBoundedGraalSpeculationWarmup'
```

This diagnostic intentionally bypasses the two probes and conditional
recompilation. On the recorded compiler it fails at the first valid boundary
that invalidates the host. For a compiler trace, pass HotSpot
`-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=...` and Graal
`-Djdk.graal.Dump=Truffle:2 -Djdk.graal.PrintGraph=File -Djdk.graal.DumpPath=...`
through `JAVA_TOOL_OPTIONS`, then inspect the relevant host graph with
`tools/GraphInspect.java`. No compiler optimization is disabled.

Reauditing the existing real Map and Set exports removes their former
`readMutVar#` capability issue. Strict loading still rejects both: Map has 52
reachable bindings and Set has 71, each with zero capability issues and the same
three missing exception/backtrace/call-stack definitions. Native STRef evidence
does not resolve those source identities or promote diagnostic library runs to
strict support.

## Managed address-read producer

The following description belongs to the earlier Python producer. Its
[retained source](https://github.com/ekmett/thc/blob/de804ab7eab7b418439d44fa57f5ac8acd9475f0/scripts/prepare-managed-address-reads.py)
generates six genuine-Core mutations per entry at each of two stages, for four
entries. The current Haskell producer and Kotlin loader controls have different
responsibilities, described in the current guide.

`scripts/prepare-managed-address-reads.py` prepares 1,800 native/model cases,
eight accepted pre/post-Tidy closure audits and 48 rejected genuine-Core proof
mutations. `ManagedAddressReadTest` checks those exports on AST and bytecode,
then checks compiled mutable reads, bounds/overflow, State-before-read order,
failure-before-publication and malformed loader proofs. Native inputs are
aligned live allocations; adversarial out-of-bounds inputs are managed-only
tests. These reads do not enable arbitrary native pointer dereferences.
