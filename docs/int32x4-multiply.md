# Signed Int32X4 multiplication

Both backends implement `timesInt32X4# :: Int32X4# -> Int32X4# -> Int32X4#`
with `IntVector.mul`, retaining a raw `IntVector.SPECIES_128` result.
The exact vector proof is `VecRep 4 Int32ElemRep`; pack/unpack uses one
logical four-`Int32#` tuple with `Int32Rep` leaves, not `Word32Rep`.

Each product retains its low 32 bits. Explicit unpack sign-extends those bits
to the scalar carrier: MIN × −1 yields MIN, MAX × MAX yields 1, and MIN × MIN
yields 0. Scalar extraction belongs to unpack or durable heap storage, not
each arithmetic operation. Wrong signedness, width, arity or liftedness is
rejected at lowering.

The [SIMD transport contract](simd.md) covers guest arguments/results, tuple
leaves, joins, PAP prefixes and owned captures/heap fields. The
[Core host ABI](site/embedding.md#load-a-core-entry) also accepts and returns
exact raw JDK vectors.
