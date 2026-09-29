# Atomic integer byte arrays

Both runtime backends implement GHC 9.14.1's complete atomic integer
`MutableByteArray#` family on THC-owned mutable allocations:

| Operations | Index unit | Result |
| --- | --- | --- |
| `atomicReadIntArray#` | 8-byte machine word | `(# State# s, Int# #)` |
| `atomicWriteIntArray#` | 8-byte machine word | `State# s` |
| `fetchAdd/Sub/And/Nand/Or/XorIntArray#` | 8-byte machine word | State and previous signed `Int#` |
| `casIntArray#`, `casInt64Array#` | 8 bytes | State and previous signed integer |
| `casInt8Array#`, `casInt16Array#`, `casInt32Array#` | 1, 2, 4 bytes respectively | State and previous signed integer of that width |

CAS returns the value observed before the operation, on success and failure.
It has no separate success flag. A failed comparison does not write. Narrow
operations compare and store the low bits of their integer carriers, then sign
extend their result. Fetch arithmetic wraps at the machine width; NAND writes
the complement of the bitwise AND. For example, a `fetchSubIntArray#` of one
from the minimum `Int#` returns the minimum and stores the maximum.

Every operation validates ownership, mutability, its complete logical element
range, and overlap with managed pointer cells. Empty arrays, partial final
elements, negative or overflowing indices, immutable allocations, raw host byte
arrays and pointer-cell overlaps fault before changing storage. Shrinking an
allocation changes the range seen by subsequent atomics. An operation on a
disjoint byte range leaves pointer cells intact.

The allocation monitor keeps each read/modify/write indivisible with respect to
other managed accesses, shrink and pointer installation. A backing-array monitor,
always acquired after the allocation monitor, also allows address atomics through
exposed byte-array aliases to use the same ordering. Successful and failed CAS,
atomic reads, writes and fetch operations all provide full memory barriers.
These are managed-storage guarantees, not hardware lock-free operations or
support for racing unchecked native accesses to exposed storage.

Java implements the numeric behavior, AST path and Truffle Bytecode DSL
specializations. Results write directly to typed destination slots;
there is no temporary pair. Lowering checks physical integer, reference and
state carriers, arity, and tuple order. Exact GHC `RuntimeRep` spelling belongs
to the strict exporter audit, so runtime operations do not recheck lexical
distinctions between integer values already represented by `Long`.
