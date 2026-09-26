# Scalar unaligned memory

Both runtime backends lower all 84 scalar GHC 9.14.1 operations in the
index/read/writeWord8ArrayAs<T># and index/read/writeWord8OffAddrAs<T># families.
The pinned generator includes Char, WideChar, Int, Word, Addr, Float, Double,
StablePtr, Int16, Int32, Int64, Word16, Word32 and Word64. It excludes Int8 and
Word8; use the ordinary byte operations for those types. Vector and atomic
operations are separate families.

These operations count **bytes**, independently of the payload width. For
example, writeWord8ArrayAsInt64# array 1# value state starts its eight-byte store
at byte one. The ordinary writeInt64Array# array 1# value state starts at byte
eight. OffAddrAs operations also allow negative byte displacements from an
interior or one-past address when the entire access remains in its allocation.

Numeric values use native byte order. Narrow signed reads sign-extend; unsigned
reads zero-extend. Int#/Word# use the pinned 64-bit target width. Char accesses
one byte and WideChar four. Float and Double preserve defined raw IEEE bits,
including signed zero and quiet NaN payloads; signaling NaNs have no portable
bit-copy guarantee. There is no additional alignment restriction.

Lowering uses the existing typed array and address nodes with a constant
byte-offset mode. Integral values share the Long carrier without redundant
RuntimeRep identity checks. Arity, actual carrier distinctions, state tokens,
vector exclusion, and aggregate shape and order are checked. The exporter
auditor separately checks the exact original GHC representations.

Numeric arrays accept ordinary host byte arrays and allocation-owned guest
storage. Addresses use the existing bounded literal, managed and owned-native
storage paths, with full-range validation and native lifetime/context checks.
Owned native malloc remains restricted to the verified Linux x86_64 ABI.

Addr and StablePtr memory cells use retained ManagedAddress references inside
allocation-owned storage, including unaligned cells. They do not fabricate
process pointer bits. Raw-exposed arrays and native storage cannot hold these
managed cells; the capability checklist therefore continues to classify this
family as partial. Scalar reads cannot inspect pointer bits, partial overwrites
fault, and complete writes or copies preserve the existing cell invalidation and
ownership rules. A stored StablePtr retains its opaque handle; dereferencing it
still checks the originating context and explicit free/disposal.

## Example and checks

[UnalignedScalarMemoryAudit.hs](../compiler/test-fixtures/UnalignedScalarMemoryAudit.hs)
is an executable example for every scalar type. Each entry writes through the
array alias, reads through both views, writes a distinct value through a
one-past address with a negative displacement, and indexes both views. Sentinel
bytes detect writes outside the intended range. Its native driver owns and frees
the real StablePtr inputs.

Run the Haskell producer once, then reuse those artifacts in both handoff modes:

    cabal run exe:thc-fixtures --offline -- unaligned-scalar-memory
    ./gradlew --no-daemon test --tests thc.runtime.UnalignedScalarMemoryTest
    JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew --no-daemon test --rerun \
      --tests thc.runtime.UnalignedScalarMemoryTest

The producer verifies the complete inventory against installed GHC 9.14.1,
exports unmodified pre/post-tidy Core, retains strict audits, and records native
commands, exit status and source/artifact hashes. Kotlin checks all native rows
with a separate native-endian model, then exercises both interpreters and the
first installed calls with exact compiled-entry increments and target identity
checks. Storage tests cover boundary and overflowing offsets, empty inputs,
unaligned pointers, aliases, raw exposure, stale handles and foreign contexts.
The existing Float/Double, Int16/Word16 and Int32/Word32 byte-offset suites remain
regression controls.
