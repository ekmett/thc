# Aligned StablePtr and WideChar memory

Both JVM backends lower the nine remaining aligned memory operations directly
through the existing pointer-cell and unsigned 32-bit implementations:

- `index/read/writeStablePtrArray#`
- `index/read/writeStablePtrOffAddr#`
- `index/read/writeWideCharArray#`

The existing three WideChar OffAddr operations are regression controls.
These twelve names are checked against the actual GHC 9.14.1 primop inventory.

Indices are elements, not bytes: StablePtr uses the pinned 64-bit pointer width;
WideChar uses four bytes in native byte order. Negative address indices are
accepted only when the complete addressed element lies inside its live owned
backing. Array indices must be nonnegative. WideChar reads zero-extend 32 bits;
the runtime does not add Unicode validation to GHC's raw memory operation.

StablePtr cells retain the opaque `ManagedAddress` handle itself, never invented
machine pointer bits. Allocation-owned managed pointer cells support copying
and array/address aliases. They do not extend a registry handle's lifetime:
dereference and equality continue to reject freed or foreign-context handles.
Partial scalar overwrites and scalar reads of pointer cells are rejected;
a complete overwrite removes the retained reference. Raw byte-array storage,
native-exposed managed storage and native pointer-cell memory remain outside
the supported StablePtr storage contract.

The Haskell producer generates 128 native rows (120 WideChar and eight StablePtr),
six observations per row, with all eight element positions, replacement stores,
both memory views and untouched sentinel bytes. It exports and strictly audits
original pre/post-tidy Core. The Kotlin model independently checks numerical
results, storage bytes, boundaries, ownership and lifetimes. Compiled proofs
require exactly two original-Core guest roots and exactly two compiled entries
on the first and every subsequent measured call; compilation restores the
shared call boundary without invoking the guest. Argument/result pool depths,
retained references and allocation reuse are checked in both handoff modes.

`examples/StableWideCells.hs` demonstrates preserving a caller-owned StablePtr
through an array and storing a supplementary-plane character in slot one:

```sh
ghc -O2 examples/StableWideCells.hs -o build/stable-wide-cells
build/stable-wide-cells
cabal run exe:thc-fixtures --offline -- aligned-scalar-memory
./gradlew test --tests thc.runtime.AlignedScalarMemoryTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew test --tests thc.runtime.AlignedScalarMemoryTest --rerun
```

The example's ordinary printing is a native GHC demonstration. The strict
runtime coverage is the two audited scalar entries, not a claim that every
platform can execute this example's complete `Main` library closure.

Validation on Linux x86_64 (2026-09-26): all five new tests and 36 affected
regression tests passed in each handoff mode, reusing the same JVM compilation.
The native/Core producer and example passed; fast-selection, fixture-cache,
input-cache, byte-array audit and primop-coverage checks passed.
