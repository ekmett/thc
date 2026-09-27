# Byte-array storage documentation checkpoint

This preserves the complete resize, size-query and fill/copy guides from
`8bf993d744e57f2c314102c7b9f58d26e6c8788b`, with relative links repaired.
Their earlier carrier descriptions, limitations and command sequences are
historical, not current guidance. Existing fixture artifacts are unchanged.
See the current [resize/shrink](../docs/resize-bytearrays.md),
[size-query](../docs/mutable-bytearray-size.md) and
[fill/copy](../docs/mutable-bytearray-ops.md) contracts.

---

Source: `docs/resize-bytearrays.md`

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
reference. It never accesses a retired array. `cabal run exe:thc-fixtures --offline -- resize-bytearrays`
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

---

Source: `docs/mutable-bytearray-size.md`

# Mutable byte-array size

Pinned GHC 9.14.1 defines two distinct primitives. `getSizeofMutableByteArray#`
accepts one unlifted managed byte array and scalar `State#`, returning the logical
unboxed pair `(# State#, Int# #)`. Both backends evaluate/check State before reading
length and publish the size through one primitive Long destination. State consumes
no tuple storage. The result does not use a boxed pair or generic aggregate carrier.

`sizeofMutableByteArray#` is the separate deprecated pure primitive with a single
`Int#` result. It uses the existing typed array-length path. Its GHC warning matters:
it is unsafe around shrinking/resizing of the same reference. Native controls query
only live, stable references. After resize, all accesses use the returned array;
there is no promise about retired aliases. `shrinkMutableByteArray#` now changes
an owned allocation's logical size in place; both queries observe that size,
not retained backing capacity. Pointer-cell truncation and host-array limits
are listed in the [behavior reference](../docs/primop-behavior.md#addresses-pinning-and-pointer-containing-storage).

The fresh preparer retains original OPAQUE `getSizeWorker`/`pureSizeWorker` calls in
both pre/post Core and checks exact pinned signatures and ten strict audits.
`MutableByteArraySizeNative.hs` produces the input inventory and 3,070 native rows;
`MutableByteArraySizeTest` compares them with an independent Kotlin size/byte model
before running either backend. It covers zero/boundary sizes,
all 17×17 grow/shrink/equal pairs, repeat resize, ordered writes and reads, machine-width
selectors and all 256 byte patterns. No uninitialized or retired storage is observed.

`MutableByteArraySizeTest` runs the native roots on AST and bytecode, inline and
residual, with source/artifact hash checks, per-row compiled guest activity, unchanged
active target identities, valid original/host/active targets and clear input/result
pools. Fixture-free Kotlin tests check the full input inventory and reject malformed,
missing, duplicate, reordered and incorrect oracle rows. Independent direct primitive entries require exactly one compiled entry for
each measured call. State-failure controls require no destination publication;
malformed saturation, State/empty-tuple confusion, signedness, levity, missing proofs
and wrong runtime carriers reject. Ordinary default and dense-handoff modes use the
same gates and normal compilation thresholds.

Reproduce preparation with `cabal run exe:thc-fixtures --offline -- mutable-bytearray-size`, the
auditor proof checks with `python3 scripts/test-core-bytearrays.py`, and the model,
native-comparison and compiled-path tests together with
`./gradlew --no-daemon test --tests thc.runtime.MutableByteArraySizeTest`.
For dense handoff use `JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true` and selected-task
`test --rerun` with the same test filter. This original size-query fixture is not
the separate shrink implementation's evidence, nor a public Text closure claim.

---

Source: `docs/mutable-bytearray-ops.md`

# Mutable byte-array fills and copies

The managed `byte[]` carrier supports three additional GHC 9.14.1 operations.
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
the disjoint copy to `memcpy`. THC uses JVM primitive-array fill/copy operations
and rejects violations of the defined range/overlap domain before writing.
Undefined native inputs are excluded from the GHC oracle.

The existing `copyByteArray#` contract is unchanged: an immutable source and a
mutable destination must have different backing identities, even for disjoint
or empty ranges. The new operations preserve their destination's backing identity
and length. All operands, including the canonical zero-width State carrier, are
evaluated before the first mutation. AST nodes have fixed child operands;
bytecode operations have typed `long` positions, counts and fill values, with the
copy policy fixed at node construction. Exact unlifted boxed-reference, `IntRep`
and scalar `VOID` proofs are required; a `Word8#` value is not an `Int#` fill proof.

`MutableByteArrayAudit` contains five primitive workloads and one genuine public
`ShortByteString.replicate`/`foldl'` consumer, including the empty-string branch.
Both Core stages retain each declared primitive and strictly accept every root,
including the installed bytestring `empty` dependency. No source bodies or cold
branches are replaced or removed. Fresh native values are compared with an
independent byte-list model over full-width fill carriers, every small contained
move range, both overlap directions, disjoint adjacent regions and independent
source/destination storage. A source write after copying checks snapshot behavior.

The focused suite checks the same native rows on AST/bytecode, pre/post Core and
inlined/residual paths, with per-row compiled activity and stable valid host,
original and active guest targets. Separate exact-byte tests exhaust small
contained ranges, verify identities and bytes outside the mutation, reject
full-width bounds and overlap violations, and require State failure to leave
storage unchanged. Result pools must remain empty and reference-clean.

```sh
cabal run exe:thc-fixtures --offline -- mutable-bytearrays
python3 scripts/test-core-bytearrays.py
./gradlew test --tests thc.runtime.MutableByteArrayTest --tests thc.runtime.ByteArrayTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew test --rerun --tests thc.runtime.MutableByteArrayTest --tests thc.runtime.ByteArrayTest
```

This adds no resizing, pinned allocation, `Addr#`, foreign-memory, concurrent or
atomic operations. Native undefined ranges and overlapping calls to the disjoint
primitive are not counted as execution coverage.

The five related byte-array fixture producers share the Haskell
`ByteArrayFixtures` module. `MutableByteArrayTest` independently checks the
complete ordered native corpus, all contained ranges, overlap snapshots and
distinct-storage copy equivalence. Missing, duplicate, reordered and wrong rows,
missing source/artifact hashes and corrupt receipts are rejected. The existing
Python Core auditor, original-source exporter and primop inventory remain shared
dependencies; there is no Python fixture-model entry point for this family.
