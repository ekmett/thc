# Real containers coverage beyond Map

The library workload sources use ordinary public containers APIs. They are
compiled from the same unmodified, SHA256-verified containers-0.8 source archive
as the Map example. No library operation is a JVM builtin.

## Data.Set Int: explicit unsupported frontier

`THC.SetWorkload.setAggregate` builds mixed-sign sets with duplicate insertions,
deletes present and absent keys, combines sets with union/intersection/difference,
queries membership, and folds in ascending order with an order-sensitive checksum.
Empty and negative workloads are defined. Native GHC 9.14.1 agreed with an
independent Python set model for all 1,041 inputs from -16 through 1024.

This is **not currently an executable THC coverage claim**. Its strict reachable
audit exposes these gaps:

- `reallyUnsafePtrEquality#` is used by insertion, deletion, union and intersection.
- Deletion reaches unboxed pairs through `glue` and min/max extraction workers.
  Union/difference use pairs in split workers; intersection uses triples in
  `splitMember`. These must retain their unboxed representation when supported.
- Existing cold exception paths additionally reach `readMutVar#` and missing
  exception-construction, backtrace and call-stack bindings.

The current post-Tidy source export has 71 reachable bindings, three missing
boot-library definitions and 210 capability issues, including 101 explicit
aggregate-representation diagnostics. The original pre-Tidy export also missed
the actual identity of `main:Data.Set.Internal.merge_$smerge1`; the post-Tidy
boundary resolves it without aliases. Counts can change as the exporter learns
more precise diagnostics; rejection does not count as execution support.

The full source and its strict diagnostic are retained so aggregate lowering,
pointer identity and source/interface identity work can be tested against an
ordinary library program. No synthetic boxed tuples or ad hoc name substitutions
are used to make this frontier appear supported.

## IntMap and word primitives

`THC.IntMapWorkload.intMapAggregate` uses `Data.IntMap.Strict` insertion with
combining, adjustment, deletion, membership, lookup, size and an order-sensitive
fold. Its signed keys include `minBound`, `maxBound`, negative keys and zero;
the workload includes duplicate, absent and empty-map operations.

The dedicated native driver and independent Python set/dictionary/integer models
agree on 994 entry/input pairs: 22 Set inputs, 22 IntMap inputs and 190 inputs
for each of five word-primitive entries. The primitive fixtures cover `clz#`
and unsigned `ltWord#`, including zero, all 64 bit positions, adjacent values,
the sign boundary and the all-ones word.

The initial pre-Tidy IntMap audit found five missing worker identities:
`Data.IntMap.Internal.$wdelete`, `Data.IntMap.Internal.$wgo`,
`Data.IntMap.Strict.Internal.$winsert`,
`Data.IntMap.Strict.Internal.adjustWithKey_$sadjustWithKey`, and
`Data.IntMap.Strict.Internal.insertWithKey_$sinsertWithKey` (all in unit `main`).
These definitions exist in the source export under pre-Tidy private identities,
while consumers refer to their actual Tidy-generated interface identities.

The library preparation helper selects the existing, explicitly recorded
post-Tidy/pre-CorePrep exporter for the complete Set and IntMap compilations.
This uses GHC's actual definitions and names, not a name-matching heuristic.
The primitive fixture still uses the ordinary pre-Tidy boundary. IntMap's
regenerated strict audit accepts 26 reachable definitions with zero missing
globals or capability issues. The helper requires `clz#` and `ltWord#` to remain
reachable in both the genuine IntMap workload and primitive fixture.

Run `scripts/try-libraries.sh` with the pinned GHC and GraalVM environments.
Reports are under `build/libraries/`: per-bundle source provenance and strict
audits, `oracle.tsv`, `oracle-validation.json`, `cases.json`, and explicit AST
and bytecode check logs. Input and artifact fingerprints reject stale examples,
exporter/auditor implementations, capabilities, vendored sources, Core and
native-oracle artifacts.

The checker keeps strict rejection separate from execution passes and disables
compilation for its interpreted phase. A fresh context warms only the declared
warm inputs, requires successful guest compilation and installed-code entry,
then checks the withheld cold inputs. Cold branches may legitimately invalidate
code. After broad warmup and another compilation request, **every** input must
produce the native result and enter installed guest code. Unsupported traps and
blackholes must remain zero; no diagnostic unsupported mode is needed for
IntMap or the primitive entries.

Validated on Linux x86-64 with GHC 9.14.1 and GraalVM 25.3.4.1: 225 JVM tests
passed, and each backend passed 2,916 native-oracle comparisons (972 interpreted,
33 compiled-warm, 939 after-compilation cold, 972 final compiled). The 1,005
compiled-warm/final calls per backend each required a positive installed-code
entry counter delta. All six supported entries recorded zero unsupported traps
and blackholes. Set was rejected for its explicit unboxed-tuple representation
on both backends and is excluded from those execution counts. A deliberately
stale source fingerprint was also rejected before guest loading.
