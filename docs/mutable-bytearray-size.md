# Mutable byte-array size

Pinned GHC 9.14.1 defines two distinct primitives. `getSizeofMutableByteArray#`
accepts one unlifted managed `byte[]` and scalar `State#`, returning the logical
unboxed pair `(# State#, Int# #)`. Both backends evaluate/check State before reading
length and publish the size through one primitive Long destination. State consumes
no tuple storage. The result does not use a boxed pair or generic aggregate carrier.

`sizeofMutableByteArray#` is the separate deprecated pure primitive with a single
`Int#` result. It uses the existing typed array-length path. Its GHC warning matters:
it is unsafe around shrinking/resizing of the same reference. Native controls query
only live, stable references. After resize, all accesses use the returned array;
there is no promise about retired aliases. `shrinkMutableByteArray#` stays unsupported,
and this slice does not alter direct byte[] storage or add a logical-size wrapper.

The fresh preparer retains original OPAQUE `getSizeWorker`/`pureSizeWorker` calls in
both pre/post Core, checks exact pinned signatures and ten strict audits, and compares
3,070 native rows with an independent size/byte model. It covers zero/boundary sizes,
all 17×17 grow/shrink/equal pairs, repeat resize, ordered writes and reads, machine-width
selectors and all 256 byte patterns. No uninitialized or retired storage is observed.

`MutableByteArraySizeTest` runs the native roots on AST and bytecode, inline and
residual, with source/artifact hash checks, per-row compiled guest activity, unchanged
active target identities, valid original/host/active targets and clear input/result
pools. Independent direct primitive entries require exactly one compiled entry for
each measured call. State-failure controls require no destination publication;
malformed saturation, State/empty-tuple confusion, signedness, levity, missing proofs
and wrong runtime carriers reject. Ordinary default and dense-handoff modes use the
same gates and normal compilation thresholds.

Reproduce preparation with `python3 scripts/prepare-mutable-bytearray-size.py`, the
model/proof checks with `python3 scripts/test-mutable-bytearray-size.py`, and the focused
JVM tests with `./gradlew --no-daemon test --tests thc.runtime.MutableByteArraySizeTest`.
For dense handoff use `JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true` and selected-task
`test --rerun` with the same test filter. No public Text closure or shrink support is claimed.
