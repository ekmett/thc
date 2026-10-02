# SIMD families and guest transport

The [capability contract](../bin/core-capabilities.json) admits 30 exact
`VecRep` shapes. Their fixed-species raw JDK representation is described in
[SIMD execution and storage](simd.md).

| Lane family | Supported lane counts | Vector widths |
|---|---|---|
| Signed/unsigned 8-bit | 16, 32, 64 | 128, 256, 512 bits |
| Signed/unsigned 16-bit | 8, 16, 32 | 128, 256, 512 bits |
| Signed/unsigned 32-bit | 4, 8, 16 | 128, 256, 512 bits |
| Signed/unsigned 64-bit | 2, 4, 8 | 128, 256, 512 bits |
| Float | 4, 8, 16 | 128, 256, 512 bits |
| Double | 2, 4, 8 | 128, 256, 512 bits |

Both backends support exact guest arguments/results, PAP prefixes, tail
transfers, local calls, join arguments/results and same-frame captures, unboxed
tuple fields, nonrecursive unlifted lets, owned closure/thunk captures and boxed
constructor fields. Supported sums retain exact vector payloads. The
[Core host ABI](site/embedding.md#load-a-core-entry) accepts and returns raw
JDK vectors with the declared species. Recursive or lifted vector lets remain
unsupported. Transport does not add an operation merely because its
representation is admitted.

## Generated operations

[`bin/simd-families.json`](../bin/simd-families.json) declares generated
arithmetic, pack/unpack, broadcast, insertion, extrema, division and shuffle
families, complementing handwritten foundations. The
[primop checklist](primops.md) records implemented names. Individual
[array](simd-wide-array-memory.md) and [address](simd-address-families.md) guides
state their own memory-operation scope.

The generator emits typed AST nodes and proof checks under
`build/generated/simd`, using raw Vector API values of the fixed species.
Two marked bytecode regions contain corresponding concrete specializations;
normal builds verify them without rewriting source. Pack uses scalar lanes,
unpack writes typed locals, and ordinary arithmetic returns its raw vector
result. Integer quotient/remainder use typed scalar-lane helpers; this is not
an integer SIMD-divide instruction claim.

Pack/unpack with at least sixteen lanes groups immutable local-slot metadata
into one bytecode operand. Lane values still use typed primitive reads/writes.
Activation transport owns a raw reference; durable heap storage owns primitive
lane fields.

Refresh checked regions and verify pinned GHC machine contracts with:

```sh
python3 bin/generate-simd-families.py --write --verify-ghc
python3 bin/test-simd-families.py
```

`--write` changes the marked source regions deliberately; ordinary Gradle
builds use `--check`. The GHC verification compares exact PrimReps and logical
tuple positions through `primOpSig`, `typePrimRep_maybe` and the tuple TyCon
API, not printed Haskell types.

## Compact smoke and full experiment

The compact smoke derives its operations from the family table and compares
composite contracts with the canonical capability declaration. Its default
native oracle uses scalar lanes and does not require native AVX512 code:

```sh
python3 bin/prepare-simd-capability-smoke.py
./gradlew --no-daemon test --tests thc.runtime.SimdCapabilitySmokeTest --rerun
```

The ordinary arithmetic suite observes every lane and includes signed and
unsigned boundaries, addition/multiplication wraparound and subtraction across
both signed limits. Floating extrema also have separate Java NaN/signed-zero
controls. `SimdFamiliesTest` checks malformed literal, carrier and vector proofs
without preparing Core fixtures.

`SimdCallNativeTest` covers vector calls, PAPs, captures and unpacked scalar tuple
returns. Its signed Int32 and unsigned Word8 tuple cases preserve a residual
call and check the first installed execution against native/model results.
The separate array/address suites own memory and state-token behavior. Graph
inspection remains necessary for performance claims.

Prepare the larger scalar-entry experiment without native code generation:

```sh
python3 bin/prepare-simd-families.py --export-only
```

Its independent model uses modular integer arithmetic and rational floating
arithmetic with ties-to-even rounding. Arithmetic NaNs are normalized; no
universal GHC bit-equivalence is claimed for NaN payloads or platform-specific
edges. Each selected lane is observed separately.

For native evidence, omit `--export-only` on a suitable pinned GHC host. Explicit
`--ghc-option=...` flags are recorded with toolchain identity, generated source,
inputs, native oracle and exported Core. Then run:

```sh
./gradlew --no-daemon simdFamiliesExperimentTest --rerun
```

Ordinary tests retain fixture-free vector/proof checks and exclude the four
tagged prepared experiment methods. The native gates require both Core stages
and byte-identical native/model TSVs. Separately named wider-shape controls use
the prepared model. Both retain exact guest-entry, active-target and handoff
cleanup checks. Use `python3 bin/prepare-simd-families.py --check-only`
to verify an existing manifest's input/artifact hashes without regenerating it.
