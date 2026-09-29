# Pinned memory and address lifetimes

The [aligned scalar extension](aligned-scalar-memory.md) also supports opaque
StablePtr array/address cells and four-byte WideChar array elements.

Both backends support `newPinnedByteArray#`, `newAlignedPinnedByteArray#`,
`byteArrayContents#`, `mutableByteArrayContents#`, `readWord8OffAddr#`, `writeWord8OffAddr#`,
`readAddrOffAddr#`, `writeAddrOffAddr#`, `keepAlive#` and `touch#`
Byte loads/stores retain GHC 9.14.1's exact `Word8Rep`, not
`WordRep`. Existing address arithmetic and character loads work on either
immutable literal bytes or mutable byte-array backing.

Addresses hold strong backing references and a checked Long offset. Aliases and
unsafe-frozen byte arrays observe the same storage. Immutable literals retain
their trailing NUL and cannot be written. Full-width bounds are checked before
narrowing or effects. One-past addresses are valid only for empty ranges.
Mutable contents are never compilation-final.

`mutableByteArrayContents#` returns a managed address directly from a mutable
array without copying or first freezing it. It requires an exact unlifted object
operand and an `AddrRep` result, with no State argument or tuple result. Both
contents operations preserve the same backing allocation and offset identity;
addresses keep that storage alive and retain pointer-cell protections. This is
stable address access. Explicitly pinned arrays have real native storage from
allocation; ordinary arrays follow the context's heap/native storage policy. Neither contents
operation copies, promotes, or temporarily pins an array.

Pinned `ByteArray#` values have one allocation owner shared by frozen values
and every address alias. An owner stores managed `Addr#` references in sparse
pointer cells; OffAddr pointer offsets count pointer-sized elements, while the
owner's lookup/write API uses byte offsets. A pointer cell's bytes are not a
fabricated host address: raw reads and partial overwrites reject before any
change. Complete byte copies between pinned owners preserve references, and
complete fills invalidate them. An unrestricted raw Sulong buffer view cannot expose managed pointer cells.
Typed package calls use a separate checked pointer-graph projection and
reconciliation protocol; see [C finalizers](c-finalizers.md).
Handing out a raw byte-array alias likewise prevents later pointer-cell writes.
Ordinary guest byte arrays also have allocation owners; pointer-cell maps are
created only when needed. Owner accesses synchronize to order pointer-cell
changes. Numeric accesses use the backing byte storage while respecting the
owner's logical bounds and cell protections.
Scalar byte-array reads and writes inspect only their touched range, so a
disjoint numeric field remains usable beside a pointer cell. Vector operations
also validate their touched range: disjoint numeric regions and complete-cell
overwrites are permitted, while pointer-cell byte exposure or partial overlap
is rejected.
An aligned full-width numeric overwrite releases the replaced pointer;
partial overlap fails before changing bytes or references.

`newPinnedByteArray#` and `newAlignedPinnedByteArray#` allocate a shared automatic
FFM arena once. Alignment is physical, including requested power-of-two
alignment. The native segment, its bounded buffer view, every `Addr#` alias,
and unsafe-frozen arrays share exactly the same bytes. This naturally backs
GHC's existing `mallocForeignPtrBytes` and aligned/plain allocation paths.
Repeated native address acquisition and foreign calls never copy or repin it.
The automatic arena is reclaimed only after all segment/buffer/address owners
become unreachable; `keepAlive#` and `touch#` retain their existing fences.
Numeric pointers alone do not keep the allocation alive. Resize down changes
the logical size in place without copying; growth allocates an ordinary
array under the context's storage policy and copies the prefix, as GHC's replacement-allocation contract requires.

Ordinary arrays use heap segments by default; the optional
[native storage policy](bytearrays.md) chooses stable native backing at allocation.
Heap segments retain moving-GC freedom. Managed Sulong accesses them by object plus offset, without
physical pinning; raw native projection rejects them even after unsafe freeze.
Buffer-only and native-pointer-capable views of pinned storage are both
available without copying. Native GMP and Windows MD5 may stage unpinned heap data; pinned paths borrow
the original segment. Native storage ownership does not imply that allocation
is as cheap as JVM heap allocation.
The separate [native address projection](native-addresses.md) and explicit
malloc/free ownership paths do not grant access to arbitrary process memory. See the
[primop behavior reference](primop-behavior.md#addresses-pinning-and-pointer-containing-storage)
for the current interoperability boundaries.

`readWord32OffAddr#`, `readWordOffAddr#`, `readInt32OffAddr#` and
`readIntOffAddr#` also read managed literal or byte-array storage. Their offsets
count four-byte or eight-byte elements on the pinned 64-bit target. Negative
offsets from a derived address are valid when the complete element remains
inside its allocation. Multiplication overflow and partial elements reject
before reading. Word32 and Int32 results retain raw `Int` bits; their declared
widenings respectively zero-extend and sign-extend into machine `Long`. Reads use native byte order and observe intervening
writes through aliases, including after unsafe freeze. The exact State/payload
tuple keeps Word32, Word, Int32 and Int representation proofs distinct.

`keepAlive#` preserves a lifted kept reference without forcing it, validates
State before the action, and invokes the continuation non-tail with exactly
one logical State argument. A Java reachability fence follows actual return or
throw. Scalar results follow the existing call-root WHNF convention; tuple
components retain their own evaluatedness. Its result follows the existing typed calling convention.

`touch#` preserves an exact lifted or unlifted reference until its State-thread
position, without entering a lifted thunk. It validates the State carrier before
issuing a Java reachability fence and returns bare State, not a singleton tuple.
Raw operand/result proofs and levity flags are checked before lowering; known
stored or intrinsic representations cannot be disguised by occurrence metadata.
For weak pointers and cleanup, see [weak finalization](weak-explicit.md).


See [foreign imports](interface-foreign.md#pass-buffers-and-pointers) for C
buffer transport and [unaligned memory](unaligned-scalar-memory.md) for byte-offset
scalar accesses.
