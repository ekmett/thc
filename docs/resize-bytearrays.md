# Mutable byte-array resize and shrink

Both backends implement `resizeMutableByteArray#` and
`shrinkMutableByteArray#` on THC-owned mutable allocations. Guest allocations
have an owner with a logical byte length and heap or pinned backing storage;
the logical length need not equal retained capacity. Host-supplied `byte[]`
values retain a separate compatibility path.

## Resize

`resizeMutableByteArray#` takes an unlifted mutable reference, an `Int#`
length and scalar `State#`. It returns `(# State#, MutableByteArray# #)`
through one typed reference destination; State has no tuple storage.
All operands, including State, are evaluated before allocation or publication.
Both loaders require saturation, unlifted operands and the logical pair.
Integral annotations use the shared `Long` carrier after lowering; strict
exporter audits separately check the original source-level representations.

For an owned allocation, equal size returns the same owner. A smaller size
shrinks that owner in place. Growth creates a replacement under the context's storage policy, copies the
live prefix and preserves complete managed pointer cells. The raw `byte[]`
path returns the original only at equal size and otherwise copies the prefix
into a replacement array. GHC's contract requires subsequent accesses to use
the returned reference; an observed in-place result does not authorize further
use of a retired resize alias.

Full-width checks reject negative sizes or requests above `Int.MAX_VALUE`
before narrowing. Allocation can still fail. Newly grown bytes must be
initialized before observation: JVM zero initialization is not a guest guarantee.
Unsafe freeze shares the owner; it is not a copy or permission to mutate an
immutable alias.

## Logical shrink

`shrinkMutableByteArray#` returns only scalar State. It requires an owned
mutable allocation and a new size between zero and the current logical size.
It preserves the owner and backing identity, including existing frozen and
managed-address aliases; managed accesses observe the new logical bound.
Backing capacity is retained, not released. Previously exposed raw/native
storage is not physically resized.

Byte, scalar, vector, address and checked C-buffer accesses enforce the shorter
managed range. A cutoff through a live managed pointer cell is rejected before
changing the size. Cells wholly beyond the new end are removed from the
owner's reference map. Host-injected raw `byte[]` values cannot be shrunk
in place and are rejected by this primitive.
