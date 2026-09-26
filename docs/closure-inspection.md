# Closure inspection on the JVM target

`unpackClosure#`, `closureSize#`, `getApStackVal#`, `getCCSOf#`,
`clearCCS#`, and `whereFrom#` have target-relative implementations on both
backends. Inspection does not enter its operand or any pointer payload.

THC is not a GHC heap emulator. `unpackClosure#` returns a detached managed
word image and a fresh array of the actual, unforced pointer fields. The image
starts with a kind word, followed by constructor fields, closure captures and
partial-application payloads in storage order. Primitive fields retain their
native-endian bits; a Float occupies one word, and a vector occupies its species
width. Pointer words are zero, with their actual references in the separate
pointer array. The returned info address identifies immutable context-owned
description storage, not a native GHC info table. Foreign objects without a
THC closure layout are opaque one-word images.

`closureSize#` measures that same image in 64-bit words, not JVM object overhead
or GHC's native allocation size. An unevaluated, evaluating or failed thunk is
observed without waiting, forcing or rethrowing; a completed thunk's image has
its indirection pointer. Snapshot arrays do not alias the original fields.
Code which parses GHC-specific heap headers must use a target-specific decoder.

There is no exposed GHC `AP_STACK` heap carrier. `getApStackVal#` consequently
returns the native non-`AP_STACK` result `(0, original argument)`, including for
THC's private continuation machinery. No arbitrary stack offset is dereferenced.

This is a non-profiling target: `getCCSOf#` returns the null address, and
`clearCCS#` invokes the supplied State action without forcing its result. No
closure IPE table is registered, so `whereFrom#` returns zero and leaves the
destination untouched, exactly as the native absent-IPE path. The separate
managed stack-source compatibility service is not a closure heap IPE table.

The Haskell fixture producer compares seven original-Core examples with native
GHC, including a raising pointer payload, absent provenance with a sentinel
buffer, non-`AP_STACK` input, and non-profiling cost-centre behavior. Kotlin
also checks detached images, raw primitive bits, pointer identities, independent
storage and thunk states. Native heap headers and JVM headers are deliberately
not compared byte-for-byte.

`annotateStack#` remains a separate follow-up: its lazy annotation lifetime must
survive continuation capture and restoration, rather than become a no-op or a
synchronous-only admission restriction.
