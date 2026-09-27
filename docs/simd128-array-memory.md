# 128-bit vector byte-array memory

Both backends implement packed-vector and scalar-offset byte-array operations
for these exact GHC vector shapes. Every carrier uses `SPECIES_128`.

| GHC shapes | JDK carrier | Scalar-offset stride |
| --- | --- | ---: |
| `Int8X16#`, `Word8X16#` | `ByteVector` | 1 byte |
| `Int16X8#`, `Word16X8#` | `ShortVector` | 2 bytes |
| `Int32X4#`, `Word32X4#` | `IntVector` | 4 bytes |
| `Int64X2#`, `Word64X2#` | `LongVector` | 8 bytes |
| `FloatX4#` | `FloatVector` | 4 bytes |
| `DoubleX2#` | `DoubleVector` | 8 bytes |

For each shape, `index<shape>Array#`, `read<shape>Array#` and
`write<shape>Array#` count **16-byte vectors**. Their
`<scalar>ArrayAs<shape>#` forms count **scalar elements** using the table's
stride. Every access covers a full sixteen bytes; incomplete tails are not
masked, and scalar offsets need not be vector-aligned.

Exact `VecRep` metadata keeps lane count and element representation, including
signedness. Signed and unsigned families can share a physical carrier without
sharing type proofs. Explicit unpack preserves each lane's signed/unsigned
scalar interpretation; memory transfers preserve its bits.

## Memory and lowering contract

Index accepts an immutable `ByteArray#` and `Int#`, returning the vector.
Read accepts `MutableByteArray# s`, `Int#` and `State# s`, returning
`(# State# s, vector #)`: two logical fields, not State plus scalar lanes.
Write accepts the mutable array, index, exact vector and State, returning State.
Both backends check State before access or result publication.

Transfers use typed Vector API `fromMemorySegment`/`intoMemorySegment`
operations in native byte order. Access is non-atomic. Owned heap and pinned
allocations retain owner locking, lifetime, mutability and logical-size checks,
including after shrink. Reads cannot overlap a managed pointer cell; stores
invalidate completely overwritten cells and reject partial overlaps.
Host byte arrays use array-backed segments with physical bounds checks.
Negative indices, scaling overflow and incomplete ranges fail before access;
invalid-range stores cannot partially change bytes. Native GHC invalid-offset
behavior is not an oracle for managed bounds failures.

Mutable vector reads require an immediate, exact registered tuple case with
ordered State/vector pattern binders and an unused whole-tuple binder.
The binders must be unlifted, non-coercion values; lane counts, constructor
arity, lexical identities and producer/whole-binder annotations are checked.
Returning the primitive's whole read tuple directly remains unsupported.

This intrinsic restriction is distinct from [guest vector transport](simd.md),
which supports arguments/results, tuple leaves, joins, PAP prefixes and owned
captures/heap fields. Public host vector arguments/results and unboxed-tuple
results remain unsupported. [Address operations](simd128-address-memory.md)
and [wider byte-array vectors](simd-wide-array-memory.md) have separate contracts.

## Reproducible checks

The Haskell producers preserve genuine Core, strict audits, native inputs and
source/artifact hashes. Independent Kotlin models decode bytes and model writes;
the four family-specific producers also compare native results with their
Haskell integer/byte model.

| Fixture selector | Covered shapes | JVM test classes |
| --- | --- | --- |
| `simd128-arrays` | Signed/unsigned 8-, 16- and 64-bit lanes | `Simd128ArrayNativeTest`, `Simd128ArrayProofTest` |
| `int32x4-bytearray` | `Int32X4#` | `SimdInt32ByteArrayTest`, `Int32VectorMemoryProofTest` |
| `word32x4-bytearray` | `Word32X4#` | `SimdWord32ByteArrayTest`, `Word32VectorMemoryProofTest` |
| `floatx4-bytearray` | `FloatX4#` | `SimdFloatByteArrayTest`, `FloatVectorMemoryProofTest` |
| `doublex2-bytearray` | `DoubleX2#` | `SimdDoubleByteArrayTest`, `DoubleVectorMemoryProofTest` |

After the [pinned build setup](../README.md#build-and-run), a full native run
requires a supported GHC/LLVM host. Use the checkout's build lease:

```sh
cabal run exe:thc-fixtures --offline -- simd128-arrays
for family in int32x4-bytearray word32x4-bytearray floatx4-bytearray doublex2-bytearray; do
  cabal run exe:thc-fixtures --offline -- "$family"
done
./gradlew --max-workers=2 --continue \
  testDefault --tests 'thc.runtime.Simd128Array*' --tests 'thc.runtime.Simd*ByteArrayTest' --tests 'thc.runtime.*VectorMemoryProofTest' \
  testDense --tests 'thc.runtime.Simd128Array*' --tests 'thc.runtime.Simd*ByteArrayTest' --tests 'thc.runtime.*VectorMemoryProofTest'
```

On ARM64, `simd128-arrays` automatically exports pre-Tidy Core without native
execution. Pass `--export-only` to each of the other four selectors for the
same preparation policy used by `scripts/prepare-tests.sh`. Their repeatable
`--ghc-option=OPTION` forwards and records explicit code-generation options.
Export-only provenance has null native fields and makes no native or post-Tidy
claim; it is not a fallback after native failure.

The suites cover packed/scalar offsets, aliases, all output bytes, bounds and
State failures, and no mutable use after unsafe freeze. Floating controls
separate finite values, signed zeros, infinities and quiet-NaN payloads from
signaling-NaN observations: scalar movement may quiet signaling NaNs, and
arithmetic does not promise payload preservation.

JVM checks use both backends and inlining modes, source-proven entry counts,
active installed-target validity and clean handoff storage. First-compiled-call
checks do not use settling calls or retries. Native/model agreement does not
establish packed machine instructions, allocation elimination or performance.
