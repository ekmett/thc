# SIMD address memory

The local address-memory contract covers packed and scalar-offset index, read
and write for these 18 existing vector representations:

- Int32X4, Word32X4, FloatX4, DoubleX2.
- Int16X16, Word16X16, Int32X8, Word32X8, Int32X16, Word32X16.
- Int64X4, Word64X4, Int64X8, Word64X8.
- FloatX8, FloatX16, DoubleX4, DoubleX8.

For example, `readInt32X8OffAddr# address i state` starts at byte offset
`32*i`; `readInt32OffAddrAsInt32X8# address i state` starts at `4*i`.
Both read the full 32-byte vector. Negative offsets are valid only when the
resulting whole region remains within the same live allocation.

These operations use the existing checked address storage, including managed
byte-array aliases and owned native allocations. The owner monitor or native
borrow spans validation and transfer. Bounds use the complete vector width;
overflow, read-only writes, released native storage and partially overwritten
managed pointer cells are rejected. Disjoint managed pointer cells retain their
actual carriers. Floating lanes are transferred as raw bits without arithmetic.
No vector access is promised atomic relative to competing scalar accesses.

## Evidence and scope

`cabal run exe:thc-fixtures --offline -- simd-address-families` generates
original GHC 9.14.1 Core for all 108 operations, audits it strictly, and obtains
2,592 native results from an independent scalar-lane address model. A separate
576-row native vector128 oracle and post-tidy Core corpus cover the four
128-bit shapes on the supported non-ARM LLVM producer path. This is not a claim
that native 256-/512-bit vector instructions ran on the producer host.
On ARM the scalar oracle and original pre-Core remain available; the native
vector128/post-tidy stage is explicitly omitted.

`SimdAddressFamiliesTest` compares both AST and bytecode execution with a
separate Kotlin scalar-byte model, including every byte after stores. It
checks 19 fixed compiled representatives spanning all shapes and typed operations,
including the first installed call with exact source-root entry counts, retained
handoff references and allocation baselines in both default and dense modes.
The shared SIMD128 address tests exercise native lifetime and storage kinds;
the wider tests add full-span, species, shrink and pointer-cell boundaries.
Fixture manifests retain generated Haskell sources, exact native/Core commands,
oracles and input/artifact hashes; CI caches those inputs, never JVM outcomes.

The six other 128-bit shapes are documented in
[SIMD128 address memory](simd128-address-memory.md). The six new byte/short
wide representations have a separate implementation and fixture owner.
