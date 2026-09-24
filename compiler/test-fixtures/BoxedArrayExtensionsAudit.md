# Managed boxed-array extensions

This bounded slice adds `sizeofArray#`, `sizeofMutableArray#`,
`cloneMutableArray#`, `copyArray#`, `copyMutableArray#`, and `unsafeThawArray#`.
Their signatures and aliasing contract come from GHC 9.14.1
`compiler/GHC/Builtin/primops.txt.pp` at source commit
`902339d332fb4ce2b3c87dcac1ee6495d41ad886`, lines 1566–1668.

The managed representation is exact JVM `Object[]` storage. Sizes are logical
element counts. Clone storage is independent but elements remain shared lazy
references; unsafe thaw preserves storage identity. Mutable copying has overlap
semantics equivalent to copying from a snapshot. Immutable-source copying
rejects source/destination identity, including zero-length copies, because GHC
requires distinct arrays for `copyArray#`. Both ranges and State are validated
before mutation. Invalid offsets, counts and overflow are JVM safety extensions,
not inputs executed by the native oracle.

Run `cabal run exe:thc-fixtures --offline -- boxed-array-extensions` to export
pre/post Core, run strict closure audits, and collect native results. Preparation
uses the shared Haskell framework, preserves every attempted run's logs, and
hashes source and native/Core artifacts without hashing installed GHC files.
The producer records observations only; Kotlin independently generates the
1,830-request corpus, models results and verifies hashes/proofs. The JVM suite
checks both backends before and after explicit compilation installation,
including the first compiled invocation, plus lazy payloads, alias identity,
overlap, invalid State/ranges and malformed raw representation metadata.

No small arrays, atomic array operations, native pointers, or new exception
semantics are included. The original public-array cold error frontiers remain
separate and are not removed to make exports executable.
