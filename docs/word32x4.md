# Word32X4 vectors

Both backends execute `VecRep 4 Word32ElemRep` with raw
`IntVector.SPECIES_128` values. An activation carries one exact vector
reference; owned captures and boxed constructor fields store primitive lanes
inside the enclosing heap object. Equal bit width does not make another vector
shape compatible.

The foundation fixture covers six GHC 9.14.1 operations:
`packWord32X4#`, `unpackWord32X4#`, `broadcastWord32X4#`,
`plusWord32X4#`, `minusWord32X4#`, `timesWord32X4#`.
GHC provides no unsigned vector negation primitive.
This is a fixture scope, not the complete operation inventory; see
[SIMD families](simd-families.md) and the [primop checklist](primops.md).

Pack takes one logical 4-component unboxed tuple; unpack returns that
scalar-lane tuple with exact `Word32Rep` leaves. Arithmetic wraps modulo
2^32, including low-32-bit multiplication and subtraction underflow.
Unpack zero-extends each lane to 0..4,294,967,295.
A high unsigned lane must not become a negative scalar.

Guest vector arguments/results, PAP prefixes, joins, tuple fields, nonrecursive
unlifted lets, owned captures and boxed constructor fields are supported.
Exact vector fields in supported sums and raw JDK vectors at the
[Core host boundary](site/embedding.md#load-a-core-entry) are also supported.
Recursive or lifted vector lets remain unsupported; see the
[SIMD transport contract](simd.md). The scalar-observation fixtures below
keep vectors local. Their `vectorArgument` negative checks the separate
auditor's scalar-entry restriction.

Canonical `word32` literals use 0..4,294,967,295. After lowering, integral
narrow annotations share an `Int` carrier and the literal tag supplies narrowing.
Machine-word and explicit 64-bit integers retain `Long` and are distinct carriers.
Wrong physical carriers, malformed literal values, lifted operand flags, tuple
lane proofs and vector shapes are checked separately. The strict exporter audit
also checks exact source-level representations; it is not the runtime's scalar
carrier contract.

`SimdWord32VectorTest` checks the raw carrier, lane arithmetic, exact shape and
literal controls on both loaders. Native/Core tests require exact per-call
compiled guest-entry counts, stable active targets, valid last-tier code and
released argument/result handoff pools, with Truffle inlining on and off.
No post-compilation settling calls or retries are part of those checks.
Its 65,536 constructed lane samples do not exhaust the 32-bit value space.

## Genuine Core and independent observations

`compiler/test-fixtures/SimdWord32X4.hs` uses the real pinned primitives.
The graph roots `plusCase`, `minusCase` and `timesCase` each have arity two.
Inputs narrow through `int2Word#` and `wordToWord32#`; all four result lanes
zero-extend through `word32ToWord#` and `word2Int#` before weighting.

| Lane | Left | Right | Checksum weight |
| ---: | --- | --- | ---: |
| 0 | a | b+2 | 3 |
| 1 | b | a-3 | 5 |
| 2 | a+1 | 7*b+13 | 7 |
| 3 | b-1 | 11*a-17 | 11 |

Weights sum to 26, bounding checksums by 111,669,149,670. The scalar helper's
added 48 keeps every result below 2^37, safely within signed 64-bit range.
Affine inputs can wrap at machine width, and unsigned lane products can exceed
the signed 64-bit range, but low-32-bit reduction agrees with the independent
arbitrary-precision model. Its separate test formulas explicitly wrap machine
input arithmetic before narrowing.

`packCase` roundtrips all four lanes. `broadcastCase` broadcasts unsigned
`a-b+29` modulo 2^32. Both have arity two. Broadcast uses scalar `plusWord32#`
with a folded genuine `word32` literal 29; preparation requires original
exported narrow literals in both Core stages, not synthetic replacements.

`laneCase` has arity four: `operation, lane, a, b`. Operations 0..4 select plus,
minus, times, pack or broadcast; lanes 0..3 select a single unsigned result.
Each branch finishes its local vector operation and returns only a scalar.
Only those selector domains belong to the corpus.

Each lane's left and right inputs depend on independent seeds with odd affine
coefficients. The model inverts these coefficients modulo 2^32, covering the full
9-by-9 operand grid `[0,1,2,1073741823,2147483647,2147483648,2147483649,4294967294,4294967295]`
at each lane and operation. Broadcast rows arrange each boundary directly.
These 1,620 individual observations prevent weighted checksums from hiding
lane-order or sign-extension errors.

The scalar corpus contains 466 distinct pairs from all thirty-two power-of-two
neighborhoods, unsigned boundaries, machine extremes and large signed seeds.
Seven arity-two entries contribute 3,262 rows, yielding exactly 4,882 native/model
rows. Model tests compare every row against separate lane formulas. A separate
65,536-word sample covers every 16-bit value in each half through two bijections;
nine boundary multipliers give 589,824 scalar product checks. Neither all 2^32
word encodings nor all 2^64 binary operand pairs are claimed to be exhausted.

## Residual roots and negative controls

`scalarHelperCase` calls opaque `scalarWorker`, which computes the plus checksum
and adds 31; the caller adds 17. `tupleHelperCase` calls opaque `tupleWorker`,
receiving four actual scalar `Word32#` multiplication lanes and forming the
checksum in the caller. No vector crosses either helper boundary. Direct graph
roots have no optimizer fences.

Preparation proves exactly one actual guest root per direct entry, including
`laneCase`, and two per helper entry before and after Tidy. Checks require exact
closure membership, no hidden lambda or alias, saturated unconditional helper
calls using original scalar inputs, machine-Int formals/results, all four
unsigned tuple leaves and exact vector primitive counts. Counts exclude the
public host bridge. Static proof alone is not compiled execution. The full
pre/post × AST/bytecode × inlining-on/off runtime corpus checks 39,056 compiled
invocations and 46,512 guest entries per handoff mode, requiring those exact
per-call deltas separately from static proof, unchanged targets and empty pools.

The genuine `vectorArgument` negative must fail each stage with exactly one
public-host-vector issue and no missing globals. Two additional controls deliberately
mutate separate metadata copies of `plusCase`: a four-lane `Int32Rep` tuple and
an `Int32ElemRep` vector operand. They are clearly labeled non-genuine controls,
never original Core or native oracle replacements. The tuple mutation requires
one vector-argument, five aggregate-proof and four scalar-proof issues; the
vector mutation requires one vector-argument, two aggregate-proof and one
vector-result issue. Both require zero missing globals.

## Preparation and provenance

Run `cabal run exe:thc-fixtures --offline -- word32x4` with the pinned environment and shared
resource gate. Preparation builds the genuine exporter, exports pre/post Core,
requires all sixteen positive audits to have zero issues and missing globals,
checks the exact negatives and actual root counts, and compiles/runs the real
GHC NCG oracle. Complete keyed native rows must equal the independent model,
including duplicate/arity rejection. No ISA override is needed for native
fixture compilation on the pinned x86 host.

`build/simd-word32x4/provenance.json` records stages, original Core structure,
entry arities and cases, actual root counts, strict audits, labeled signedness
controls, commands, toolchain identity, and complete source/artifact hashes.
Artifacts include expected rows, original Core, strict reports, separate negative
copies, native rows/executable, and command outputs/status. The shared
[integer SIMD fixture guide](integer-simd-fixtures.md) includes the independent
Java model tests and both-handoff runtime recipe.

`--export-only` exports only pre-Tidy Core and model rows for environments such
as AArch64 where the pinned native backend is not the validation path. It removes
stale oracle/provenance outputs, records null `nativeRows` and `modelMatched`,
and makes no native or post-Tidy claim. It is not a native-failure fallback.
A final full preparation restores native evidence after testing this mode.

Fixture/model/native evidence alone establishes no JVM compilation, packed
machine instructions, allocation elimination, throughput, vector calling
conventions or cross-architecture execution. Those require separately retained
runtime tests and graph/LIR evidence.
