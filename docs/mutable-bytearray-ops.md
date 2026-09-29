# Mutable byte-array fills and copies

THC-owned heap/pinned allocations and host-supplied `byte[]` values support these
three GHC 9.14.1 operations on both backends.
All return scalar `State# s`; no result tuple or result-storage loan is involved.
Offsets/counts measure bytes, remain primitive `long` values, and must describe
contained ranges. THC validates full-width ranges by subtraction before narrowing
or mutation, including empty ranges at either array end.

| Primitive | Value arguments before `State# s` | Overlap contract |
|---|---|---|
| `setByteArray#` | mutable array, offset, count, `Int#` fill value | Fills the range with the low eight bits of the value. |
| `copyMutableByteArray#` | mutable source, source offset, mutable destination, destination offset, count | Same-array overlap is permitted, with snapshot/memmove semantics. |
| `copyMutableByteArrayNonOverlapping#` | same five arguments | Regions must be disjoint; one backing array is allowed when its two ranges do not overlap. |

These are the pinned [primitive declarations](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp)
and [GHC lowering](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/StgToCmm/Prim.hs).
GHC lowers the fill to `memset`, the overlapping same-array move to `memmove`, and
the disjoint copy to `memcpy`. THC retains JVM primitive-array fill/copy fast
paths for raw arrays. Owned allocations use their existing memory segments,
validate logical bounds and preserve complete pointer cells when copying between
owners. Copy and comparison use the same ordered owner locks. Invalid ranges,
forbidden overlap and partial pointer-cell overwrites are rejected before writing.
A fill or replacement copy covering a whole pointer cell releases that cell's
managed reference; raw byte transfer cannot expose a live managed pointer as bytes.
Undefined native inputs are excluded from the GHC oracle.

The existing `copyByteArray#` contract is unchanged: an immutable source and a
mutable destination must have different backing identities, even for disjoint
or empty ranges. These operations preserve their destination's backing identity
and logical length. All operands, including the canonical zero-width State carrier, are
evaluated before the first mutation. AST nodes have fixed child operands;
bytecode operations have typed `long` positions, counts and fill values, with the
copy policy fixed at node construction. Lowering checks unlifted boxed references,
scalar State, arity and physical carriers. Integral annotations share `Long`;
the fill operation stores its low eight bits. Strict exporter auditing checks the
original `Int#` source signature separately.

See [resize/shrink](resize-bytearrays.md), [pinned memory](pinned-memory.md)
and [atomic integer access](atomic-int-arrays.md) for their separate contracts.
Owner locking protects storage invariants; it does not make arbitrary native
accesses atomic with managed copies.
