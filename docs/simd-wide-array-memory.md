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

The ordinary Haskell fixture exports original vector Core for all 120 operations.
An independent native GHC fixture implements the same transfers with scalar
array primops, so verification does not require executable AVX-512 code on the
host. Its 2,880 rows are also compared with a Kotlin scalar-byte model. The
manifest explicitly records scalar-lane native evidence, not native wide-vector
execution. Tests check full buffers, aliases, tails, invalid ranges, metadata,
first-installed calls, and handoff cleanup on both backends/storage modes.

Run `cabal run exe:thc-fixtures --offline -- simd-wide-arrays`, then
`./gradlew test --tests 'thc.runtime.SimdWideArray*'`. Repeat with
`JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true` for dense handoffs.

This array corpus makes no hardware-width or performance claim.
Address operations have a separate corpus. The native corpus uses the pinned
64-bit little-endian target.
