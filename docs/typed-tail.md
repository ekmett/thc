# Typed results and tail cycles

The AST interpreter now forwards primitive results through cases, lets, forcing,
and lambda bodies. The bytecode interpreter now handles a tail call back to any
matching active root, including an interior cycle such as `A → B → C → D → E → C`.
The earlier bytecode implementation handled direct self calls but sent these
longer cycles to the outer trampoline.

## Typed execution

`Expr` has typed entry points for `Long`, closures, constructors, and managed
literal addresses, backed by a Truffle type system. Case alternatives invoke the
selected child's typed entry directly and profile which alternative matches.
Primitive scrutinees, unlifted bindings, captured fields, and constructor fields
can travel through primitive frame slots and properties without an Object-array
bridge. AST constructor construction initializes the final fields directly.

A typed forcing node that encounters a thunk evaluates it once and remembers
that it needs the generic forcing path. Widening consumes the value saved in
`UnexpectedResultException`; it never retries the expression. Each cloned node
owns its widening state. This matters because repeatedly throwing that exception
would repeatedly invalidate compiled code.

Lambda bodies retain their primitive path up to Truffle's Object-valued root
return. Applications still cross Truffle's Object-array calling convention; an
inlined call can eliminate that boundary. The tests cover closed and captured
lambdas, partial and overapplication, changing captures, cold case alternatives,
and a polymorphic lifted result changing from data to a closure.

`Int#` and `Word#` still have their existing machine arithmetic semantics. This
change does not replace GHC's `Integer` or `Natural` representation. A future
small/big representation needs its own arithmetic specializations and widening
rules rather than an implicit conversion for every machine integer.

## Closing a tail cycle

Every root has a nonempty mask in a 64-bit bloom filter. A non-tail call starts
fresh ancestry. A tail call can extend the active chain when
`(seen & targetMask) != targetMask`; adding the target mask then sets at least one
previously absent bit. The chain therefore cannot keep growing indefinitely.
A bloom hit throws a tail transfer carrying the target and its complete argument
packet. Collisions can cause an early transfer, but cannot hide a repeated root.

A root consumes a transfer only when the target has the same body identity.
Clones retain that identity. Other roots rethrow it. In
`A → B → C → D → E → C`, E and D unwind, C restores the new arguments and captures,
and C takes its loop backedge. A and B remain suspended. C keeps its original
`A | B | C` ancestry; D and E do not accumulate in the filter across iterations.
A subsequent transfer to B can unwind to B and close the loop there instead.

The bytecode implementation catches a matching transfer at a tail application,
restores the root's bytecode locals, and branches to a Bytecode DSL loop backedge.
Its existing direct-self path remains packet-free. Non-tail dispatch protects
pending work, including the first stage of overapplication. When a conservative
bloom hit has no matching active root, the outer trampoline resets ancestry and
continues the call.

## Validation

All **71 tests** pass. Seven dedicated cycle tests cover two-root cycles, the
five-root interior example and outward retargeting, changed captures together
with a PAP prefix, overapplication, pending non-tail additions, actual cloned
call targets, and a controlled bloom false positive. The collision-free cycle
tests require matching-root reentry and **zero trampoline iterations**, before
and after compilation; stack safety alone would not catch the earlier omission.

Both backends also match native GHC on all **18 Map inputs**, up to 100,000,
before and after requested compilation, with no unsupported traps.
[Correctness results](../bench/results/typed-tail/correctness.json) retain the
counters and logs.

The instrumented Map correctness run exercises direct-self backedges in bytecode
and does not enter its new multi-root handler. Both backends record 66 outer
trampoline iterations over that run. AST records 5,032,652 consumed matching-root
transfers; bytecode's packet-free direct-self path does not emit those transfers.
These counters distinguish mechanisms, not total loop iterations. The missing
handler was important for general tail cycles; it was not dominating this Map
workload.

## Steady-state Map cost

The combined build takes about **1.87 times the native GHC time**. Typed AST
execution reduces elapsed time by **14.1%**. Bytecode takes **1.1% more time** in
this comparison; the cycle repair does not provide a Map speedup.

