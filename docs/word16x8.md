# Word16X8 vectors

Both backends execute `VecRep 8 Word16ElemRep` with raw
`ShortVector.SPECIES_128` values. An activation carries one exact vector
reference; owned captures and boxed constructor fields store primitive lanes
inside the enclosing heap object. Equal bit width does not make another vector
shape compatible.

The foundation fixture covers six GHC 9.14.1 operations:
`packWord16X8#`, `unpackWord16X8#`, `broadcastWord16X8#`,
`plusWord16X8#`, `minusWord16X8#`, `timesWord16X8#`.
GHC provides no unsigned vector negation primitive.
This is a fixture scope, not the complete operation inventory; see
[SIMD families](simd-families.md) and the [primop checklist](primops.md).

Pack takes one logical 8-component unboxed tuple; unpack returns that
scalar-lane tuple with exact `Word16Rep` leaves. Arithmetic wraps modulo
65,536, including low-16-bit multiplication and subtraction underflow.
Unpack zero-extends each lane to 0..65,535.
A high unsigned lane must not become a negative scalar.

Guest vector arguments/results, PAP prefixes, joins, tuple fields, nonrecursive
unlifted lets, owned captures and boxed constructor fields are supported.
Exact vector fields in supported sums and raw JDK vectors at the
[Core host boundary](site/embedding.md#load-a-core-entry) are also supported.
Recursive or lifted vector lets remain unsupported; see the
[SIMD transport contract](simd.md). The scalar-observation fixtures below
keep vectors local. Their `vectorArgument` negative checks the separate
auditor's scalar-entry restriction.

Canonical `word16` literals use 0..65,535. After lowering, integral
narrow annotations share an `Int` carrier and the literal tag supplies narrowing.
Machine-word and explicit 64-bit integers retain `Long` and are distinct carriers.
Wrong physical carriers, malformed literal values, lifted operand flags, tuple
lane proofs and vector shapes are checked separately. The strict exporter audit
also checks exact source-level representations; it is not the runtime's scalar
carrier contract.

`SimdWord16VectorTest` checks the raw carrier, lane arithmetic, exact shape and
literal controls on both loaders. Native/Core tests require exact per-call
compiled guest-entry counts, stable active targets, valid last-tier code and
released argument/result handoff pools, with Truffle inlining on and off.
No post-compilation settling calls or retries are part of those checks.

## Genuine Core and independent observations

`t/fixtures/compiler/SimdWord16X8.hs` uses the real pinned primitives.
The graph roots `plusCase`, `minusCase` and `timesCase` each have arity two.
Every input lane narrows through `int2Word#` and `wordToWord16#`; every result
lane zero-extends through `word16ToWord#` and `word2Int#` before weighting.

| Lane | Left | Right | Checksum weight |
| ---: | --- | --- | ---: |
| 0 | a | b+2 | 3 |
| 1 | b | a-3 | 5 |
| 2 | a+1 | 7*b+13 | 7 |
| 3 | b-1 | 11*a-17 | 11 |
| 4 | a+32767 | b+32768 | 13 |
| 5 | b-32768 | a-32767 | 17 |
| 6 | 3*a+7 | 13*b+19 | 19 |
| 7 | 5*b-11 | 17*a-23 | 23 |

Weights sum to 98, bounding checksums by 6,422,430. The scalar helper's added
48 keeps all results below 2^23. Affine seed arithmetic can wrap at machine
width, but subsequent low-16-bit reduction agrees with the independent
arbitrary-precision model; its separate test implementation explicitly wraps
the machine arithmetic before narrowing.

`packCase` roundtrips all eight lanes. `broadcastCase` broadcasts unsigned
`a-b+29` modulo 65,536. Both have arity two. Broadcast uses scalar
`plusWord16#` with a folded genuine `word16` literal 29; preparation requires
original exported narrow literals in both stages, not synthetic replacements.

`laneCase` has arity four: `operation, lane, a, b`. Operations 0..4 select plus,
minus, times, pack or broadcast; lanes 0..7 select one unsigned result. Every
branch finishes its local vector operation and returns only a scalar. Only
those selector domains belong to the declared corpus.

Left and right at each lane depend on independent seeds with odd affine
coefficients. The model inverts them modulo 65,536, obtaining the full 9-by-9
operand grid `[0,1,2,16383,32767,32768,32769,65534,65535]` independently at each
lane and operation. Broadcast rows arrange each boundary value directly.
These 3,240 individual lane observations prevent weighted checksums from hiding
lane-order or sign-extension errors.

The scalar corpus contains 268 distinct pairs from all sixteen power-of-two
neighborhoods, unsigned boundaries, machine extremes and large positive/negative
seeds. Seven arity-two entries contribute 1,876 rows, yielding exactly 5,116
native/model rows. Model tests compare every row against separate lane formulas,
check every 16-bit encoding, and compare all 65,536 left operands against nine
boundary right operands (589,824 scalar products). This is not an exhaustive
claim over all 2^32 Word16 operand pairs.

## Residual roots and negative controls

`scalarHelperCase` calls opaque `scalarWorker`, which forms the plus checksum
and adds 31; the caller adds 17. `tupleHelperCase` calls opaque `tupleWorker`,
receiving eight actual scalar `Word16#` multiplication lanes and forming the
checksum in the caller. No vector crosses either helper boundary. Direct graph
roots have no optimizer fences.

Preparation proves exactly one actual guest root per direct entry, including
`laneCase`, and two per helper entry, both before and after Tidy. Checks require
exact closure membership, no hidden local lambda or alias, saturated
unconditional helper calls using original scalar inputs, machine-Int formals
and results, all eight unsigned tuple leaves and exact vector primitive counts.
Counts exclude the public host bridge. Static proof is not compiled execution.
The full pre/post × AST/bytecode × inlining-on/off runtime corpus checks 40,928
compiled calls and 45,216 guest entries per handoff mode, with exact per-call
deltas, unchanged targets and empty pools required separately from static proof.

The genuine `vectorArgument` negative has a Word16X8 formal and must fail each
stage's audit with exactly one public-host-vector issue and no missing globals.
Two additional controls deliberately mutate separate metadata copies of
`plusCase`: an eight-lane `Int16Rep` tuple and an `Int16ElemRep` vector operand.
These are clearly labeled non-genuine controls, never native oracle inputs or
replacements for original exported Core. The tuple mutation requires exactly
one vector-argument, nine aggregate-proof and eight scalar-proof issues; the
vector mutation requires one vector-argument, two aggregate-proof and one
vector-result issue. Both must have no missing globals.

## Preparation and provenance

The shared [integer SIMD fixture guide](integer-simd-fixtures.md) provides the
independent Java model and both-handoff recipe.

Run `cabal run exe:thc-fixtures --offline -- word16x8` with the pinned environment and the
shared resource gate. Preparation builds the real exporter, exports pre/post
Core, requires all sixteen positive audits to have zero issues and zero missing
globals, checks the exact negatives and root counts, and compiles/runs the real
GHC NCG native oracle. The oracle's complete keyed row map must exactly equal
the independent integer model, including duplicate/arity rejection.

`build/simd-word16x8/provenance.json` records stages, original Core structure,
entry arities and inputs, actual root counts, strict audits, separately labeled
signedness controls, commands, toolchain identity, and complete source/artifact
hashes. Artifacts cover expected rows, both original Core files, audit reports,
negative copies, native rows/executable, and command output/status. Java's
`IntegerSimdModelTest` independently checks the lane model; the existing
`SimdWord16VectorTest` retains exact compiled-entry checks in both handoff modes.

`--export-only` exports only pre-Tidy Core and model rows for environments such
as AArch64 where the pinned native backend is not the validation path. It removes
stale oracle/provenance outputs, records `nativeRows: null` and
`modelMatched: null`, and makes no native or post-Tidy claim. It is not a fallback
after a native failure. A final full preparation is required to restore native
evidence after testing this mode.

Fixture/model/native evidence alone establishes no JVM compilation, packed
machine instructions, allocation elimination, throughput, vector calling
conventions or cross-architecture execution. Those require separately retained
runtime tests and graph/LIR evidence.
