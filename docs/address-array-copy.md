# Address and byte-array copies

The three GHC 9.14.1 copy primops return scalar `State# s`. Counts and array
offsets are byte counts, carried as full-width Kotlin `Long` values.

| Primitive | Operands before `State# s` |
|---|---|
| `copyAddrToByteArray#` | source address, mutable destination array, destination offset, count |
| `copyByteArrayToAddr#` | immutable source array, source offset, destination address, count |
| `copyMutableByteArrayToAddr#` | mutable source array, source offset, destination address, count |

The pinned GHC declarations require that the address **not point into the array**.
THC therefore rejects the same backing allocation even for disjoint or empty
ranges, including a raw alias of a managed owner. This is stronger than the
disjoint-region rule of `copyAddrToAddrNonOverlapping#`; it does not narrow these
three primops' defined GHC domain. Native undefined inputs are not oracle cases.

Both complete ranges, destination mutability and the canonical State carrier
are checked before mutation. Empty valid ranges can lie at an allocation end or
inside a managed pointer cell without accessing that cell's bytes. Managed
copies preserve complete pointer cells as references; partial-cell operations
and attempts to expose those references as raw bytes fail before mutation.
Existing ordered owner locks protect managed copies. A native owner remains
borrowed throughout range validation, staging and the managed copy boundary,
so concurrent `free` waits until the entire transport finishes.

The address domain remains bounded: managed storage and immutable source
literals, or live context-owned malloc storage on the existing native-enabled
Linux x86_64 backend. Unowned numeric pointers, freed or foreign-context native
owners and immutable destinations are rejected. This does not introduce an
arbitrary-pointer FFI, `copyAddrToAddr#`, or additional native platforms.

`AddressArrayCopyFixtures` builds the genuine Haskell consumers with pinned GHC
9.14.1 and records 1,323 native rows, each observing all eight source and eight
destination bytes. The rows cover seven seeds, all contained ranges in a small
window, whole-array and interior copies, and empty end positions. Pre/post Core
exports retain one saturated copy primop per consumer and strict audits check
their original GHC signatures. The Kotlin tests compare every byte with an
independent snapshot model on AST and bytecode, with and without inlining.
They require unchanged target identities and last-tier validity, and exactly
two compiled guest entries per installed observation: the public entry and its
genuine `runRW#` state lambda. Result pools remain empty and reference-clean.

Additional tests exhaust contained managed ranges and reject full-width bounds,
backing aliases, pointer-bit exposure, wrong carriers/arity/aggregate results,
and invalid State values without changing storage. Explicitly synthetic primop
consumers exercise owned native transport with exactly one compiled guest entry
per installed call; they are not counted as native GHC corpus evidence. Opposing
managed copies and a copy blocked on an owner monitor test lock ordering and
the native lifetime held against concurrent `free`.

```sh
cabal run exe:thc-fixtures --offline -- address-array-copy
cabal run exe:thc-fixtures --offline -- native-addresses
./gradlew test --tests thc.runtime.AddressArrayCopyTest --tests thc.runtime.NativeMallocTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew test --rerun --tests thc.runtime.AddressArrayCopyTest --tests thc.runtime.NativeMallocTest
```

Fixture manifests retain exact source and artifact inventories, including native
binary/input/output, both Core exports, strict audit reports and command logs.
Fast and full CI register the Haskell producer and retain its evidence directory.
