# Local Word8X16 vectors

The original slice measured on this page covers six GHC 9.14.1 primitives:
`packWord8X16#`, `unpackWord8X16#`, `broadcastWord8X16#`,
`plusWord8X16#`, `minusWord8X16#`, and `timesWord8X16#`.
GHC provides no `negateWord8X16#`. The exact vector representation is
`VecRep 16 Word8ElemRep`, with sixteen `Word8#`/`Word8Rep` lanes.
Signed `Int8Rep` or `VecRep 16 Int8ElemRep` metadata is incompatible even though
the payload has the same number of bits.

Pack takes **one logical sixteen-component unboxed tuple** and unpack returns
that scalar-lane tuple. Arithmetic wraps modulo 256, including low-byte products
and unsigned subtraction underflow. Widened lanes are 0..255: a high byte such
as 255 must not become -1. These measurements keep vectors local. Current
transport also supports exact vector arguments/results, PAP prefixes, joins,
tuple fields, owned captures and boxed constructor fields; see the
[current vector contract and limits](primops.md#current-aggregate-and-address-limits).
The [checklist](primops.md) records later additions to the operation family.

The runtime stores exactly sixteen final primitive `byte` fields in a distinct
`Word8X16` carrier: 16 bytes of lane payload, not total object size. Transient
`ByteVector.SPECIES_128` values implement bitwise-equivalent wrapping arithmetic;
there are no stored vector objects, generic payload arrays or per-lane boxes.
Pack narrows sixteen Long slots to raw bytes; both AST and bytecode unpack each
field with `& 0xffL`. Numeric operation dispatch is selected while lowering Core.

Canonical `word8` values and alternatives are decimal 0..255. Intrinsic unsigned
identity refines absent or genuine UNKNOWN/null metadata, but signed, wrong-width
and aggregate metadata remain errors. Exact unlifted flags and all sixteen tuple
leaf proofs are checked before execution; byte storage never licenses an Int8
operand or result in a Word8 operation.

The JVM tests independently cover all 65,536 byte pairs in every lane and signed/
unsigned mismatch controls in both loaders and diagnostic modes. The native corpus
runs at both Core stages on AST/bytecode with inlining on/off. Every measured
invocation requires its exact 1/2 compiled guest-entry delta, stable actual target
identities, valid last-tier code and released argument/result handoff pools. With
the full native package this is 61,696 compiled calls and 64,512 guest entries per
handoff mode. No settling, retries or compiler-limit changes are part of the tests.

## Genuine Core and independent observations

`compiler/test-fixtures/SimdWord8X16.hs` uses the real pinned primops.
The graph roots `plusCase`, `minusCase`, and `timesCase` have two machine `Int#`
seeds and a machine `Int#` checksum result on a required 64-bit host. Each lane
narrows its affine input through `int2Word#` and `wordToWord8#`:

| Lane | Left | Right | Checksum weight |
| ---: | --- | --- | ---: |
| 0 | a | b+2 | 3 |
| 1 | b | a-3 | 5 |
| 2 | a+1 | 7*b+13 | 7 |
| 3 | b-1 | 11*a-17 | 11 |
| 4 | a+127 | b+128 | 13 |
| 5 | b-128 | a-127 | 17 |
| 6 | 3*a+7 | 13*b+19 | 19 |
| 7 | 5*b-11 | 17*a-23 | 23 |
| 8 | 7*a+29 | 23*b+61 | 29 |
| 9 | 9*b-31 | 25*a-67 | 31 |
| 10 | 11*a+37 | 27*b+71 | 37 |
| 11 | 13*b-41 | 29*a-73 | 41 |
| 12 | 15*a+43 | 31*b+79 | 43 |
| 13 | 17*b-47 | 33*a-83 | 47 |
| 14 | 19*a+53 | 35*b+89 | 53 |
| 15 | 21*b-59 | 37*a-97 | 59 |

Each checksum lane widens through `word8ToWord#` and then `word2Int#` before
multiplication by its distinct weight. Weights sum to 438, so results lie in
0..111,690; the scalar residual helper's added 48 keeps its result below 2^17.
The weighted arithmetic safely fits signed 64-bit host results. Affine seed
arithmetic can wrap at machine width, but subsequent low-byte reduction gives
the same result as the independent arbitrary-precision model.

`packCase` roundtrips the sixteen distinct lanes. `broadcastCase` broadcasts
unsigned `a-b+29` modulo 256; both also have arity two. Broadcast uses scalar
`plusWord8#` and a folded genuine `word8` literal 29, so the original exported
Core exercises unsigned literal parsing in both stages.

`laneCase` has arity four: `operation, lane, a, b`. Operations 0..4 select plus,
minus, times, pack, or broadcast; lanes 0..15 select one zero-extended result.
Every branch finishes its local vector operation and returns only a scalar.
The declared corpus uses only those selector domains.

For every lane, left and right depend on independent seeds with odd affine
coefficients. The model inverts those coefficients modulo 256 to test the full
9-by-9 operand grid `[0,1,2,63,127,128,129,254,255]` independently at each
lane and operation. Broadcast rows arrange each boundary value directly. This
gives 6,480 individual lane observations, supplementing weighted checksums with
observations that cannot hide incorrect lane ordering or sign extension.

## Residual roots and negative controls

`scalarHelperCase` calls opaque `scalarWorker`, which computes the plus checksum
and adds 31; the caller adds 17, yielding `plusChecksum+48`. `tupleHelperCase`
calls opaque `tupleWorker`, receiving sixteen actual scalar `Word8#` lanes from
vector multiplication, and forms the checksum in the caller. No vector crosses
either helper boundary. Direct graph roots have no optimizer fences.

Preparation proves one actual guest root for each direct entry, including
`laneCase`, and exactly two per residual helper entry, before and after Tidy.
Checks require exact closure membership, no hidden local lambda or alias,
saturated unconditional helper calls with original scalar inputs, machine-Int
formals/results, all sixteen unsigned tuple leaves, and exact vector-primitive
counts. The same counts are required with Truffle inlining enabled or disabled,
excluding the public host bridge; static proof alone is not compiled execution.

The genuine `vectorArgument` negative has a Word8X16 formal and must fail each
stage's audit with exactly one vector-formal issue and no missing globals.
Two additional controls deliberately mutate copies of original `plusCase`
metadata: `signedLaneTuple` replaces all sixteen tuple leaves with `Int8Rep`,
and `signedVectorOperand` substitutes `VecRep 16 Int8ElemRep` on one operand.
Their exact argument/result/tuple/scalar mismatch counts are checked and retained
in `signedUnsignedNegativeControls`. These copies are explicitly labeled as
mutations, are never substituted for original Core, and never enter the native
oracle. GHC itself would not typecheck those mismatches.

## Preparation and evidence limits

Use the pinned environment and the shared resource gate for this checkout's
`build` directory:

```sh
python3 scripts/test-word8x16-model.py
python3 scripts/prepare-word8x16-audit.py
```

The seven arity-two entries each have 176 seed pairs covering unsigned byte
boundaries, all eight bit positions and neighbors, negative/high-bit machine
inputs, and signed 64-bit endpoints. Together with `laneCase`, the corpus has
7,712 native/model rows. Ten model/proof tests cross-check every row against a
separate mask-based implementation, cover all 256 byte encodings and 65,536
unsigned-byte products, require every lane to observe 255 rather than -1, and
exercise tuple width/identity, hidden roots, and mutation-copy isolation.

Preparation exports original pre/post-Tidy Core, requires all sixteen positive
audits to have no issues or missing globals, checks the negative controls, then
compiles `SimdWord8X16Native.hs` and compares every unique row with the model.
Pinned GHC 9.14.1's x86-64 native code generator supports this fixture, including
`timesWord8X16#` and the sixteen-Word8 scalar tuple return.

`build/simd-word8x16/provenance.json` retains entry names/arities/cases, actual
root proofs/counts, exact vector and literal inventories, negative controls,
toolchain identity, commands, and source/artifact hashes. `oracle.tsv` stores
native results; `expected.tsv` stores independent model results.

`--export-only` records pre-Tidy/model-only evidence, with null `nativeRows`
and `modelMatched`. It performs no native code generation or post-Tidy export,
and removes stale oracle/provenance claims before starting. This supports the
bounded AArch64 path without claiming native conformance there.

Native agreement and static audits do not establish JVM compiled execution,
allocation elimination, packed instructions, or performance. Those require
separate runtime tests and retained graph/final-LIR evidence. In particular,
unsigned low-byte multiplication may lower through wider lanes; no packed-byte
multiply instruction is implied by the GHC primitive's name.

## Retained compiled-code evidence

The [x86-64 evidence](../bench/experiments/word8x16-foundation/evidence-x86_64/README.md)
retains twelve actual pre/post × AST/bytecode × plus/minus/times captures at
runtime `cf0f03df51346bfdd96a104f952ad37eef99d88a`. All passed their first graph
check. Every one of the sixteen result lanes feeds an exact unsigned 8-to-64-bit
extension. Addition/subtraction use packed XMM byte instructions; multiplication
uses two packed XMM short products with checked byte reconstruction. No local
vector payload, carrier or lane box survives in these selected inlined graphs.
The public Long result box and virtual deoptimization metadata are explicitly
allowed; this is not a globally allocation-free or throughput claim.

The complete JVM suite passes 467 tests in each of default and dense-handoff
modes, with zero failures, errors or skips. All 194 suite reports and both logs
are retained alongside the native and compiled-code evidence, including the
initial focused diagnostic-expectation failure and its test-only correction.
