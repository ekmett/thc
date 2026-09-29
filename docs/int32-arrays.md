# Native-endian Int32 and Word32 arrays

Both backends support `readInt32Array#`, `writeInt32Array#`,
`indexInt32Array#`, and their three `Word32Array#` counterparts. Typed array
indices select four-byte elements; `Word8Array#` indices select individual bytes.
The exact payloads are `Int32Rep` and `Word32Rep`, not machine `IntRep`/`WordRep`
or same-width floating representations. Reads return a genuine unboxed
`(# State#, Int32# #)` or `(# State#, Word32# #)` tuple, with no physical slot for
the state token. Writes return the state token; indexing returns the narrow
scalar. Reads retain raw `Int` bits; Int32 widening sign-extends and Word32
widening zero-extends to machine `Long`.

The runtime uses a plain native-order `int` VarHandle view of the existing JVM
`byte[]`. It creates no separate `int[]`, boxed-element array or storage wrapper.
Both write operations keep the low 32 bits; only read/index widening differs.
Full-width Long indices must be within `[0, byteCount / 4)` before narrowing and
scaling, so incomplete final elements and overflowed indices cannot be accessed.
State is evaluated and validated before memory access, and failed reads do not
publish their primitive Int result. Accesses are not atomic/concurrent APIs.

Canonical `int32` literals accept `[-2147483648,2147483647]`; `word32` accepts
`[0,4294967295]`. The literal kind determines range and signedness even when
optional metadata is absent. Contradictory carriers and malformed records fail.
See [Unboxed32Arrays.hs](../src/examples/THC/Unboxed32Arrays.hs) for public
array examples.
