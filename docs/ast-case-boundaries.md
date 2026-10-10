# AST case-arm boundaries

An outlined arm is a distinct `FunctionRoot` with immutable `PASS_THROUGH` role.
It executes its body once. It does not consume self-tail transfers, install a
self loop, or run a generic trampoline. Scalar and typed self-call shortcuts
cannot mutate its frame and emit the targetless `AstSelfCall` singleton.
Transfers with explicit targets and owned arguments propagate to their real
caller. Ordinary function roots retain their existing behavior.

`Program` currently exposes an internal construction-time `outlineCaseArms`
selection for controlled integration. It defaults to false; public loading does
not automatically outline or retry compilation. When selected, non-atomic
scalar, tuple and sum case arms use this boundary. Selection stays outside the
arm: the scrutinee runs once, existing match profiles choose the alternative,
and original constructor/payload restoration precedes the call. No additional
branch profile or ever-seen flag is introduced.

The call inherits the caller's Bloom ancestry. It is not an ordinary non-tail
call with a reset Bloom and local trampoline. An outer backedge therefore
crosses the side root and reaches its original loop owner. Capture-capable side
roots add no Bloom bit of their own: they do not install a catcher.
Genuine non-tail calls inside the arm retain their ordinary returning
continuations. Lowering preserves the arm's original tail position; crossing
the new root boundary does not make a non-tail expression tail-positioned.

Live inputs use the existing immutable `CaptureLayout`, with exact primitive,
reference and aggregate fields, not an array of boxed scalar locals. Result
proofs and tuple destinations remain exact. Case/capture slots whose carriers
are already fixed are initialized during lowering; unknown captures retain
normal widening behavior. The selected side is ordinarily inlinable. No edge is
unconditionally marked no-inline.

An arm that references an outer lexical join remains in that join's activation.
There is no implicit cross-root transfer of join frame slots. Lambdas already
have their own roots; atomic arms remain inline.

Outlined programs enable internal continuation capture independently of their
external async-delivery policy. This includes operand sequencing, saved result
completion and the existing bounded stack driver; synchronous selection does
not turn on polling or external async delivery. The existing opaque BCO and
compact-traversal capture restrictions remain explicit. Mask/catch scopes,
tuple loans and child-update ownership use the existing continuation protocols.

Eager outlining alone does not select graph-budget boundaries. An arm's `tailPosition` metadata
alone does not authorize eliding tuple completion, masks, catches or updates.

## Literal label selection

Cases with a proved Integer or Long binder and more than eight distinct labels
of that same physical carrier prepare an immutable key-to-arm index. Selection
reads the carrier once and searches signed primitive keys without an exploded
loop. Signed ordering preserves Word32/Word bit patterns; Integer and Long
matching domains remain distinct. Cases outside this index use the existing
matcher.

Original arm bodies still execute through their typed entries in their owning
activation, including the saved scrutinee-resumption path and normal binder
cleanup. The index retains no nodes, frames or context values. This commoning
bounds label search, not arm-body graph growth: compiled body dispatch still
contains the original arms and index comparisons.

## Deferred default-arm extraction

The separate internal `Program` constructor option `deferDefaultArm` keeps an
eligible arm inline until its caller actually exceeds Graal's graph budget.
It defaults to false and is not a public loader option. The initial slice selects
one top-level, single-default case in a synchronous, non-delimited function,
with evaluated Int, Long, Float or Double results and live local captures.
Multiple alternatives, nested choices, aggregate/reference captures, mutable
cells and outer lexical joins do not acquire this deferred plan. Existing case
profiles and selection semantics are unchanged; AST size is not used as evidence
that a body survives partial evaluation.

Lowering prepares an empty pass-through target in its language context. It
retains exact capture metadata and the original slot numbering, but no copied
arm body or guest invocation state. Only the real graph-budget callback clones
the lowered body into that prepared target and replaces the caller's inline arm
with `AstCaseArm`. This actual structural change advances a finite per-caller
generation through the pinned [compilation lifecycle](../tools/truffle-protocol/README.md).
The failed graph can then be discarded and the same caller target compiled from
its smaller body, without executing the guest or seeding observed profiles.

Only that failure-created edge has an inlining veto; eager side roots retain
normal inliner discretion. Caller clones have independent generations and may
share their context's prepared side code and immutable capture layout, never
frames or values. The prepared side target itself is not split/cloned. Source
metadata and exact primitive capture storage survive the extraction.

This is not arbitrary recursive graph partitioning: an oversized extracted
side body can still exceed its own compilation budget and execute interpreted.
No graph limit is increased and no other permanent compilation failure is
rearmed. `DeferredDefaultArmTest` covers actual Core graph-budget recovery,
original first-installed calls, the matching unextractable failure, small arms
remaining inline, scalar raw-bit preservation, caller clones and context-local
side targets. Explicit callback unit controls are distinct from the test that
provokes the compiler's actual budget failure.

## Stock-runtime entry recovery

With stock Truffle, the compiler callback only records failure. The next fresh
function entry claims the terminal failed task, clones the body with independent
case targets and fresh loop state, and applies the finite extraction to that
replacement. Entry publication adopts the child and reports the structural change
atomically. Old `executeBody` paths, tail anchors and saved frames remain on their
original body; no guest invocation is replayed. A failed same-frame side selects
the corresponding nested region in the copied owning function, even when that
function's enclosing target was successfully installed. Deferred default sides
are also prepared independently for the replacement. See the
[stock-runtime workflow](contributing.md) for eligibility and limitations.

## Internal tail-spill compaction

Compaction covers synchronous, non-delimited, evaluated primitive scalar arms
and exact `DATA`, `CLOSURE` and `ADDRESS` reference results without a typed
return loan. Reference edges require matching result kinds and the existing exact
reference carrier. They forward the identical guest object: constructor fields
and closure bodies remain unentered, and addresses retain their backing owner
and offset. Unknown/object-only and lazy proofs do not qualify.
It uses the existing conservative
64-root scheduling budget, independently of user async delivery. That logical
budget is not a guarantee about arbitrary carrier stack sizes; no native
stack-pointer probe or runtime-specific stack-limit API is used.

A proved identity-tail edge defers publishing its child call segment. An
untouched pass-through activation can then forward that exact saved child and
final target without retaining its own frame. Appended work or a lexical cleanup
enclosure retains its frame and steps. This only applies to newly owned cuts;
existing parked state-5 chains are not inspected and collapsed retroactively.

A normal function retains a one-shot loop anchor around the saved suffix. It
never restarts the function body to recover a saved program counter. Only a
subsequent matching tail transfer restores arguments and starts an iteration.
The cold driver unwinds retained mask/catch/annotation scopes before delivering
that transfer to the anchor. Its internal transfer input is not a guest exception
or async request and cannot acknowledge or replace user delivery.

Only explicitly marked new tail segments can forward to that anchor. Ordinary
non-tail calls and thunk updates retain their existing returning/trampoline
boundaries. A nested anchor starts a separate tail segment: an outer owner across
the non-tail boundary is not eligible, even while its Java catcher remains live.
Bloom ancestry is rebuilt from the selected surviving owner, not removed by
bit subtraction or inferred from parked roots. The anchor remains installed
across legitimate loop iterations without a generic trampoline on each lap.

Async-enabled, delimited, aggregate/vector, unknown/lazy-result and result-loan paths
continue using their existing capture/completion protocols; they are not compacted
by this tranche. Public loading still does not enable outlining by default.
