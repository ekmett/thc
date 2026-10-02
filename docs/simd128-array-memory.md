# 128-bit vector byte-array memory

Both backends implement packed-vector and scalar-offset byte-array operations
for these exact GHC vector shapes. Every carrier uses `SPECIES_128`.

| GHC shapes | JDK carrier | Scalar-offset stride |
| --- | --- | ---: |
| `Int8X16#`, `Word8X16#` | `ByteVector` | 1 byte |
| `Int16X8#`, `Word16X8#` | `ShortVector` | 2 bytes |
| `Int32X4#`, `Word32X4#` | `IntVector` | 4 bytes |
| `Int64X2#`, `Word64X2#` | `LongVector` | 8 bytes |
| `FloatX4#` | `FloatVector` | 4 bytes |
| `DoubleX2#` | `DoubleVector` | 8 bytes |

For each shape, `index<shape>Array#`, `read<shape>Array#` and
`write<shape>Array#` count **16-byte vectors**. Their
`<scalar>ArrayAs<shape>#` forms count **scalar elements** using the table's
stride. Every access covers a full sixteen bytes; incomplete tails are not
masked, and scalar offsets need not be vector-aligned.

Exact `VecRep` metadata keeps lane count and element representation, including
signedness. Signed and unsigned families can share a physical carrier without
sharing type proofs. Explicit unpack preserves each lane's signed/unsigned
scalar interpretation; memory transfers preserve its bits.

## Memory and lowering contract

Index accepts an immutable `ByteArray#` and `Int#`, returning the vector.
Read accepts `MutableByteArray# s`, `Int#` and `State# s`, returning
`(# State# s, vector #)`: two logical fields, not State plus scalar lanes.
Write accepts the mutable array, index, exact vector and State, returning State.
Both backends check State before access or result publication.

Transfers use typed Vector API `fromMemorySegment`/`intoMemorySegment`
operations in native byte order. Access is non-atomic. Owned heap and pinned
allocations retain owner locking, lifetime, mutability and logical-size checks,
including after shrink. Reads cannot overlap a managed pointer cell; stores
invalidate completely overwritten cells and reject partial overlaps.
Host byte arrays use array-backed segments with physical bounds checks.
Negative indices, scaling overflow and incomplete ranges fail before access;
invalid-range stores cannot partially change bytes. Native GHC invalid-offset
behavior is not an oracle for managed bounds failures.

Mutable vector reads require an immediate, exact registered tuple case with
ordered State/vector pattern binders and an unused whole-tuple binder.
The binders must be unlifted, non-coercion values; lane counts, constructor
arity, lexical identities and producer/whole-binder annotations are checked.
Returning the primitive's whole read tuple directly remains unsupported.

This intrinsic restriction is distinct from [guest vector transport](simd.md),
which supports arguments/results, tuple leaves, joins, PAP prefixes and owned
captures/heap fields. The [Core host ABI](site/embedding.md#load-a-core-entry)
transports exact vector and unboxed-tuple arguments/results; it does not relax
the mutable-read intrinsic's immediate-case requirement.
[Address operations](simd-address-families.md) and
[wider byte-array vectors](simd-wide-array-memory.md) have separate contracts.
