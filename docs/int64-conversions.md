# Int64 scalar foundation

On THC's supported 64-bit targets, `intToInt64#` and `int64ToInt#` preserve
all bits in the existing primitive Long carrier. GHC's `Int64Rep` argument,
application and result proofs remain exact; neither conversion boxes a value.
Both AST and bytecode use their existing scalar identity operation.

The `int64` literal kind accepts canonical signed decimal values from
`-9223372036854775808` through `9223372036854775807`, including case alternatives.
Malformed and out-of-range literals are load errors even in diagnostic mode.

The [explicit64 scalar slice](explicit64-primops.md) extends this foundation with
Int64/Word64 arithmetic, ordering, shifts, bitwise operations, remaining scalar
conversions and Word64 literals. SIMD and 32-bit target semantics are separate.
Aggregate argument, PAP, capture and sum support is unchanged.
