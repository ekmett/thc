# Address and byte-array copies

The three GHC 9.14.1 copy primops return scalar `State# s`. Counts and array
offsets are byte counts, carried as full-width Java `long` values.

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
