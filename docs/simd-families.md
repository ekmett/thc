# Generated SIMD families

The repository capability table advertises these 47 generated **local** vector
operations after native GHC validation in [PR #90](https://github.com/ekmett/thc/pull/90).
It does not advertise a vector calling convention. Ordinary CI exports their
real scalar-entry Core, strictly audits every operation against the canonical
table, and runs a 48-row finite smoke on interpreted and explicitly compiled
AST and bytecode paths. The large native/model corpus remains an explicit
experiment rather than work repeated on every pull request.

The declarative table is `scripts/simd-families.json`. It currently describes
local pack, unpack, broadcast and arithmetic for Word64X2, Word32X8, Int32X8 and Int32X16,
plus Int64X2 multiplication, FloatX4/DoubleX2 negation and division, and
FloatX8/DoubleX4 pack, unpack, broadcast, add, subtract, multiply, negate and divide. Existing
carrier class names and memory operations remain unchanged. Vector arguments,
returns, captures, heap fields and join boundaries remain unsupported.

The generator emits six concrete carriers with final primitive fields, typed
AST nodes and exact proof checks under `build/generated/simd`. Each arithmetic
method uses a fixed Vector API species. Two marked regions in the existing
bytecode loader/root contain concrete operation calls and specializations;
normal builds check these regions without rewriting source files.

Refresh the checked regions and validate the pinned GHC machine contracts:

```sh
python3 scripts/generate-simd-families.py --write --verify-ghc
python3 scripts/test-simd-families.py
```

The GHC check uses `primOpSig`, `typePrimRep_maybe` and the tuple TyCon API on the
selected fixed signatures. It compares exact vector/lane PrimReps and logical
tuple positions, rather than interpreting printed Haskell types. Unsupported
runtime-polymorphic signatures are never passed to a partial placement API.

Prepare the ordinary compact smoke without native code generation:

```sh
python3 scripts/prepare-simd-capability-smoke.py
./gradlew --no-daemon test --tests thc.runtime.SimdCapabilitySmokeTest --rerun
```

The preparer requires GHC 9.14.1, verifies its 47 machine signatures, and
checks all 25 reachable scalar entries with the normal capability contract.
The JVM test checks every new carrier through six representative entries,
including each floating composite operation and first/last lane, without
requiring native code generation or AVX512. It verifies the actual compiled
target graph and exact guest-entry counts; its finite rows do not replace the
separate native edge corpus.

Prepare the larger scalar-entry experiment without native code generation:

```sh
python3 scripts/prepare-simd-families.py --export-only
```

This produces pre-Tidy Core and 84,162 independent model rows. Integer arithmetic uses mathematical modular
arithmetic. Floating arithmetic uses rational arithmetic with ties-to-even
rounding for this fixed corpus; arithmetic NaNs are normalized. JVM behavior
follows Java Vector API semantics, without a general GHC bit-equivalence claim
for NaN payloads or platform-specific edge cases. Every selected lane is
observed separately. One dynamic scalar selector per new floating shape keeps
all six operations and every lane in the same compiled entry graph. OPAQUE
workers provide real residual calls without a vector calling convention.

A full native preparation omits `--export-only` on a suitable GHC9.14.1 x86 host.
Target flags may be passed explicitly as `--ghc-option=...`; the manifest records
them, toolchain identity, generated sources, exact inputs, oracle and exported
Core. Run the prepared experiment explicitly with
`./gradlew --no-daemon simdFamiliesExperimentTest --rerun` after full
native preparation. Ordinary `test` keeps the fixture-free carrier and proof
checks; it excludes the four prepared experiment methods. The native JVM gates
require both pre/post Core and byte-identical native/model TSVs. Early wider-shape
gates use the prepared model and are named separately. Both preserve exact
guest-entry, actual-target identity/validity and
input/result-pool cleanup checks.
