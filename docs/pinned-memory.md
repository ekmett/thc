# Managed pinned memory and bounded MD5 calls

This slice supports `newPinnedByteArray#`, `newAlignedPinnedByteArray#`,
`byteArrayContents#`, `mutableByteArrayContents#`, `readWord8OffAddr#`, `writeWord8OffAddr#`,
`readAddrOffAddr#`, `writeAddrOffAddr#`, `keepAlive#` and `touch#`
in both backends. Byte loads/stores retain GHC 9.14.1's exact `Word8Rep`, not
`WordRep`. Existing address arithmetic and character loads work on either
immutable literal bytes or mutable byte-array backing.

Addresses hold strong backing references and a checked Long offset. Aliases and
unsafe-frozen byte arrays observe the same storage. Immutable literals retain
their trailing NUL and cannot be written. Full-width bounds are checked before
narrowing or effects. One-past addresses are valid only for empty ranges.
Mutable contents are never compilation-final.

`mutableByteArrayContents#` returns a managed address directly from a mutable
array without copying or first freezing it. It requires an exact unlifted object
operand and an `AddrRep` result, with no State argument or tuple result. Both
contents operations preserve the same backing allocation and offset identity;
addresses keep that storage alive and retain pointer-cell protections. This is
stable managed address access, not a physical JVM address or native heap pin.

Pinned `ByteArray#` values have one allocation owner shared by frozen values
and every address alias. An owner stores managed `Addr#` references in sparse
pointer cells; OffAddr pointer offsets count pointer-sized elements, while the
owner's lookup/write API uses byte offsets. A pointer cell's bytes are not a
fabricated host address: raw reads and partial overwrites reject before any
change. Complete byte copies between pinned owners preserve references, and
complete fills invalidate them. A Sulong buffer view and managed pointer cells
cannot coexist in one allocation because raw C writes cannot track references.
Handing out a raw byte-array alias likewise prevents later pointer-cell writes.
Ordinary unpinned byte arrays remain raw `byte[]`; each pinned allocation adds
one owner object and creates its cell map only on the first pointer write.
Pinned owner accesses synchronize to order pointer-cell changes. Ordinary
unpinned arrays retain their direct byte-array fast path. Concurrent raw writes
to one guest array remain outside this narrow contract.
Scalar byte-array reads and writes inspect only their touched range, so a
disjoint numeric field remains usable beside a pointer cell. Vector operations
on a pinned array still require a raw view and therefore cannot mix with
pointer cells in that allocation.
An aligned full-width numeric overwrite releases the replaced pointer;
partial overlap fails before changing bytes or references.

Pinning and power-of-two alignment are logical properties of managed storage,
not physical JVM heap pinning or native process pointers. There is no address
to integer conversion, arbitrary memory dereference, allocation/free FFI, or
general foreign-call machinery in this slice.

`readWord32OffAddr#`, `readWordOffAddr#`, `readInt32OffAddr#` and
`readIntOffAddr#` also read managed literal or byte-array storage. Their offsets
count four-byte or eight-byte elements on the pinned 64-bit target. Negative
offsets from a derived address are valid when the complete element remains
inside its allocation. Multiplication overflow and partial elements reject
before reading; Word32 results zero-extend and Int32 results sign-extend into
the runtime's Long carrier. Reads use native byte order and observe intervening
writes through aliases, including after unsafe freeze. The exact State/payload
tuple keeps Word32, Word, Int32 and Int representation proofs distinct.

`scripts/prepare-managed-address-reads.py` prepares 1,800 native/model cases,
eight accepted pre/post-Tidy closure audits and 48 rejected genuine-Core proof
mutations. `ManagedAddressReadTest` checks those exports on AST and bytecode,
then checks compiled mutable reads, bounds/overflow, State-before-read order,
failure-before-publication and malformed loader proofs. Native inputs are
aligned live allocations; adversarial out-of-bounds inputs are managed-only
tests. These reads do not enable arbitrary native pointer dereferences.

`cabal run exe:thc-fixtures -- pinned-pointer-cells` exports the genuine
`newPinnedByteArray#`/freeze/contents/`keepAlive#` sequence at both Core stages,
with strict representation audits and seven native oracle inputs.
`PinnedPointerCellsTest` checks the same rows interpreted and explicitly
compiled on AST and bytecode. It also checks an interior pointer cell and a
separate numeric field; this does not turn arbitrary C pointer memory into
managed addresses.

`keepAlive#` preserves a lifted kept reference without forcing it, validates
State before the action, and invokes the continuation non-tail with exactly
one logical State argument. A Java reachability fence follows actual return or
throw. Scalar results follow the existing call-root WHNF convention; tuple
components retain their own evaluatedness. Existing tuple and binary-sum result
ABIs are reused, without extending aggregate inputs, captures or vector ABIs.

`touch#` preserves an exact lifted or unlifted reference until its State-thread
position, without entering a lifted thunk. It validates the State carrier before
issuing a Java reachability fence and returns bare State, not a singleton tuple.
Raw operand/result proofs and levity flags are checked before lowering; known
stored or intrinsic representations cannot be disguised by occurrence metadata.
This does not add weak pointers, finalizers or asynchronous exception semantics.

