# Byte arrays

Both backends support managed byte arrays, scalar typed accesses and copies.
The [primop checklist](primops.md) lists available operations; [Int](int-arrays.md),
[Double](double-arrays.md) and [Float/Word](float-word-arrays.md) accesses share
this storage with element indices of their respective widths.

`ByteArray#` and `MutableByteArray# s` are each one unlifted boxed reference.
Guest allocations use an owner containing heap or native storage; host-supplied
primitive `byte[]` values retain their typed fast paths. Neither representation boxes individual bytes. They are
not unboxed tuples. Allocation and freeze return logical
`(# State# s, reference #)` results: two logical components, zero storage for
the State# component, and one physical reference destination. Saturated
primitive expressions write that destination directly. Ordinary tuple return
transport applies when a library worker returns the result across a call.
Lowering checks State, unlifted references, physical carriers, saturation and
logical result shape. Integral scalar annotations share `Long`; strict exporter
audits independently check exact source-level representations.

Standalone launchers use native backing by default, so guest byte arrays can pass
their original storage to native C functions. Set `-Dthc.byteArrayStorage=heap`
through JVM options to choose heap backing explicitly.

Embedded contexts default to heap storage. Embedders may select native backing
at creation with `allowExperimentalOptions(true)`, `allowNativeAccess(true)` and
`option("thc.ByteArrayStorage", "native")`. The option is context-local and
requires native authority before the context initializes. It does not enable
any additional C ABI.

With native backing, ordinary guest allocations and resize replacements use the
existing automatic FFM arena ownership. Unsafe freeze/thaw keep the same owner;
compact copies and imported compact byte storage use the same creation policy.
Physical native backing is distinct from GHC's strong-pinned contract: ordinary
native-backed arrays report weak-pinned true, strong-pinned false, and remain
compactable. Explicit pinned allocations retain their strong guarantee and
cannot be compacted. Shrink retains the allocation; growth returns a new,
strong-unpinned prefix copy as required by GHC.

No foreign call copies, promotes, or pins an existing array. Raw host `byte[]`
ingress remains heap-backed, including its existing resize path, and still cannot
be passed to C implementations requiring a native pointer. Static literal images
retain their existing separate transport. Native arrays follow the lifetime of
their owners and views, not context closure; numeric pointer bits do not root
them. Each allocation incurs native-memory/FFM cleanup bookkeeping instead of a
JVM byte array.

Writes and copies evaluate all their operands, including the state expression, before the
effect. Core case evaluation preserves ordering. Freeze returns the same object
without a copy, matching GHC's shared mutable/immutable heap representation.
Array contents are never marked compilation-final. Word8 indexing reads one
byte at a byte offset and returns a canonical unsigned value from 0 through 255;
writes retain the low eight bits. Size is the owner's current logical byte count,
independent of backing capacity and native allocation rounding. All managed
accesses observe a shorter bound after [logical shrink](resize-bytearrays.md).

`copyByteArray# :: ByteArray# -> Int# -> MutableByteArray# s -> Int# -> Int# -> State# s -> State# s`
returns only the zero-width state token. Both references retain exact unlifted
boxed proofs. Fixed-child AST nodes and a typed bytecode operation evaluate all
six operands before copying. Raw arrays use `System.arraycopy`; owned allocations
copy their existing segments while preserving managed pointer cells. Both
ranges are checked at full width: nonnegative offsets/count, offsets at
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
does not authorize unsafe alias misuse. Owned storage also supports the separate
[pinned/address operations](pinned-memory.md); comparison does not expose or
convert that storage into a native pointer.

The primitive contracts come from the pinned
[GHC primop declarations](https://gitlab.haskell.org/ghc/ghc/-/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Builtin/primops.txt.pp),
whose ByteArray documentation states that freeze does not copy and both variants
share the same heap structure. `thc-primops coverage` also checks the advertised
names and arities against the installed GHC API, and records its signatures.

`compareByteArrays# :: ByteArray# -> Int# -> ByteArray# -> Int# -> Int# -> Int#`
compares equal-length byte ranges using unsigned byte ordering. Only the sign of
the result is contractual; THC does not promise GHC's particular nonzero magnitude.
The fixed-child AST node and typed bytecode operation return primitive Long values.
All five operands are evaluated in order, including for zero-length comparisons.
Both ranges are checked at full Long width before narrowing: nonnegative offsets
and length, offsets no greater than size, and length no greater than
`size - offset`. Empty ranges at either endpoint are valid. Comparing overlapping
ranges of the same immutable array is valid and does not mutate storage.

Raw/raw comparisons keep `Arrays.compareUnsigned`. If either argument is an
allocation owner, comparison uses `MemorySegment.mismatch` on the existing
storage and reads the two unsigned bytes at the first mismatch. It does not
snapshot either range, copy immutable images, pin heap arrays, or expose a raw
storage alias. Full logical-range and pointer-cell overlap checks run before
the scan, even for aliases or an early mismatch; a pointer outside the compared
range is harmless. Shrunk owners use their current logical size.

Both owners remain locked through validation and comparison. Comparisons and
copies share ascending identity-hash ordering and the same collision lock,
including opposed operations. Mixed raw/owned comparisons retain the owner
lock without changing the raw-array concurrency contract. The FFM path has a
Truffle boundary to keep cold bounds/error formatting out of guest partial
evaluation; the typed raw/raw path is unchanged.
