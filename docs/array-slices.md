# Shallow boxed-array slices

The three GHC 9.14.1 copying primitives operate on the existing managed `Object[]`
carrier. Every call creates independent storage containing the same element
references, without entering thunks or copying the referenced objects.

| Primitive | Arguments | Result |
|---|---|---|
| `cloneArray#` | `Array# a`, `Int#` offset, `Int#` count | `Array# a` |
| `freezeArray#` | `MutableArray# s a`, offset, count, `State# s` | `(# State# s, Array# a #)` |
| `thawArray#` | `Array# a`, offset, count, `State# s` | `(# State# s, MutableArray# s a #)` |

These are the actual pinned [GHC primitive contracts](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp).
Offsets and counts measure elements. The whole slice must be contained in the
source; zero elements at the source end are valid. GHC leaves invalid raw ranges
unchecked. THC rejects them in its managed domain: it checks full-width offset
and count against source length by subtraction before narrowing or adding them.
Invalid inputs are runtime controls, not native-GHC semantic comparisons.

State operands are evaluated and validated before the copy or destination
publication. The tuple forms write only the unlifted array reference to a typed
local destination; `State#` has no payload slot. `cloneArray#` returns one scalar
unlifted reference, not a singleton tuple. AST nodes have fixed child operands;
bytecode operations receive primitive `long` offsets/counts. The existing
`unsafeFreezeArray#` operation retains storage identity; the copying operations
are distinct.

Element-exposing operations still require the existing known-lifted element
proof. Copying storage is opaque: shared thunks, closures and existing unlifted
boxed references preserve their identities, without adding support for creating
or indexing arrays at otherwise unsupported element representations. Scalar
array references require exact `BoxedRep (Just Unlifted)` proofs.

`ArraySliceAudit` retains every new primitive in five strictly supported roots
at both Core stages. It checks offset slices, snapshots before subsequent source
and destination writes, copying unused recursive bottoms, captured closures,
zero elements, and genuine public `runSTArray` construction and `Data.Array.!`
consumption surrounding the raw slice operations. Native GHC and an independent
list/integer model cover 386 inputs per root, including machine extrema and every
bit boundary.

A sixth native root uses ordinary public `Data.Array.ST.freeze` and `thaw`.
Those pinned library implementations allocate via `arrEleBottom` and copy in
loops; adding these primops does not replace those bodies. Both strict audits
must still reject exactly the missing original `GHC.Internal.Arr.arrEleBottom`.
No error body, Typeable dependency or cold branch is removed. Its native results
are not counted as supported THC execution.

The focused JVM suite checks AST/bytecode, pre/post Core and inlined/residual
calls, including compiled-entry activity, valid stable original/active targets,
zero unsupported traps/blackholes and cleared argument/result pools. Separate
controls require reference identity, independent storage, every contained slice
of a small array, full-width invalid ranges, bad carriers, State-before-copy,
and exact arity/levity/result proofs in strict and diagnostic modes.

```sh
python3 scripts/prepare-array-slices.py
python3 scripts/test-array-slice-model.py
python3 scripts/test-core-arrays.py
./gradlew test --tests thc.runtime.ArraySliceTest --tests thc.runtime.BoxedArrayTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew test --rerun --tests thc.runtime.ArraySliceTest --tests thc.runtime.BoxedArrayTest
```

Clean-checkout preparation and CI retain full Core, native oracle and source
provenance. `copyArray#`, `copyMutableArray#`, `cloneMutableArray#`, resizing,
concurrent mutation and atomic array operations remain outside this slice.
