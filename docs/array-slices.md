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
