# Unboxed tuple results

Both execution backends support exact unboxed tuple results from functions with
scalar/reference inputs or [typed tuple inputs](tuple-inputs.md). This includes empty and singleton
tuples, nested tuples, concrete Long/Float/Double fields, lazy lifted references, boxed unlifted reference fields,
non-tail calls, tail forwarding, scalar PAP prefixes and overapplication.
An exact evaluated `AddrRep` leaf uses a managed `LiteralAddress` reference
field, with carrier checks on construction, copy and consumption. This does
not admit native pointers or address-bearing unboxed sums.
Saturated [tuple arithmetic primitives](tuple-arithmetic.md) write directly to
typed local destinations without using the function-return carrier.

Ordinary boxed tuples, boxed unit and unlifted boxed products continue to use
`DataValue` references. An empty unboxed tuple remains logically distinct from
`State#` and `Proxy#`. These zero-width scalar fields retain logical tuple positions
without payload fields or result slots. Their expressions still execute in source
order, even if the corresponding pattern binder is unused. A bound zero-width
field is a canonical Unit alias; nested closures need no capture field for that
alias. Existing scalar State# formals and call packets retain their ordinary ABI.
`ByteArray#` is one unlifted boxed reference, independent of State# erasure.

The public Truffle boundary remains `Object[] -> Object`. Tuple results use a
mandatory private protocol, independent of `thc.handoffSlabs`:

1. A root computes every result leaf into typed local slots. Tuple cases give
   binders views of these slots, preserving exact logical nesting separately.
2. An inlined compiled root creates fresh generated `StaticShape` storage and
   requires it to remain virtual with `ensureVirtualized`. Its immediate caller
   copies primitive/reference fields into typed locals within the same dispatch
   arm. The representation is designed for partial escape analysis to eliminate
   the carrier and copies; a typed storage declaration alone does not prove that.
3. An interpreter or residual compiled root acquires a reusable typed output
   slab only after guest evaluation is complete, writes the fields, and returns
   a private completion token. The caller copies and releases it before running
   any guest continuation. The pool has one active result at most, independently
   of input storage's lifetime. References are cleared on release,
   including when a result layout check fails.
4. Tail forwarding follows the same copy-to-locals path. Each enclosing root
   finishes once, so a fresh inlined carrier cannot cross the actual Object
   return boundary. A fresh carrier materialized by deoptimization owns no pool
   loan; consuming it never releases pooled storage.

No tuple payload `Object[]`, per-return heap tuple, retained `VirtualFrame`, or
boxed primitive field is needed by this protocol. Residual scalar input packets
still follow the existing ABI. Bounded direct caches specialize target, remaining
arity, PAP prefix length and environment presence before making those packets.
Each arm consumes its result before control flow merges.

Floating leaves retain JVM `float` and `double` fields in the result slab, with
typed frame and BytecodeDSL local accesses throughout construction, forwarding,
case binding and local join results. Layout interning distinguishes both widths
from Longs and references. Cleanup touches only reference fields. This does not
expand the optional scalar handoff ABI: its arguments remain Long/reference
only, and residual scalar floating inputs still travel through Object packets.

The compiler and auditor compare tagged recursive tuple/scalar layouts, not
register counts or pretty names. They reject equal-width but differently nested
proofs. Scalar reference kind/evaluatedness may refine at each use without forcing
a lazy field. Polymorphic constructor-table fields are not layout evidence;
the instantiated constructor application and case metadata provide the layout.

[Typed tuple inputs](tuple-inputs.md) preserve recursive logical shape and use
concrete primitive/reference fields, while [exact empty tuple inputs](empty-tuple-inputs.md)
need no payload fields. Aggregate captures, ordinary let bindings and join parameters
remain unsupported. Exact tuple
join results use typed local slots inside the same guest root; no result carrier
or pool loan is needed for that local control flow. Join captures of whole tuples
remain unsupported; individual scalar/reference fields can be used normally.
[Binary sum results](sum-results.md) reuse this completion protocol with exact tag
and projection validation. Nested sums, unknown/null aggregate layouts and
unsupported physical leaves remain rejected. Host entries must return a
scalar/reference result; diagnostic mode defers an unsupported host result to a
trap without executing a tuple producer.

