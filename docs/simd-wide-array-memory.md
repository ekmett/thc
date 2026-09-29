# Wide vector byte-array memory

ByteArray memory supports packed-vector and scalar-element-offset index, read,
and write operations for Int8X32/Word8X32, Int8X64/Word8X64,
Int16X16/Word16X16, Int16X32/Word16X32, Int32X8/Word32X8,
Int32X16/Word32X16, Int64X4/Word64X4, Int64X8/Word64X8,
FloatX8/FloatX16, and DoubleX4/DoubleX8: 120 operations.

Packed offsets count 32- or 64-byte vectors. ArrayAs offsets count scalar
elements. Each operation accesses one complete vector, in native byte order;
there are no masked partial-tail accesses. Both backends preserve the selected
VecRep and exact public Vector API species. Owned storage retains its monitor,
logical shrink bounds and pointer-cell protections for the full vector width.
Raw byte arrays retain their physical bounds. Floating transfers preserve bits,
including signed zero and NaN payloads; no floating arithmetic is performed.