| Backend | Frozen baseline | Combined build | Native reference | Change | Cost / GHC |
|---|---:|---:|---:|---:|---:|
| AST | 2.825657 ms | 2.426003 ms | 1.292251 ms | -14.14% | 1.877× |
| Bytecode | 2.330575 ms | 2.356178 ms | 1.259554 ms | +1.10% | 1.871× |

Each row comes from its own three-engine comparison against the frozen preceding
runtime and the same native oracle. Native references are measured in each run;
the two candidate times are not a separately controlled AST-versus-bytecode
comparison. Both candidates use the same frozen runtime JAR, SHA-256
`570901a5300dac18d63fb7f93177b76992108e65b37f998fe6f412610045d7f0`.

Each comparison uses three fresh processes per engine in rotating order. JVMs
warm for at least 12,000 complete workloads and 15 seconds, then measure five
two-second windows. The input varies from 10,000 through 10,015; checksums match
the native oracle. Instrumentation is disabled. All **90 windows** pass, with no
Truffle compilation or deoptimization events in measurement or final installed
code verification. This does not establish the absence of host JIT, GC, or
external machine contention.

The [combined summary](../bench/results/typed-tail/summary.json),
[AST run](../bench/results/typed-tail/ast/summary.json), and
[bytecode run](../bench/results/typed-tail/bytecode/summary.json) retain the results.
Each run directory includes raw windows, logs, validation, exact commands, and
source/runtime/module hashes. The native GHC binary and exported Map modules are
unchanged from the preceding experiment.

## Compiled graph evidence

The graph captures use the same frozen combined build as the measurements above,
whose source hashes match commit `cb7488259e8418ee1fdff5c5d37569e40b44af29`.
Later thunk/writeback work is outside this evidence. The
[graph manifest](typed-tail-graphs/manifest.json) maps the exact source files and
JARs to the original BGV captures.

In the synthetic `A → B → C → D → E → C` fixture, the old bytecode C root inlines D
and E but allocates a `TailCall` and `Object[3]` packet and unwinds. The new graph
contains a real loop: its two `i64` phis carry `n - 3` and `acc + 3` around the
C/D/E cycle. It has no residual calls, tail-transfer allocation or argument
arrays. Its one remaining `Long` allocation is after the loop, at the
Object-valued root return. The new AST graph has the same primitive backedge and
allocation behavior. The final entry and host-bridge graphs also eliminate the
outer trampoline for this cycle.

This conclusion comes from the actual dataflow and scheduled control flow, not
just counting loop nodes. The [cycle graph review](typed-tail-graphs/cycle-review.md)
links the original BGVs, selected-phase JSON and rendered CFGs with compiler node
IDs. It also shows why the old graph's zero residual calls did not prove that the
cycle had become a native loop.

The [ten-root Map comparison](typed-tail-graphs/map-graph-comparison.md) verifies
unchanged exported modules and matches roots by identity and label. Typed AST
execution removes the three residual `Long.longValue` calls in weighted fold and
the one in range 31. All 106 remaining guest call sites have constant targets.
Lookup still has zero calls, argument arrays and Long allocations; query loses
its remaining Object[] site.

Other static counts are mixed as the inlining decisions change: weighted fold
has 17 residual guest sites instead of 16, while range 36 has 17 instead of 18.
These are sites exposed in different inlined graphs, not measured call counts.
The remaining call boundaries and their packets are still visible; the graph
comparison does not establish that all application overhead has disappeared.

Two [focused application graphs](typed-tail-graphs/application-review.md) separate
inlined lambda arithmetic from remaining application machinery. A direct plus
captured-lambda fixture compiles to 29 before-high nodes, with no calls, argument
arrays, closures or captured-frame allocations. Its two static Long sites are
mutually exclusive root-return boxes; the intermediate application results feed
primitive arithmetic.

The partial-then-overapplication fixture still calls `Closure.pap` across its
explicit Truffle boundary and materializes an Object[1] argument array. The
prefix array and Closure allocated inside that boundary are additional costs
hidden from the guest graph's allocation-node count. The maker and returned
lambda both inline and their arithmetic remains primitive, but PAP construction
is still an avoidable boundary in this frozen build.
