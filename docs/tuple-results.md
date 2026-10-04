# Unboxed tuple results

Both execution backends support exact unboxed tuple results from functions with
scalar/reference inputs or [typed tuple inputs](tuple-inputs.md). This includes empty and singleton
tuples, nested tuples, concrete Long/Float/Double fields, lazy lifted references, boxed unlifted reference fields,
non-tail calls, tail forwarding, scalar PAP prefixes and overapplication.
An exact evaluated `AddrRep` leaf uses a checked `ManagedAddress` reference
field, with carrier checks on construction, copy and consumption. Native pointer projection requires a separate checked foreign boundary.
Saturated [tuple arithmetic primitives](tuple-arithmetic.md) write directly to
typed local destinations without using the function-return carrier.

Fixture-free owning tests cover tuple transport and result ownership. Genuine GHC
pre/post-Tidy qualification of ordinary tuple-result calls, tails, PAPs and
overapplication remains incomplete.

Ordinary boxed tuples, boxed unit and unlifted boxed products continue to use
`DataValue` references. An empty unboxed tuple remains logically distinct from
`State#` and `Proxy#`. These zero-width scalar fields retain logical tuple positions
without payload fields or result slots. Their expressions still execute in source
order, even if the corresponding pattern binder is unused. A bound zero-width
field is a canonical Unit alias; nested closures need no capture field for that
alias. Existing scalar State# formals and call packets retain their ordinary ABI.
`ByteArray#` is one unlifted boxed reference, independent of State# erasure.

Fixture-free controls execute ordinary scalar State# producers in ignored fields
and suppress later field work on a guest exception, with released loans after
failure and recovery. Genuine GHC/export preservation of arbitrary State-producing
ordinary calls remains unqualified. Proofless State operands reject non-Unit
scalar carriers and release loans; validation may follow operand materialization.

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

`FloatingTupleTest` retains genuine pre/post GHC complex/mixed arithmetic and
native IEEE tuple-bit controls. Its whole-tuple identity case is deliberately
inserted into exported Core; it does not qualify GHC preservation of that case.
Arithmetic NaNs require classification, while non-arithmetic transport retains
exact payload bits in the independent tuple completion owner.

The compiler and auditor compare tagged recursive tuple/scalar layouts, not
register counts or pretty names. They reject equal-width but differently nested
proofs. Scalar reference kind/evaluatedness may refine at each use without forcing
a lazy field. Polymorphic constructor-table fields are not layout evidence;
the instantiated constructor application and case metadata provide the layout.

[Typed tuple inputs](tuple-inputs.md) preserve recursive logical shape and use
concrete primitive/reference fields, while [exact empty tuple inputs](empty-tuple-inputs.md)
need no payload fields. [Ordinary tuple captures](tuple-captures.md) retain owned
typed fields. Nonrecursive unlifted tuple/sum lets store their result in typed
frame locals; recursive/lifted aggregate lets and global aggregate storage remain unsupported.
[Tuple join parameters](tuple-joins.md) use typed parallel frame moves. Exact tuple
join results use typed local slots inside the same guest root; no result carrier
or pool loan is needed for that local control flow. Same-frame join captures can
read whole tuples from their existing typed slots. Empty tuple cases evaluate
their scrutinee and propagate its exception/retry/bottom; a normal return traps
as a non-exhaustive case instead of inventing an alternative.
[Sum results](sum-results.md) reuse this completion protocol with exact tag
and projection validation. Supported sums may occur inside recursive tuple
components, and concrete sums may themselves contain tuples or sums. Unknown/null aggregate layouts and
unsupported physical leaves remain rejected. The
[Core host ABI](site/embedding.md#load-a-core-entry) returns supported tuples as
read-only logical arrays, copied before temporary result storage is released.
