# Coverage and runtime guide

Scalar memory: [complete unaligned scalar families](unaligned-scalar-memory.md).

THC uses GHC's Core and representation information to execute Haskell on
Truffle/Graal. These pages describe the supported forms and their tests. A native
oracle result, a passing diagnostic workload, and a strict whole-program pass
are different claims; each report identifies which it establishes.

## Programs and libraries

| Area | Reports |
| --- | --- |
| Core corpus | [Lazy lists, streams, sharing, trees and application](coverage.md); inputs in [coverage.json](../examples/coverage.json) |
| Containers | [IntMap, IntSet and Sequence](library-coverage.md); [breadth-first graph example](graph-example.md); [Map example and its diagnostic frontier](map-example.md); [Set source-binding audit](set-source-binding-audit.md) |
| Formatting | [Int formatting](show-int.md), [Word and list formatting](show-word-list.md) |
| Integer and Natural | [BigNat literals and original conversion workers](bignat-literals.md); this does not imply general large-integer arithmetic |
| Absent-value fillers | [Scalar GHC rubbish literals](rubbish-literals.md), original interface evidence and aggregate/vector frontiers |
| Mutable references | [ST/STRef with lazy lifted storage](mutvars.md) |
| Stable names | [Non-evaluating weak identity tokens](stable-names.md) |
| Compact regions | [Copied graphs, membership, sharing, cycles and context-local serialized blocks](compact-regions.md); no cross-context/process import or GHC wire-format compatibility |
| Boxed atomic updates | [Pointer CAS and lazy atomic modification](boxed-cas.md) |
| MVars | [Managed cells, lazy payloads and blocking handoff](managed-mvars.md); a Handle IO foundation, not complete Handle support |
| Weak pointers | [Retained registrations and explicit finalization](weak-explicit.md), including [bounded C finalizers](c-finalizers.md); no automatic GC/ephemerons |
| Managed exports | [Declared scalar and IO actions](site/embedding.md) through polyglot bindings; not native C callback addresses |
| Native executable probe | [Native Image feasibility](native-image-feasibility.md); pure-Core interpretation in a native executable, not guest JIT/AOT or a complete native FFI lifecycle |
| Locale and iconv | [Original native glibc/Sulong imports](original-iconv.md); explicit full-Core proof group, not complete Handle/IO |
| Byte comparison and search | [Original libc `memcmp`/`memchr`](original-memory-search.md), unsigned byte semantics and allocation-preserving interior pointers |
| Byte sorting | [Original ByteString `fps_sort`](original-bytestring-sort.md), unsigned ordering in checked writable slices |
| Native file ownership | [Opened-resource provider and original fstat](native-file-provider.md); Linux x86_64 `--run-io` uses it, RTS locking remains separate |
| Threads | [Asynchronous exceptions and resumable thunk evaluation](async-exceptions.md); [thread snapshots and boundness](thread-inventory.md); context-owned logical guest identities, masking and interruptible MVar waits |
| Delimited continuations | [Initial synchronous multi-shot slice](delimited-continuations.md); prompt identity, saved suffixes, shared effects, and catch/mask restoration |
| GHC bytecode objects | [Executable scalar BCOs and updating wrappers](ghc-bco.md); real instruction decoding and guest application, with explicit opcode/ABI limits |
| Compiler-library RTS hooks | [FastString shared CAF, CAF retention and unique-supply cells](compiler-rts.md); not the native GHC object loader |
| GC, statistics and time | [Original GHC declarations](gc-stats-clock.md); advisory JVM collection, explicitly disabled GHC statistics and monotonic nanoseconds |
| Process signals | [Original GHC INT/QUIT/HUP/TERM dispatch](process-signals.md); Linux x86_64 launcher with `-Xrs` and async enabled on either backend, not embedding authority |
| ShortByteString | [Pack, length, unpack, uncons, comparison, prefix and suffix](bytearrays.md); [public slicing](library-coverage.md) |
| Boxed arrays | [Public fixed-bounds STArray and lazy elements](core-evidence.md#lifted-boxed-array-storage); [clone, freeze and thaw slices](array-slices.md) |
| Numeric arrays | [Int](int-arrays.md), [Double](double-arrays.md), [Float and machine Word](float-word-arrays.md), [Int32/Word32](int32-arrays.md), [Int16/Word16](int16-arrays.md), [Int8/Word8](int8-arrays.md) |

`scripts/try.sh` prepares the native-GHC corpus and runs the JVM suite.
`scripts/try-libraries.sh` checks library workloads on both backends. The Map
example still needs `THC_DIAGNOSTIC_UNSUPPORTED=true`; the Set frontier remains
explicit. Neither is counted as strict whole-library support.

## Representation and primitive coverage

The [closure inspection guide](closure-inspection.md) describes lazy heap images,
non-profiling cost centres, and absent closure provenance on the JVM target.

The generated [primop checklist](primops.md) lists every primop from the pinned
GHC 9.14.1 API. It distinguishes implemented and missing operations; concrete
runtime limitations are documented [primop by primop in the behavior reference](primop-behavior.md).
That page distinguishes concrete restrictions from target choices and performance
hints. The [capability contract](../scripts/core-capabilities.json)
is the machine-readable declaration used by the auditor.

| Area | Contracts and evidence |
| --- | --- |
| Core proofs | [Representation evidence, strictness and local joins](core-evidence.md); [aggregate layouts](aggregate-layout.md); [shared scalar signatures](scalar-primitive-signatures.md) |
| Integer scalars | [Unsigned machine/narrow operations](integer-primops.md), [signed narrow operations](signed-narrow-primops.md), [Int64 conversions](int64-conversions.md), [explicit Int64/Word64 operations](explicit64-primops.md), [bit operations](bit-primops.md) |
| Floating scalars | [Float/Double arithmetic, conversions, square roots, raw bit casts and integer decomposition](floating-primitives.md) |
| Tuple arithmetic | [Quotient/remainder, overflow and carry results](tuple-arithmetic.md) |
| Remaining scalar integer operations | [Narrow division, logical shifts, double-word division and overflow](integer-completion.md) |
| Constructors | [Concrete tagToEnum families](tag-to-enum.md); [constructor-to-tag families and precise address fields](core-evidence.md) |
| Managed byte storage | [Allocation, reads, writes and copies](bytearrays.md), [fill and mutable copies](mutable-bytearray-ops.md), [address/array copies](address-array-copy.md), [resize](resize-bytearrays.md), [mutable size queries](mutable-bytearray-size.md), [address utilities, pinning, unsafe thaw and small-array shrink](scalar-memory-utilities.md), [atomic integer reads, writes, fetch and CAS](atomic-int-arrays.md) |
| Address atomics | [Word and pointer atomic reads, writes, exchange, CAS and fetch operations](atomic-address.md) |
| Aligned pointer/character storage | [Opaque StablePtr cells and four-byte WideChar slots](aligned-scalar-memory.md) |
| Type erasure | [Unsafe-equality cases](unsafe-equality-cases.md), [zero-width proxy](proxy-void.md) |

### Tuples and sums

[Tuple results](tuple-results.md) retain exact recursive layouts, including
Float/Double leaves, local join results and zero-width State components.
[Typed tuple inputs](tuple-inputs.md) preserve logical arity across direct calls,
PAPs, overapplication and tail transfers. [Empty inputs](empty-tuple-inputs.md)
and [empty join inputs](empty-tuple-joins.md) have no physical payload fields.
[Tuple join inputs](tuple-joins.md) use parallel typed frame moves, including
the original GHC floating-formatting worker. [Ordinary tuple captures](tuple-captures.md)
retain owned typed properties.
[Aggregate constructor fields](aggregate-heap-fields.md) flatten exact tuple and
sum layouts into owned typed heap properties.

[Sum results](sum-results.md) use typed destinations and explicit tags.
Local sum join results use those same destinations. [Sum inputs](sum-inputs.md)
support direct calls, PAPs, tail transfers, local join inputs/captures and owned
closure captures, including concrete nested sums, managed addresses and supported
vector species. Ordinary sum lets and unresolved layouts remain rejected.
These contracts distinguish lifted tuples, unboxed aggregates and scalar State.

### SIMD operations and guest transport

[Packed and scalar-offset SIMD address memory](simd-address-families.md) covers
18 existing 128-/256-/512-bit shapes with original-Core and native scalar models.

The current [guest transport contract](simd-families.md) carries 30 exact `VecRep`
shapes through calls, results, PAPs, joins, tuple fields, owned closure/thunk
captures and boxed constructor fields. Public host vector arguments/results,
other shapes and unimplemented operations remain outside that contract. The
local operations and listed managed-memory families below have their own
operation-specific native/model checks.

These are GHC vector type names. Execution uses [fixed-species JDK vectors](simd.md);
Core metadata retains exact lane width and signedness.

| GHC vector shape | Local arithmetic | Managed byte-array memory |
| --- | --- | --- |
| Int64X2 | [Foundation](simd.md) | [ByteArray](simd128-array-memory.md), [Addr#](simd128-address-memory.md) |
| Word64X2 | [Generated arithmetic](simd-wide-arithmetic.md) | [ByteArray](simd128-array-memory.md), [Addr#](simd128-address-memory.md) |
| Int32X4 | [Foundation](simd.md), [wrapping multiplication](int32x4-multiply.md) | [ByteArray](simd128-array-memory.md), [Addr#](simd128-address-memory.md) |
| Int16X8 | [Foundation](int16x8.md) | [ByteArray](simd128-array-memory.md), [Addr#](simd128-address-memory.md) |
| Int8X16 | [Foundation](int8x16.md) | [ByteArray](simd128-array-memory.md), [Addr#](simd128-address-memory.md) |
| Word8X16 | [Foundation](word8x16.md) | [ByteArray](simd128-array-memory.md), [Addr#](simd128-address-memory.md) |
| Word16X8 | [Foundation](word16x8.md) | [ByteArray](simd128-array-memory.md), [Addr#](simd128-address-memory.md) |
| Word32X4 | [Foundation](word32x4.md) | [ByteArray](simd128-array-memory.md), [Addr#](simd128-address-memory.md) |
| FloatX4 | [Foundation](floatx4.md) | [ByteArray](simd128-array-memory.md), [Addr#](simd128-address-memory.md) |
| DoubleX2 | [Foundation](doublex2.md) | [ByteArray](simd128-array-memory.md), [Addr#](simd128-address-memory.md) |

The [generated wide arithmetic families](simd-wide-arithmetic.md) share exact
lane contracts and finite scalar-entry Haskell oracles/compiled JVM drivers.
All fourteen admitted wide shapes also support [packed and scalar-offset
byte-array memory](simd-wide-array-memory.md), and
[integer vector division and shuffle](simd-quot-rem-shuffle.md).
The [floating vector min/max operations](floating-vector-minmax.md) use Java
NaN and signed-zero rules with separate native finite-input comparisons.
The [512-bit floating fused operations](wide-floating-fma.md) use genuine wide
Core and native scalar-lane expectations; native wide instruction parity remains
unproved.

## Performance and runtime design

These guides describe current mechanisms and their limits. A source-level
representation change is not evidence of a particular allocation or throughput
result; use matched measurements and actual compiler output for that question.

* [Entry contracts and type preservation](entry-contracts.md),
  [typed execution and tail cycles](typed-tail.md) and
  [residual call boundaries](call-boundaries.md).
* [Dense scalar handoff](handoff-slabs.md), [laziness and thunk updates](thunk-updates.md),
  [boxed-value controls](boxed-values.md) and [caller-demand controls](demand-probe.md).
* [Bytecode backend](bytecode.md), [source locations](debug-locations.md) and
  [graph inspection tooling and captures](graph-inspection.md).
* [Current architecture and planned work](architecture.md) and
  [development checks](contributing.md).

Runtime experiments are opt-in except compact headers and class-owned layouts.
`-Dthc.classOwnedLayouts=false` selects field-bearing layouts;
`-Pthc.compactObjectHeaders=false` disables compact headers for Gradle launches.
The controlled benchmark also accepts `-XX:-UseCompactObjectHeaders` for a
matched header-off run. Keep graph capture separate from timed measurements.

[Open design questions](../research/open-questions.md) identify concrete next
decisions about program closures, optimization, resumable effects and packaging.

## Project integration

[The Haskell runtime-service API](runtime-services.md) covers context permissions,
thread/affinity observations, JVM memory/GC statistics, structured tracing and
the explicitly `Unsafe` `THC.Internal.JIT` telemetry interface. Stable wrappers
are available to Safe Haskell callers; JVM-specific services have explicit
native-GHC unavailable results.

[The driver guide](driver.md) covers the current bounded Cabal build/run path,
including the optional complete-Core executable provider. Standalone `thc build`
and `thc repl` commands are not implemented. The separate
[managed export API](site/embedding.md) exposes declared scalar and IO actions
to polyglot callers.
