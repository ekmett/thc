# Floating vector minimum and maximum

`min` and `max` cover FloatX4/X8/X16 and DoubleX2/X4/X8: twelve primops,
each taking two exact vector values and returning the same shape. Both AST
and bytecode use the existing generated typed operations. Fixed-width Java
Vector API operations return raw `FloatVector` or `DoubleVector` values, with
no THC wrapper or boxed lane fallback. Exact shape remains in `VecRep` metadata;
see the [SIMD representation contract](simd.md). All twelve count as implemented;
their numerical portability caveat is recorded in the
[primop behavior reference](primop-behavior.md#floating-simd-portability).

The runtime follows Java floating semantics. If either operand is NaN, the
result is NaN; payload and sign selection are unspecified. Negative zero orders
below positive zero, so `min(-0,+0)` is `-0` and `max(-0,+0)` is `+0`, in either
operand order. Equal negative zeros remain negative. Infinities use numeric
ordering. This is the documented [Java Vector API min/max contract](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.incubator.vector/jdk/incubator/vector/FloatVector.html#min(jdk.incubator.vector.Vector)).

These Java NaN and zero-tie rules may differ from native GHC vector lowering.
Hardware SIMD instruction selection depends on the host and compiler.
