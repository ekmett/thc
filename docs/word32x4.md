# Local Word32X4 vectors

The bounded contract is exactly six pinned GHC 9.14.1 primitives:
`packWord32X4#`, `unpackWord32X4#`, `broadcastWord32X4#`,
`plusWord32X4#`, `minusWord32X4#`, and `timesWord32X4#`.
GHC provides no `negateWord32X4#`. The exact representation is
`VecRep 4 Word32ElemRep`, with four `Word32#`/`Word32Rep` lanes.
Signed `Int32Rep` or `VecRep 4 Int32ElemRep` metadata is incompatible despite
having the same payload width.

Pack takes one logical four-component unboxed tuple; unpack returns that
scalar-lane tuple. Arithmetic wraps modulo 2^32, including subtraction underflow
and low-32-bit multiplication. Unpacked lanes widen to 0..4,294,967,295;
4,294,967,295 must not become -1. Vectors remain local: vector formals, function
results, captures, heap fields and vector-containing tuple ABIs remain
unsupported. Machine `Int#` inputs and scalar results require a 64-bit host.

The runtime uses a distinct `Word32X4` carrier with four final primitive
`int` fields: sixteen bytes of lane payload, not total object size. Transient
`IntVector.SPECIES_128` values perform low-32-bit arithmetic. No vector object,
payload array or lane box is stored in the carrier. Pack narrows four Long slots;
both AST and bytecode unpack each field with `& 0xffff_ffffL`.

Canonical Word32 literal and aggregate-aware guards are unchanged. JVM checks
include 65,536 constructed samples covering each 16-bit half in every lane,
independent Cartesian power-neighborhood pairs with a BigInteger product oracle,
signedness mismatches, malformed literals and vector calling-frontier rejection.
These samples do not exhaust the 32-bit value space. Native execution checks
require exact per-call compiled guest-entry deltas, stable active target
identities, valid last-tier code and empty argument/result pools. No retries,
postcompile settling or compiler-limit changes are allowed.

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
vector-formal issue and no missing globals. Two additional controls deliberately
mutate separate metadata copies of `plusCase`: a four-lane `Int32Rep` tuple and
an `Int32ElemRep` vector operand. They are clearly labeled non-genuine controls,
never original Core or native oracle replacements. The tuple mutation requires
one vector-argument, five aggregate-proof and four scalar-proof issues; the
vector mutation requires one vector-argument, two aggregate-proof and one
vector-result issue. Both require zero missing globals.

## Preparation and provenance

Run `python3 scripts/test-word32x4-model.py`, then
`python3 scripts/prepare-word32x4-audit.py` with the pinned environment and shared
resource gate. Preparation builds the genuine exporter, exports pre/post Core,
requires all sixteen positive audits to have zero issues and missing globals,
checks the exact negatives and actual root counts, and compiles/runs the real
GHC NCG oracle. Complete keyed native rows must equal the independent model,
including duplicate/arity rejection. No ISA override is needed for native
fixture compilation on the pinned x86 host.

`build/simd-word32x4/provenance.json` records stages, original Core structure,
entry arities and cases, actual root counts, strict audits, labeled signedness
controls, commands, toolchain identity, seventeen source hashes and seven
artifact hashes. The artifacts cover expected rows, both original Core files,
both audit reports, native oracle rows and the actual native executable.

`--export-only` exports only pre-Tidy Core and model rows for environments such
as AArch64 where the pinned native backend is not the validation path. It removes
stale oracle/provenance outputs, records null `nativeRows` and `modelMatched`,
and makes no native or post-Tidy claim. It is not a native-failure fallback.
A final full preparation restores native evidence after testing this mode.

Fixture/model/native evidence alone establishes no JVM compilation, packed
machine instructions, allocation elimination, throughput, vector calling
conventions or cross-architecture execution. Those require separately retained
runtime tests and graph/LIR evidence.

## Retained compiled-code evidence

The [x86-64 evidence](../bench/experiments/word32x4-foundation/evidence-x86_64/README.md)
retains twelve actual pre/post × AST/bytecode × plus/minus/times captures at
runtime `b048144c75a775f6f1538bf7e1b04119f39a1163`. All pass on their first check,
without reader correction or guest replay. Every output lane feeds an exact
unsigned 32-to-64-bit extension; all 48 observations are connected to the public
checksum. Each graph has one live i32x4 arithmetic node and one corresponding
physical XMM VPADDD, VPSUBD or VPMULLD instruction.

The captures check 11,184 native answers, with installed entry validity and
active-target identity checked after every input. Selected inlined graphs
eliminate the local vector carrier, payload and lane boxes. The public Long
result box and precisely proved virtual frame-tag deoptimization metadata remain.
This is bounded packed-code evidence, not throughput, globally allocation-free
execution, no spills, vector ABI or cross-platform native conformance.

The complete JVM suite passes 481 tests in each of default and dense-handoff
modes with zero failures, errors or skips. All 198 suite reports and both full
logs are retained. Classes, seven focused tests, both complete modes and the
twelve graph captures pass first time. The forced dense rebuild executes all
twelve Gradle tasks and preserves every frozen source, JAR and JDK snapshot hash.
