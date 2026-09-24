# Scalar floating primitives

THC supports a bounded scalar `Float#`/`Double#` foundation in both the AST and
bytecode backends. It includes floating literals, primitive locals, constructor
fields and closure captures, scalar arguments/results, and 66 primops:

| Family | Float# | Double# |
| --- | --- | --- |
| Arithmetic | `plusFloat#`, `minusFloat#`, `timesFloat#`, `divideFloat#`, `negateFloat#` | `+##`, `-##`, `*##`, `/##`, `negateDouble#` |
| Square root | `sqrtFloat#` | `sqrtDouble#` |
| Scalar math | `fabsFloat#`, `expFloat#`, `expm1Float#`, `logFloat#`, `log1pFloat#`, `sinFloat#`, `cosFloat#`, `powerFloat#` | `fabsDouble#`, `expDouble#`, `expm1Double#`, `logDouble#`, `log1pDouble#`, `sinDouble#`, `cosDouble#`, `**##` |
| Trigonometric and hyperbolic | `tanFloat#`, `asinFloat#`, `acosFloat#`, `atanFloat#`, `sinhFloat#`, `coshFloat#`, `tanhFloat#` | `tanDouble#`, `asinDouble#`, `acosDouble#`, `atanDouble#`, `sinhDouble#`, `coshDouble#`, `tanhDouble#` |
| Comparisons | `eqFloat#`, `neFloat#`, `ltFloat#`, `leFloat#`, `gtFloat#`, `geFloat#` | `==##`, `/=##`, `<##`, `<=##`, `>##`, `>=##` |
| Int conversion | `int2Float#`, `float2Int#` | `int2Double#`, `double2Int#` |
| Unsigned Word conversion | `word2Float#` | `word2Double#` |
| Precision conversion | `double2Float#` | `float2Double#` |
| Raw bit casts | `castFloatToWord32#`, `castWord32ToFloat#` | `castDoubleToWord64#`, `castWord64ToDouble#` |

The exporter retains `FloatRep` and `DoubleRep` as distinct scalar proofs.
AST execution has `executeFloat`/`executeDouble` paths; frames and StaticShape
fields/captures store JVM `float`/`double` directly. Bytecode operations use those
same concrete types with Bytecode DSL boxing elimination enabled for each.
There is no implicit widening between the two types. Every floating operation
rounds to its declared precision; comparisons use IEEE arithmetic equality and
ordering, including unordered NaNs and equal positive/negative zeros.

`word2Float#` and `word2Double#` accept the full unsigned 64-bit `Word#`
range, represented by raw Long bits. Top-bit-set values are shifted right with
a sticky low bit before a direct conversion at the destination precision, then
scaled exactly by two. The Float path never goes through Double: that would
double-round inputs one integer away from a binary32 midpoint. Both operations
round to nearest, ties to even, including rounding `maxBound :: Word` to 2^64.
`cabal run exe:thc-fixtures -- word-floating` produces argument-fed native raw-bit
observations and strict pre/post Core audits using the existing Haskell fixture
runner. `WordFloatingTest` independently derives integer rounding and checks
both backends before and on the first/subsequent installed compiled calls, with
inlining enabled/disabled. Its input domain includes 2^24/2^53, both sides of
2^63, max Word, and even/odd midpoint neighbors; exact Word/Float/Double proofs
and unary arities have negative controls. Preparation is wired into full and
focused CI; no installed GHC artifacts are hashed.

Generic function call packets and root returns still use the existing Object
ABI. Non-inlined floating calls can therefore allocate wrapper objects. The
optional integer/reference handoff ABI does not cover floating values. The
public embedding interface still accepts integer inputs; the fixtures enter
through `Int# -> Int#` wrappers. This does not establish an unboxed scalar floating
calling convention across residual calls.

Exact floating [unboxed tuple results](tuple-results.md) use primitive float/double
fields and typed destination slots on both backends. Ordinary GHC CPR can turn a
boxed producer into such a worker; the original `floatingTupleFrontier` now runs
as a positive native control. A separate suite executes public `Data.Complex`
multiplication and `conjugate` through real floating CPR workers. The boxed-field
control keeps its `OPAQUE` producer to preserve the boxed boundary under test.
Aggregate arguments/captures, vector ABI expansion and unboxed sums remain
unsupported. Floating literal case alternatives are invalid GHC Core and fail closed.

