# Aligned StablePtr and WideChar memory

Both JVM backends lower the nine remaining aligned memory operations directly
through the existing pointer-cell and unsigned 32-bit implementations:

- `index/read/writeStablePtrArray#`
- `index/read/writeStablePtrOffAddr#`
- `index/read/writeWideCharArray#`

`index/read/writeWideCharOffAddr#` use the same four-byte representation.

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

See [StableWideCells.hs](../src/examples/StableWideCells.hs) for a native GHC
example of StablePtr storage and four-byte character slots.
