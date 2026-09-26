# Owned unboxed tuple captures

Ordinary closures and thunks on both backends can retain exact unboxed tuples.
The logical tuple remains an alias for flattened capture fields; it is not boxed
into a guest constructor, payload array or saved invocation frame. Nested tuple
boundaries remain part of the layout even though their physical fields flatten.

Long, Float and Double leaves use existing primitive capture properties. Known
references remain references and lifted leaves remain lazy. Exact evaluated
AddrRep leaves retain their checked managed address. Vector leaves use existing
fixed-species owned vector properties, independently of surrounding scalar
fields. State#/Proxy# and empty tuple components occupy no payload fields. A
closure capturing only an empty tuple needs no environment allocation.

Each closure or thunk owns its captures after the creator returns. Captures are
independent of reusable argument/result loans and durable PAP prefix storage.
Restoring a capture rebinds the original logical tuple to callee-local fields.
Closure inspection sees primitive/vector bytes and the actual lazy references,
not an additional tuple pointer. Existing exact shape, scalar carrier, vector
species and ownership checks still apply.

Ordinary aggregate let/global storage, tuples containing sums, unresolved
layouts and public host aggregate parameters/results remain unsupported. Sum
captures are described [separately](sum-inputs.md); local tuple joins keep using
their [same-frame capture path](tuple-joins.md).

## Reproduce

With the pinned GHC 9.14.1 and Graal/JDK 25 toolchain:

```sh
cabal run exe:thc-fixtures --offline -fdevelopment -- tuple-capture
./gradlew tupleCaptureFullCoreTest tupleCaptureFullCoreDenseTest
./gradlew testDefault --tests '*BytecodeVectorTransportTest.tupleCapturesKeep*' \
  testDense --tests '*BytecodeVectorTransportTest.tupleCapturesKeep*'
```

The unchanged Haskell fixture produces 296 native observations across eight
roots: escaped closures and thunks, independent captures, PAP reuse, nested
tuples, empty components, State# and lazy bottom fields. Pre/post-Tidy exports
are strictly audited and hash-checked. Both backends compare the native values
before and immediately after compilation, with inlining enabled/disabled and
both handoff modes, retaining exact compiled target validity and no input/result
loan references.

Original-Core storage checks also retain a closure after another creator call,
inspect its typed Float/Double/address/lazy-reference properties without forcing
them, and force a captured thunk exactly once. A tuple with no physical fields
has no capture environment.

Separate backend controls exercise captured tuple vector leaves for all 30
exact species, reuse an escaped capture after another creator call, inspect its
owned representation, and verify four representative species from the first
compiled call with exact three-entry counts. These vector controls are synthetic
transport tests, not an additional native SIMD oracle.