`scripts/prepare-floating-audit.py` builds the native oracle, exports current
GHC 9.14.1 Core, checks all 28 primops, verifies concrete worker results/arguments,
boxed field representations and retained floating captures, and audits all 15
positive roots strictly. It independently checks 441 native rows, including
binary32's 2^24 boundary, binary64's 2^53 boundary, negative values, signed zero,
infinities, NaNs and the smallest subnormals. Floating-to-Int conversion tests
exclude non-finite and out-of-range operands, whose GHC behavior is undefined.
The ordinary `floatingJoinSwap` retains a real recursive join with two Float and
two Double formals, testing parallel argument permutations independently of the
self-tail accumulator loop. Native executable, oracle, source, auditor,
capabilities and exported Core hashes are retained under
`build/floating/checks.json`; JVM tests reject stale evidence.

Preparation runs automatically in `scripts/prepare-tests.sh`; Gradle tracks the
generated inputs and CI retains their evidence. The focused checks are:

```sh
compiler/build.sh
python3 scripts/prepare-floating-audit.py
./gradlew --no-daemon test --tests thc.runtime.FloatingPrimitiveTest
python3 scripts/test-audit-core.py
```

The JVM tests replay every native row before and after requested compilation,
requiring each final row to execute installed guest code with the default
splitting policy. Additional checks cover previously cold non-finite paths,
direct primitive storage and exact signed-zero/NaN-payload preservation across
fields and captures, shared frame-descriptor widening, and unsupported boundaries.
Exact captures also accept the same boxed scalar carrier after shared descriptor
widening, without numeric coercion. This is API/legacy robustness: no ordinary
valid GHC lowering path that widens an exact scalar binder is currently known.
Malformed case results are checked against every known alternative independent
of ordering; an unknown alternative does not inherit a floating proof from its
peers.

`scripts/prepare-sqrt-audit.py` separately exports public `Prelude.sqrt` and
the scalar math wrappers for both precisions before and after Tidy. Its 418
sqrt/control native rows include signed zeros,
subnormals, infinities, negative values, signaling/quiet NaNs, boundaries around
perfect squares, and deterministic random finite inputs. An independent exact
rational model finds adjacent output values and compares their squared midpoint,
checking nearest-even rounding without calling a floating square-root function.
The suite checks 288 exact bit results, 104 NaN classifications and 26 scalar
consumer results. Another 402 native rows exercise the 30 scalar math primops
through typed `Float#`/`Double#` wrappers. Arithmetic NaN payloads and signs
are not specified. Inverse trigonometric rows include inputs outside [-1, 1]
for NaN classification; hyperbolic rows include infinities and values near
Float and Double overflow.

Scalar math uses JVM `Math` operations and rounds each Float result to
binary32. Native GHC is the differential oracle, not a claim of bit-exact
transcendentals across `libm` and the JVM. Both backends execute every math
row before and after explicit compilation. The comparison checks NaN by class,
infinities and signed zero by exact bits, and finite results within the larger
of 8 ulps or 2e-6 relative for Float, and 16 ulps or 2e-14 relative for Double.
These bounds apply to the tested finite domain; they do not promise
cross-platform bit identity or a universal `Math.pow` accuracy contract.

