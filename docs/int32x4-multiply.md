# Local signed Int32X4 multiplication

This page retains the original multiplication checkpoint and its measurements.
Current execution uses raw `IntVector.SPECIES_128` values, not an `Int32X4`
wrapper. Historical local-only restrictions below are superseded by the
[current SIMD representation and transport contract](simd.md).

This bounded slice adds only `timesInt32X4#` to the existing local signed
Int32X4 contract. Pinned GHC 9.14.1 gives the exact signature
`Int32X4# -> Int32X4# -> Int32X4#`. Genuine fixtures also use existing
`packInt32X4#` and `unpackInt32X4#`, with one logical four-`Int32#` tuple for
packing and the corresponding scalar tuple result for unpacking.

The vector proof remains `VecRep 4 Int32ElemRep`; each scalar lane is exactly
`Int32Rep`, not `Word32Rep`. Products retain the low 32 bits and interpret them
as signed two's-complement values before widening to the required 64-bit host
`Int#`. In particular, MIN * -1 yields MIN, MAX * MAX yields 1, and MIN * MIN
yields 0. No vector function arguments, results, captures, heap fields or
vector-containing tuple ABIs are enabled.

The original runtime reused the four final `int` fields of `Int32X4` and its
fixed 128-bit `IntVector` species. Its multiplication used `IntVector.mul`,
extracting four signed low-word results. Current multiplication retains the raw
vector result; scalar extraction belongs to explicit unpack or heap storage.
Both the AST loader and a dedicated bytecode
operation enforce two exact, unlifted signed vector operands. The existing
scalar-literal and aggregate policies are unchanged. Loader tests reject
wrong signedness, width, arity, liftedness and malformed signed literals;
direct arithmetic tests compare independent lane products with a masked
arbitrary-precision oracle, including MIN * -1 and MAX * 2.

## Genuine arithmetic and independent observations

`compiler/test-fixtures/SimdInt32X4Multiply.hs` is separate from existing signed
and unsigned vector fixtures. The only graph root, `timesCase`, has two machine
`Int#` seeds and one machine `Int#` checksum result.

| Lane | Left | Right | Checksum weight |
| ---: | --- | --- | ---: |
| 0 | a | b+2 | 3 |
| 1 | b | a-3 | 5 |
| 2 | a+1 | 7*b+13 | 7 |
| 3 | b-1 | 11*a-17 | 11 |

Inputs narrow to signed 32 bits. The third left lane uses scalar `plusInt32#`
with `intToInt32# 1#`, preserving a genuine folded `int32` literal without an
extra entry or vector operation. Every result lane widens via `int32ToInt#`
before weighting. Weights sum to 26: checksums lie between -55,834,574,848 and
55,834,574,822; adding the scalar helper's 48 stays below 2^36 in magnitude.
Machine input wrap is harmless under later low-32-bit reduction. The independent
model uses arbitrary-precision integers; separate test formulas explicitly wrap
the machine input arithmetic and implement signed narrowing with masks/XOR.

`laneCase` has arity three: `lane, a, b`. Only lane selectors 0..3 belong to the
declared domain. It computes the same local multiplication and returns one
independently sign-extended lane. Left/right affine coefficients are odd and
depend on independent seeds, so modular inverses produce the full 9-by-9 grid
`[-2147483648,-2147483647,-65537,-1,0,1,65537,2147483646,2147483647]` at every
selected lane. The resulting 324 observations include positive and negative
extremes, sign-bit products and MIN * -1, independently of weighted checksums.

The scalar corpus has 466 distinct pairs spanning all 32 power-of-two
neighborhoods, signed boundaries, machine extremes and large positive/negative
inputs. Three arity-two entries plus the lane observations give exactly 1,722
native/model rows. Model tests also sample 65,536 distinct encodings with both
16-bit halves covered bijectively, testing nine signed boundary products each.
This is 589,824 scalar products, not all 2^32 encodings or 2^64 binary pairs.

