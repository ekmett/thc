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

Strict loading still rejects this workload. Three exception-construction,
backtrace and call-stack bindings remain missing. The formerly unsupported
`readMutVar#` now has [native-validated managed reference support](mutvars.md).

The current strict audit has 71 reachable bindings, zero capability issues and
three missing definitions. Collection tuple results and the two reachable
min/max tuple-result joins now lower directly, and zero-width `State#` tuple components no longer add
capability gaps. Fresh `runRW#` exports also retain the exact
`(# State# RealWorld, SomeException #)` layout in the cold exception case.
Preparation pins the exact remaining diagnostics; neither new
gaps nor resolved gaps silently change the declared frontier.

Insertion, deletion, union and intersection retain the real
`reallyUnsafePtrEquality#` primitive. It is now supported on both backends as
non-strict reference identity, without following thunk indirections. Separate
native-oracle fixtures test identity shortcuts with valid value-based fallbacks;
they do not require GHC and THC to make identical allocation choices. This does
not make the Set workload strictly supported while its cold-path gaps remain.

The initial post-Tidy source export had 71 reachable bindings, three missing
boot-library definitions and 210 capability issues, including 101 explicit
aggregate-representation diagnostics. The original pre-Tidy export also missed
the actual identity of `main:Data.Set.Internal.merge_$smerge1`; the post-Tidy
boundary resolves it without aliases. Counts can change as the exporter learns
more precise diagnostics; rejection does not count as execution support.
The [focused source-binding audit](set-source-binding-audit.md) reproduces the
before/after identity difference and confirms that the unsupported issues remain.

The full source and its strict diagnostic are retained so aggregate lowering,
pointer identity and source/interface identity work can be tested against an
ordinary library program. No synthetic boxed tuples or ad hoc name substitutions
are used to make this frontier appear supported.

## IntMap and word primitives

`THC.IntMapWorkload.intMapAggregate` uses `Data.IntMap.Strict` insertion with
combining, adjustment, deletion, membership, lookup, size and an order-sensitive
fold. Its signed keys include `minBound`, `maxBound`, negative keys and zero;
the workload includes duplicate, absent and empty-map operations.

The original Set/IntMap entries in the dedicated native driver and independent
Python models agree on 994 entry/input pairs: 22 Set inputs, 22 IntMap inputs and 190 inputs
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

## IntSet and bitmap primitives

`THC.IntSetWorkload.intSetAggregate` exercises the public `Data.IntSet` insertion,
deletion, membership, size, union, intersection, difference and ascending-list
APIs. The workload combines dense bitmap leaves with mixed-sign Patricia
prefixes, explicit `minBound`/`maxBound` keys, values on both sides of 64-bit
leaf boundaries, duplicates, absent deletions and empty/negative inputs.
An order-sensitive checksum distinguishes signed ascending traversal from
unsigned or reversed traversal. Expected results come from the same native GHC
driver and a separate Python set model, not from reproducing the Patricia tree.

The three additional runtime primitives are `popCnt#`, `ctz#` and unsigned
`leWord#`. Both the workload and the dedicated primitive fixture must retain all
three in reachable Core. The fixture checks every bit position and its adjacent
values, zero, all-ones, single cleared bits, alternating bit patterns, and
inclusive comparisons at zero, the signed maximum, the sign bit and all-ones.
All values cross the host boundary as their unchanged signed `Int#` bit patterns.
Focused JVM tests also exercise both comparison operands and reject malformed
arities in strict and diagnostic modes.

The IntSet additions contribute 22 workload inputs and 255 inputs for each of
six primitive entries, bringing the complete native/model oracle to 2,546 rows.
The strict IntSet audit accepts 22 reachable bindings with no missing globals
or capability issues; `ctz#`, `popCnt#` and `leWord#` all remain reachable.

