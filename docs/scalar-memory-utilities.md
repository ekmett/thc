# Scalar memory utilities

Both backends implement `copyAddrToAddr#`, `setAddrRange#`, `minusAddr#`,
`remAddr#`, all four byte-array pinning queries, `unsafeThawByteArray#`, and
`shrinkSmallMutableArray#`.

Address copy has memmove semantics, including overlap. Fill stores the low byte
of its value. Both validate whole ranges before mutation, preserve native borrows
and context/lifetime checks, and reject writes to read-only memory. Managed
pointer cells move as references; a fill may erase a complete cell but cannot
partially overwrite one. No raw JVM address is required.

Managed addresses have an allocation-local origin: subtraction compares offsets
of aliases, and remainder uses that logical byte offset. Unrelated managed
allocations have no shared numeric address space. Native and numeric carriers
retain machine-word arithmetic; notably pinned GHC 9.14.1 implements `remAddr#`
as **unsigned** remainder. A zero divisor is rejected.

Explicit pinned allocations record a stable logical address guarantee, retained
through freeze/thaw, shrink and resize. All four queries report it. Ordinary
managed and host byte arrays promise neither strong nor weak pinning; THC does
not emulate the GHC RTS large-object or compact-region allocation policies.
This is a managed pointer guarantee, not physical JVM heap pinning.

Unsafe thaw, like unsafe freeze, changes the guest type without copying storage.
The normal guest allocations retain identity and mutability. Internal read-only
foreign images remain non-writable; an unsafe cast does not grant foreign-memory
write permission. Callers must obey GHC's unsafe aliasing preconditions.

Small-array shrink retains both the wrapper and its backing identity, changes the
logical length, and clears discarded references without forcing them. Reads,
writes, slices, copies and size queries respect the shorter bound. The new length
must be between zero and the current size. As with GHC, callers must synchronize
shrink with access to removed elements; it is not an atomic concurrent resize.

## Checks

```sh
cabal run exe:thc-fixtures -- scalar-memory-utilities
./gradlew --max-workers=2 test --tests thc.runtime.ScalarMemoryUtilitiesTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew --max-workers=2 \
  test --rerun --tests thc.runtime.ScalarMemoryUtilitiesTest
```

The Haskell producer records 271 genuine native results, pre/post Core exports,
strict audits and source/artifact hashes. Kotlin independently models the corpus
and checks both backends, including the first installed guest call, exact entry
counts and handoff cleanup. Boundary tests cover overlap, pointer-cell handling,
truncated references and native lifetime/context restrictions.
