# Unboxed tuple results

Both execution backends support exact unboxed tuple results from functions whose
inputs use the ordinary scalar/reference ABI. This includes empty and singleton
tuples, nested tuples, lazy lifted references, boxed unlifted reference fields,
non-tail calls, tail forwarding, scalar PAP prefixes and overapplication.
Ordinary boxed tuples, boxed unit and unlifted boxed products continue to use
`DataValue` references. An empty unboxed tuple remains logically distinct from
`State#` and `Proxy#`.

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
   of the optional input slab's lifetime. References are cleared on release,
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

The compiler and auditor compare tagged recursive tuple/scalar layouts, not
register counts or pretty names. They reject equal-width but differently nested
proofs. Scalar reference kind/evaluatedness may refine at each use without forcing
a lazy field. Polymorphic constructor-table fields are not layout evidence;
the instantiated constructor application and case metadata provide the layout.

Aggregate formal arguments, captures, ordinary let bindings, join parameters and
join results remain unsupported, including unused formals and zero-width tuples.
Sums, unknown/null aggregate layouts, unsupported physical leaves and scalar
void components inside tuples are also rejected. Host entries must return a
scalar/reference result; diagnostic mode defers an unsupported host result to a
trap without executing a tuple producer.

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
