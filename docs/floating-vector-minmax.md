# Floating vector minimum and maximum

`min` and `max` now cover FloatX4/X8/X16 and DoubleX2/X4/X8: twelve primops,
each taking two exact vector carriers and returning the same shape. Both AST
and bytecode use the existing generated typed operations. Fixed-width Java
Vector API operations preserve the existing carrier layouts without boxed lane
fallbacks. These primops remain partial in the coverage checklist.

The runtime follows Java floating semantics. If either operand is NaN, the
result is NaN; payload and sign selection are unspecified. Negative zero orders
below positive zero, so `min(-0,+0)` is `-0` and `max(-0,+0)` is `+0`, in either
operand order. Equal negative zeros remain negative. Infinities use numeric
ordering. This is the documented [Java Vector API min/max contract](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.incubator.vector/jdk/incubator/vector/FloatVector.html#min(jdk.incubator.vector.Vector)).

The existing generated Haskell smoke exports genuine GHC 9.14.1 Core for all
180 generated operations in fifteen bounded composite entries. Its separate
native scalar oracle now produces 5,430 observations, including 276 floating
min/max rows. Those comparisons cover finite inputs in both orders, equal
values, signs and subnormals. Native floating extrema comparisons exclude NaNs,
infinities and mixed-zero ties. The optional broad native vector corpus uses
the same restriction for these twelve new operations.

The Kotlin consumer adds 1,848 Java-model edge requests to those same genuine
Core entries. They observe every one of the 42 floating lanes, both operations
and both operand orders, including NaNs, infinities and signed-zero ties.
The fixture canonicalizes NaNs for observation; all other results use raw bits.
The existing test checks interpreted execution and every first installed
compiled call on both backends, retained targets and released handoff state.

Local validation includes pinned GHC signature checks, strict Core audits and
the native scalar observations. JVM execution is pending the Linux gate:

```sh
python3 scripts/prepare-simd-capability-smoke.py
./gradlew --no-daemon test --tests thc.runtime.SimdCapabilitySmokeTest --tests thc.runtime.SimdFamiliesTest --rerun
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew --no-daemon test --tests thc.runtime.SimdCapabilitySmokeTest --tests thc.runtime.SimdFamiliesTest --rerun
```

This gate needs no AVX512 instructions. Native vector parity for these primops
has not been measured, and Java NaN or zero-tie rules may differ from native
GHC vector lowering. There is no hardware SIMD or performance claim.
