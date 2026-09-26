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
