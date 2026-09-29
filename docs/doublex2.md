# DoubleX2 operations

`DoubleX2#` has exact GHC representation `VecRep 2 DoubleElemRep` and uses a raw
`DoubleVector.SPECIES_128` value. Pack takes one logical
`(# Double#, Double# #)` argument and unpack returns it. The vector, scalar
tuple, boxed pair and byte array are distinct representations; equal width does
not make `Int64X2#` or `FloatX4#` compatible.

Both backends use primitive Double lanes and exact `DoubleRep` proofs without
narrowing through Float. Vector arithmetic retains the raw Vector API result.
The [guest transport contract](simd-families.md) includes calls, PAPs, joins,
tuples and owned captures/heap fields. The [Core host ABI](site/embedding.md#load-a-core-entry)
also accepts and returns the exact raw JDK vector species.

## Operation scope

The foundation fixture focuses on broadcast, pack, unpack, add, subtract and
multiply. Current execution also has generated negate, divide, insertion,
min/max and shuffle operations, plus four fused multiply/add variants.
See [generated arithmetic](simd-wide-arithmetic.md),
[floating extrema](floating-vector-minmax.md),
[shuffle](simd-quot-rem-shuffle.md) and the [capability checklist](primops.md).
[DoubleX2 ByteArray operations](simd128-array-memory.md) distinguish packed-vector
indices from scalar-Double offsets. [Address operations](simd-address-families.md)
have their own memory rules; ordinary scalar Double arrays are independent.

Arithmetic NaNs have no specified payload or sign. Exact vector type acceptance
does not guarantee packed hardware instructions; inspect the generated code when
that property matters.
