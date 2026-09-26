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

Kotlin owns the numeric behavior and AST path. Java specializations are confined
to the Truffle Bytecode DSL. Results write directly to typed destination slots;
there is no temporary pair. Lowering checks physical integer, reference and
state carriers, arity, and tuple order. Exact GHC `RuntimeRep` spelling belongs
to the strict exporter audit, so runtime operations do not recheck lexical
distinctions between integer values already represented by `Long`.

`compiler/test-fixtures/AtomicIntArrayAudit.hs` is a small executable example of
each primitive. Its `casInt8Result` entry performs two comparisons on element
one, returns a checksum of both old values and the final signed byte, and checks
the two neighboring bytes. `atomicLoadStore` demonstrates state-threaded atomic
publication through three writes and reads. The producer is Haskell:

```sh
cabal run exe:thc-fixtures --offline -- atomic-int-arrays
./gradlew --no-daemon test --tests thc.runtime.AtomicIntArrayTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew --no-daemon test --tests thc.runtime.AtomicIntArrayTest --rerun
```

The producer exports genuine pre/post-tidy Core, requires strict audits, builds
a separate native GHC oracle, and records input/artifact hashes and command logs.
The Kotlin test independently models every native row. It checks both backends,
with inlining enabled and disabled, exact compiled-entry counts starting with
the first call after installation, stable active call-target identities, and
handoff cleanup. Direct typed-backend tests cover effects, state validation and
bounds; concurrency controls check linearizable fetch histories, one CAS winner
and retry loops at every width, and payload publication through atomic reads and
writes. The prior fetch-add fixtures and operand-evaluation test remain active.

The shared CI fixture registry owns the producer and Core source, and fingerprints
their dependencies. Cached native/Core artifacts do not replace runtime tests.
The generated [primop checklist](primops.md) retains the managed-storage limits.