The existing `pinned-pointer-cells` native fixture exercises mutable contents
without a freeze, bidirectional array/address writes and an unlifted touch. A
separate root touches a lifted bottom without entering it. The combined fixture
keeps the pointer, byte and halfword observations: eleven inputs, nine roots and
ten TSV columns including the input. Genuine pre/post Core applications retain
their exact levity, operand and bare-State result proofs; malformed mutations
are rejected by both loaders. New compiled checks cover both backends with
inlining enabled and disabled, including active `runRW#` lambdas and split
callees. They require valid targets on the first and every installed invocation,
without post-installation settling or recompilation. Managed-only checks cover
escaped-address lifetime, pointer-cell protection and invalid offsets; native
tests never execute invalid pointer operations.

## Three closed foreign contracts

The existing compiler exporter supplies structured `app[6].foreignCall` metadata
from GHC's `FCallId`, retaining the original variable ID. The MD5 adapter accepts only static
function targets in unit `ghc-internal`, convention `ccall`, safety `unsafe`:

| Symbol | Logical arguments | Logical result |
| --- | --- | --- |
| `__hsbase_MD5Init` | Addr, State | singleton State tuple |
| `__hsbase_MD5Update` | Addr, Addr, Int32, State | singleton State tuple |
| `__hsbase_MD5Final` | Addr, Addr, State | singleton State tuple |

Descriptors, actual operands, saturation, flags and result layout must agree.
There is no name-parsing fallback or ordinary Haskell-name interception. Other
foreign calls retain their existing gates. Wrong contracts for
these known symbols fail explicitly, including main-unit declarations, safe or
interruptible calls, CApi, dynamic targets and wrong CInt width.

The implementation executes the pinned GHC 9.14.1 public-domain `md5.c`/`md5.h`
through Sulong 25.3.4.1. The original files and notice remain unchanged under
`bench/experiments/pinned-addresses/reference`. `scripts/build-cbits.py` checks
their pinned Git blob hashes and compiles them with a small byte-buffer ABI
adapter during the Gradle resource build. Clang and the installed GHC 9.14.1
headers are required. The compiler target must match the current Linux/macOS
64-bit host; a packaged runtime also rejects bitcode for a different platform.
`THC_CLANG` can select the compiler; no local bitcode is committed for other hosts.
The entire 88-byte context is guest-visible: four hash words, two counter words
and 64 scratch bytes. Init leaves scratch untouched; Final emits 16 bytes then
clears the context. Native-endian context words, little-endian MD5 input and the
original Haskell Fingerprint's separate big-endian Storable layout are distinct.
No Java digest object or hidden context-identity table substitutes for this ABI.
All range, four-byte MD5Context alignment and memcpy-overlap checks precede effects; negative/out-of-range CInt
lengths reject. The THC call is an explicit Truffle boundary; Sulong executes
the C body. This establishes no cross-language inlining or hashing-throughput
claim.

Each THC context initializes and caches its C executable values once. The
initial C load executes outside cache locks; concurrent callers wait for its
result. A byte-buffer interop view
shares the existing allocation and exposes typed, byte-addressed accesses;
array-element interop would give incorrect C word reinterpretation. C adapters
receive a canonical base and explicit byte offset, preserving zero-offset and
alias behavior without treating a buffer as named C struct members. Both keys
and values in the context's view cache are weak, so the cache cannot keep dead
allocations alive. View creation is synchronized, while C calls execute outside
that lock. Live calls strongly retain their views. Literals remain
read-only; no process pointer is invented and no payload is copied.

Embedding contexts that execute cbits must explicitly enable native access for
Sulong's runtime libraries (`allowNativeAccess(true)`); the command-line context
does so. The buffer protocol requires no host-object/member access. Other RTS,
libc and foreign entry points remain outside the exact descriptor gate. Full
public Fingerprint execution still depends on composing its original source
closure.

## Verification and remaining source frontier

`scripts/prepare-pinned-addresses.py` creates genuine pre/post-Tidy Core and
7,269 native/model rows. Of these, 6,387 exercise the primitive slice and are
checked on AST/bytecode with inlining enabled/disabled. Tests compile active
guest targets plus the host boundary, then check reversed native inputs with
exact guest-entry counts, target identity/validity and released handoff pools.
Twelve malformed-proof controls per stage start from accepted genuine Core.

The remaining 882 rows exercise native public Storable functions only. Their
original specialized peek/poke workers currently lack exported bodies in this
fixture. `fingerprintByte` is explicitly a byte-layout control, not a replacement
Haskell implementation. Full public Fingerprint/error execution must compose the
genuine source exports separately; this checkpoint does not claim that result.

`scripts/prepare-managed-md5.py` compiles the pinned original C with an ABI/layout
driver. It archives prior owned attempts intact in unique sibling directories;
ambiguous contents and symlinked output paths are rejected. It checks 558 cases,
2,247 full memory snapshots, seeded counter carries,
padding boundaries, defined aliases and 540 independently checked digests. JVM
tests consume those snapshots, copy/resume visible contexts, exercise both
compiled adapters and check malformed State/ranges/alignment/metadata. The real
GHC `fingerprintData` source proof exports all three original `ghc-internal`
FCallIds at pre- and post-Tidy stages, with no foreign-call audit issues. Its
full entry remains rejected for the IO host boundary and a missing specialized
Storable worker. Synthetic adapter
tests remain labeled synthetic rather than relabeled main-unit exports.

First failures and reviewed corrections are retained in the checkpoint evidence;
no settling retries, compiler-policy changes or reduced compiled-entry checks
are part of these gates.
