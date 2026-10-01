# Int64 scalar foundation

On THC's supported 64-bit targets, `intToInt64#` and `int64ToInt#` preserve
all bits in the existing primitive Long carrier. GHC's `Int64Rep` argument,
application and result proofs remain exact; neither conversion boxes a value.
Both AST and bytecode use their existing scalar identity operation.

The `int64` literal kind accepts canonical signed decimal values from
`-9223372036854775808` through `9223372036854775807`, including case alternatives.
Malformed and out-of-range literals are load errors even in diagnostic mode.

`THC.Int64Conversions` is part of the normal native-GHC coverage corpus:
`scripts/prepare-tests.sh` regenerates its Core and oracle. The corpus checks
cold boundary inputs and compiled entries on both backends. `Int64ConversionTest`
also checks exact exported argument/result representations, direct compiled
conversion entries, literal validation and malformed primitive arities.

This foundation does not implement the remaining explicit Int64 arithmetic,
Word64 conversions/literals, SIMD values or 32-bit target semantics. Aggregate
argument, PAP, capture and sum support is unchanged.
