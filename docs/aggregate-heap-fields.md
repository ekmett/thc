# Aggregate fields in boxed constructors

Both backends construct and match boxed values with exact unboxed tuple or
sum fields. This includes GHC 9.14.1's real `GHC.Core.TyCon.BoxedRep`: its source
field `{-# UNPACK #-} !(Maybe Levity)` becomes one logical worker field
`(# (# #) | Levity #)`, stored as a tag and one lifted reference.

Constructor metadata retains logical field order and shape. Lowering maps each
logical field to a contiguous range of existing final typed heap properties;
tuple components flatten recursively and sum payloads retain GHC's existing
slot proofs, with the [managed storage adaptation](sum-results.md) applied separately.
Empty tuples and scalar void components occupy no payload
property, but their expressions still execute. Scalar fields after a zero-width
or multi-register field retain their own positions. No boxed tuple/sum payload
array or additional guest carrier is introduced.

Lifted payloads remain lazy. A `BoxedRep Nothing` field uses a traced Object
property without claiming liftedness or evaluatedness. An explicit strict-field
annotation still demands the field. Demanding an unboxed field does not demand its
lifted children. Inactive sum reference slots are cleared null padding, not guest roots:
closure inspection omits them from its pointer list, compact copying ignores
them, and compact images preserve their inactive state. Active references retain
ordinary reachability, sharing and existing compact-region evaluation semantics.

The supported tuple leaves and sum payloads are the existing
[tuple](tuple-results.md) and [sum](sum-results.md) layouts. Logical shape, arity,
sum tags/projections, scalar carriers, vector species and heap ownership remain
checked. Families with two or more alternatives and sums inside recursive tuples
are supported, including sums nested inside sum payloads, managed addresses and
supported exact vector species. Nonrecursive unlifted tuple/sum lets use typed frame locals.
[Tuple captures](tuple-captures.md), [sum inputs and captures](sum-inputs.md) and [tuple join inputs](tuple-joins.md)
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
python3 bin/test-audit-core.py
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

## Four-way and nested aggregate fixture

The original `GHC.CmmToAsm.Format.VirtualRegWithFormat` contains an unpacked
`GHC.Platform.Reg.VirtualReg`: its I/Hi/D/V128 alternatives each contain an
evaluated Word64 Unique. The worker has logical arity 2 and physical fields
`[WordRep, Word64Rep, BoxedRep (Just Lifted)]`; Format stays at offset 2, after
the tag and shared payload. The fixture imports those original GHC definitions.

```sh
cabal run exe:thc-fixtures --offline -fdevelopment -- fourway-aggregate
./gradlew fourwayAggregateFullCoreTest fourwayAggregateFullCoreDenseTest
```

This producer requires full installed GHC 9.14.1 Core. OPAQUE makers/consumers
retain the original worker and producer/consumer partial applications. The
1,536 native rows cover all tags, repeated alternatives, unsigned high-bit values,
retention and DEFAULT, plus nested tuples with scalar sentinels, erased State#,
empty tuples and lazy lifted neighbours. A mixed four-way sum adds reference,
Word64, empty and Int alternatives. Both genuine export stages must strict-audit
successfully before the manifest permits interpreted/first-compiled comparisons.

Storage controls exercise field-/array-based layouts, PAP prefixes, inactive
references, compact copies and image roundtrips. Negative controls retain exact
arity, tags, projections, carrier, levity and logical-shape boundaries. Source,
toolchain and artifact hashes preserve the origin of the evidence; positive Core
proofs are never rewritten to make them acceptable.
