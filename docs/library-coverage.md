# Real containers coverage beyond Map

The library workload sources use ordinary public containers APIs. They are
compiled from the same unmodified, SHA256-verified containers-0.8 source archive
as the Map example. No library operation is a JVM builtin. The supported/frontier
labels below describe the exact bundles prepared by `scripts/prepare-library-tests.py`,
not every possible complete-Core provider or every API in those packages.

## Data.Set Int: explicit unsupported frontier

`THC.SetWorkload.setAggregate` builds mixed-sign sets with duplicate insertions,
deletes present and absent keys, combines sets with union/intersection/difference,
queries membership, and folds in ascending order with an order-sensitive checksum.
Empty and negative workloads are defined. Preparation compares the selected
native GHC rows with an independent Python set model.

The prepared library bundle deliberately remains a strict frontier. Its auditor
requires zero capability issues and exactly three missing original definitions:

- `GHC.Internal.Exception.$fExceptionErrorCall_$ctoException`
- `GHC.Internal.Exception.Backtrace.collectExceptionAnnotationMechanismRef`
- `GHC.Internal.Stack.withFrozenCallStack1`

Collection tuple results, min/max tuple-result joins, zero-width `State#`
components and managed `readMutVar#` are supported. Preparation checks the
remaining diagnostics exactly: both new gaps and resolved gaps require review.
These bundle-local missing definitions do not imply that all exception handling
is unavailable in the runtime.

Insertion, deletion, union and intersection retain the real
`reallyUnsafePtrEquality#` primitive. Both backends implement it as
non-strict reference identity, without following thunk indirections. Separate
native-oracle fixtures test identity shortcuts with valid value-based fallbacks;
they do not require GHC and THC to make identical allocation choices. This does
not make the Set workload strictly supported while its cold-path gaps remain.

The complete source and strict diagnostic remain inputs to source-identity and
coverage checks. No synthetic boxed tuples or name substitutions make the bundle
appear supported. The [source-binding audit](set-source-binding-audit.md) records
the original export-boundary investigation.

## IntMap and word primitives

`THC.IntMapWorkload.intMapAggregate` uses `Data.IntMap.Strict` insertion with
combining, adjustment, deletion, membership, lookup, size and an order-sensitive
fold. Its signed keys include `minBound`, `maxBound`, negative keys and zero;
the workload includes duplicate, absent and empty-map operations.

The primitive fixtures cover `clz#` and unsigned `ltWord#`, including zero,
all 64 bit positions, adjacent values, the sign boundary and the all-ones word.
Native answers are compared with independent Python models.

The library preparer uses post-Tidy/pre-CorePrep export for complete Set and
IntMap compilations so consumers and source definitions use GHC's actual
interface identities. The primitive fixture uses pre-Tidy export. Preparation
requires the IntMap entries to audit with no missing globals or capability
issues, and retains `clz#` and `ltWord#` in both the genuine workload and
primitive fixture.

## IntSet and bitmap primitives

`THC.IntSetWorkload.intSetAggregate` exercises the public `Data.IntSet` insertion,
deletion, membership, size, union, intersection, difference and ascending-list
APIs. The workload combines dense bitmap leaves with mixed-sign Patricia
prefixes, explicit `minBound`/`maxBound` keys, values on both sides of 64-bit
leaf boundaries, duplicates, absent deletions and empty/negative inputs.
An order-sensitive checksum distinguishes signed ascending traversal from
unsigned or reversed traversal. Expected results come from the same native GHC
driver and a separate Python set model, not from reproducing the Patricia tree.

The dedicated runtime primitives are `popCnt#`, `ctz#` and unsigned
`leWord#`. Both the workload and the dedicated primitive fixture must retain all
three in reachable Core. The fixture checks every bit position and its adjacent
values, zero, all-ones, single cleared bits, alternating bit patterns, and
inclusive comparisons at zero, the signed maximum, the sign bit and all-ones.
All values cross the host boundary as their unchanged signed `Int#` bit patterns.
Focused JVM tests also exercise both comparison operands and reject malformed
arities in strict and diagnostic modes.

