# Managed ByteArray coverage

The bounded runtime supports the GHC 9.14.1 operations `newByteArray#`,
`writeWord8Array#`, `unsafeFreezeByteArray#`, `sizeofByteArray#`,
`indexWord8Array#`, `copyByteArray#`, and `compareByteArrays#` on AST and bytecode. The ordinary public
`Data.ByteString.Short.pack`, `length`, `unpack`, and repeated `uncons` workloads execute the
installed bytestring `$wpack`/`$wgo`/`uncons`/`$wuncons` bodies and the original, source-exported
`GHC.Internal.List.$wlenAcc`. Preparation requires those exact dependencies and
all five original primitives to remain reachable, plus `copyByteArray#` in both
the uncons roundtrip and direct copy workload; it does not substitute library bodies.
The [Int-array extension](int-arrays.md) adds `readIntArray#`, `writeIntArray#`,
and `indexIntArray#` over the same backing storage, with element rather than
byte offsets.
The [Double-array extension](double-arrays.md) uses the same backing storage and
typed Double result destinations for `readDoubleArray#`, `writeDoubleArray#`,
and `indexDoubleArray#`.

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

Writes and copies evaluate all their operands, including the state expression, before the
effect. Core case evaluation preserves ordering. Freeze returns the same object
without a copy, matching GHC's shared mutable/immutable heap representation.
Array contents are never marked compilation-final. Word8 indexing reads one
byte at a byte offset and returns a canonical unsigned value from 0 through 255;
writes retain the low eight bits. Size is the requested byte count, independent
of native heap allocation rounding.

`copyByteArray# :: ByteArray# -> Int# -> MutableByteArray# s -> Int# -> Int# -> State# s -> State# s`
returns only the zero-width state token. Both references retain exact unlifted
boxed proofs. Fixed-child AST nodes and a typed bytecode operation evaluate all
six operands before copying. The managed implementation uses `System.arraycopy`
after checking both ranges at full width: nonnegative offsets/count, offsets at
most the array size, and count at most `size - offset`. It does not add offset
and count before validation or narrow unchecked values. Zero-length copies at
the ends are valid. GHC explicitly forbids the same array in immutable and mutable
states as source and destination; THC rejects that identity even for empty or
disjoint ranges. This operation makes no overlapping-copy support claim.

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
share the same heap structure. `scripts/primop-coverage.py` also checks the advertised
names and arities against the installed GHC API, and records its signatures.

Run `compiler/build.sh`, then `python3 scripts/prepare-bytearray.py` and
`scripts/gradle.sh test --tests thc.runtime.ByteArrayTest`. Preparation exports
pre/post-Tidy source fixtures, exports the pinned List source, and records hashes
of sources, exporter, auditor, capability manifest, Core artifacts, and native
oracle. The tests compare 4,116 real native rows against an independent unbounded
integer byte/checksum model, then execute each row before and after compilation
on both backends. Every checked compiled invocation must enter installed guest
code and leave its warmed host/active entry valid. Unsupported traps and
blackholes must remain zero. CI repeats the suite with dense handoff enabled.

The inputs cover lengths 0–32, all byte values including NUL and bytes above 127,
signed endpoints, and wrapping arithmetic. `orderedBytes` additionally checks
separate allocations and repeated writes before freezing. `shortUncons` rebuilds
a checksum by repeatedly calling the public operation until `Nothing`, including
empty strings and embedded 0/255. `copiedBytes` checks distinct initialized arrays,
dynamic source/destination offsets and counts, zero-length endpoint copies, source
preservation, and unchanged destination bytes outside the copied range. Native
results match independent unbounded integer/list models. Invalid ranges and alias
misuse are managed rejection controls only, never native oracle inputs.

Focused controls check storage identity, exact length, bounds, state-operand evaluation before effects,
no writes/result publication on state failure, exhaustive small contained copy
ranges, full-width invalid ranges, forbidden alias identity, exact proofs for
all six copy operands and its State# result, and malformed or partial primitive
applications. This is correctness evidence, not a throughput measurement.

`compareByteArrays# :: ByteArray# -> Int# -> ByteArray# -> Int# -> Int# -> Int#`
compares equal-length byte ranges using unsigned byte ordering. Only the sign of
the result is contractual; THC does not promise GHC's particular nonzero magnitude.
The fixed-child AST node and typed bytecode operation return primitive Long values.
All five operands are evaluated in order, including for zero-length comparisons.
Both ranges are checked at full Long width before narrowing: nonnegative offsets
and length, offsets no greater than size, and length no greater than
`size - offset`. Empty ranges at either endpoint are valid. Comparing overlapping
ranges of the same immutable array is valid and does not mutate storage.

`prepare-compare-byte-arrays.py` exports genuine pre/post-Tidy public
ShortByteString `Ord`, `isPrefixOf`, and `isSuffixOf` workloads plus direct range
and alias controls. The 3,027 native rows are checked against independent
unsigned-list models; all ten entry audits must retain `compareByteArrays#` and
the installed `$wpack` body with no missing globals or unsupported operations.
`CompareByteArraysTest` runs every row on both backends with inlining enabled and
disabled, requiring per-row compiled guest entry, valid original and observed
active targets, and empty input/result loans. Synthetic controls cover every
unsigned byte pair, contained small ranges, endpoint empties, operand failure,
full-width invalid ranges, wrong storage carriers, and exact proof/saturation
rejection. Invalid domains are tested only against THC, never by invoking native
undefined behavior. This adds comparison coverage; it does not change the
existing exception-library frontiers or permit mutable alias misuse.
