# Generated SIMD families

The initial generated-family checkpoint in
[PR #90](https://github.com/ekmett/thc/pull/90) admitted 47 local vector operations
and used a 48-row finite smoke on interpreted and compiled AST/bytecode paths.
The counts and measurements below describe that checkpoint. Current operations
and transport limits are recorded in the [capability contract](../scripts/core-capabilities.json)
and [generated checklist](primops.md#current-aggregate-and-address-limits).
The large native/model corpus remains an explicit experiment rather than work
repeated on every pull request.

The declarative table is `scripts/simd-families.json`. At that checkpoint it described
local pack, unpack, broadcast and arithmetic for Word64X2, Word32X8, Int32X8 and Int32X16,
plus Int64X2 multiplication, FloatX4/DoubleX2 negation and division, and
FloatX8/DoubleX4 pack, unpack, broadcast, add, subtract, multiply, negate and divide. Existing
carrier class names and memory operations were unchanged. Current transport
supports 24 exact `VecRep` shapes through arguments/results, PAP prefixes, joins,
tuple fields, owned closure/thunk captures and boxed constructor fields.
Recursive or lifted vector let bindings, sum fields and public host vector
arguments/results remain outside that contract.

The initial generator emitted six concrete carriers with final primitive fields, typed
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

Prepare the ordinary compact smoke with a native scalar oracle, without native
vector code generation:

```sh
python3 scripts/prepare-simd-capability-smoke.py
./gradlew --no-daemon test --tests thc.runtime.SimdCapabilitySmokeTest --rerun
```

At the initial checkpoint the preparer verified 47 GHC 9.14.1 machine signatures
and 25 reachable scalar entries; the JVM test used six representative entries.
The current preparer derives the operations and corpus from the declarative
table and checks every composite against the canonical capability contract.
Its native scalar oracle does not require AVX512. The JVM test verifies the actual compiled
target graph and exact guest-entry counts; its finite rows do not replace the
separate native edge corpus.

Prepare the larger scalar-entry experiment without native code generation:

```sh
python3 scripts/prepare-simd-families.py --export-only
```

The initial experiment produced pre-Tidy Core and 84,162 independent model rows. Integer arithmetic uses mathematical modular
arithmetic. Floating arithmetic uses rational arithmetic with ties-to-even
rounding for this fixed corpus; arithmetic NaNs are normalized. JVM behavior
follows Java Vector API semantics, without a general GHC bit-equivalence claim
for NaN payloads or platform-specific edge cases. Every selected lane is
observed separately. At that checkpoint, one dynamic scalar selector per new
floating shape kept all six operations and every lane in the same compiled entry
graph. OPAQUE workers provided real residual calls through scalar entries; those measurements
do not establish the separate vector transport contract.

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
