# Mutable byte-array size

Pinned GHC 9.14.1 defines two distinct primitives. `getSizeofMutableByteArray#`
accepts one unlifted managed byte-array reference and scalar `State#`, returning the logical
unboxed pair `(# State#, Int# #)`. Both backends evaluate/check State before reading
length and publish the size through one primitive Long destination. State consumes
no tuple storage. The result does not use a boxed pair or generic aggregate carrier.

`sizeofMutableByteArray#` is the separate deprecated pure primitive with a single
`Int#` result. It uses the existing typed array-length path. Its GHC warning matters:
it is unsafe around shrinking/resizing of the same reference. Native controls query
only live, stable references. After resize, all accesses use the returned array;
there is no promise about retired aliases. `shrinkMutableByteArray#` changes
an owned allocation's logical size in place; both queries observe that size,
not retained backing capacity. Pointer-cell truncation and host-array limits
are described in the [resize/shrink guide](resize-bytearrays.md).
Host-supplied raw `byte[]` values report their physical array length; guest-owned
heap and pinned allocations report their logical size, independent of retained
capacity. The unsafe pure query is not a synchronization mechanism.

The fresh preparer retains original OPAQUE `getSizeWorker`/`pureSizeWorker` calls in
both pre/post Core and checks exact pinned signatures and ten strict audits.
`MutableByteArraySizeNative.hs` produces the input inventory and 3,070 native rows;
`MutableByteArraySizeTest` compares them with an independent Java size/byte model
before running either backend. It covers zero/boundary sizes,
all 17×17 grow/shrink/equal pairs, repeat resize, ordered writes and reads, machine-width
selectors and all 256 byte patterns. No uninitialized or retired storage is observed.

`MutableByteArraySizeTest` runs the native roots on AST and bytecode, inline and
residual, with source/artifact hash checks, per-row compiled guest activity, unchanged
active target identities, valid original/host/active targets and clear input/result
pools. Fixture-free Java tests check the full input inventory and reject malformed,
missing, duplicate, reordered and incorrect oracle rows. Independent direct primitive entries require exactly one compiled entry for
each measured call. State-failure controls require no destination publication;
malformed saturation, State/empty-tuple confusion, levity, missing aggregate proofs
and wrong physical carriers reject. Integral annotations share `Long` after
lowering; the selected operation supplies scalar interpretation and the strict
Core auditor independently checks source-level signatures. Ordinary default and dense-handoff modes use the
same gates and normal compilation thresholds.

Run the producer, auditor controls and both runtime modes with the pinned toolchain:

```sh
cabal run exe:thc-fixtures --offline -- mutable-bytearray-size
python3 bin/test-core-bytearrays.py
./gradlew --max-workers=2 --continue \
  testDefault --tests thc.runtime.MutableByteArraySizeTest \
  testDense --tests thc.runtime.MutableByteArraySizeTest
```

See the dedicated [shrink checks](resize-bytearrays.md#fixture-and-runtime-checks).
