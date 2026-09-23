# Local Int8X16 vectors

This bounded contract covers exactly seven GHC 9.14.1 primitives:
`packInt8X16#`, `unpackInt8X16#`, `broadcastInt8X16#`,
`plusInt8X16#`, `minusInt8X16#`, `negateInt8X16#`, and
`timesInt8X16#`. The vector representation is exactly
`VecRep 16 Int8ElemRep`; each scalar lane is `Int8#`/`Int8Rep`.
Equal total bit width does not make another vector or scalar type compatible.

Pack takes **one logical sixteen-component unboxed tuple** and unpack returns
that scalar-lane tuple. Each arithmetic operation wraps modulo 256, including
low-byte multiplication and negation of -128. Widening sign-extends. These
fixtures keep vectors local: no vector formal, function result, capture, heap
field, or vector-containing tuple ABI is enabled. The explicit `vectorArgument`
negative control is rejected specifically for its vector formal.

## Genuine Core and independently observable lanes

`compiler/test-fixtures/SimdInt8X16.hs` uses the actual pinned primops.
`plusCase`, `minusCase`, `timesCase`, and `negateCase` each have two machine
`Int#` inputs and a machine `Int#` checksum result on a required 64-bit host.
The sixteen lanes before narrowing are:

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

The checksum widens every lane before multiplying by its distinct weight.
Weights sum to 438, so the checksum magnitude is at most 56,064; even the
residual scalar helper's added 48 keeps its magnitude below 2^16. The arithmetic
therefore fits safely in signed 64-bit host results. Affine input arithmetic can
wrap at machine width; subsequent low-byte reduction makes the arbitrary-
precision model equivalent.

`packCase` checks pack/unpack roundtripping and `broadcastCase` observes all
sixteen copies of narrowed `a-b+29`; both have arity two. The broadcast operand
uses scalar `plusInt8#` with a genuine folded `int8` literal 29, so both exported
Core stages exercise canonical narrow literals without adding another root.

`laneCase` has arity four: `operation, lane, a, b`. Selectors 0..5 choose plus,
minus, times, negate, pack, or broadcast; lanes 0..15 select one signed result.
Each branch finishes its vector work and returns a scalar. The declared corpus
uses only those selector domains.

Every lane's left and right operands depend on different seeds through odd
coefficients. The Python model inverts those coefficients modulo 256, producing
the complete 9-by-9 operand grid
`[-128,-127,-65,-1,0,1,65,126,127]` for each lane and operation.
Broadcast rows arrange each boundary value at every lane directly. This gives
7,776 independent lane observations in addition to the checksums, exposing lane
permutations and errors that could otherwise cancel in a sum.

## Residual scalar and sixteen-lane tuple controls

`scalarHelperCase` calls opaque `scalarWorker`, which computes the plus checksum
and adds 31; its caller adds 17, giving `plusChecksum+48`. `tupleHelperCase`
calls opaque `tupleWorker`, receives the sixteen actual scalar `Int8#` lanes
from vector multiplication, and forms its checksum in the caller. Neither
worker accepts or returns a vector. The direct graph entries have no optimizer
fences; only residual workers and the negative boundary control are opaque.

Preparation proves one actual guest root for each direct entry, including
`laneCase`, and exactly two for each helper entry in both Core stages. It checks
closure membership, absence of hidden local lambdas, saturated unconditional
helper calls with the original arguments, exact machine-Int formals and results,
all sixteen tuple leaves, and exact vector-primitive counts. These are also the
expected per-invocation compiled guest counts with Truffle inlining enabled or
disabled, excluding the public host bridge. Static counts alone do not prove
compiled JVM execution.

## Reproduction and limits

Use the pinned environment and wrap the preparation command with the shared
resource gate for this checkout's `build` directory:

```sh
python3 scripts/test-int8x16-model.py
python3 scripts/prepare-int8x16-audit.py
```

Each of the eight arity-two entries has 174 seed pairs covering byte sign/wrap
boundaries, all eight bit positions and neighbors, varied high bits, and signed
64-bit endpoints. Together with `laneCase`, this gives 9,168 native/model rows.
The model uses integer modulo/sign arithmetic and is checked against a separate
mask/sign implementation for every row. Its tests cover all 256 lane encodings,
all 65,536 signed-byte scalar products, each lane's full operand grid, lane order,
malformed tuple widths/leaf types, and hidden or changed guest-root boundaries.

Preparation regenerates original pre/post-Tidy Core, requires every positive
audit to have zero issues and zero missing globals, and requires each negative
audit to have precisely one vector-formal issue and no missing globals. It
compiles `SimdInt8X16Native.hs` using GHC's native code generator and compares
complete unique native rows against the independent model. This path, including
`timesInt8X16#` and the sixteen-lane scalar tuple return, succeeds with pinned
GHC 9.14.1 on x86-64. No primop is silently dropped or substituted.

`build/simd-int8x16/provenance.json` records the original entry names, arities,
cases, actual Core root proofs/counts, literal and vector inventories, toolchain
identity, commands, and source/artifact hashes. `oracle.tsv` contains native
results; `expected.tsv` contains the independent model results.

`--export-only` is pre-Tidy/model-only for hosts without working native vector
code generation. It runs no native binary or post-Tidy export and records null
`nativeRows` and `modelMatched`. It removes stale oracle/provenance claims before
starting. A successful export-only run is not native conformance.

Native agreement and strict static audits do not establish JVM compiled
execution, allocation elimination, packed machine instructions, or performance.
Those require the separate runtime tests and retained graph/final-LIR evidence.
In particular, byte multiplication may lower through wider lanes or scalar
operations; the presence of `timesInt8X16#` is not proof of packed byte multiply.
