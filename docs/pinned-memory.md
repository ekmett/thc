# Managed pinned memory and bounded MD5 calls

This slice supports `newPinnedByteArray#`, `newAlignedPinnedByteArray#`,
`byteArrayContents#`, `readWord8OffAddr#`, `writeWord8OffAddr#` and `keepAlive#`
in both backends. Byte loads/stores retain GHC 9.14.1's exact `Word8Rep`, not
`WordRep`. Existing address arithmetic and character loads work on either
immutable literal bytes or mutable byte-array backing.

Addresses hold strong backing references and a checked Long offset. Aliases and
unsafe-frozen byte arrays observe the same storage. Immutable literals retain
their trailing NUL and cannot be written. Full-width bounds are checked before
narrowing or effects. One-past addresses are valid only for empty ranges.
Mutable contents are never compilation-final.

Pinning and power-of-two alignment are logical properties of managed storage,
not physical JVM heap pinning or native process pointers. There is no address
to integer conversion, arbitrary memory dereference, allocation/free FFI, or
general foreign-call machinery in this slice.

`keepAlive#` preserves a lifted kept reference without forcing it, validates
State before the action, and invokes the continuation non-tail with exactly
one logical State argument. A Java reachability fence follows actual return or
throw. Scalar results follow the existing call-root WHNF convention; tuple
components retain their own evaluatedness. Existing tuple and binary-sum result
ABIs are reused, without extending aggregate inputs, captures or vector ABIs.

## Three closed foreign contracts

The compiler now exports structured `app[6].foreignCall` metadata from GHC's
`FCallId`, retaining the original variable ID. The adapter accepts only static
function targets in unit `ghc-internal`, convention `ccall`, safety `unsafe`:

| Symbol | Logical arguments | Logical result |
| --- | --- | --- |
| `__hsbase_MD5Init` | Addr, State | singleton State tuple |
| `__hsbase_MD5Update` | Addr, Addr, Int32, State | singleton State tuple |
| `__hsbase_MD5Final` | Addr, Addr, State | singleton State tuple |

Descriptors, actual operands, saturation, flags and result layout must agree.
There is no name-parsing fallback or ordinary Haskell-name interception. Other
foreign calls retain their unsupported-global boundary. Wrong contracts for
these known symbols fail explicitly, including main-unit declarations, safe or
interruptible calls, CApi, dynamic targets and wrong CInt width.

The port follows the pinned GHC 9.14.1 public-domain `md5.c`/`md5.h`, preserved
with their original notice under `bench/experiments/pinned-addresses/reference`.
The entire 88-byte context is guest-visible: four hash words, two counter words
and 64 scratch bytes. Init leaves scratch untouched; Final emits 16 bytes then
clears the context. Native-endian context words, little-endian MD5 input and the
original Haskell Fingerprint's separate big-endian Storable layout are distinct.
No Java digest object or hidden context-identity table substitutes for this ABI.
All range and memcpy-overlap checks precede effects; negative/out-of-range CInt
lengths reject. The MD5 kernel is an explicit Truffle boundary, not a claim of
fully guest-inlined hashing or a cryptographic security recommendation.

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
driver. It checks 558 cases, 2,247 full memory snapshots, seeded counter carries,
padding boundaries, defined aliases and 540 independently checked digests. JVM
tests consume those snapshots, copy/resume visible contexts, exercise both
compiled adapters and check malformed State/ranges/metadata. Synthetic adapter
tests remain labeled synthetic rather than relabeled main-unit exports.

First failures and reviewed corrections are retained in the checkpoint evidence;
no settling retries, compiler-policy changes or reduced compiled-entry checks
are part of these gates.