`scripts/prepare-floating-tuples.py` checks genuine `Data.Complex` multiplication
and `conjugate`: ordinary NOINLINE boxed producers become GHC CPR workers returning
`(# Float#, Float# #)` and `(# Double#, Double# #)`. The public `Complex` datatype
itself remains boxed. Both export stages are strict-audited, and 44 native results
match independent formulas. Eight additional native bit rows cover opposite zero
signs, subnormals, infinities, NaNs and finite values; arithmetic NaN payload/sign
is not specified, so that row checks NaN classification. JVM protocol tests also
preserve deliberately chosen NaN payload bits without performing arithmetic.
The suite covers a genuine tuple-result join, nested empty and State# fields,
two outstanding mixed results, and an ignored lifted leaf that is itself bottom.
Every measured row checks its exact compiled-entry increment and installed target
validity on AST and BytecodeDSL, with guest inlining enabled and disabled. The CI
handoff run repeats these checks with the optional scalar handoff enabled. Source,
auditor, native executable, oracle and export hashes are checked before execution.
The [floating result graph experiment](../bench/experiments/floating-tuple-graphs/README.md)
checks six normally inlined production graphs and two residual controls against
the native oracle. Inlined Float/Double tuple fields become scalar floating
register values with no tuple carrier allocation or field traffic. Residual calls
retain the typed result slab and existing Object argument ABI.

The exporter preserves native proofs through `runRW# f` to `f realWorld#` only
when GHC's exact type equality confirms the rewrite. Representation-changing
wired rewrites remain uncertified. `scripts/prepare-state-tuple-audit.py` checks
the genuine pre/post-Tidy metadata and 21 native rows against independent
wraparound formulas. `StateTupleTest` runs these rows with and without guest
inlining on both backends, checks installed entry validity after every compiled
call, and covers lazy payloads, nested empty fields, zero-storage captures and
an ignored State# field whose evaluation throws before tuple completion. Preparation
strict-audits all positive roots and the exception control; tests verify source,
auditor and artifact hashes before execution. Erasing a field also verifies its
canonical Unit carrier, so missing legacy metadata cannot hide an invalid value.

`TupleResultTest` executes the genuine pre/post-Tidy `AggregateFrontier` tuple
entries and all 94 `TupleReturnAudit` native oracle rows on AST and BytecodeDSL,
with inlining enabled and disabled. It explicitly compiles guest roots, executes
them, checks that entry code remains installed, and checks pool release and
allocation reuse. Cases include two differently weighted outstanding pairs,
lazy bottom fields, empty/singleton/nested results, 20,000 self-tail and 20,001
mutual-tail iterations, PAPs and overapplication. `TupleRepresentationTest` covers
logical-shape forgeries, mismatched-layout cleanup, fresh-carrier ownership and
independence from scalar handoff. The [production graph experiment](../bench/experiments/tuple-runtime-graphs/README.md)
executes five real exported Haskell consumers on both backends, with normal and
disabled guest inlining. All twenty configurations agree with native GHC and keep
the selected entry compiled after execution. The ten normal-inlining graphs
eliminate tuple carriers, their field traffic and guest calls; their final AArch64
LIR computes the dynamic leaves in scalar registers. A host-result `Long` box
remains. Residual controls retain a call boundary and typed slab traffic. This
proves those compiled entries, not the inlining frequency of arbitrary programs
or a multiple-register return convention across residual calls.

The [real Set diagnostic experiment](../bench/experiments/set-diagnostic/README.md)
also runs all 22 existing native oracle rows on both backends, including the
exported `minViewSure`/`maxViewSure` tuple-result joins and `glue`, with no
unsupported traps and valid compiled entries after replay. Its focused AST
`minViewSure` graph keeps join results as separate SSA values until outer tuple
completion; residual call traffic and three exception-control join guard groups
(nine field reads) remain.
This is diagnostic execution only: strict Set still rejects the remaining cold
exception/backtrace and state-representation gaps.
