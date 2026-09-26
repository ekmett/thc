# Aggregate fields in boxed constructors

Both backends construct and match boxed values with exact unboxed tuple or binary
sum fields. This includes GHC 9.14.1's real `GHC.Core.TyCon.BoxedRep`: its source
field `{-# UNPACK #-} !(Maybe Levity)` becomes one logical worker field
`(# (# #) | Levity #)`, stored as a tag and one lifted reference.

Constructor metadata retains logical field order and shape. Lowering maps each
logical field to a contiguous range of existing final typed heap properties;
tuple components flatten recursively and sum payloads retain GHC's existing
slot projections. Empty tuples and scalar void components occupy no payload
property, but their expressions still execute. Scalar fields after a zero-width
or multi-register field retain their own positions. No boxed tuple/sum payload
array or additional guest carrier is introduced.

Lifted payloads remain lazy. Demanding an unboxed field does not demand its lifted
children. Inactive sum reference slots are cleared null padding, not guest roots:
closure inspection omits them from its pointer list, compact copying ignores
them, and compact images preserve their inactive state. Active references retain
ordinary reachability, sharing and existing compact-region evaluation semantics.

The supported tuple leaves and binary-sum payloads are the existing
[tuple](tuple-results.md) and [sum](sum-results.md) layouts. Logical shape, arity,
sum tags/projections, scalar carriers, vector species and heap ownership remain
checked. This does not add nested/nonbinary sums, ordinary sum function inputs,
aggregate closure captures or ordinary aggregate lets. [Tuple join inputs](tuple-joins.md)
are supported separately. Constructors with aggregate fields currently require direct saturated
applications; their unsaturated/PAP workers remain an explicit boundary.

## Reproduce

With the pinned GHC 9.14.1, Graal/JDK 25 toolchain and ordinary repository build
prerequisites on `PATH`:

```sh
cabal run exe:thc-fixtures -- aggregate-heap
./gradlew aggregateHeapFullCoreTest aggregateHeapFullCoreDenseTest
./gradlew test --tests thc.runtime.AggregateHeapStorageTest \
  --tests thc.runtime.ManagedCompactsTest --tests thc.runtime.CompactImagesTest \
  --tests thc.runtime.VectorHeapStorageTest
python3 scripts/test-audit-core.py
```

The Haskell producer compiles original sources natively with Core/STG lint,
retains genuine pre/post-Tidy Core and seven constructor layouts, and strictly
audits all nine entry roots. Its 117 oracle rows cover the compiler's original
`BoxedRep`, both sum tags, nested and empty tuples, integer boundaries, IEEE
values, lazy bottoms and shared payloads. Both backends match all rows before
and on first compiled entry in both handoff modes: 1,872 comparisons. Additional
tests cover physical/logical offsets, field-based and array-based storage,
inactive references, compact copying/image roundtrips and malformed input.

`--export-only` is available for investigating a new runtime frontier, but writes
only `export-manifest.json`, never a successful strict execution receipt. No
full compiler acquisition or compiler rebuild is needed for this fixture.