This example also exposed excessive AST loop nesting: nonrecursive joins were
being lowered to `LoopNode`, causing Graal escape analysis to exceed the existing
30-second compilation limit. Nonrecursive groups now dispatch once without a
loop; their RHS lexical scopes cannot jump back into the same group. Recursive
joins still use loops, and ancestor transfers, typed results and lazy values
retain their semantics. Catch dispatch structurally excludes the entry body so
opaque exception edges cannot make partial evaluation expand it twice. This
also avoids exceeding the graph-size limit with opt-in handoff transport.
The unchanged workload then passed compilation without
raising limits or adding Haskell optimizer fences. Regression tests exercise
deep acyclic nesting, actual cloned compiled targets, shadowed ancestor jumps,
full-width values and recursive/nonrecursive reference-result laziness.

## Data.Sequence: four supported public workloads

The regular library matrix includes four entries from the genuine
`THC.SequenceWorkload` source, compiled with the same unmodified containers-0.8
archive. Each has 38 inputs covering negative and empty sizes, small digit/tree
boundaries, 159/160/161, 255/256/257, 512, 1024 and both machine-Int extremes.
Sizes are explicitly bounded to 0–1024 by the workload.

| Entry | Public operations exercised |
| --- | --- |
| `sequenceBuild` | `fromList`, left/right order-sensitive folds, length |
| `sequenceEnds` | alternating prepend/append, both view directions, folds, length |
| `sequenceAppend` | concatenation in both operand orders, folds, length |
| `sequenceLazyPayloads` | lazy lifted payloads through end insertion, length and a spine-only fold |

The lazy workload places a genuine recursive bottom at each endpoint; neither
length nor the constant-valued spine fold demands either payload. The list/deque
model derives element order independently of the library's finger-tree
representation. Fresh native GHC output is checked against that model before any
JVM execution. Exact-empty unboxed tuple inputs are now supported at the guest
call boundaries reached by these workloads; this does not equate them with
`State#`, boxed unit, or general nonempty aggregate arguments.

`sequenceSplit`, `sequenceIndexUpdate` and `sequenceAggregate` remain explicit
strict frontiers. Their 114 native/model rows are retained separately and never
count as supported JVM execution. Fresh per-entry audits require zero capability
issues and the exact remaining exception/backtrace/call-stack definitions; the
index/update and combined entries additionally lack
`GHC.Internal.Show.$fShowCallStack_itos'`. Both newly introduced gaps and resolved
gaps fail preparation for review. Strict loading must reject one of the recorded
missing definitions. Each selected entry has its own hash-verified audit even
though the source is exported only once.

