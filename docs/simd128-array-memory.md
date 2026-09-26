# Integer 128-bit vector byte-array memory

ByteArray memory supports Int8X16, Word8X16, Int16X8, Word16X8,
Int64X2 and Word64X2, in addition to the existing four 128-bit families.
For each shape, `index<shape>Array#`, `read<shape>Array#` and
`write<shape>Array#` count **16-byte vectors**. Their
`<scalar>ArrayAs<shape>#` forms count **scalar elements**: 1, 2 or 8 bytes.
Every access covers a full 16 bytes; incomplete tails are not masked.

Both AST and bytecode lowering retain exact vector species and VecRep
signedness. Memory transfers use raw ByteVector, ShortVector or LongVector
carriers and native byte order. Scalar unpacking supplies sign/zero extension;
signed and unsigned stores move the same bits. Owned allocations retain their
monitor, logical shrink bounds and pointer-cell protections. Host byte arrays
retain physical bounds checks. Invalid offsets cannot wrap into valid storage.
The [Addr# counterparts](simd128-address-memory.md) use the same vector carriers.
Neither API promises allocation-free execution.

`Simd128ArrayAudit.hs` is ordinary Haskell using all 36 primops, with every
byte initialized before access. Its Haskell producer preserves original Core,
strict per-entry audits, native LLVM binary/output and source/artifact hashes.
The native corpus has 2,304 rows spanning packed/scalar offsets and signed
limits. Kotlin independently decodes bytes and models writes; direct tests also
compare entire arrays, including untouched prefixes/tails and frozen aliases.
The compiled tests use both backends and inlining modes, with exact first-call
counts and active installed-target validity, without settling calls.

Run `cabal run exe:thc-fixtures --offline -- simd128-arrays`, then
`./gradlew test --tests 'thc.runtime.Simd128Array*'`. Repeat the test task with
`JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true`. Normal Build and Fast fixture
selection prepare the same inputs.

Native LLVM/post-Tidy evidence is checked on little-endian 64-bit x86.
As with the existing SIMD fixtures, ARM64 preparation explicitly provides
pre-Tidy Core only; Kotlin executes the same independently modeled inputs.
Its manifest records null native rows, never fabricated native observations.
