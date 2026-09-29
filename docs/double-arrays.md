# Managed Double arrays

`readDoubleArray#`, `writeDoubleArray#`, and `indexDoubleArray#` use the same
primitive `byte[]` storage as the [Int-array operations](int-arrays.md). A plain,
native-endian double-view VarHandle accesses eight-byte elements with primitive
Java `double` signatures. There is no separate `Double[]`, `double[]`, buffer
wrapper, copy, or boxed element representation in the array.

Indices count **eight-byte Double elements**, not bytes. The full-width index
must lie in `[0, byteCount / 8)` before narrowing or multiplying by eight.
Incomplete trailing elements are inaccessible, and invalid writes leave storage
unchanged. This is not an atomic or concurrent-access API.

GHC and the strict exporter audit require `DoubleRep` payloads and `IntRep`
indices. Runtime lowering accepts integral metadata aliases for Long indices,
while retaining Double carriers, unlifted array references and zero-width State
arguments. Neither `FloatRep`
nor an equal-width integer payload is interchangeable with Double. A mutable
read returns exactly `(# State# s, Double# #)`: two logical components and one
primitive Double destination. State expressions run before memory access; only
a successful read publishes its result. AST and bytecode use typed Double
frame/local accesses and the existing [floating tuple result](tuple-results.md)
protocol. The optional scalar handoff ABI remains Long/reference-only; residual
scalar floating calls can still box in their existing Object call packets.

[Public examples](../src/examples/THC/UnboxedDoubleArrays.hs) use ordinary checked
`accumArray` and `runSTUArray` APIs. Bounds and indices are fixed, while values
depend on the input. GHC discharges the cold bounds checks itself; no exported
Core is rewritten or cold branch discarded. Arithmetic uses bounded dyadic
values, so the final floating-to-Int conversion is defined and the independent
model can use integer fixed-point arithmetic.

Float/Double memory movement preserves raw quiet-NaN bits; arithmetic need not
preserve NaN payloads. Signaling-NaN bit identity is not guaranteed by the JVM.
See [floating-point semantics](floating-primitives.md) and
[the host ABI](site/embedding.md#load-a-core-entry).
