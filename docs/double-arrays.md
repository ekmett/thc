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

The loaders and auditor require exact `DoubleRep` payloads, `IntRep` indices,
unlifted array references, and zero-width State arguments. Neither `FloatRep`
nor an equal-width integer payload is interchangeable with Double. A mutable
read returns exactly `(# State# s, Double# #)`: two logical components and one
primitive Double destination. State expressions run before memory access; only
a successful read publishes its result. AST and bytecode use typed Double
frame/local accesses and the existing [floating tuple result](tuple-results.md)
protocol. The optional scalar handoff ABI remains Long/reference-only; residual
scalar floating calls can still box in their existing Object call packets.

[Public examples](../examples/THC/UnboxedDoubleArrays.hs) use ordinary checked
`accumArray` and `runSTUArray` APIs. Bounds and indices are fixed, while values
depend on the input. GHC discharges the cold bounds checks itself; no exported
Core is rewritten or cold branch discarded. Arithmetic uses bounded dyadic
values, so the final floating-to-Int conversion is defined and the independent
model can use integer fixed-point arithmetic.

[Primitive movement fixtures](../compiler/test-fixtures/DoubleArrayAudit.hs)
seed Int bits, move them through actual Double reads/writes, then inspect Int
bits. One retains an opaque State/Double tuple-returning read helper; the other
retains a scalar Double index helper. Both use separate allocations and obey
unsafe-freeze alias rules. Preparation checks the actual helper bodies, exact
primitive use counts, and straight-line call structure at both Core stages.

The 506-input corpus covers signed zeros, infinities, subnormals, finite
endpoints, asymmetric bit patterns and multiple quiet-NaN payloads of both signs.
All 2,024 native rows must match independent models. Movement performs no
floating arithmetic: it does not promise arithmetic NaN payload preservation.
Signaling NaNs are excluded from the exact bit-identity evidence domain because
the pinned JDK permits copies and returns to quiet them. The tests do not
silently canonicalize quiet-NaN payloads or zero signs to pass.

```sh
cabal run exe:thc-fixtures --offline -- double-arrays
./gradlew test --tests thc.runtime.DoubleArrayNativeTest
python3 scripts/test-core-bytearrays.py
./gradlew test --tests 'thc.runtime.DoubleArray*'
```

The manifest under `build/double-arrays` records source/artifact hashes, native
byte order and exact input domain. JVM
tests compare every row on both backends with guest inlining enabled and
disabled. Every post-compilation call must make the exact expected compiled
entry increment (two for public roots, three with a retained helper). Each entry
also calls the immediate local State lambda left by `runRW#` lowering. Tests
discover the active call tree after interpreter coverage, explicitly compile
every guest target, require unchanged active target identities and valid code,
and release result/argument slabs. CI repeats with dense handoff
enabled. Bounds and malformed proof controls never compare invalid native reads.

General dynamic-index error closures, copying freeze/thaw and array updates
using FFI `memcpy`, pinned/raw addresses, concurrent access, unsafe alias misuse,
uninitialized reads and cross-endian exports remain outside this slice. It does
not expand aggregate arguments or the public integer-only embedding interface,
and makes no throughput or allocation-elimination claim.
