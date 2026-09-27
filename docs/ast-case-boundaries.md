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

This does not automatically select graph-budget boundaries or generate replacement
targets after permanent compilation failure. An arm's `tailPosition` metadata
alone does not authorize eliding tuple completion, masks, catches or updates.

## Internal tail-spill compaction

The first compaction path covers synchronous, non-delimited, evaluated primitive
scalar arms without a typed return loan. It uses the existing conservative
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

Async-enabled, delimited, aggregate, reference/lazy-result and result-loan paths
continue using their existing capture/completion protocols; they are not compacted
by this tranche. Public loading still does not enable outlining by default.

`PassThroughRootTest` covers root roles, explicit packet ownership, Bloom
backedges, typed/scalar self-shortcut suppression and cold compiled clones.
`CaseArmOutliningTest` covers actual Core lowering, cold compiled caller/callee
entry, selection effects, returning continuations, local joins, mixed typed
tuples, a real blocking async cut and original native-backed delimited resumes.
Prepare its existing fixture with:

```sh
cabal run exe:thc-fixtures -fdevelopment -- delimited-continuations
```

`AstTailSpillTest` checks long identity side chains, exact prefix counts, retained
non-tail suffixes, masks live before and after the first spill, and nested anchors
whose tail transfers must not cross a non-tail return boundary. `AstStackTest`
continues to cover shared updates, failures, async delivery and driver isolation.
