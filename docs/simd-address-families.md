# SIMD address memory

The local address-memory contract covers packed and scalar-offset index, read
and write for these 24 vector representations:

- Int8X32, Word8X32, Int8X64, Word8X64, Int16X32, Word16X32.
- Int32X4, Word32X4, FloatX4, DoubleX2.
- Int16X16, Word16X16, Int32X8, Word32X8, Int32X16, Word32X16.
- Int64X4, Word64X4, Int64X8, Word64X8.
- FloatX8, FloatX16, DoubleX4, DoubleX8.

For example, `readInt32X8OffAddr# address i state` starts at byte offset
`32*i`; `readInt32OffAddrAsInt32X8# address i state` starts at `4*i`.
Both read the full 32-byte vector. Negative offsets are valid only when the
resulting whole region remains within the same live allocation.

These operations use the existing checked address storage, including managed
byte-array aliases and owned native allocations. The owner monitor or native
borrow spans validation and transfer. Bounds use the complete vector width;
overflow, read-only writes, released native storage and partially overwritten
managed pointer cells are rejected. Disjoint managed pointer cells retain their
actual carriers. Floating lanes are transferred as raw bits without arithmetic.
No vector access is promised atomic relative to competing scalar accesses.

The six other 128-bit shapes are documented in
[SIMD128 address memory](simd128-address-memory.md).
