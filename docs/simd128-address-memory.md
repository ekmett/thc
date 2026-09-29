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
