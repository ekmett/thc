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
shrinks that owner in place. Growth creates an unpinned replacement, copies the
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

## Fixture and runtime checks

`compiler/test-fixtures/ResizeByteArrayAudit.hs` retains an OPAQUE resize worker
across actual calls, including repeat resize and later writes through the returned
reference. It never accesses a retired array. `cabal run exe:thc-fixtures --offline -- resize-bytearrays`
rebuilds the pinned exporter and generates fresh pre/post Core, native TSV and
source/artifact hashes. `ResizeByteArrayNative.hs` owns native input generation
and execution; `ResizeByteArrayTest` checks every native row against an independent
Kotlin byte-list model before either backend runs. The corpus covers all 17×17 small
size pairs, all 256 byte patterns across grow/shrink/equal/zero cases, machine-width
selectors and seeded repeated resizing: 3,192 rows, four strict accepted audits.
Every newly introduced byte is initialized before native observation. The
byte-array native drivers share `ByteArrayFixtureInputs.hs`; producer orchestration
is in Haskell and the independent runtime models are in Kotlin.

`ResizeByteArrayTest` checks these values on AST/bytecode with inline/residual
calls, per-row compiled guest entry, unchanged actual call targets and valid
original/host/active compiled targets. These public-wrapper counts are positive
increments, not a claim of one guest call per row. Separate primitive controls
require exactly one compiled entry, check every retained byte, invalid State and
full-width sizes, publication failure, wrong carriers, and malformed proofs.
Result/input loan depth and retained references must return to zero. Fixture-free
Kotlin checks cover the input grid and reject malformed, missing, duplicate,
reordered and incorrect oracle rows. Python-auditor-specific representation
mutations remain in the shared `scripts/test-core-bytearrays.py` suite.

`ShrinkMutableByteArrayAudit` separately exercises ordinary and pinned shrink.
The Haskell producer records 45 native rows with genuine pre/post-Tidy Core,
strict per-entry audits and source/artifact hashes. `ShrinkByteArrayTest`
compares them with an independent byte model on both backends before and after
guest compilation. Its direct controls cover frozen/address aliases, logical
bounds, checked C buffers, whole versus partial pointer-cell truncation, raw-array
rejection, and vector accesses after shrink. `ManagedAllocationTest` also checks
pointer-preserving resize and copy behavior.

Using the pinned toolchain and the checkout's build lease:

```sh
cabal run exe:thc-fixtures --offline -- resize-bytearrays
cabal run exe:thc-fixtures --offline -- shrink-bytearrays
python3 scripts/test-core-bytearrays.py
./gradlew --max-workers=2 --continue \
  testDefault --tests thc.runtime.ResizeByteArrayTest --tests thc.runtime.ShrinkByteArrayTest \
  testDense --tests thc.runtime.ResizeByteArrayTest --tests thc.runtime.ShrinkByteArrayTest
```

See [mutable size queries](mutable-bytearray-size.md) and
[pinned/address ownership](pinned-memory.md) for related contracts.
