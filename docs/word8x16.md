# Local Word8X16 vectors

The bounded contract is exactly six GHC 9.14.1 primitives:
`packWord8X16#`, `unpackWord8X16#`, `broadcastWord8X16#`,
`plusWord8X16#`, `minusWord8X16#`, and `timesWord8X16#`.
GHC provides no `negateWord8X16#`. The exact vector representation is
`VecRep 16 Word8ElemRep`, with sixteen `Word8#`/`Word8Rep` lanes.
Signed `Int8Rep` or `VecRep 16 Int8ElemRep` metadata is incompatible even though
the payload has the same number of bits.

Pack takes **one logical sixteen-component unboxed tuple** and unpack returns
that scalar-lane tuple. Arithmetic wraps modulo 256, including low-byte products
and unsigned subtraction underflow. Widened lanes are 0..255: a high byte such
as 255 must not become -1. Vectors remain local; vector formals, function results,
captures, heap fields, and vector-containing tuple ABIs remain unsupported.

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
