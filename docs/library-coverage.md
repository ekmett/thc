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

## Data.Sequence: executable slices and aggregate frontiers

`THC.SequenceWorkload` exercises ordinary public `Data.Sequence` APIs from the
same pinned, unmodified containers sources. Eleven entries share one post-Tidy
export, with a separate strict audit and explicit support declaration for each
entry. The full bundle's rejected audit cannot hide either supported slices or
unsupported operations. All per-entry reports are fingerprinted artifacts.

Four entries have accepted strict audits with no missing globals or capability
issues: `sequenceBuildViews` builds with `fromList` and drains through alternating
left/right views; `sequenceDequeViews` builds through alternating left/right
insertion and observes both drain directions; `sequenceAppendViews` concatenates
unequal sequences in both orders; `sequenceLazyLength` observes length/null with
self-referential, unused lifted payloads at both ends. They retain respectively
19, 18, 23 and 9 reachable definitions. These separate API slices do not replace
the full workload or stand in for fold/split/index support.

The remaining seven entries deliberately retain their strict unsupported
frontiers. Specialized `foldl'`/`foldr` workers pass genuine zero-width unboxed
`(# #)` **arguments**, so supporting only aggregate results will not unblock
them. Split, lookup, index and update additionally reach unboxed pair/triple
workers after GHC's constructor-result optimization. Cold error paths reach
`readMutVar#`, `quotRemInt#` and missing exception/backtrace/call-stack/Show
definitions. No `main:` source-library binding is missing. Boxed pairs, triples,
unit, views and finger-tree nodes are not themselves aggregate-ABI failures;
lifted element payloads must stay lazy. Nothing is boxed or substituted to make
a rejected operation appear supported.

The independent oracle uses Python lists/deques, not a second finger-tree
implementation. It checks order-sensitive folds and both drain directions,
both concatenation orders, negative/end/interior split positions, present/missing
lookups and invalid/in-range updates. Each entry has 38 inputs, including empty
and negative sizes, every size from 0 through 17, digit/node carry boundaries,
25/160 bulk-builder boundaries, 255/256/257, 512/1024 and machine-Int extremes.
Sizes are clamped to 0–1024 before arithmetic. The six focused model sanity tests
are available through `python3 scripts/test-sequence-model.py`.

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
IntMap, IntSet or the primitive entries. The existing Linux/macOS library CI step
prepares all groups and runs both explicit backends; no separate opt-in is needed
for the IntSet workload.

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
