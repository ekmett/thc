# Scalar floating primitives

THC supports a bounded scalar `Float#`/`Double#` foundation in both the AST and
bytecode backends. It includes floating literals, primitive locals, constructor
fields and closure captures, scalar arguments/results, and 28 primops:

| Family | Float# | Double# |
| --- | --- | --- |
| Arithmetic | `plusFloat#`, `minusFloat#`, `timesFloat#`, `divideFloat#`, `negateFloat#` | `+##`, `-##`, `*##`, `/##`, `negateDouble#` |
| Comparisons | `eqFloat#`, `neFloat#`, `ltFloat#`, `leFloat#`, `gtFloat#`, `geFloat#` | `==##`, `/=##`, `<##`, `<=##`, `>##`, `>=##` |
| Int conversion | `int2Float#`, `float2Int#` | `int2Double#`, `double2Int#` |
| Precision conversion | `double2Float#` | `float2Double#` |

The exporter retains `FloatRep` and `DoubleRep` as distinct scalar proofs.
AST execution has `executeFloat`/`executeDouble` paths; frames and StaticShape
fields/captures store JVM `float`/`double` directly. Bytecode operations use those
same concrete types with Bytecode DSL boxing elimination enabled for each.
There is no implicit widening between the two types. Every floating operation
rounds to its declared precision; comparisons use IEEE arithmetic equality and
ordering, including unordered NaNs and equal positive/negative zeros.

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
scripts/gradle.sh --no-daemon test --tests thc.runtime.FloatingPrimitiveTest
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
