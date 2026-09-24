# Mutable byte-array resize

`resizeMutableByteArray#` accepts a managed unlifted `byte[]`, an exact `Int#`
length, and scalar `State#`. It returns the logical pair `(# State#, MutableByteArray# #)`
through one typed reference destination; State has no tuple storage. Both backends
validate exact saturation, signedness, levity and the logical pair before lowering.

Pinned GHC 9.14.1 allows replacement allocation and forbids further access to the
original reference after resize. The implementation returns the original at equal
size, otherwise copies the retained prefix to a new JVM `byte[]`. Full-width size
checks reject negative or greater-than-`Int.MAX_VALUE` requests before narrowing
or allocation. State is evaluated before allocation/publication. Newly grown bytes
are unspecified to guests; JVM zero initialization is not a guest guarantee.

`compiler/test-fixtures/ResizeByteArrayAudit.hs` retains an OPAQUE resize worker
across actual calls, including repeat resize and later writes through the returned
reference. It never accesses a retired array. `scripts/prepare-resize-bytearrays.py`
rebuilds the pinned exporter and generates fresh pre/post Core, native TSV and
source/artifact hashes. `ResizeByteArrayNative.hs` owns native input generation
and execution; `ResizeByteArrayTest` checks every native row against an independent
Kotlin byte-list model before either backend runs. The corpus covers all 17×17 small
size pairs, all 256 byte patterns across grow/shrink/equal/zero cases, machine-width
selectors and seeded repeated resizing: 3,192 rows, four strict accepted audits.
Every newly introduced byte is initialized before native observation. The two
byte-array native drivers share `ByteArrayFixtureInputs.hs`; Python no longer
generates Haskell drivers or computes expected semantic results for these families.

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

`shrinkMutableByteArray#` remains unsupported: its State-only return requires all
aliases to observe a changed logical size, which immutable JVM array lengths do
not provide. This slice does not change storage identity rules, add pinning/raw
addresses, or claim public Text support. Text pack still needs shrink and original
source closure; other Text routes retain separate FFI/linkage frontiers.