The strict IntSet audit must accept without missing globals or capability issues;
`ctz#`, `popCnt#` and `leWord#` must remain reachable. Native rows and
per-entry audits are fingerprinted with the original sources.

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
JVM execution. Exact-empty unboxed tuple inputs are supported at these guest
call boundaries; they are not the same representation as `State#` or boxed unit.
Other [aggregate inputs](tuple-inputs.md) have their own exact layout contract.

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
regular matrix. Their [historical boundary investigation](https://github.com/ekmett/thc/blob/ffdbca6f091b6ee48cd9baed2a8f61e3a6eb5a3d/docs/sequence-entry-boundary.md)
is separate research evidence, not an additional supported workload.

The declared complete native oracle contains 2,812 rows: 2,676 supported inputs and
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

To additionally exercise opt-in dense handoff, run
`JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true scripts/try-libraries.sh`.
The script launches fresh LibraryCheck processes for both backends regardless of
Gradle task caching. For the JVM unit suite's explicit per-mode forks, use the
[`testDefault`/`testDense` recipe](contributing.md#build-and-test).

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

The [unsafe-equality case lowering](unsafe-equality-cases.md) follows GHC's exact
late compiler rule. It removes matching proof-case dependencies without supplying
a general proof value or the missing cold Haskell definitions in this bundle.

## Public Show Int

[Original-source Show Int coverage](show-int.md) supplies the exact missing
installed decimal worker from the complete pinned GHC Show module. Public
pre/post-Tidy consumers compare checksums and every character against fresh
native GHC and an independent decimal model. This separate source-coverage
slice does not supply the missing Set/Sequence definitions listed above.

## Public Show Word and lists

[Original-source Word/list formatting](show-word-list.md) reuses the complete
pinned Show module and original CString helper. Public unsigned decimal and
`[Int]` consumers observe checksums, every character and end sentinels against
fresh native GHC and independent models. Empty, singleton and multiple lists
preserve the genuine list worker; no runtime or primitive capability is added.

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

## Public ShortByteString slices

`ShortByteStringSliceAudit` exercises ordinary `Data.ByteString.Short.take`,
`drop` and `splitAt` through installed bytestring-0.12.2.0 bodies, with the
complete original GHC List module supplying the pack worker's `$wlenAcc`
dependency. No primitive or runtime capability is added. Fresh pre/post exports
and strict per-entry audits retain the real `copyByteArray#` path.

The native/list model observes every result byte, length, checksum and end
sentinel, including both split parts, all 256 byte patterns, empty inputs and
negative/oversized machine-width counts. The AST/bytecode tests cover inlined
and residual calls, actual compiled entries, original/active target validity
and released result/argument storage. Preparation is
`python3 scripts/prepare-short-bytes-slices.py`; its `--check-only` mode verifies hashes,
all native rows and the exact strict audits.

Within this prepared slice bundle, `append`/`concat` remain strict frontiers
with the exact missing `Data.ByteString.Internal.Type.overflowError` definition
after original CString/List composition. The cold overflow branches stay intact.

This fixture makes no public Text claim. Managed
[resize and logical shrink](mutable-bytearray-size.md) are implemented, and
[three original text C adapters](interface-foreign.md) cover search, UTF-8
measurement and reversal under their Linux x86-64 configuration. Those primitive
and foreign-call implementations are not an audit of every public Text consumer;
a consumer still needs its complete original Haskell closure.

[BigNat literal and Integer/Natural conversion coverage](bignat-literals.md)
supplies complete original Bignum source bodies and canonical unlifted byte-array
literals. Its arithmetic controls accept Natural addition statically and retain
only the exact missing `raiseUnderflow` worker for Integer addition; that fixture's
native/JVM execution corpus remains literals and conversions. Original GMP
execution has [separate provider tests](gmp-limb-provider.md).

## Research history

The [archived library guide](../research/library-coverage-checkpoint.md) preserves
original export failures, compiler investigations and recorded test runs. Those
measurements describe their recorded revisions, not validation of the current tree.