The AST uses separate typed unary nodes; BytecodeDSL uses typed unary operations.
Both call JVM `Math.sqrt`, whose [specified behavior](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Math.html#sqrt(double))
preserves signed zero and returns the correctly rounded binary64 root. Float
operands are widened exactly, then the result is narrowed to binary32. The native
and independent bit checks cover this path at both precision boundaries. This
widening does not introduce double rounding: every positive finite binary32 root
is normal. In a result binade `[2^e, 2^(e+1))`, a binary32 midpoint has a 25-bit
significand, so its square is an odd integer times `2^(2e-48)`. A binary32 input
cannot equal that midpoint square and differs by at least `2^(2e-48)`. Dividing
by the sum of the root and midpoint puts their distance above `2^(e-50)`, while
binary64 rounding changes the root by at most `2^(e-53)`. It therefore cannot
cross a binary32 rounding midpoint. Zeros, infinities and NaNs follow their
separate IEEE rules. Generic
Object call/return boxing and optional scalar handoff eligibility remain unchanged.
`SqrtPrimitiveTest` runs both exports and backends with guest inlining enabled and
disabled. Every measured row requires the exact guest-entry count (one primitive
producer or two roots for a scalar consumer) and the selected target still
installed. Cold special values are checked without a compilation retry. The CI
handoff run repeats the same tests; source, auditor, native and export hashes are
validated before execution.
The [sqrt production graphs](../bench/experiments/sqrt-graphs/README.md) check
dynamic native-backed scalar consumers on both backends. The recorded graphs
contain one square-root node, no intermediate floating boxes or guest calls,
and final `FSQRT SINGLE`/`FSQRT DOUBLE` instructions. One host-result Long box
remains.

For actual compiler evidence, `floatingLoop` has both f32 and f64 accumulators.
Capture its graph with `scripts/dump-graph.sh`, selecting
`build/floating/core/FloatingAudit.json` through `THC_GRAPH_MODULES` and each
backend through `THC_BACKEND`. Run `tools/check-floating-loop-graph.py` on the
parsed `Before phase HighTierLowering` snapshot. The checker follows actual CFG
backedges and dominators, requires f32/f64 addition recurrences, and rejects
boxing, allocation, heap loads and calls throughout the continuing loop blocks.
Entry/exit ABI costs are outside that narrowly stated check.

AST primop names resolve to numeric opcodes during lowering. String comparison
can simplify too late for Graal's speculative frame virtualization, exposing
unreachable reads of one slot as incompatible primitive kinds. Numeric dispatch
removes those paths during partial evaluation. Frame initialization, scratch
clearing, and the normal splitting policy remain unchanged.

During development the string-dispatched AST loop installed a five-node graph
ending in an unconditional `RuntimeConstraint` deoptimization under the
`IntrinsifyFrameAccessor` speculation. A detailed pre-escape-analysis graph
showed Float formal slot 5 read as Long through the unreachable `int2Float#`
branch of `executeFloat` (node 2446), and as Double through the unreachable
comparison branch of `float2Int#` (node 1373). Numeric dispatch removed the
cross-kind paths; the unchanged first compilation then passed every native row.
Initializing floating slots or changing scratch clearing did not solve the
problem and neither experiment is retained.

`tools/record-floating-graphs.py --ast AST_DUMP_DIR --bytecode BYTECODE_DUMP_DIR
--output docs/floating-graphs` retains each checked entry snapshot, its original
BGV, hashes, installed runtime identity, and loop evidence. This record is
about the compiler snapshot, not final machine-code instruction selection.

The four raw bit casts preserve IEEE encodings instead of performing numeric
conversions. Their integer sides are exactly `Word32#` and `Word64#`; binary32
bits use a zero-extended Long, while binary64 uses all 64 Long bits. Separate
typed AST nodes and BytecodeDSL operations call the raw-bit JVM APIs. Saturation
and the pinned scalar signature table reject contradictory exact argument or
result proofs, including machine-Word substitutions. No vector or call ABI is
added.

`scripts/prepare-scalar-bitcasts.py` retains 13,555 native/model rows across ten
pre/post-Tidy roots. Integer-only models cover signed zeros, infinities,
subnormals and signed quiet/signalling NaNs. The fixtures retain opaque calls,
floating constructor fields with an unused recursive bottom, and primitive
closure captures. Separate encode and decode roots compare through native-order
array storage, so a pair of compensating bit-cast errors cannot satisfy only a
round-trip test. Export checks derive fixed guest-entry counts from the retained
call structure; every measured row requires that exact count and unchanged,
installed active targets, with inlining both enabled and disabled. The normal
handoff matrix repeats these gates. A separate helper test enumerates every
binary32 NaN encoding and 262,140 selected binary64 NaN encodings. Raw-bit
preservation, including signalling NaNs, is an explicit platform/JDK test gate;
this does not rely on floating equality or claim that Java specifies universal
signalling-NaN preservation on other architectures. Generic residual floating
calls can still box, as described above.
