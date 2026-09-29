# Generated SIMD arithmetic and lane insertion

The family table adds 48 local operations across Int64X4/X8, Word64X4/X8,
Word32X16, FloatX16 and DoubleX8. Each shape has pack, unpack, broadcast, plus,
minus and times; signed and floating shapes also have negate, and floating
shapes have divide. The table also includes Int16X16 and Word16X16, with pack, unpack, broadcast, plus,
minus, times, insertion and signed negate. Signed min/max use the existing
Int8X16, Int16X8, Int32X4, Int32X8, Int64X2, Int64X4 and Int64X8 guest shapes;
unsigned min/max use Word8X16,
Word16X8, Word32X4, Word32X8 and Word64X2/X4/X8. Floating min/max cover
FloatX4/X8/X16 and DoubleX2/X4/X8 with a separate [Java-semantics contract](floating-vector-minmax.md).
The six wide byte/short shapes Int8X32/X64, Word8X32/X64, Int16X32 and
Word16X32 plus [integer quotient/remainder and shuffle](simd-quot-rem-shuffle.md)
bring the table to 315 operations, including 30 `insert` names.
These tested operations are implemented entries in the primop checklist.

The shared generator emits Java declarations for exact Core proofs, typed AST
families and the Bytecode DSL's nested operations. Each operation uses a raw
fixed-species JDK Vector API value;
Int16X16 and Word16X16 both use `ShortVector.SPECIES_256`, with signedness retained
in `VecRep` metadata. Signed unpack sign-extends; unsigned Word16 and Word32 unpack
zero-extends. Word64 preserves the full 64-bit pattern, and integer arithmetic
wraps at lane width.
Floating arithmetic follows Java semantics; NaN payload selection is not
claimed. Vector arguments/results, captures, heap fields and joins have a separate
[transport contract](simd.md); this operation corpus alone does not certify it.

Insertion takes the exact vector, an exact scalar lane, and an `Int#` index.
The generated Java operations use the raw vector's `withLane` operation and
preserve the other lanes, including floating-point bit patterns. Indices outside
the shape's lane range raise a runtime fault before any narrowing. Insertion covers
all currently represented vector shapes.
