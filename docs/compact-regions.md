# Compact regions

THC implements the ordinary `GHC.Compact` API through `compactNew#`,
`compactResize#`, `compactAdd#`, `compactAddWithSharing#`, `compactContains#`,
`compactContainsAny#`, and `compactSize#` on both backends.

Adding a value forces and copies its immutable object graph. Ordinary addition
duplicates sharing; the sharing variant preserves shared children and cycles.
References already in the same region are reused. The copied graph does not
retain the source graph. Region handles keep all their additions alive; an
escaped copied value remains usable and identifiable as compacted after its
handle is dropped. Membership is identity-based and context-local, with weak
keys so an unreachable region does not leak through the membership index.

Functions, mutable pointer-bearing objects, and pinned byte arrays raise the
original GHC `CompactionFailed` exception payloads. Frozen boxed arrays are
distinguished from mutable arrays without replacing their storage carrier.
Only one addition or resize may operate on a region at once; the public library
already serializes writers with its `MVar`. An invalid concurrent/reentrant
raw-primop mutation is rejected rather than corrupting the graph.

The target uses managed JVM objects, not a contiguous GHC heap image. `compactSize#`
reports target region-capacity accounting in 4-KiB units; it is not a measurement
of JVM object size. There is no promise of GHC's GC traversal avoidance or its
exact byte counts. Plain addition on a cyclic graph is rejected with a diagnostic
directing the caller to the sharing variant. Resumable asynchronous forcing
during addition is not implemented yet; synchronous guest exceptions are retained.

The four serialized-block operations (`compactGetFirstBlock#`,
`compactGetNextBlock#`, `compactAllocateBlock#`, `compactFixupPointers#`) remain
unsupported. `GHC.Compact.Serialized` does not yet have a target image format or
pointer-fixup implementation. No GHC byte-level ABI or serialization roundtrip
is claimed.

Ordinary graph, cycle, array-mutability and context tests run in the standard
JVM suite. Original-library validation uses the existing opt-in complete-Core
fixture infrastructure, including original installed `ghc-compact` modules and
the original exception dictionaries:

```sh
cabal run exe:thc-fixtures --offline -- compact-regions
./gradlew --continue compactRegionsFullCoreDefault compactRegionsFullCoreDense
```

This requires the pinned GHC 9.14.1 installation with complete Core; a stock thin
interface installation is an explicit missing prerequisite, not a reason to
rewrite the library or skip its native result comparison. The native examples
exercise creation, addition, membership, growth, sharing, cycles, frozen arrays,
and the public exception handler in both pre- and post-Tidy exports.
