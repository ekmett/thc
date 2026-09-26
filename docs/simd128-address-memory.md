# Integer 128-bit vector address memory

Addr# memory supports Int8X16, Word8X16, Int16X8, Word16X8, Int64X2 and
Word64X2. Each shape has packed and scalar-offset index/read/write operations:
`index<shape>OffAddr#`, `read<shape>OffAddr#`, `write<shape>OffAddr#`, and their
`<scalar>OffAddrAs<shape>#` forms. Packed offsets count 16-byte vectors;
scalar offsets count 1, 2 or 8-byte elements. Every access covers 16 bytes.

Both backends use exact ByteVector, ShortVector or LongVector carriers and
native byte order. Reads use the existing immediate State/vector case lowering;
the State field remains erased. The runtime trusts integral Long-carrier aliases
but still checks address carriers, vector species and aggregate shape.

The existing address abstraction supplies storage and lifetime semantics.
Interior pointers may use negative offsets when the entire access stays within
the allocation. Offset multiplication cannot wrap. Managed allocations keep the
complete operation under their monitor, including logical shrink bounds and
pointer-cell overlap validation. Stores reject immutable storage, reject partial
pointer-cell overlaps, and invalidate fully overwritten pointer cells. Native
allocations retain a context-owned borrow throughout each checked access;
foreign-context and freed owners reject. Null, opaque and unowned numeric
addresses are not byte-addressable. These are ordinary vector memory operations,
not an atomic-memory API or a claim of hardware SIMD acceleration.

`Simd128AddressAudit.hs` uses all 36 operations over initialized pinned storage,
kept alive with `keepAlive#`. Its interior pointer deliberately exercises valid
negative offsets. The Haskell fixture command retains native LLVM output,
original pre/post-Tidy Core, strict entry audits and source/artifact hashes.
Kotlin checks the 2,304 native rows with an independent scalar-byte model and
tests both lowerings, installed-code execution and memory boundaries.

Run `cabal run exe:thc-fixtures --offline -- simd128-addresses`, then
`./gradlew test --tests 'thc.runtime.Simd128Address*'`; repeat the test task with
`JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true`. Fixture selection uses the same
producer. As with the sibling fixture, native/post-Tidy evidence is retained on
little-endian x86-64; ARM64 preparation currently emits pre-Tidy Core and records
no native rows. Native allocation tests retain that subsystem's Linux/LP64 host
gate. None of these host-test limits implies a different vector representation.
