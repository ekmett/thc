# Original libc byte comparison and search

Both backends lower the original GHC 9.14.1 ByteString `memcmp` and `memchr`
imports to checked reads of their existing storage. This is a sensible JVM
implementation of the libc operations, not a host-native call or replacement
Haskell package. It neither copies buffers nor pins ordinary byte arrays.

`memcmp` compares unsigned bytes, stopping at the first difference. Its result
has the C-required negative/zero/positive sign; the particular nonzero magnitude
is not promised to match a platform libc. `memchr` converts the `CInt` needle to
an unsigned byte, returns the first matching interior address, or returns null.
The interior address retains the original allocation and offset. Mutations
through either alias remain visible, and immutable literals remain immutable.

Supported storage includes ordinary heap byte arrays, managed allocations,
already-pinned native-backed arrays, immutable literals, and context-owned native
allocations. Owned native allocations stay borrowed for the whole scan; free or
reallocation retirement cannot race its reads. A returned address does not prevent
a later explicit free: subsequent accesses reject. Foreign-context/freed owners,
opaque pointer cells, unowned numeric addresses, out-of-bounds ranges and `CSize`
values beyond the signed JVM size domain reject. Null with zero length succeeds.
No access beyond the declared count is performed.

Admission retains exact unsafe `ccall` metadata, including 32-bit `CInt`, 64-bit
`CSize`, `Addr#` operands/results and the `State#` tuple. The original
`ghc-internal` `memcmp` declaration has the same admitted ABI. This does not
admit arbitrary libc signatures, callbacks or safe/blocking native calls.

## Checks

With the pinned toolchain from the [build guide](../README.md):

```sh
cabal run exe:thc-fixtures -- original-memory-search
./gradlew --continue \
  testDefault --tests thc.runtime.OriginalMemorySearchTest \
  testDense --tests thc.runtime.OriginalMemorySearchTest
```

The Haskell fixture calls the real exposed ByteString wrappers. Native GHC
records 392 observations, including unequal unsigned bytes, offsets, empty
prefixes, vector-boundary lengths, first-match and absent-byte searches. Both
pre/post-tidy Core exports pass strict audits. JVM checks compare those original
consumers against native results on four storage kinds in both backends,
interpreted and on their first compiled calls, in both handoff modes. Additional
controls exercise native owner lifetime/context isolation, pointer-cell rejection,
result aliasing, immutable literals, malformed declarations and invalid State
carriers. This advances the GHC-as-library frontier; it is not a claim that the
whole compiler session now runs.
