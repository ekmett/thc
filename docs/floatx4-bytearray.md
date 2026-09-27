# FloatX4 ByteArray operations

Both backends implement these six GHC 9.14.1 operations with exact
`VecRep 4 FloatElemRep` metadata and raw `FloatVector.SPECIES_128` values.

| Operations | Index stride | Access width |
| --- | ---: | ---: |
| `indexFloatX4Array#`, `readFloatX4Array#`, `writeFloatX4Array#` | 16 bytes | 16 bytes |
| `indexFloatArrayAsFloatX4#`, `readFloatArrayAsFloatX4#`, `writeFloatArrayAsFloatX4#` | 4 bytes | 16 bytes |

Index takes `ByteArray#` and `Int#`, returning `FloatX4#`. Read takes
`MutableByteArray# s`, `Int#` and `State# s`, returning
`(# State# s, FloatX4# #)`. Write takes the mutable array, index, exact
vector and State token, returning State. The read result has two logical
fields, not State plus 4 scalar lanes.

## Memory and lowering contract

Access is native-endian and non-atomic. The complete sixteen-byte range must
fit the array's current logical size; scalar-element offsets need not be
vector-aligned. Negative indices, scaling overflow and out-of-range accesses
fail before memory access. Invalid-range stores cannot partially change bytes.
Native GHC behavior outside its valid-index preconditions is not an oracle for
managed bounds failures. Both backends evaluate and check State before reading,
writing or publishing a result.

The runtime uses the Vector API's typed `fromMemorySegment` and
`intoMemorySegment` operations with native byte order. Owned heap and pinned
allocations share this route, with owner locking, lifetime, mutability and
managed-pointer-cell checks. Reads cannot overlap a managed pointer cell;
stores invalidate completely overwritten cells and reject partial overlaps.
The raw `ByteArray` compatibility path uses an array-backed segment. Logical
bounds still apply after an owned allocation shrinks.

Mutable vector reads have a specific lowering restriction: the primitive must
be consumed by one immediate, exact registered tuple case. Its ordered
State/vector pattern binders and unused whole-tuple binder must be unlifted,
non-coercion values. Exact integer lane counts, constructor arity, lexical
identities and the producer/whole-binder annotations are checked. Returning
the primitive's whole read tuple directly remains unsupported.

That intrinsic rule is distinct from the [guest SIMD transport contract](simd.md):
guest vector arguments/results, tuple leaves, joins, PAP prefixes, captures
and owned heap fields are supported. Public host vector arguments/results and
unboxed-tuple results remain unsupported. Corresponding address operations
have their own [memory contract](simd128-address-memory.md); this byte-array
fixture is not their validation.

## Fixtures and checks

The genuine GHC fixture exercises all six operations, every safe offset in
64-byte storage, and every output byte after stores. No mutable access follows
`unsafeFreeze`. Coupled rotations cover the declared offset and lane domains;
they are not an exhaustive Cartesian product of all lane bit patterns.

Raw-bit fixtures use existing Word32/Float scalar-array aliases to observe
finite values, signed zeros, subnormals, infinities and selected quiet-NaN
payloads. Their 6,720 portable rows are separate from
640 selected native signaling-NaN observations. Java permits signaling
NaNs to quiet during scalar movement; neither scalar copy/pack/unpack nor
floating arithmetic promises portable signaling-NaN payload preservation.
Finite graph witnesses are separate from these raw-bit observations.

Use the pinned toolchain and the [shared resource gate](contributing.md):

```sh
cabal run exe:thc-fixtures -- floatx4-bytearray
./gradlew --max-workers=2 --continue \
  testDefault --tests 'thc.runtime.SimdFloatByteArrayTest' --tests 'thc.runtime.FloatVectorMemoryProofTest' \
  testDense --tests 'thc.runtime.SimdFloatByteArrayTest' --tests 'thc.runtime.FloatVectorMemoryProofTest'
```

The Haskell `SimdByteArrayFixtures` producer exports genuine pre/post-Tidy
Core, checks exact positive roots and frontier diagnostics, compares native
rows with its independent integer/byte model, and records source/artifact
hashes and command exits. `SimdByteArrayCorpus` supplies independent Kotlin
inventory and model controls in the native test class. Strict Core audits use
`audit-core.py`; the fixture's deliberately mutated metadata controls are
labeled separately from original Core and native inputs.

`--export-only` emits pre-Tidy/model evidence with native fields explicitly
null; it is the ARM policy in `scripts/prepare-tests.sh`, not a native-success
fallback. Repeatable `--ghc-option=OPTION` records and forwards explicit
code-generation options to exports and native builds, for example
`--ghc-option=-fllvm`. Previous receipts and recorded artifacts are retained
before canonical outputs are replaced.

The JVM suite selects stages from provenance, tests AST and bytecode with
inlining on/off, and checks exact source-proven guest-entry deltas, installed
target identity and validity. First-compiled-call checks do not use settling
calls or retries. Model agreement alone does not establish JVM compilation,
packed instructions, allocation elimination or performance.

## Historical evidence

The [archived FloatX4 memory checkpoint](../research/floatx4-bytearray-checkpoint.md)
preserves the original command descriptions, source revisions, failures and
graph/LIR results. Those results apply to their recorded sources and hosts,
not automatically to the current segment-backed runtime. Current use of the
Vector API does not by itself prove packed-code survival, absence of spills,
allocation-free execution or behavior on another architecture.
