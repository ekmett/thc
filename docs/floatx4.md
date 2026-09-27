# FloatX4 operations and checks

`FloatX4#` has exact GHC representation `VecRep 4 FloatElemRep` and uses a raw
`FloatVector.SPECIES_128` value. Each scalar lane has a concrete Float carrier
and `FloatRep` proof. Pack takes one logical
`(# Float#, Float#, Float#, Float# #)` argument; unpack returns that scalar
tuple. A vector and an equal-width tuple are different representations.

Both backends preserve primitive Float lanes when packing/unpacking; there is
no Double or `Number` widening step. Arithmetic uses the raw Vector API result.
The [guest transport contract](simd-families.md) includes calls, PAPs, joins,
tuples and owned captures/heap fields. Public host vector arguments/results
remain unsupported.

## Operation scope

The foundation fixture focuses on broadcast, pack, unpack, add, subtract and
multiply. Current execution also has generated negate, divide, insertion,
min/max and shuffle operations, plus the four fused multiply/add variants.
See [generated arithmetic](simd-wide-arithmetic.md),
[floating extrema](floating-vector-minmax.md),
[shuffle](simd-quot-rem-shuffle.md) and the [capability checklist](primops.md).
[ByteArray](floatx4-bytearray.md) and [address](simd-address-families.md)
operations have separate memory contracts. Separate multiply/add retains two
roundings; fused operations have a single rounding after the declared operand
negations. Floating min/max follows Java's NaN and signed-zero rules.

## Original-Core and independent-model checks

```sh
python3 scripts/prepare-floatx4-audit.py
./gradlew testDefault --tests thc.runtime.SimdFloatVectorTest
```

The existing Python producer exports genuine pre/post-Tidy Core and compares
fresh native GHC rows with an independent binary32 model. All six foundation
primops must survive with exact logical pack/unpack signatures, and positive
entries require strict reachable audits. The genuine `vectorArgument` control
is rejected as a public host entry, not as a guest function formal: both backend
loaders accept well-proven guest vector formals and reject forged shapes.

Finite arithmetic entries expose lane-sensitive scalar checksums and rounding
boundaries. Exceptional entries distinguish NaNs, signed zeros, infinities,
subnormals and normal values without converting non-finite values to Int.
Arithmetic NaNs compare by class, not payload or sign. Movement controls and
ordinary arithmetic additionally check raw bits. A separate multiply/add
witness checks the unfused rounding contract.

`SimdFloatVectorTest` validates source/artifact hashes, every provenance stage
and both backends. Its compiled phase requires exactly one selected compiled
entry per row, retained last-tier validity and released argument/result storage;
helper roots remain interpreted with inlining disabled. Handoff modes are
separate JVM test runs; no post-compilation settling call substitutes for entry.

With `--export-only`, preparation retains real pre-Tidy Core and model-only
expectations, not a native oracle or post-Tidy export. Full preparation needs a
working native GHC SIMD configuration. Neither mode by itself establishes packed
machine instructions, eliminated allocations or throughput.
