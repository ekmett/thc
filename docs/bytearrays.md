# Managed ByteArray coverage

The bounded runtime supports the GHC 9.14.1 operations `newByteArray#`,
`writeWord8Array#`, `unsafeFreezeByteArray#`, `sizeofByteArray#`, and
`indexWord8Array#` on AST and bytecode. The ordinary public
`Data.ByteString.Short.pack`, `length`, and `unpack` workload executes the
installed bytestring `$wpack`/`$wgo` bodies and the original, source-exported
`GHC.Internal.List.$wlenAcc`. Preparation requires those exact dependencies and
all five primitives to remain reachable; it does not substitute library bodies.
The [Int-array extension](int-arrays.md) adds `readIntArray#`, `writeIntArray#`,
and `indexIntArray#` over the same backing storage, with element rather than
byte offsets.

`ByteArray#` and `MutableByteArray# s` are each one unlifted boxed reference,
represented directly by a mutable JVM primitive `byte[]`, with no wrapper
object or per-byte boxing. They are
not unboxed tuples. Allocation and freeze return logical
`(# State# s, reference #)` results: two logical components, zero storage for
the State# component, and one physical reference destination. Saturated
primitive expressions write that destination directly. Ordinary tuple return
transport applies when a library worker returns the result across a call.
Exact state/reference/word representations and saturation are checked at load
time and by the strict auditor.

Writes evaluate all their operands, including the state expression, before the
effect. Core case evaluation preserves ordering. Freeze returns the same object
without a copy, matching GHC's shared mutable/immutable heap representation.
Array contents are never marked compilation-final. Word8 indexing reads one
byte at a byte offset and returns a canonical unsigned value from 0 through 255;
writes retain the low eight bits. Size is the requested byte count, independent
of native heap allocation rounding.

The defined workload domain uses nonnegative representable sizes, initialized
bytes, valid offsets, and no mutation through an alias after unsafe freeze.
Managed size checks reject negative values and values above `Int.MAX_VALUE`;
allocation can still fail because of JVM limits or available memory. Reads and
writes reject offsets outside `[0, size)` with `RuntimeFault`, without truncating
large offsets. Native results for invalid accesses are not compared. Java's
initial zeroes are not a promise about native uninitialized memory. This slice
does not support raw pointers, pinned arrays, FFI, concurrent access, or unsafe
alias misuse.

The primitive contracts come from the pinned
[GHC primop declarations](https://gitlab.haskell.org/ghc/ghc/-/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Builtin/primops.txt.pp),
whose ByteArray documentation states that freeze does not copy and both variants
share the same heap structure. `scripts/primop-coverage.py` also checks all five
names and arities against the installed GHC API, and records its signatures.

Run `compiler/build.sh`, then `python3 scripts/prepare-bytearray.py` and
`scripts/gradle.sh test --tests thc.runtime.ByteArrayTest`. Preparation exports
pre/post-Tidy source fixtures, exports the pinned List source, and records hashes
of sources, exporter, auditor, capability manifest, Core artifacts, and native
oracle. The tests compare 2,058 real native rows against an independent unbounded
integer byte/checksum model, then execute each row before and after compilation
on both backends. Every checked compiled invocation must enter installed guest
code and leave its warmed host/active entry valid. Unsupported traps and
blackholes must remain zero. CI repeats the suite with dense handoff enabled.

The inputs cover lengths 0–32, all byte values including NUL and bytes above 127,
signed endpoints, and wrapping arithmetic. `orderedBytes` additionally checks
separate allocations and repeated writes before freezing. Focused controls check
storage identity, exact length, bounds, state-operand evaluation before effects,
no result publication on state failure, and malformed or partial primitive
applications. This is correctness evidence, not a throughput measurement.