The additional view-only and lazy-length source entries are excluded from the
regular matrix. The historical first-compiled-call host linkage observation is
documented in the separate
[Sequence boundary investigation](https://github.com/ekmett/thc/blob/ffdbca6f091b6ee48cd9baed2a8f61e3a6eb5a3d/docs/sequence-entry-boundary.md).
Adding the four workloads above is coverage integration, not a claim that the
historical linkage condition has been fixed.

The complete native oracle now contains 2,812 rows: 2,676 supported inputs and
136 frontier-only inputs (22 Set and 114 Sequence). Each successful backend/mode
library run checks 8,028 native results, with a positive compiled-entry counter
delta required for all 2,760 compiled-warm/final calls. The 152 supported Sequence
inputs contribute 456 of those comparisons and 164 required compiled calls.
All calls use the normal public JSON loader and `Value.execute` path, with the
existing compiler limits and no added settling or recovery phase.

## Running the checks

Run `scripts/try-libraries.sh` with the pinned GHC and GraalVM environments.
Reports are under `build/libraries/`: per-bundle source provenance and strict
audits, `oracle.tsv`, `oracle-validation.json`, `cases.json`, and explicit AST
and bytecode check logs. Input and artifact fingerprints reject stale examples,
exporter/auditor implementations, capabilities, vendored sources, Core and
native-oracle artifacts. The declared entries and manifest rows must also match
the fingerprinted native oracle. CI runs the checks on Linux and macOS.

The checker keeps strict rejection separate from execution passes and disables
compilation for its interpreted phase. A fresh context warms only the declared
warm inputs, requires successful guest compilation and installed-code entry,
then checks the withheld cold inputs. Cold branches may legitimately invalidate
code. After broad warmup and another compilation request, **every** input must
produce the native result and enter installed guest code. Unsupported traps and
blackholes must remain zero; no diagnostic unsupported mode is needed for
IntMap, IntSet, the four Sequence workloads or the primitive entries. The existing
Linux/macOS library CI step prepares all groups and runs both explicit backends
with handoff disabled and enabled; no separate opt-in is needed.

To additionally exercise opt-in dense argument handoff transport, run
`JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true scripts/try-libraries.sh --rerun-tasks`.

The initial Set/IntMap checkpoint was validated on Linux x86-64 with GHC 9.14.1
and GraalVM 25.3.4.1: 225 JVM tests
passed, and each backend passed 2,916 native-oracle comparisons (972 interpreted,
33 compiled-warm, 939 after-compilation cold, 972 final compiled). The 1,005
compiled-warm/final calls per backend each required a positive installed-code
entry counter delta. All six supported entries recorded zero unsupported traps
and blackholes. Set was rejected for its explicit unboxed-tuple representation
on both backends and is excluded from those execution counts. A deliberately
stale source fingerprint was also rejected before guest loading.

The IntSet checkpoint was validated on the same Linux x86-64 toolchain:
242 JVM tests passed in both default and opt-in handoff configurations. Default
AST, default bytecode and opt-in AST handoff each passed 7,572 native-oracle
comparisons: 2,524 interpreted, 72 compiled-warm, 2,452 after-compilation cold and
2,524 final compiled. Each configuration required installed-code entry on all
2,596 compiled-warm/final calls, with zero unsupported traps or blackholes across
all 13 supported entries. The 22 Set oracle rows remain an explicit unsupported
frontier and are excluded from execution counts. A deliberately modified IntSet
expected value in the manifest was rejected against the fingerprinted native
oracle before guest loading. The 30-second compilation timeout and 100,000 graph
size limit remain unchanged.

## ShortByteString and managed bytes

The [managed ByteArray workload](bytearrays.md) executes the installed
ShortByteString pack/unpack/uncons workers and genuine GHC List length body. Strict
pre/post-Tidy audits retain all six supported byte primitives across the fixtures;
the public uncons roundtrip has nine reachable bindings and no audit issues.
Native/model checks cover empty arrays, every byte value, ordered writes, and
contained copies between distinct arrays on both backends.

## STRef and managed references

The [managed MutVar workload](mutvars.md) retains public `Control.Monad.ST` and
`Data.STRef` operations, including lazy and strict modification, reference
captures, saved read values and a recursive local join. Six pre/post-Tidy entry
points strictly audit with no missing definitions or capability issues; 1,590
native rows agree with an independent arithmetic model. Lifted values remain
lazy through storage and reads, including bottom and closure payloads.

The [unsafe-equality case lowering](unsafe-equality-cases.md) follows GHC's exact late compiler rule. It removes matching proof-case dependencies without supplying a general proof value; full Typeable/ErrorCall fingerprint and FFI paths remain unsupported.

## Public Show Int

[Original-source Show Int coverage](show-int.md) supplies the exact missing
installed decimal worker from the complete pinned GHC Show module. Public
pre/post-Tidy consumers compare checksums and every character against fresh
native GHC and an independent decimal model. This separate source-coverage
slice does not close the Set/Sequence exception and Typeable frontiers above.

## Boxed-array slices

[Shallow Array# slices](array-slices.md) add genuine `cloneArray#`, `freezeArray#`
and `thawArray#` with independent storage and lazy shared references. Public
STArray construction and Array indexing are covered around the slice operations;
ordinary public freeze/thaw remains an explicit `arrEleBottom` source frontier.

Managed mutable byte storage also supports `setByteArray#`,
`copyMutableByteArray#` and `copyMutableByteArrayNonOverlapping#`. The genuine
public ShortByteString replicate/fold consumer and defined-domain native models
are described in [mutable byte-array operations](mutable-bytearray-ops.md).
The immutable-to-mutable `copyByteArray#` keeps its distinct-storage requirement.
