# Narrow integer carriers

`Int8Rep`, `Word8Rep`, `Int16Rep`, `Word16Rep`, `Int32Rep` and `Word32Rep`
compute in JVM `int` values on both backends. `IntRep`, `WordRep`, `Int64Rep`
and `Word64Rep` remain JVM `long`. The exact lowered PrimRep selects the carrier;
the exporter's broad `kind: long` spelling is not a JVM storage instruction.

AST expressions expose `executeInt`, frames use Int slots, and bytecode
operations retain primitive int operands/results. Captures, constructors, PAP
prefixes and handoff packets store 8/16/32-bit fields as byte/short/int rather
than widening every stored value to its computation width. Vector lanes follow
the same scalar rule while vector values keep their existing raw species.

`Word32` is an unsigned value represented by raw Int bits. Comparisons, division
and remainder use unsigned operations. Only explicit GHC conversions, public
numeric boundaries, native ABI adapters and canonical sum projections widen it
with zero extension. Signed widening preserves the sign. Public narrow inputs
are range checked. There is no global implicit Int-to-Long conversion.

`intToInt32#` changes the carrier; `int32ToInt#` explicitly widens it.
`narrow32Int#` still consumes and returns machine `Int#` in a Long carrier.
Comparison results and shift counts remain machine Int#. The analogous Word
operations preserve this distinction. GHC's original sum slot proofs and
projections remain unchanged; see [sum results](sum-results.md).
