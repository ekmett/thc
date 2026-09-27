# Signed Int32X4 multiplication

Both backends implement `timesInt32X4# :: Int32X4# -> Int32X4# -> Int32X4#`
with `IntVector.mul`, retaining a raw `IntVector.SPECIES_128` result.
The exact vector proof is `VecRep 4 Int32ElemRep`; pack/unpack uses one
logical four-`Int32#` tuple with `Int32Rep` leaves, not `Word32Rep`.

Each product retains its low 32 bits. Explicit unpack sign-extends those bits
to the scalar carrier: MIN × −1 yields MIN, MAX × MAX yields 1, and MIN × MIN
yields 0. Scalar extraction belongs to unpack or durable heap storage, not
each arithmetic operation. Wrong signedness, width, arity or liftedness is
rejected at lowering.

The [SIMD transport contract](simd.md) covers guest arguments/results, tuple
leaves, joins, PAP prefixes and owned captures/heap fields. Public host vector
arguments/results remain unsupported.

## Reproducible checks

With the pinned environment and checkout's build lease:

```sh
python3 scripts/test-int32x4-multiply-model.py
python3 scripts/prepare-int32x4-multiply-audit.py
./gradlew --max-workers=2 --continue \
  testDefault --tests 'thc.runtime.SimdInt32MultiplyTest' \
  testDense --tests 'thc.runtime.SimdInt32MultiplyTest'
```

The model test uses hash-verified genuine pre/post-Tidy Core and also checks
fresh exports when present, so it can run before preparation. The producer
exports `SimdInt32X4Multiply.hs`, checks exact reachable shapes and compares
fresh native GHC output with an independent arbitrary-precision integer model.
Scalar-host roots observe each lane and weighted checksums through direct,
scalar-helper and tuple-helper paths. Boundary controls include overflow,
negative products and independent signed narrowing.

The genuine `vectorArgument` negative tests public host admission, not guest
vector formals. Separately labeled metadata mutations test signed/unsigned
proof mismatches; they are not original Core or native oracle inputs.

`build/simd-int32x4-multiply/provenance.json` records commands, toolchain,
source/artifact hashes, input domains, actual guest-root counts and mutation
controls. `--export-only` emits pre-Tidy/model inputs with null native fields,
not native or post-Tidy evidence. ARM preparation in `scripts/prepare-tests.sh`
uses that mode.

The JVM suite checks both backends, inlining modes and handoff configurations,
with exact per-call compiled entries, stable active targets, valid installed
code and released handoff storage. Native/model agreement alone does not prove
packed instructions, allocation elimination or performance.
