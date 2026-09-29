# FloatX4 operations

`FloatX4#` has exact GHC representation `VecRep 4 FloatElemRep` and uses a raw
`FloatVector.SPECIES_128` value. Each scalar lane has a concrete Float carrier
and `FloatRep` proof. Pack takes one logical
`(# Float#, Float#, Float#, Float# #)` argument; unpack returns that scalar
tuple. A vector and an equal-width tuple are different representations.

Both backends preserve primitive Float lanes when packing/unpacking; there is
no Double or `Number` widening step. Arithmetic uses the raw Vector API result.
The [guest transport contract](simd-families.md) includes calls, PAPs, joins,
tuples and owned captures/heap fields. The [Core host ABI](site/embedding.md#load-a-core-entry)
also accepts and returns the exact raw JDK vector species.

## Operation scope

The foundation fixture focuses on broadcast, pack, unpack, add, subtract and
multiply. Current execution also has generated negate, divide, insertion,
min/max and shuffle operations, plus the four fused multiply/add variants.
See [generated arithmetic](simd-wide-arithmetic.md),
[floating extrema](floating-vector-minmax.md),
[shuffle](simd-quot-rem-shuffle.md) and the [capability checklist](primops.md).
[ByteArray](simd128-array-memory.md) and [address](simd-address-families.md)
operations have separate memory contracts. Separate multiply/add retains two
roundings; fused operations have a single rounding after the declared operand
negations. Floating min/max follows Java's NaN and signed-zero rules.
