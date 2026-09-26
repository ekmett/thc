# Coverage and runtime guide

THC uses GHC's Core and representation information to execute Haskell on
Truffle/Graal. These pages describe the supported forms and their tests. A native
oracle result, a passing diagnostic workload, and a strict whole-program pass
are different claims; each report identifies which it establishes.

## Programs and libraries

| Area | Reports |
| --- | --- |
| Core corpus | [Lazy lists, streams, sharing, trees and application](coverage.md); inputs in [coverage.json](../examples/coverage.json) |
| Containers | [IntMap, IntSet and Sequence](library-coverage.md); [Map example and its diagnostic frontier](map-example.md); [Set source-binding audit](set-source-binding-audit.md) |
| Formatting | [Int formatting](show-int.md), [Word and list formatting](show-word-list.md) |
| Integer and Natural | [BigNat literals and original conversion workers](bignat-literals.md); this does not imply general large-integer arithmetic |
| Mutable references | [ST/STRef with lazy lifted storage](mutvars.md) |
| MVars | [Managed cells, lazy payloads and blocking handoff](managed-mvars.md); a Handle IO foundation, not complete Handle support |
| Weak pointers | [Retained registrations and explicit finalization](weak-explicit.md), including [bounded C finalizers](c-finalizers.md); no automatic GC/ephemerons |
| Managed exports | [Declared scalar and IO actions](site/embedding.md) through polyglot bindings; not native C callback addresses |
| Locale and iconv | [Original native glibc/Sulong imports](original-iconv.md); explicit full-Core proof group, not complete Handle/IO |
| Native file ownership | [Opened-resource provider and original fstat](native-file-provider.md); Linux x86_64 `--run-io` uses it, RTS locking remains separate |
| Threads | [Asynchronous exceptions and resumable thunk evaluation](async-exceptions.md); [thread snapshots and boundness](thread-inventory.md); Java thread identities, masking and interruptible MVar waits |
| Delimited continuations | [Initial synchronous multi-shot slice](delimited-continuations.md); prompt identity, saved suffixes, shared effects, and catch/mask restoration |
| ShortByteString | [Pack, length, unpack, uncons, comparison, prefix and suffix](bytearrays.md); [public slicing](library-coverage.md) |
| Boxed arrays | [Public fixed-bounds STArray and lazy elements](core-evidence.md#lifted-boxed-array-storage); [clone, freeze and thaw slices](array-slices.md) |
| Numeric arrays | [Int](int-arrays.md), [Double](double-arrays.md), [Float and machine Word](float-word-arrays.md), [Int32/Word32](int32-arrays.md), [Int16/Word16](int16-arrays.md), [Int8/Word8](int8-arrays.md) |

`scripts/try.sh` prepares the native-GHC corpus and runs the JVM suite.
`scripts/try-libraries.sh` checks library workloads on both backends. The Map
example still needs `THC_DIAGNOSTIC_UNSUPPORTED=true`; the Set frontier remains
explicit. Neither is counted as strict whole-library support.

## Representation and primitive coverage

The generated [primop checklist](primops.md) lists every primop from the pinned
GHC 9.14.1 API. It distinguishes fixed scalar support from partial forms and
missing operations. The [capability contract](../scripts/core-capabilities.json)
is the machine-readable declaration used by the auditor.

| Area | Contracts and evidence |
| --- | --- |
| Core proofs | [Representation evidence, strictness and local joins](core-evidence.md); [aggregate layouts](aggregate-layout.md); [shared scalar signatures](scalar-primitive-signatures.md) |
| Integer scalars | [Unsigned machine/narrow operations](integer-primops.md), [signed narrow operations](signed-narrow-primops.md), [Int64 conversions](int64-conversions.md), [explicit Int64/Word64 operations](explicit64-primops.md), [bit operations](bit-primops.md) |
| Floating scalars | [Float/Double arithmetic, conversions, square roots and raw bit casts](floating-primitives.md) |
| Tuple arithmetic | [Quotient/remainder, overflow and carry results](tuple-arithmetic.md) |
| Constructors | [Concrete tagToEnum families](tag-to-enum.md); [constructor-to-tag families and precise address fields](core-evidence.md) |
| Managed byte storage | [Allocation, reads, writes and copies](bytearrays.md), [fill and mutable copies](mutable-bytearray-ops.md), [resize](resize-bytearrays.md), [mutable size queries](mutable-bytearray-size.md) |
| Type erasure | [Unsafe-equality cases](unsafe-equality-cases.md) |

### Tuples and sums

[Tuple results](tuple-results.md) retain exact recursive layouts, including
Float/Double leaves, local join results and zero-width State components.
[Typed tuple inputs](tuple-inputs.md) preserve logical arity across direct calls,
PAPs, overapplication and tail transfers. [Empty inputs](empty-tuple-inputs.md)
and [empty join inputs](empty-tuple-joins.md) have no physical payload fields.
Nonempty aggregate join inputs, aggregate captures and heap fields remain
unsupported.

[Binary sum results](sum-results.md) use typed destinations and explicit tags.
Sum inputs, storage, joins, nested sums and unresolved layouts remain rejected.
These contracts distinguish lifted tuples, unboxed aggregates and scalar State.

### SIMD operations and guest transport

The current [guest transport contract](simd-families.md) carries 24 exact `VecRep`
shapes through calls, results, PAPs, joins, tuple fields, owned closure/thunk
captures and boxed constructor fields. Public host vector arguments/results,
other shapes and unimplemented operations remain outside that contract. The
local operations and listed managed-memory slices below have their own
native/model checks and separate graph evidence.

These are GHC vector type names, not JVM classes. Current execution uses
[raw fixed-species JDK vectors](simd.md); the foundation pages retain historical
implementation and measurement checkpoints, not the removed THC wrappers as
current APIs.

| GHC vector shape | Local arithmetic | Managed byte-array memory |
| --- | --- | --- |
| Int64X2 | [Foundation](simd.md) | — |
| Int32X4 | [Foundation](simd.md), [wrapping multiplication](int32x4-multiply.md) | [Signed packed memory](int32x4-bytearray.md) |
| Int16X8 | [Foundation](int16x8.md) | — |
| Int8X16 | [Foundation](int8x16.md) | — |
| Word8X16 | [Foundation](word8x16.md) | — |
| Word16X8 | [Foundation](word16x8.md) | — |
| Word32X4 | [Foundation](word32x4.md) | [Unsigned packed memory](word32x4-bytearray.md) |
| FloatX4 | [Foundation](floatx4.md) | [Raw-bit packed memory](floatx4-bytearray.md) |
| DoubleX2 | [Foundation](doublex2.md) | [Raw-bit packed memory](doublex2-bytearray.md) |

The [generated wide arithmetic families](simd-wide-arithmetic.md) share exact
lane contracts and finite scalar-entry Haskell oracles/compiled JVM drivers.
The [floating vector min/max operations](floating-vector-minmax.md) use Java
NaN and signed-zero rules with separate native finite-input comparisons.
The [512-bit floating fused operations](wide-floating-fma.md) use genuine wide
Core and native scalar-lane expectations; native wide instruction parity remains
unproved.

## Performance and runtime design

The recorded Map measurements are workload-specific. The controlled ARM64 run
reduced bytecode time from 2.36 ms to 1.52 ms against native GHC's 1.28 ms. The
matched Linux i9-12900K run measured 1.60 ms against 1.34 ms. Both are about
1.19 times GHC's elapsed cost. Each retained report records warmup, process
variation and native correctness checks; these are not general Haskell timings.

* [Entry contracts and type preservation](entry-contracts.md), with the
  [controlled local runs](../bench/results/constructor-class/powered-default/),
  [hosted runs](../bench/results/hosted-2026-09-23/) and
  [Linux runs](../bench/results/castlemeadow-2026-09-23/).
* [Class-owned layouts](../bench/results/class-owned-layouts/): allocation,
  compact headers, retained graphs and comparison switches.
* [Typed execution and tail cycles](typed-tail.md), [call boundaries](call-boundaries.md),
  [call packets](call-packets.md), [dense handoff](handoff-slabs.md) and
  [Map inlining](map-inlining.md).
* [Laziness and thunk updates](thunk-updates.md), [boxed values](boxed-values.md)
  and [demand probes](demand-probe.md).
* [Bytecode backend](bytecode.md), [source locations](debug-locations.md),
  [graph inspection](graph-inspection.md) and [kernel measurements](prototype-results.md).
* [Architecture](architecture.md) and [development checks](contributing.md).

Runtime experiments are opt-in except compact headers and class-owned layouts.
`-Dthc.classOwnedLayouts=false` selects field-bearing layouts;
`-Pthc.compactObjectHeaders=false` disables compact headers for Gradle launches.
The controlled benchmark also accepts `-XX:-UseCompactObjectHeaders` for a
matched header-off run. Keep graph capture separate from timed measurements.

## Project integration

[The driver guide](driver.md) covers the current bounded Cabal build/run path,
including the optional complete-Core executable provider. Standalone `thc build`
and `thc repl` commands are not implemented. The separate
[managed export API](site/embedding.md) exposes declared scalar and IO actions
to polyglot callers.