## Actual roots and rejected boundaries

`scalarHelperCase` calls opaque `scalarWorker`, which forms the multiplication
checksum and adds 31; the caller adds 17. `tupleHelperCase` calls opaque
`tupleWorker`, receiving four actual scalar `Int32#` product lanes, and forms
the checksum in the caller. No vector crosses either helper boundary.

Preparation proves one actual guest root for `timesCase` and `laneCase`, and
two for each helper entry, before and after Tidy. The proof requires exact
closure membership, no hidden lambda or alias, unconditional saturated helper
calls with original scalar inputs, machine-Int formals/results, exact signed
tuple leaves and two packs/one multiply/one unpack per reachable entry.
The full pre/post × AST/bytecode × inlining-on/off corpus checks 13,776 compiled
invocations and 21,232 guest entries per handoff mode, excluding the host bridge.
Actual JVM tests separately require the exact per-call increments, stable active
target identities, valid installed code and released handoff pools.

The genuine `vectorArgument` negative must yield exactly one vector-formal issue
and no missing globals. Two separately labeled metadata mutations change a
`timesCase` pack tuple to `Word32Rep` or its first vector operand to
`VecRep 4 Word32ElemRep`. They are not original Core or native oracle inputs.
`unsignedLaneTuple` requires one vector-argument, five aggregate-proof and four
scalar-proof issues. `unsignedVectorOperand` requires one vector-argument, two
aggregate-proof and one vector-result issue. Both require zero missing globals.

## Preparation and limits

Run `python3 scripts/test-int32x4-multiply-model.py` and
`python3 scripts/prepare-int32x4-multiply-audit.py` with the pinned environment
and shared resource gate. Preparation exports real pre/post Core, requires all
eight positive audits to have zero issues and missing globals, checks the exact
negatives and closure counts, then compiles/runs the genuine GHC NCG oracle.
The complete keyed output must equal the independent model; duplicate or
wrong-arity rows fail.

`build/simd-int32x4-multiply/provenance.json` uses vector identity
`int32x4-multiply`, records commands/toolchain/entry cases/actual root counts,
and retains `signedUnsignedNegativeControls` separately from genuine Core.
Seventeen source hashes and seven artifact hashes cover both Core stages,
their audits, expected/native rows and `native/int32x4-multiply-oracle`.

`--export-only` provides pre-Tidy Core and model rows only, with null native
row/match fields and no native or post-Tidy claim. It removes stale oracle and
provenance outputs; it is not a fallback after a native failure. A fresh full
preparation restores native evidence after testing this mode.

These fixtures alone establish no JVM compilation, packed instructions,
allocation elimination, performance, vector ABI support or execution on other
architectures. Those require independently retained runtime and graph/LIR checks.

## Compiled-code evidence

The [x86-64 evidence](../bench/experiments/int32x4-multiply/evidence-x86_64/README.md)
retains four actual pre/post × AST/bytecode multiplication captures at runtime
`597ed24ee8835606437a7cdeb313e28b726d4762`. Each observes all four signed result
lanes and checks 466 native rows twice after compilation. Every selected graph
has one live i32x4 multiply and four result-connected SignExtend32-to-64 chains;
final allocated LIR has one physical XMM `VPMULLD`. All four captures pass the
original checker, without correction or guest replay.

No local carrier, vector payload allocation, field traffic, fallback call or
intermediate lane box survives in these selected inlined graphs. The public
Long result box remains; both bytecode graphs also retain precisely checked
virtual frame-tag metadata. These are bounded code-generation observations,
not throughput, no-spill, globally allocation-free or cross-platform claims.
Instrumented tests separately retain the exact compiled-entry checks above.

Four original committed unsigned Word32 multiplication graphs have the same
host arity. Each passes its unsigned checker but fails the full signed checker
specifically because the result lanes zero-extend rather than sign-extend.
They are independent negative controls, not signed native execution evidence.
